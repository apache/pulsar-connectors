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
package org.apache.pulsar.io.mongodb;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test for {@link MongoSource} that exercises the full path from a real MongoDB change
 * stream into the source's record queue. A {@link MongoDBContainer} provides a single-node replica
 * set (which change streams require) and the source is driven end-to-end against it.
 */
@Slf4j
public class MongoSourceContainerTest {

    private static final String DB = "testdb";
    private static final String COLLECTION = "messages";

    // Change streams surface within seconds, but give each read a generous deadline so a
    // non-delivering source fails promptly rather than hanging the suite.
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int EXPECTED_RECORDS = 3;

    private MongoDBContainer mongoContainer;
    private com.mongodb.client.MongoClient verifyClient;
    private MongoSource source;
    private ExecutorService readerExecutor;
    private Thread writerThread;
    private final AtomicBoolean keepWriting = new AtomicBoolean(false);

    @BeforeMethod
    public void setUp() {
        mongoContainer = new MongoDBContainer("mongo:6.0")
                .withStartupTimeout(Duration.ofMinutes(3));
        mongoContainer.start();

        // Sync driver used only to write test documents and drive the change stream.
        verifyClient = com.mongodb.client.MongoClients.create(mongoContainer.getConnectionString());

        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mongo-it-reader");
            t.setDaemon(true);
            return t;
        });

        source = new MongoSource();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        keepWriting.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (readerExecutor != null) {
            // A reader may still be blocked in read(), which never returns null.
            readerExecutor.shutdownNow();
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
        if (verifyClient != null) {
            verifyClient.close();
        }
        if (mongoContainer != null) {
            mongoContainer.stop();
        }
    }

    @Test(timeOut = 300_000)
    public void testReadFromChangeStream() throws Exception {
        // Seed some documents before the source starts; FULL_SYNC replays them from the start.
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            insertDoc("seed-" + i);
        }

        // Keep writing after open so the change stream has a steady supply regardless of
        // exactly when the subscription becomes active.
        source.open(buildConfig(), mock(SourceContext.class));

        startBackgroundWriter();

        int received = 0;
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            Record<byte[]> record = readOne();
            assertNotNull(record, "read() returned null");
            assertTrue(record.getKey().isPresent(), "record had no key (document key)");

            Document value = Document.parse(new String(record.getValue(), StandardCharsets.UTF_8));
            assertEquals(value.getString("operation"), "INSERT",
                    "unexpected operation; got " + value.getString("operation"));
            assertNotNull(value.get("ns"), "record missing ns");
            Document fullDocument = value.get("fullDocument", Document.class);
            assertNotNull(fullDocument, "record missing fullDocument");

            String name = fullDocument.getString("name");
            assertNotNull(name, "fullDocument missing name");
            assertTrue(name.startsWith("seed-"), "unexpected fullDocument name: " + name);

            log.info("Received change-stream record: key={} name={}",
                    record.getKey().orElse(null), name);
            received++;
        }
        assertTrue(received >= EXPECTED_RECORDS,
                "Expected at least " + EXPECTED_RECORDS + " records, got " + received);
    }

    /**
     * Reads a single record on a bounded worker thread, failing (rather than hanging) if none
     * arrives in time. {@link MongoSource} is a {@code PushSource}; its {@code read()} blocks
     * forever on an empty queue, so it must never be called on the test thread.
     */
    private Record<byte[]> readOne() throws Exception {
        Future<Record<byte[]>> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a record from the MongoDB change stream. The source produced "
                    + "no record; see the logs above.", e);
        }
    }

    private void startBackgroundWriter() {
        keepWriting.set(true);
        final AtomicInteger counter = new AtomicInteger();
        writerThread = new Thread(() -> {
            while (keepWriting.get()) {
                try {
                    insertDoc("live-" + counter.incrementAndGet());
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Background write failed", e);
                }
            }
        }, "mongo-it-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void insertDoc(String name) {
        verifyClient.getDatabase(DB).getCollection(COLLECTION)
                .insertOne(new Document("name", name));
    }

    private Map<String, Object> buildConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("mongoUri", mongoContainer.getConnectionString());
        config.put("database", DB);
        config.put("collection", COLLECTION);
        config.put("batchSize", 2);
        config.put("batchTimeMs", 500);
        // FULL_SYNC replays existing documents from the start of the stream, making delivery of the
        // pre-seeded documents deterministic rather than racing the subscription becoming active.
        config.put("syncType", "FULL_SYNC");
        return config;
    }
}
