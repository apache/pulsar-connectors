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

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;

/**
 * Kafka sink that forwards message keys and values as bytes without applying a schema.
 * String keys retain their UTF-8 representation, while binary keys are forwarded without
 * the Base64 encoding used in Pulsar's string key representation.
 */
@Connector(
        name = "kafka",
        type = IOType.SINK,
        help = "The KafkaBytesSink is used for moving messages from Pulsar to Kafka.",
        configClass = KafkaSinkConfig.class
)
@Slf4j
public class KafkaBytesSink extends KafkaAbstractSink<byte[], byte[]> {

    @Override
    protected Properties beforeCreateProducer(Properties props) {
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        log.info("Created kafka producer on : {}", props.getOrDefault(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ""));
        return props;
    }

    @Override
    public KeyValue<byte[], byte[]> extractKeyValue(Record<byte[]> record) {
        byte[] key = record.getMessage()
                .filter(message -> message.hasKey() && message.hasBase64EncodedKey())
                .map(Message::getKeyBytes)
                .orElseGet(() -> record.getKey().map(value -> value.getBytes(StandardCharsets.UTF_8)).orElse(null));
        return new KeyValue<>(key, record.getValue());
    }
}
