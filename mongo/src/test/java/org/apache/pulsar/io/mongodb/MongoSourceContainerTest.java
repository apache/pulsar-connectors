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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *
 * <p>The test seeds documents <em>before</em> opening the source and configures {@code
 * syncType=FULL_SYNC}, then asserts that exactly those seeded documents are replayed through the
 * change stream — so a pass proves the FULL_SYNC replay path, not merely that some record arrived.
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

    @BeforeMethod
    public void setUp() {
        mongoContainer = new MongoDBContainer("mongo:6.0")
                .withStartupTimeout(Duration.ofMinutes(3));
        mongoContainer.start();

        // Sync driver used only to write the test documents.
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
        // Seed the documents before the source starts. FULL_SYNC (startAtOperationTime=0) replays
        // them from the start of the stream, so these exact documents must be delivered.
        Set<String> expectedNames = new HashSet<>();
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            String name = "seed-" + i;
            insertDoc(name);
            expectedNames.add(name);
        }

        source.open(buildConfig(), mock(SourceContext.class));

        Set<String> receivedNames = new HashSet<>();
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
            assertTrue(expectedNames.contains(name),
                    "received an unexpected document '" + name + "'; FULL_SYNC should replay only "
                            + "the seeded documents " + expectedNames);
            log.info("Received change-stream record: key={} name={}",
                    record.getKey().orElse(null), name);
            receivedNames.add(name);
        }

        // Every seeded document was replayed exactly once through the change stream.
        assertEquals(receivedNames, expectedNames,
                "FULL_SYNC did not replay all seeded documents; got " + receivedNames);
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
