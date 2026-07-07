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
package org.apache.pulsar.io.kafka.connect;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.apache.kafka.connect.storage.OffsetStorageReaderImpl;
import org.apache.kafka.connect.storage.OffsetStorageWriter;
import org.testng.annotations.Test;

/**
 * Tests that connector offsets written before a restart can be read back after the restart.
 *
 * <p>The adaptor previously serialized offsets with the connector's data converters. With
 * converters that keep out-of-band state (e.g. {@link AvroConverter} backed by an in-memory
 * {@link MockSchemaRegistryClient}), offsets became unreadable after a restart and connectors
 * silently lost their position. Offsets must instead use dedicated schema-less JSON converters,
 * matching Kafka Connect's internal converters.
 *
 * <p><b>TODO:</b> These tests verify the offset converters work in isolation but don't test the
 * actual production code path - that {@link AbstractKafkaConnectSource#open} uses these JSON
 * converters for offset storage. Once pulsar-broker test artifacts are available, replace these
 * with an E2E test that: (1) initializes the adaptor with AvroConverter for data topics,
 * (2) processes a message and stores an offset, (3) simulates a restart with fresh converter
 * instances, and (4) verifies the offset was loaded correctly. This would prove the production
 * path uses JSON converters for offsets regardless of data converter configuration.
 */
public class KafkaConnectOffsetConverterTest {

    private static final String NAMESPACE = "pulsar-kafka-connect-adaptor";
    private static final Map<String, Object> PARTITION = Map.of("topic", "test-topic", "partition", 0);
    private static final Map<String, Object> OFFSET = Map.of("position", 42L);

    /** Minimal in-memory store standing in for the offset topic that survives a connector restart. */
    private static class InMemoryOffsetBackingStore extends MemoryOffsetBackingStore {
        @Override
        public Set<Map<String, Object>> connectorPartitions(String connectorName) {
            return Collections.emptySet();
        }
    }

    private void writeOffset(MemoryOffsetBackingStore store, Converter keyConverter, Converter valueConverter)
            throws Exception {
        OffsetStorageWriter writer = new OffsetStorageWriter(store, NAMESPACE, keyConverter, valueConverter);
        writer.offset(PARTITION, OFFSET);
        assertTrue(writer.beginFlush(10, TimeUnit.SECONDS));
        writer.doFlush((error, ignored) -> { }).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void testOffsetsSurviveRestartWithJsonOffsetConverters() throws Exception {
        MemoryOffsetBackingStore store = new InMemoryOffsetBackingStore();
        store.start();
        try {
            writeOffset(store,
                    AbstractKafkaConnectSource.createOffsetConverter(true),
                    AbstractKafkaConnectSource.createOffsetConverter(false));

            // simulate a connector restart: fresh converter instances, same backing store
            OffsetStorageReaderImpl reader = new OffsetStorageReaderImpl(store, NAMESPACE,
                    AbstractKafkaConnectSource.createOffsetConverter(true),
                    AbstractKafkaConnectSource.createOffsetConverter(false));

            Map<String, Object> restored = reader.offset(PARTITION);
            assertNotNull(restored, "offset must be readable after a restart");
            assertEquals(((Number) restored.get("position")).longValue(), 42L);
        } finally {
            store.stop();
        }
    }

    /**
     * Documents the failure mode this fix addresses: offsets written with the Avro data
     * converters (as the adaptor did before) are not readable after a restart, because the
     * fresh {@link MockSchemaRegistryClient} no longer holds the writer's schemas.
     */
    @Test
    public void testAvroDataConvertersLoseOffsetsAfterRestart() throws Exception {
        Map<String, Object> avroConfig =
                Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock");

        MemoryOffsetBackingStore store = new InMemoryOffsetBackingStore();
        store.start();
        try {
            Converter oldKeyConverter = new AvroConverter(new MockSchemaRegistryClient());
            oldKeyConverter.configure(avroConfig, true);
            Converter oldValueConverter = new AvroConverter(new MockSchemaRegistryClient());
            oldValueConverter.configure(avroConfig, false);
            writeOffset(store, oldKeyConverter, oldValueConverter);

            // simulate a connector restart: the new mock schema registries are empty
            Converter newKeyConverter = new AvroConverter(new MockSchemaRegistryClient());
            newKeyConverter.configure(avroConfig, true);
            Converter newValueConverter = new AvroConverter(new MockSchemaRegistryClient());
            newValueConverter.configure(avroConfig, false);
            OffsetStorageReaderImpl reader = new OffsetStorageReaderImpl(store, NAMESPACE,
                    newKeyConverter, newValueConverter);

            Map<String, Object> restored;
            try {
                restored = reader.offset(PARTITION);
            } catch (Exception e) {
                // reading failed outright — the offset is lost, which is the bug
                return;
            }
            assertNull(restored, "expected the offset written by the data converters to be lost after restart");
        } finally {
            store.stop();
        }
    }
}
