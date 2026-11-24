/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.hdfs3.sink;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.fs.Syncable;
import org.apache.pulsar.functions.api.Record;
import org.awaitility.Awaitility;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests {@link HdfsSyncThread} against a fake stream whose first {@code hsync()} can be held until
 * the test releases it, so that the sync thread can be caught in the middle of a sync.
 */
public class HdfsSyncThreadTest {

    private FakeStream stream;
    private HdfsSyncThread<String> syncThread;
    private final AtomicInteger concurrentAcks = new AtomicInteger();
    private final AtomicInteger maxConcurrentAcks = new AtomicInteger();

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        // Never leave a non-daemon sync thread behind, even when a test fails
        if (stream != null) {
            stream.releaseFirstSync();
        }
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread.join(5_000);
        }
    }

    /**
     * halt() called while the sync thread is in the middle of a sync, as close() can be, must wait
     * for that sync to finish instead of draining the queue at the same time. Two threads draining
     * it could both see one record left, and one of them then blocked forever on an empty queue.
     */
    @Test(timeOut = 30_000)
    public void testHaltWaitsForTheRunningSync() throws Exception {
        stream = new FakeStream(true, false);
        BlockingQueue<Record<String>> queue = new LinkedBlockingQueue<>();
        // Slow acks widen the window in which two threads could drain the queue together
        List<TestRecord> records = records(10, 20);
        queue.addAll(records);
        syncThread = start(queue, 10);
        assertTrue(stream.firstSyncStarted.await(10, TimeUnit.SECONDS));

        AtomicReference<Throwable> haltError = new AtomicReference<>();
        Thread halter = new Thread(() -> {
            try {
                syncThread.halt();
            } catch (Throwable t) {
                haltError.set(t);
            }
        }, "halter");
        halter.start();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() ->
                halter.getState() == Thread.State.WAITING || halter.getState() == Thread.State.BLOCKED);
        stream.releaseFirstSync();

        halter.join(10_000);
        assertFalse(halter.isAlive(), "halt() did not return");
        assertNull(haltError.get());
        assertFalse(syncThread.isAlive());
        assertTrue(queue.isEmpty());
        for (TestRecord record : records) {
            assertEquals(record.acks.get(), 1);
        }
        assertEquals(maxConcurrentAcks.get(), 1, "records were acked on two threads at once");
    }

    /**
     * A record queued while a sync is running may have been written after the sync started, so
     * that sync must not ack it: the next one does.
     */
    @Test(timeOut = 30_000)
    public void testRecordsQueuedDuringASyncAreAckedByTheNextOne() throws Exception {
        stream = new FakeStream(true, false);
        BlockingQueue<Record<String>> queue = new LinkedBlockingQueue<>();
        List<TestRecord> synced = records(3, 0);
        queue.addAll(synced);
        syncThread = start(queue, 10);
        assertTrue(stream.firstSyncStarted.await(10, TimeUnit.SECONDS));

        TestRecord late = new TestRecord(0);
        queue.add(late);
        stream.releaseFirstSync();
        // Let the sync thread ack it, rather than halt()
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> late.acks.get() == 1);
        syncThread.halt();

        for (TestRecord record : synced) {
            assertEquals(record.acks.get(), 1);
            assertEquals(record.syncsBeforeAck, 1);
        }
        assertEquals(late.acks.get(), 1);
        assertTrue(late.syncsBeforeAck >= 2, "a record queued during a sync was acked by that sync");
    }

    /**
     * halt() stops the thread without waiting for the rest of the sync interval, and then syncs and
     * acks the records still queued.
     */
    @Test(timeOut = 30_000)
    public void testHaltDoesNotWaitForTheSyncInterval() throws Exception {
        stream = new FakeStream(false, false);
        BlockingQueue<Record<String>> queue = new LinkedBlockingQueue<>();
        List<TestRecord> records = records(2, 0);
        queue.addAll(records);
        syncThread = start(queue, TimeUnit.MINUTES.toMillis(10));

        syncThread.halt();

        assertFalse(syncThread.isAlive());
        assertEquals(stream.completedSyncs.get(), 1);
        for (TestRecord record : records) {
            assertEquals(record.acks.get(), 1);
        }
    }

    @Test(timeOut = 30_000)
    public void testHaltWithNothingQueuedDoesNotSync() throws Exception {
        stream = new FakeStream(false, false);
        syncThread = start(new LinkedBlockingQueue<>(), 10);

        syncThread.halt();

        assertFalse(syncThread.isAlive());
        assertEquals(stream.startedSyncs.get(), 0);
    }

    /**
     * A failed sync acks nothing; the records are acked once a later sync succeeds.
     */
    @Test(timeOut = 30_000)
    public void testFailedSyncAcksNothing() throws Exception {
        stream = new FakeStream(false, true);
        BlockingQueue<Record<String>> queue = new LinkedBlockingQueue<>();
        List<TestRecord> records = records(3, 0);
        queue.addAll(records);
        syncThread = start(queue, 10);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(queue::isEmpty);
        syncThread.halt();

        assertTrue(stream.startedSyncs.get() >= 2);
        for (TestRecord record : records) {
            assertEquals(record.acks.get(), 1);
            // The first sync failed, so the ack came after a later, successful one
            assertTrue(record.syncsBeforeAck >= 1);
        }
    }

    private HdfsSyncThread<String> start(BlockingQueue<Record<String>> queue, long syncInterval) {
        HdfsSyncThread<String> thread = new HdfsSyncThread<>(stream, queue, syncInterval);
        thread.start();
        return thread;
    }

    private List<TestRecord> records(int count, long ackDelayMs) {
        List<TestRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(new TestRecord(ackDelayMs));
        }
        return records;
    }

    /**
     * Counts its syncs. It can hold the first hsync() until released, and fail the first one.
     */
    private static final class FakeStream implements Syncable {
        private final AtomicInteger startedSyncs = new AtomicInteger();
        private final AtomicInteger completedSyncs = new AtomicInteger();
        private final CountDownLatch firstSyncStarted = new CountDownLatch(1);
        private final CountDownLatch firstSyncReleased;
        private final boolean failFirstSync;

        FakeStream(boolean holdFirstSync, boolean failFirstSync) {
            this.firstSyncReleased = new CountDownLatch(holdFirstSync ? 1 : 0);
            this.failFirstSync = failFirstSync;
        }

        void releaseFirstSync() {
            firstSyncReleased.countDown();
        }

        @Override
        public void hflush() {
        }

        @Override
        public void hsync() throws IOException {
            if (startedSyncs.getAndIncrement() == 0) {
                firstSyncStarted.countDown();
                try {
                    if (!firstSyncReleased.await(20, TimeUnit.SECONDS)) {
                        throw new IOException("The first hsync() was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                if (failFirstSync) {
                    throw new IOException("Simulated hsync() failure");
                }
            }
            completedSyncs.incrementAndGet();
        }
    }

    /**
     * Remembers how many syncs had completed when it was acked. Tracks how many records are being
     * acked at the same time, across all records of the test.
     */
    private final class TestRecord implements Record<String> {
        private final long ackDelayMs;
        private final AtomicInteger acks = new AtomicInteger();
        private volatile int syncsBeforeAck = -1;

        TestRecord(long ackDelayMs) {
            this.ackDelayMs = ackDelayMs;
        }

        @Override
        public String getValue() {
            return "value";
        }

        @Override
        public void ack() {
            syncsBeforeAck = stream.completedSyncs.get();
            maxConcurrentAcks.accumulateAndGet(concurrentAcks.incrementAndGet(), Math::max);
            try {
                if (ackDelayMs > 0) {
                    Thread.sleep(ackDelayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentAcks.decrementAndGet();
            }
            acks.incrementAndGet();
        }
    }
}
