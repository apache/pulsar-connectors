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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.awaitility.Awaitility;
import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class MongoSinkContainerTest {

    private static final String DB = "testdb";
    private static final String COLLECTION = "messages";

    private MongoDBContainer mongoContainer;
    private MongoSink sink;

    @BeforeMethod
    public void setUp() {
        mongoContainer = new MongoDBContainer("mongo:6.0");
        mongoContainer.start();
        sink = new MongoSink();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (mongoContainer != null) {
            mongoContainer.stop();
        }
    }

    @Test
    public void testSinkWriteAndVerify() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("mongoUri", mongoContainer.getConnectionString());
        config.put("database", DB);
        config.put("collection", COLLECTION);
        config.put("batchSize", 2);
        config.put("batchTimeMs", 500);

        sink.open(config, mock(SinkContext.class));

        int numRecords = 3;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < numRecords; i++) {
            final String json = "{\"name\": \"record-" + i + "\", \"value\": " + i + "}";
            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);

            Record<byte[]> record = new Record<byte[]>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.empty();
                }

                @Override
                public byte[] getValue() {
                    return json.getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public void ack() {
                    future.complete(null);
                }

                @Override
                public void fail() {
                    future.completeExceptionally(new RuntimeException("Record failed"));
                }
            };
            sink.write(record);
        }

        // Wait for all records to be acknowledged
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        // Verify data in MongoDB using a sync driver
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(
                mongoContainer.getConnectionString())) {
            com.mongodb.client.MongoCollection<Document> coll = client.getDatabase(DB).getCollection(COLLECTION);

            Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(coll.countDocuments(), numRecords);
            });

            for (int i = 0; i < numRecords; i++) {
                Document doc = coll.find(new Document("name", "record-" + i)).first();
                assertNotNull(doc, "Document record-" + i + " not found");
                assertEquals(doc.getInteger("value").intValue(), i);
            }
        }
    }
}
