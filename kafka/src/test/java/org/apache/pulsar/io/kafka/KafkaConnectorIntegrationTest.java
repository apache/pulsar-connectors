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
package org.apache.pulsar.io.kafka;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.SourceContext;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration tests for the Kafka source and sink connectors, exercised against a real Kafka
 * broker via Testcontainers.
 *
 * <p>Unlike the other Kafka unit tests in this module (which mock the broker), these tests wire
 * the connectors up to an actual {@link KafkaContainer} and verify end-to-end delivery:
 * <ul>
 *   <li><b>Sink</b>: records are written through {@link KafkaBytesSink} and then read back from
 *       the topic with a plain {@link KafkaConsumer}; keys and payloads are asserted.</li>
 *   <li><b>Source</b>: messages are produced to a topic with a plain {@link KafkaProducer} and
 *       then {@link KafkaBytesSource#read()} is asserted to yield them.</li>
 * </ul>
 *
 * <p>{@link KafkaBytesSource} extends {@code PushSource}, whose {@code read()} blocks on an
 * internal queue and never returns when the topic is drained. Every {@code read()} is therefore
 * performed on a bounded worker thread with a per-record deadline (see {@link #readOne}) so the
 * test can fail fast instead of hanging.
 */
@Slf4j
public class KafkaConnectorIntegrationTest {

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.9.1");

    private KafkaContainer kafka;
    private ExecutorService readerExecutor;

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        kafka = new KafkaContainer(KAFKA_IMAGE);
        kafka.start();
        readerExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    /**
     * Writes records through the Kafka sink connector and verifies that a plain KafkaConsumer can
     * read the exact keys and payloads back from the topic.
     */
    @Test(timeOut = 300_000)
    public void testSinkWritesToKafka() throws Exception {
        String topic = "sink-it-" + UUID.randomUUID();

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrapServers", kafka.getBootstrapServers());
        config.put("topic", topic);
        config.put("acks", "all");
        config.put("batchSize", 1);

        // key -> payload the sink should deliver to Kafka, in insertion order.
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("k0", "sink-value-0".getBytes(StandardCharsets.UTF_8));
        expected.put("k1", "sink-value-1".getBytes(StandardCharsets.UTF_8));
        expected.put("k2", "sink-value-2".getBytes(StandardCharsets.UTF_8));

        KafkaBytesSink sink = new KafkaBytesSink();
        List<CompletableFuture<Boolean>> acks = new ArrayList<>();
        try {
            sink.open(config, Mockito.mock(SinkContext.class));

            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                CompletableFuture<Boolean> ackFuture = new CompletableFuture<>();
                acks.add(ackFuture);
                sink.write(new MockRecord(entry.getKey(), entry.getValue(), ackFuture));
            }

            // Every record must be acked by the sink's producer callback.
            for (CompletableFuture<Boolean> ack : acks) {
                assertTrue(ack.get(60, TimeUnit.SECONDS), "sink should have acked the record");
            }
        } finally {
            sink.close();
        }

        // Read the topic back with a vanilla consumer and assert key + payload.
        Map<String, byte[]> actual = consumeAll(topic, expected.size());
        assertEquals(actual.size(), expected.size(), "unexpected number of records in Kafka topic");
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertTrue(actual.containsKey(entry.getKey()), "missing key " + entry.getKey());
            assertEquals(new String(actual.get(entry.getKey()), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8),
                    "payload mismatch for key " + entry.getKey());
        }
    }

    /**
     * Produces messages to a Kafka topic with a plain KafkaProducer and verifies that the Kafka
     * source connector reads back the exact keys and payloads.
     */
    @Test(timeOut = 300_000)
    public void testSourceReadsFromKafka() throws Exception {
        String topic = "source-it-" + UUID.randomUUID();

        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("sk0", "source-value-0".getBytes(StandardCharsets.UTF_8));
        expected.put("sk1", "source-value-1".getBytes(StandardCharsets.UTF_8));
        expected.put("sk2", "source-value-2".getBytes(StandardCharsets.UTF_8));

        produce(topic, expected);

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrapServers", kafka.getBootstrapServers());
        config.put("topic", topic);
        config.put("groupId", "source-it-group-" + UUID.randomUUID());
        config.put("autoOffsetReset", "earliest");

        KafkaBytesSource source = new KafkaBytesSource();
        Map<String, byte[]> actual = new HashMap<>();
        try {
            source.open(config, Mockito.mock(SourceContext.class));

            // Collect records off the source's queue with a per-record deadline until we have
            // them all, bounded overall by Awaitility.
            Awaitility.await()
                    .atMost(120, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Record<ByteBuffer> record = readOne(source, 2000);
                        if (record != null) {
                            assertNotNull(record.getValue(), "source record value should not be null");
                            String key = record.getKey().orElse(null);
                            assertNotNull(key, "source record key should not be null");
                            actual.put(key, toArray(record.getValue()));
                            record.ack();
                        }
                        assertEquals(actual.size(), expected.size(),
                                "did not yet read all records from the source");
                    });
        } finally {
            source.close();
        }

        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertTrue(actual.containsKey(entry.getKey()), "missing key " + entry.getKey());
            assertEquals(new String(actual.get(entry.getKey()), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8),
                    "payload mismatch for key " + entry.getKey());
        }
    }

    /**
     * Reads a single record from the source on a bounded worker thread. {@code PushSource.read()}
     * blocks forever once the queue is drained, so we impose a per-record deadline and return
     * {@code null} (interrupting the blocked read) when no record arrives in time.
     */
    private Record<ByteBuffer> readOne(KafkaBytesSource source, long timeoutMs) throws Exception {
        Future<Record<ByteBuffer>> future = readerExecutor.submit(source::read);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private Map<String, byte[]> consumeAll(String topic, int expectedCount) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        Map<String, byte[]> collected = new HashMap<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            Awaitility.await()
                    .atMost(60, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                        for (ConsumerRecord<String, byte[]> record : records) {
                            // The header hook is a natural place to assert Pulsar-property ->
                            // Kafka-header propagation once PR #36 lands.
                            collected.put(record.key(), record.value());
                        }
                        assertEquals(collected.size(), expectedCount,
                                "did not yet consume all records from the topic");
                    });
        }
        return collected;
    }

    private void produce(String topic, Map<String, byte[]> records) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (Producer<String, byte[]> producer = new KafkaProducer<>(props)) {
            for (Map.Entry<String, byte[]> entry : records.entrySet()) {
                producer.send(new ProducerRecord<>(topic, entry.getKey(), entry.getValue()));
            }
            producer.flush();
        }
    }

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.mark();
        buffer.get(result);
        buffer.reset();
        return result;
    }

    /**
     * Minimal {@link Record} of raw bytes for driving the sink. Carries a key, a payload and a
     * properties map (kept for a future header-propagation assertion, see PR #36), and completes
     * a future on ack/fail so the test can wait for the producer callback.
     */
    private static final class MockRecord implements Record<byte[]> {
        private final String key;
        private final byte[] value;
        private final Map<String, String> properties;
        private final CompletableFuture<Boolean> ackFuture;

        private MockRecord(String key, byte[] value, CompletableFuture<Boolean> ackFuture) {
            this.key = key;
            this.value = value;
            this.properties = new HashMap<>();
            this.ackFuture = ackFuture;
        }

        @Override
        public Optional<String> getKey() {
            return Optional.ofNullable(key);
        }

        @Override
        public byte[] getValue() {
            return value;
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }

        @Override
        public void ack() {
            ackFuture.complete(true);
        }

        @Override
        public void fail() {
            ackFuture.complete(false);
        }
    }
}
