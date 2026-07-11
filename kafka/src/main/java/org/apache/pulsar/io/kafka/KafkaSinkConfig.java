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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.FieldDoc;

@Data
@Accessors(chain = true)
public class KafkaSinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help =
                    "A comma-separated list of host and port pairs that are the addresses of "
                            + "the Kafka brokers that a Kafka client connects to initially bootstrap itself")
    private String bootstrapServers;

    @FieldDoc(
            defaultValue = "",
            help = "Protocol used to communicate with Kafka brokers.")
    private String securityProtocol;

    @FieldDoc(
            defaultValue = "",
            help = "SASL mechanism used for Kafka client connections.")
    private String saslMechanism;

    @FieldDoc(
            defaultValue = "",
            help = "JAAS login context parameters for SASL connections in the format used by JAAS configuration files.")
    private String saslJaasConfig;

    @FieldDoc(
            defaultValue = "",
            help = "The list of protocols enabled for SSL connections.")
    private String sslEnabledProtocols;

    @FieldDoc(
            defaultValue = "",
            help = "The endpoint identification algorithm to validate server hostname using server certificate.")
    private String sslEndpointIdentificationAlgorithm;

    @FieldDoc(
            defaultValue = "",
            help = "The location of the trust store file.")
    private String sslTruststoreLocation;

    @FieldDoc(
            defaultValue = "",
            sensitive = true,
            help = "The password for the trust store file.")
    private String sslTruststorePassword;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The number of acknowledgments the producer requires the leader to have received"
                    + " before considering a request complete. This controls the durability of records that are sent.")
    private String acks;
    @FieldDoc(
            defaultValue = "16384",
            help = "The batch size that Kafka producer will attempt to batch records together"
                    + " before sending them to brokers.")
    private long batchSize = 16384L;
    @FieldDoc(
            defaultValue = "1048576",
            help =
                    "The maximum size of a Kafka request in bytes.")
    private long maxRequestSize = 1048576L;
    @FieldDoc(
            required = true,
            defaultValue = "",
            help =
                    "The Kafka topic that is used for Pulsar moving messages to.")
    private String topic;
    @FieldDoc(
            defaultValue = "org.apache.kafka.common.serialization.StringSerializer",
            help =
                    "The serializer class for Kafka producer to serialize keys.")
    private String keySerializerClass = "org.apache.kafka.common.serialization.StringSerializer";
    @FieldDoc(
            defaultValue = "org.apache.kafka.common.serialization.ByteArraySerializer",
            help =
                    "The serializer class for Kafka producer to serialize values. You typically shouldn't care this. "
                            + "Since the serializer will be set by a specific implementation of `KafkaAbstractSink`.")
    private String valueSerializerClass = "org.apache.kafka.common.serialization.ByteArraySerializer";
    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "If true, the Pulsar message properties are copied into the Kafka record headers (each property "
                    + "name becomes a header key, the value is UTF-8 encoded). Useful with the Kinesis source "
                    + "messageKeyMode=SHARD_ID: the Kafka message key carries the shardId for ordered routing while the "
                    + "original partition key is preserved in the 'kinesis.partition.key' header for consumers.")
    private boolean copyHeadersEnabled = false;

    @FieldDoc(
            defaultValue = "",
            help =
                    "The producer config properties to be passed to Producer. Note that most properties specified "
                            + "in the connector config file (bootstrapServers, acks, batchSize, etc.) take precedence "
                            + "over entries in this map. Exception: if 'enable.idempotence=true' is set here, the "
                            + "sink will enforce acks=all and ignore the top-level acks field, because Kafka's "
                            + "idempotent producer requires acks=all. "
                            + "To preserve per-shard ordering when routing Kinesis records to Kafka, set "
                            + "'enable.idempotence=true' and 'max.in.flight.requests.per.connection=1' (or <=5 for "
                            + "Kafka >= 1.0.0 with idempotence) in this map, and configure the Kinesis source with "
                            + "messageKeyMode=SHARD_ID so that all records from the same shard share a key and land "
                            + "on the same Kafka partition in arrival order.")
    private Map<String, Object> producerConfigProperties;

    public static KafkaSinkConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), KafkaSinkConfig.class);
    }

    public static KafkaSinkConfig load(Map<String, Object> map, SinkContext sinkContext) throws IOException {
        return IOConfigUtils.loadWithSecrets(map, KafkaSinkConfig.class, sinkContext);
    }
}
