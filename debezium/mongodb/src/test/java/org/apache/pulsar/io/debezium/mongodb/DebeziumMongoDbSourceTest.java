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
package org.apache.pulsar.io.debezium.mongodb;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.bson.Document;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class DebeziumMongoDbSourceTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    /** Documents seeded before open(), each of which the initial snapshot must emit. */
    private static final int EXPECTED_RECORDS = 2;

    private static final int READ_TIMEOUT_SECONDS = 120;

    private MongoDBContainer mongoContainer;
    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private DebeziumMongoDbSource source;
    private ExecutorService readerExecutor;

    @BeforeMethod
    public void setup() throws Exception {
        readerExecutor = Executors.newSingleThreadExecutor();
        // MongoDBContainer starts a single-node replica set, which Debezium requires
        // for change streams.
        mongoContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
                .withStartupTimeout(Duration.ofMinutes(5));
        mongoContainer.start();

        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE))
                .withStartupTimeout(Duration.ofMinutes(5));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();

        // Create test collection and insert initial data
        try (MongoClient client = MongoClients.create(mongoContainer.getConnectionString())) {
            MongoCollection<Document> products = client.getDatabase("testdb").getCollection("products");
            products.insertOne(new Document("name", "widget").append("description", "A small widget"));
            products.insertOne(new Document("name", "gadget").append("description", "A fancy gadget"));
        }

        source = new DebeziumMongoDbSource();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() throws Exception {
        if (readerExecutor != null) {
            // shutdownNow: a reader may still be blocked in read(), which never returns null
            readerExecutor.shutdownNow();
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
        if (pulsarClient != null) {
            pulsarClient.close();
        }
        if (pulsarContainer != null) {
            pulsarContainer.stop();
        }
        if (mongoContainer != null) {
            mongoContainer.stop();
        }
    }

    @Test(timeOut = 600_000)
    public void testMongoDbCdcEvents() throws Exception {
        String pulsarServiceUrl = pulsarContainer.getPulsarBrokerUrl();

        SourceContext sourceContext = mock(SourceContext.class);
        when(sourceContext.getPulsarClient()).thenReturn(pulsarClient);
        when(sourceContext.getPulsarClientBuilder()).thenReturn(
                PulsarClient.builder().serviceUrl(pulsarServiceUrl));
        when(sourceContext.getTenant()).thenReturn("public");
        when(sourceContext.getNamespace()).thenReturn("default");
        when(sourceContext.getSourceName()).thenReturn("debezium-mongodb-test");
        when(sourceContext.getSecret(anyString())).thenReturn(null);

        Map<String, Object> config = new HashMap<>();
        config.put("mongodb.connection.string", mongoContainer.getConnectionString());
        config.put("topic.prefix", "mongodb1");
        config.put("collection.include.list", "testdb.products");

        source.open(config, sourceContext);

        // Debezium performs an initial snapshot of existing data.
        // We should receive CDC records for the 2 documents we inserted.
        int received = 0;
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            Record<KeyValue<byte[], byte[]>> record = readOne();
            assertNotNull(record.getValue());
            log.info("Received CDC record: key={}", record.getKey().orElse(null));
            record.ack();
            received++;
        }
        assertEquals(received, EXPECTED_RECORDS);
    }

    /**
     * Reads a single record, failing if none arrives in time.
     *
     * <p>{@code AbstractKafkaConnectSource.read()} loops until a record is available and never
     * returns null, so calling it on the test thread means an under-delivering connector hangs
     * the test — and, since Awaitility cannot interrupt a blocked assertion, the whole CI job
     * until its own timeout. Run it on a separate thread so a missing record surfaces as a
     * prompt, diagnosable failure instead.
     */
    private Record<KeyValue<byte[], byte[]>> readOne() throws Exception {
        Future<Record<KeyValue<byte[], byte[]>>> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a CDC record from the initial snapshot. "
                    + "The connector produced no record; see the Debezium logs above.", e);
        }
    }
}
