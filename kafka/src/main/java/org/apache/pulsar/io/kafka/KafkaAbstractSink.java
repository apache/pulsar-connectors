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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;

/**
 * A Simple abstract class for Kafka sink.
 * Users need to implement extractKeyValue function to use this sink
 */
@Slf4j
public abstract class KafkaAbstractSink<K, V> implements Sink<byte[]> {

    private Producer<K, V> producer;
    private final Properties props = new Properties();
    private KafkaSinkConfig kafkaSinkConfig;

    @Override
    public void write(Record<byte[]> sourceRecord) {
        ProducerRecord<K, V> record = buildProducerRecord(sourceRecord);
        if (log.isDebugEnabled()) {
            log.debug("Record sending to kafka, record={}.", record);
        }
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                sourceRecord.ack();
            } else {
                sourceRecord.fail();
            }
        });
    }

    // protected (NOT package-private) so the test subclass can expose it; package-private would be invisible
    // because the test lives in package org.apache.pulsar.io.kafka.sink.
    protected ProducerRecord<K, V> buildProducerRecord(Record<byte[]> sourceRecord) {
        KeyValue<K, V> keyValue = extractKeyValue(sourceRecord);
        Map<String, String> properties = sourceRecord.getProperties();
        if (!kafkaSinkConfig.isCopyHeadersEnabled() || properties == null || properties.isEmpty()) {
            return new ProducerRecord<>(kafkaSinkConfig.getTopic(), keyValue.getKey(), keyValue.getValue());
        }
        List<Header> headers = new ArrayList<>(properties.size());
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                headers.add(new RecordHeader(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new ProducerRecord<>(kafkaSinkConfig.getTopic(), null, keyValue.getKey(), keyValue.getValue(), headers);
    }

    @Override
    public void close() throws IOException {
        if (producer != null) {
            producer.close();
            log.info("Kafka sink stopped.");
        }
    }

    protected Properties beforeCreateProducer(Properties props) {
        return props;
    }

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        kafkaSinkConfig = KafkaSinkConfig.load(config, sinkContext);
        if (kafkaSinkConfig.getBatchSize() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Producer batchSize : "
                    + kafkaSinkConfig.getBatchSize());
        }
        if (kafkaSinkConfig.getMaxRequestSize() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Producer maxRequestSize : "
                    + kafkaSinkConfig.getMaxRequestSize());
        }
        if (kafkaSinkConfig.getProducerConfigProperties() != null) {
            props.putAll(kafkaSinkConfig.getProducerConfigProperties());
        }

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaSinkConfig.getBootstrapServers());
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSecurityProtocol())) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafkaSinkConfig.getSecurityProtocol());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSaslMechanism())) {
            props.put(SaslConfigs.SASL_MECHANISM, kafkaSinkConfig.getSaslMechanism());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSaslJaasConfig())) {
            props.put(SaslConfigs.SASL_JAAS_CONFIG, kafkaSinkConfig.getSaslJaasConfig());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSslEnabledProtocols())) {
            props.put(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG, kafkaSinkConfig.getSslEnabledProtocols());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSslEndpointIdentificationAlgorithm())) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG,
                    kafkaSinkConfig.getSslEndpointIdentificationAlgorithm());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSslTruststoreLocation())) {
            props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, kafkaSinkConfig.getSslTruststoreLocation());
        }
        if (StringUtils.isNotEmpty(kafkaSinkConfig.getSslTruststorePassword())) {
            props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kafkaSinkConfig.getSslTruststorePassword());
        }
        // If the user has enabled idempotent production (via producerConfigProperties), Kafka
        // requires acks=all. Do not silently downgrade acks in that case; instead enforce all.
        // Without idempotence the configured acks value is applied as before.
        boolean idempotenceEnabled = Boolean.parseBoolean(
                String.valueOf(props.getOrDefault(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false")));
        if (idempotenceEnabled) {
            Object rawAcks = props.get(ProducerConfig.ACKS_CONFIG);
            String userAcks = rawAcks != null ? String.valueOf(rawAcks) : null;
            if (userAcks != null && !userAcks.equals("all") && !userAcks.equals("-1")) {
                throw new IllegalArgumentException(
                        "enable.idempotence=true requires acks=all, but acks was set to: " + userAcks);
            }
            // Enforce acks=all when idempotence is enabled; ignore the top-level acks field.
            props.put(ProducerConfig.ACKS_CONFIG, "all");
        } else {
            props.put(ProducerConfig.ACKS_CONFIG, kafkaSinkConfig.getAcks());
        }
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(kafkaSinkConfig.getBatchSize()));
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(kafkaSinkConfig.getMaxRequestSize()));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, kafkaSinkConfig.getKeySerializerClass());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, kafkaSinkConfig.getValueSerializerClass());

        producer = new KafkaProducer<>(beforeCreateProducer(props));

        log.info("Kafka sink started : {}.", props.getOrDefault(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ""));
    }

    public abstract KeyValue<K, V> extractKeyValue(Record<byte[]> message);
}