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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class KafkaBytesSinkTest {
    private final KafkaBytesSink sink = new KafkaBytesSink();

    @DataProvider(name = "keys")
    public Object[][] keys() {
        return new Object[][] {
                {new byte[] {0, (byte) 0xff, (byte) 0x80, 1}},
                {new byte[0]},
                {"clé-你好".getBytes(StandardCharsets.UTF_8)},
                {Schema.INT32.encode(123456)}
        };
    }

    @Test(dataProvider = "keys")
    public void testBinaryMessageKeysAreNotBase64Encoded(byte[] key) {
        Message<byte[]> message = message(Base64.getEncoder().encodeToString(key), true);
        Record<byte[]> record = record(Base64.getEncoder().encodeToString(key), message);

        KeyValue<?, byte[]> extracted = sink.extractKeyValue(record);

        assertEquals(extracted.getKey(), key);
        assertSame(extracted.getValue(), record.getValue());
        try (ByteArraySerializer serializer = new ByteArraySerializer()) {
            assertEquals(serializer.serialize("topic", (byte[]) extracted.getKey()), key);
        }
    }

    @Test
    public void testPrimitiveKeyRetainsItsWireEncoding() {
        byte[] key = Schema.INT32.encode(123456);
        Message<byte[]> message = message(Base64.getEncoder().encodeToString(key), true);
        Object extracted = sink.extractKeyValue(record(Base64.getEncoder().encodeToString(key), message)).getKey();
        try (IntegerDeserializer deserializer = new IntegerDeserializer()) {
            assertEquals(deserializer.deserialize("topic", (byte[]) extracted).intValue(), 123456);
        }
    }

    @Test
    public void testStringMessageKeyRemainsUtf8() {
        String key = "clé-你好";
        Message<byte[]> message = message(key, false);
        assertStringKey(key, message);
    }

    @Test
    public void testStringRecordKeyWithoutMessageRemainsUtf8() {
        assertStringKey("clé-你好", null);
        assertStringKey("", null);
    }

    @Test
    public void testStringRecordKeyTakesPrecedenceOverMessageKey() {
        assertStringKey("record-key", message("original-message-key", false));
    }

    private void assertStringKey(String key, Message<byte[]> message) {
        try (StringSerializer serializer = new StringSerializer()) {
            assertEquals(sink.extractKeyValue(record(key, message)).getKey(), serializer.serialize("topic", key));
        }
    }

    @Test
    public void testMissingKeyRemainsNull() {
        assertNull(sink.extractKeyValue(record(null, null)).getKey());
        Message<byte[]> message = message(null, false);
        assertNull(sink.extractKeyValue(record(null, message)).getKey());
    }

    @Test
    public void testBothSerializersAcceptBytes() {
        Properties props = new Properties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        Properties configured = sink.beforeCreateProducer(props);
        assertEquals(configured.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG), ByteArraySerializer.class.getName());
        assertEquals(configured.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG), ByteArraySerializer.class.getName());
    }

    private static Message<byte[]> message(String key, boolean base64Encoded) {
        MessageMetadata metadata = new MessageMetadata();
        if (key != null) {
            metadata.setPartitionKey(key).setPartitionKeyB64Encoded(base64Encoded);
        }
        return MessageImpl.create(metadata, ByteBuffer.wrap(new byte[] {1, 2, 3}), Schema.BYTES, "topic");
    }

    private static Record<byte[]> record(String key, Message<byte[]> message) {
        byte[] value = new byte[] {1, 2, 3};
        return new Record<>() {
            @Override
            public Optional<String> getKey() {
                return Optional.ofNullable(key);
            }

            @Override
            public Optional<Message<byte[]>> getMessage() {
                return Optional.ofNullable(message);
            }

            @Override
            public byte[] getValue() {
                return value;
            }
        };
    }
}
