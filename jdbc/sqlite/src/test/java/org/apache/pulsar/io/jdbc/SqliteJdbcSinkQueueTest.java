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
package org.apache.pulsar.io.jdbc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.jdbc.SqliteJdbcSinkTest.Foo;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for the internal queue of the JDBC sink and for what happens to queued records when the
 * sink fails or is closed. Unlike {@link SqliteJdbcSinkTest}, not repeated with the JDBC batch API.
 */
public class SqliteJdbcSinkQueueTest {
    private static final String TABLE_NAME = "TestOpenAndWriteSink";
    private final SqliteUtils sqliteUtils = new SqliteUtils(UUID.randomUUID().toString());
    private SqliteJdbcAutoSchemaSink sink;
    // See blockFlushes()
    private CountDownLatch flushBlocked;
    private CountDownLatch releaseFlush;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        sqliteUtils.setUp();
        sqliteUtils.createTable("CREATE TABLE " + TABLE_NAME
                + "(field1 TEXT, field2 TEXT, field3 INTEGER, PRIMARY KEY (field1));");
        flushBlocked = new CountDownLatch(1);
        releaseFlush = new CountDownLatch(1);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        releaseFlush.countDown();
        if (sink != null) {
            sink.close();
            sink = null;
        }
        sqliteUtils.tearDown();
    }

    /**
     * Test that a full queue applies back-pressure by blocking write(), not by failing records:
     * while a flush is stuck, write() keeps waiting well past the offer interval, and its record is
     * accepted once the flush completes and the next one frees up space.
     */
    @Test
    public void testBoundedQueueBackPressure() throws Exception {
        final int capacity = 5;
        openSink(createConfig(capacity, capacity), null);
        blockFlushes(0);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(2 * capacity + 1, results);
        Record<GenericObject> blocked = records.get(2 * capacity);

        // The first batch is drained by a flush that gets stuck, the second batch fills the queue
        for (int i = 0; i < 2 * capacity; i++) {
            sink.write(records.get(i));
            if (i == capacity - 1) {
                Assert.assertTrue(flushBlocked.await(10, TimeUnit.SECONDS));
            }
        }
        Assert.assertEquals(queue().size(), capacity);
        CompletableFuture<Boolean> writeDone = new CompletableFuture<>();
        awaitBlockedInWrite(startWriter(blocked, writeDone));

        // Well past the 1 s offer interval, after which write() used to fail the record
        Thread.sleep(1500);
        Assert.assertFalse(writeDone.isDone(), "write() should still be blocked on the full queue");
        Assert.assertTrue(results.stream().noneMatch(CompletableFuture::isDone));

        // The stuck flush completes, and the next one frees up space
        releaseFlush.countDown();
        writeDone.get(10, TimeUnit.SECONDS);
        for (int i = 0; i < 2 * capacity; i++) {
            Assert.assertTrue(results.get(i).get(10, TimeUnit.SECONDS));
        }
        // Accepted: still queued, or written by a flush that the waiting write() scheduled
        awaitScheduledFlushes();
        Assert.assertTrue(queue().contains(blocked) || results.get(2 * capacity).getNow(false));
    }

    /**
     * Test that a write() blocked on a full queue schedules a flush after one offer interval when
     * no flush is due (only a time-based flush, with an interval far longer than the test).
     */
    @Test
    public void testBlockedWriteSchedulesFlush() throws Exception {
        Map<String, Object> conf = createConfig(0, 2);
        conf.put("timeoutMs", (int) TimeUnit.MINUTES.toMillis(10));
        openSink(conf, null);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(3, results);
        sink.write(records.get(0));
        sink.write(records.get(1));

        CompletableFuture<Boolean> writeDone = new CompletableFuture<>();
        long start = System.nanoTime();
        startWriter(records.get(2), writeDone);
        writeDone.get(10, TimeUnit.SECONDS);
        long blockedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        Assert.assertTrue(blockedMs >= 900, "write() returned after " + blockedMs + " ms");
        Assert.assertTrue(results.get(0).get(10, TimeUnit.SECONDS));
        Assert.assertTrue(results.get(1).get(10, TimeUnit.SECONDS));
        Assert.assertEquals(new ArrayList<>(queue()), records.subList(2, 3));
        Assert.assertFalse(results.get(2).isDone());
    }

    @DataProvider(name = "interruptOnFatal")
    public Object[] interruptOnFatal() {
        return new Object[] {false, true};
    }

    /**
     * Test that a write() blocked on a full queue fails its record and returns when a flush fails
     * and calls fatal(), as nothing frees up space after that. The Pulsar instance interrupts the
     * instance thread after fatal(), which wakes write() at once. Without an interrupt, write()
     * notices the FAILED state within one offer interval.
     */
    @Test(dataProvider = "interruptOnFatal")
    public void testBlockedWriteFailsWhenFlushFailsFatally(boolean interruptOnFatal) throws Exception {
        SinkContext sinkContext = mock(SinkContext.class);
        AtomicReference<Thread> writer = new AtomicReference<>();
        CompletableFuture<Long> fatalNanos = new CompletableFuture<>();
        doAnswer(invocation -> {
            fatalNanos.complete(System.nanoTime());
            if (interruptOnFatal) {
                writer.get().interrupt();
            }
            return null;
        }).when(sinkContext).fatal(any(Throwable.class));
        openSink(createConfig(2, 2), sinkContext);
        blockFlushes(1);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(5, results);

        // A full batch starts a flush that gets stuck. Fill the queue directly, so that no other
        // flush is scheduled.
        sink.write(records.get(0));
        sink.write(records.get(1));
        Assert.assertTrue(flushBlocked.await(10, TimeUnit.SECONDS));
        queue().addAll(records.subList(2, 4));
        CompletableFuture<Boolean> interruptedAfterWrite = new CompletableFuture<>();
        writer.set(startWriter(records.get(4), interruptedAfterWrite));
        awaitBlockedInWrite(writer.get());

        releaseFlush.countDown();
        long fatalAt = fatalNanos.get(10, TimeUnit.SECONDS);
        boolean interrupted = interruptedAfterWrite.get(10, TimeUnit.SECONDS);
        long exitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fatalAt);

        Assert.assertEquals(FieldUtils.readField(sink, "state", true).toString(), "FAILED");
        Assert.assertFalse(results.get(4).get(1, TimeUnit.SECONDS));
        Assert.assertEquals(interrupted, interruptOnFatal, "The interrupt flag should be restored");
        // The interrupt wakes write() without waiting for the end of the 1 s offer interval
        Assert.assertTrue(exitMs < (interruptOnFatal ? 500 : 3000), "write() returned after " + exitMs + " ms");
        // The failed batch is failed, the queued records are left for close()
        Assert.assertFalse(results.get(0).get(1, TimeUnit.SECONDS));
        Assert.assertFalse(results.get(1).get(1, TimeUnit.SECONDS));
        Assert.assertEquals(new ArrayList<>(queue()), records.subList(2, 4));
        Assert.assertFalse(results.get(2).isDone() || results.get(3).isDone());
    }

    /**
     * Test that a flush scheduled before another flush fails does not run once the sink is FAILED.
     * It would ack records newer than the failed batch, which the redelivered older records could
     * then overwrite (e.g. in UPSERT mode).
     */
    @Test
    public void testNoFlushAfterFatal() throws Exception {
        SinkContext sinkContext = mock(SinkContext.class);
        openSink(createConfig(2, -1), sinkContext);
        // Only the first insert fails, so a later flush would succeed
        blockFlushes(1);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(4, results);

        // The first batch starts a flush that gets stuck, the second schedules another flush
        sink.write(records.get(0));
        sink.write(records.get(1));
        Assert.assertTrue(flushBlocked.await(10, TimeUnit.SECONDS));
        sink.write(records.get(2));
        sink.write(records.get(3));
        releaseFlush.countDown();
        awaitScheduledFlushes();

        verify(sinkContext).fatal(any(SQLException.class));
        Assert.assertFalse(results.get(0).get(1, TimeUnit.SECONDS));
        Assert.assertFalse(results.get(1).get(1, TimeUnit.SECONDS));
        Assert.assertEquals(new ArrayList<>(queue()), records.subList(2, 4), "A flush ran after fatal()");
        Assert.assertFalse(results.get(2).isDone() || results.get(3).isDone());
    }

    /**
     * Test that a flush draining a backlog does not start another round once the sink is no longer
     * OPEN, e.g. closed while a round was stuck. It would write and ack records that close() is
     * failing. The remaining records are failed by close(), exactly once.
     */
    @Test
    public void testFlushStopsBetweenRoundsWhenSinkClosed() throws Exception {
        openSink(createConfig(2, -1), null);
        blockFlushes(0);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(6, results);
        for (int i = 2; i < 6; i++) {
            records.set(i, spy(records.get(i)));
        }
        // Queue a backlog of three batches, so that the flush started by the last write() has
        // more rounds to drain after the first one, which gets stuck
        queue().addAll(records.subList(0, 5));
        sink.write(records.get(5));
        Assert.assertTrue(flushBlocked.await(10, TimeUnit.SECONDS));

        // Like close() does first, without waiting for the flush
        setState("CLOSED");
        releaseFlush.countDown();
        awaitScheduledFlushes();

        Assert.assertTrue(results.get(0).get(1, TimeUnit.SECONDS));
        Assert.assertTrue(results.get(1).get(1, TimeUnit.SECONDS));
        Assert.assertEquals(new ArrayList<>(queue()), records.subList(2, 6), "Another round ran after close()");
        sink.close();
        for (int i = 2; i < 6; i++) {
            verify(records.get(i)).fail();
            verify(records.get(i), never()).ack();
        }
    }

    /**
     * Test that close() waits for an in-flight flush before failing the queued records and closing
     * the connection: the batch being written is committed and acked, and the queued records are
     * failed only after that.
     */
    @Test
    public void testCloseWaitsForInFlightFlush() throws Exception {
        openSink(createConfig(2, -1), null);
        blockFlushes(0);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(4, results);
        List<Integer> completionOrder = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < results.size(); i++) {
            int index = i;
            results.get(i).thenRun(() -> completionOrder.add(index));
        }
        Connection connection = sink.getConnection();

        // A full batch starts a flush that gets stuck. Queue another batch directly, so that no
        // other flush is scheduled.
        sink.write(records.get(0));
        sink.write(records.get(1));
        Assert.assertTrue(flushBlocked.await(10, TimeUnit.SECONDS));
        queue().addAll(records.subList(2, 4));
        CompletableFuture<Void> closeDone = new CompletableFuture<>();
        Thread closer = new Thread(() -> {
            try {
                sink.close();
                closeDone.complete(null);
            } catch (Throwable t) {
                closeDone.completeExceptionally(t);
            }
        }, "closer");
        closer.setDaemon(true);
        closer.start();
        awaitTimedWaiting(closer);

        Assert.assertFalse(closeDone.isDone(), "close() should wait for the flush");
        Assert.assertTrue(results.stream().noneMatch(CompletableFuture::isDone));
        Assert.assertFalse(connection.isClosed());
        releaseFlush.countDown();
        closeDone.get(10, TimeUnit.SECONDS);

        Assert.assertTrue(results.get(0).get(1, TimeUnit.SECONDS));
        Assert.assertTrue(results.get(1).get(1, TimeUnit.SECONDS));
        Assert.assertFalse(results.get(2).get(1, TimeUnit.SECONDS));
        Assert.assertFalse(results.get(3).get(1, TimeUnit.SECONDS));
        Assert.assertEquals(completionOrder, List.of(0, 1, 2, 3));
        Assert.assertTrue(queue().isEmpty());
        Assert.assertTrue(connection.isClosed());
    }

    /**
     * Test that a failed flush still fails the other records of the batch, rolls back and calls
     * fatal() when Record.fail() throws, as the Pulsar source's fail function does under
     * EFFECTIVELY_ONCE.
     */
    @Test
    public void testFailedFlushContinuesWhenRecordFailThrows() throws Exception {
        SinkContext sinkContext = mock(SinkContext.class);
        openSink(createConfig(3, -1), sinkContext);
        Connection realConnection = sink.getConnection();
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        FieldUtils.writeField(sink, "connection", connection, true);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.execute()).thenThrow(new SQLException("Simulated database failure"));
        FieldUtils.writeField(sink, "insertStatement", statement, true);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(3, results);
        RuntimeException failError = new RuntimeException("Simulated fail() failure");
        // The first record of the batch is failed first
        records.set(0, failThrows(records.get(0), failError));

        for (Record<GenericObject> record : records) {
            sink.write(record);
        }
        awaitScheduledFlushes();

        ArgumentCaptor<Throwable> fatalError = ArgumentCaptor.forClass(Throwable.class);
        verify(sinkContext).fatal(fatalError.capture());
        Assert.assertTrue(fatalError.getValue() instanceof SQLException);
        Assert.assertEquals(fatalError.getValue().getSuppressed(), new Throwable[] {failError});
        verify(connection).rollback();
        Assert.assertEquals(FieldUtils.readField(sink, "state", true).toString(), "FAILED");
        for (CompletableFuture<Boolean> result : results) {
            Assert.assertFalse(result.get(1, TimeUnit.SECONDS));
        }
        realConnection.close();
    }

    /**
     * Test that a JDBC batch with a failed item still fails all the records and calls fatal() when
     * Record.fail() throws, both in executeBatch() and in the flush error handling, here with the
     * same exception instance.
     */
    @Test
    public void testFailedJdbcBatchContinuesWhenRecordFailThrows() throws Exception {
        SinkContext sinkContext = mock(SinkContext.class);
        Map<String, Object> conf = createConfig(3, -1);
        conf.put("useJdbcBatch", true);
        openSink(conf, sinkContext);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeBatch()).thenReturn(new int[] {Statement.EXECUTE_FAILED, 1, 1});
        FieldUtils.writeField(sink, "insertStatement", statement, true);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        List<Record<GenericObject>> records = createRecords(3, results);
        // executeBatch() fails the records in order: the first fail() throws out of executeBatch(),
        // the error handling fails the other records, and the second fail() throws the same instance
        RuntimeException failError = new RuntimeException("Simulated fail() failure");
        records.set(0, failThrows(records.get(0), failError));
        records.set(1, failThrows(records.get(1), failError));

        for (Record<GenericObject> record : records) {
            sink.write(record);
        }
        awaitScheduledFlushes();

        verify(sinkContext).fatal(failError);
        Assert.assertEquals(FieldUtils.readField(sink, "state", true).toString(), "FAILED");
        for (CompletableFuture<Boolean> result : results) {
            Assert.assertFalse(result.get(1, TimeUnit.SECONDS));
        }
    }

    /**
     * Test that a maxQueueSize smaller than batchSize is raised to batchSize, so that a full batch
     * fits in the queue and triggers a flush.
     */
    @Test
    public void testMaxQueueSizeSmallerThanBatchSizeUsesBatchSize() throws Exception {
        openSink(createConfig(200, 199), null);
        Assert.assertEquals(sink.jdbcSinkConfig.getMaxQueueSize(), 200);
        Assert.assertEquals(((LinkedBlockingDeque<?>) queue()).remainingCapacity(), 200);
    }

    /**
     * Test that close() does not throw when the sink was never opened, or when open() failed
     * before the queue was created. Pulsar IO calls close() after a failed open().
     */
    @Test
    public void testCloseWithoutSuccessfulOpen() throws Exception {
        new SqliteJdbcAutoSchemaSink().close();

        // open() fails after the connection is opened (unknown table), before the queue exists
        Map<String, Object> conf = createConfig(1, -1);
        conf.put("tableName", "no_such_table");
        sink = new SqliteJdbcAutoSchemaSink();
        Assert.assertThrows(Exception.class, () -> sink.open(conf, null));
        Connection connection = sink.getConnection();
        Assert.assertNotNull(connection);
        sink.close();
        Assert.assertTrue(connection.isClosed());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCloseFailsQueuedRecords() throws Exception {
        openSink(createConfig(1000, -1), null);
        List<Record<GenericObject>> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            records.add(mock(Record.class));
            sink.write(records.get(i));
            verify(records.get(i), never()).fail();
        }

        sink.close();
        for (Record<GenericObject> record : records) {
            verify(record).fail();
            verify(record, never()).ack();
        }
        Assert.assertTrue(queue().isEmpty());
    }

    @DataProvider(name = "useTransactions")
    public Object[] useTransactions() {
        return new Object[] {true, false};
    }

    /**
     * Test that close() still fails the other queued records and shuts everything down when
     * Record.fail() throws, as the Pulsar source's fail function does under EFFECTIVELY_ONCE.
     */
    @Test(dataProvider = "useTransactions")
    @SuppressWarnings("unchecked")
    public void testCloseContinuesWhenRecordFailThrows(boolean useTransactions) throws Exception {
        Map<String, Object> conf = createConfig(1000, -1);
        conf.put("useTransactions", useTransactions);
        openSink(conf, null);
        Connection connection = sink.getConnection();
        ScheduledExecutorService flushExecutor =
                (ScheduledExecutorService) FieldUtils.readField(sink, "flushExecutor", true);
        Record<GenericObject> throwingRecord = mock(Record.class);
        doThrow(new RuntimeException("Simulated fail() failure")).when(throwingRecord).fail();
        Record<GenericObject> record = mock(Record.class);
        // The throwing record is queued first, so it is failed first
        sink.write(throwingRecord);
        sink.write(record);

        sink.close();

        verify(throwingRecord).fail();
        verify(record).fail();
        Assert.assertTrue(queue().isEmpty());
        Assert.assertTrue(flushExecutor.isShutdown());
        Assert.assertTrue(connection.isClosed());
    }

    private enum CloseRace {
        // close() drains the queue while write() waits in offer(): write() must take the record back
        BEFORE_OFFER,
        // close() drains and fails the record after offer() accepted it: write() must leave it alone
        AFTER_OFFER,
        // close() shuts the flush executor down right before write() schedules a flush
        BEFORE_FLUSH_SCHEDULED
    }

    @DataProvider(name = "closeRaces")
    public Object[] closeRaces() {
        return CloseRace.values();
    }

    /**
     * Test that a record is failed exactly once, and write() does not throw, when close() runs at
     * different points of a write(). close() is called from the queue methods that write() calls.
     */
    @Test(dataProvider = "closeRaces")
    @SuppressWarnings("unchecked")
    public void testCloseRacesWrite(CloseRace race) throws Exception {
        openSink(createConfig(1, -1), null);
        FieldUtils.writeField(sink, "incomingList", new LinkedBlockingDeque<Record<GenericObject>>() {
            @Override
            public boolean offer(Record<GenericObject> e, long timeout, TimeUnit unit)
                    throws InterruptedException {
                closeIf(race == CloseRace.BEFORE_OFFER);
                boolean accepted = super.offer(e, timeout, unit);
                closeIf(race == CloseRace.AFTER_OFFER);
                return accepted;
            }

            @Override
            public int size() {
                int size = super.size();
                closeIf(race == CloseRace.BEFORE_FLUSH_SCHEDULED);
                return size;
            }

            private void closeIf(boolean condition) {
                if (condition) {
                    try {
                        sink.close();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        }, true);

        Record<GenericObject> record = mock(Record.class);
        sink.write(record);

        verify(record).fail();
        verify(record, never()).ack();
        Assert.assertTrue(queue().isEmpty());
    }

    /**
     * Test that write() takes back the record it queued, and not another queued record that is
     * equal to it, when the sink is no longer OPEN after offer() accepted the record.
     */
    @Test
    public void testWriteTakesBackItsOwnRecordAfterOffer() throws Exception {
        openSink(createConfig(10, -1), null);
        EqualRecord record = new EqualRecord();
        EqualRecord equalRecord = new EqualRecord();
        // After offer(), an equal record is queued behind the record, and the sink fails
        FieldUtils.writeField(sink, "incomingList", new LinkedBlockingDeque<Record<GenericObject>>() {
            @Override
            public boolean offer(Record<GenericObject> e, long timeout, TimeUnit unit)
                    throws InterruptedException {
                boolean accepted = super.offer(e, timeout, unit);
                add(equalRecord);
                try {
                    setState("FAILED");
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException(ex);
                }
                return accepted;
            }
        }, true);

        sink.write(record);

        Assert.assertEquals(record.failCount, 1);
        Assert.assertEquals(equalRecord.failCount, 0);
        Assert.assertEquals(queue().size(), 1);
        Assert.assertSame(queue().peek(), equalRecord);
    }

    /**
     * A record that is equal to every other EqualRecord, and counts its fail() calls.
     */
    private static class EqualRecord implements Record<GenericObject> {
        private int failCount;

        @Override
        public GenericObject getValue() {
            return null;
        }

        @Override
        public void fail() {
            failCount++;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EqualRecord;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    /**
     * Sink config without a time-based flush.
     */
    private Map<String, Object> createConfig(int batchSize, int maxQueueSize) {
        Map<String, Object> conf = new HashMap<>();
        conf.put("jdbcUrl", sqliteUtils.sqliteUri());
        conf.put("tableName", TABLE_NAME);
        conf.put("key", "field3");
        conf.put("nonKey", "field1,field2");
        conf.put("batchSize", batchSize);
        conf.put("maxQueueSize", maxQueueSize);
        conf.put("timeoutMs", 0);
        return conf;
    }

    private void openSink(Map<String, Object> conf, SinkContext sinkContext) throws Exception {
        sink = new SqliteJdbcAutoSchemaSink();
        sink.open(conf, sinkContext);
    }

    @SuppressWarnings("unchecked")
    private Deque<Record<GenericObject>> queue() throws IllegalAccessException {
        return (Deque<Record<GenericObject>>) FieldUtils.readField(sink, "incomingList", true);
    }

    /**
     * Set the state of the sink to the {@code JdbcAbstractSink.State} constant {@code name}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setState(String name) throws IllegalAccessException {
        AtomicReference<Object> state = (AtomicReference<Object>) FieldUtils.readField(sink, "state", true);
        state.set(Enum.valueOf((Class) state.get().getClass(), name));
    }

    /**
     * Wait until the flushes scheduled so far have run. The flush executor has a single thread.
     */
    private void awaitScheduledFlushes() throws Exception {
        ((ScheduledExecutorService) FieldUtils.readField(sink, "flushExecutor", true))
                .submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    /**
     * Replace the insert statement with a mock. Each execute() counts down flushBlocked and waits
     * for releaseFlush, then the first {@code failures} calls throw.
     */
    private void blockFlushes(int failures) throws Exception {
        AtomicInteger remainingFailures = new AtomicInteger(failures);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.execute()).thenAnswer(invocation -> {
            flushBlocked.countDown();
            if (!releaseFlush.await(30, TimeUnit.SECONDS)) {
                throw new TimeoutException("Flush was never released");
            }
            if (remainingFailures.getAndDecrement() > 0) {
                throw new SQLException("Simulated database failure");
            }
            return false;
        });
        FieldUtils.writeField(sink, "insertStatement", statement, true);
    }

    /**
     * Create {@code count} records and add the result of each to {@code results}: true once it is
     * acked, false once it is failed.
     */
    private static List<Record<GenericObject>> createRecords(int count, List<CompletableFuture<Boolean>> results) {
        List<Record<GenericObject>> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            results.add(result);
            records.add(SqliteJdbcSinkTest.createMockFooRecord(new Foo("f" + i, "f2", i), new HashMap<>(), result));
        }
        return records;
    }

    /**
     * Wrap {@code record} so that fail() fails it and then throws {@code error}.
     */
    private static Record<GenericObject> failThrows(Record<GenericObject> record, RuntimeException error) {
        Record<GenericObject> throwing = spy(record);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw error;
        }).when(throwing).fail();
        return throwing;
    }

    /**
     * Call write() on a new thread. {@code done} completes when write() returns, with whether the
     * thread's interrupt flag is set at that point.
     */
    private Thread startWriter(Record<GenericObject> record, CompletableFuture<Boolean> done) {
        Thread writer = new Thread(() -> {
            try {
                sink.write(record);
                done.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        }, "blocked-writer");
        writer.setDaemon(true);
        writer.start();
        return writer;
    }

    /**
     * Wait until {@code writer} waits for queue space in write(), the only timed wait there. Polls
     * every millisecond so that the wait is noticed long before the offer interval ends.
     */
    private static void awaitBlockedInWrite(Thread writer) throws Exception {
        awaitTimedWaiting(writer);
    }

    /**
     * Wait until {@code thread} is in a timed wait, polling every millisecond.
     */
    private static void awaitTimedWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                throw new TimeoutException(thread.getName() + " did not block, state: " + thread.getState());
            }
            Thread.sleep(1);
        }
    }
}
