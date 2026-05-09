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
package org.apache.pulsar.io.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;

@Connector(
        name = "mqtt",
        type = IOType.SINK,
        help = "A sink connector that moves messages from Pulsar to MQTT.",
        configClass = MqttSinkConfig.class
)
@Slf4j
public class MqttSink implements Sink<byte[]> {

    private MqttSinkConfig mqttSinkConfig;
    private Mqtt5AsyncClient mqttClient;
    private MqttQos mqttQos;

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        mqttSinkConfig = MqttSinkConfig.load(config, sinkContext);
        mqttSinkConfig.validate();
        mqttQos = MqttQos.fromCode(mqttSinkConfig.getQos());

        var builder = MqttClient.builder()
                .useMqttVersion5()
                .serverHost(mqttSinkConfig.getServerHost())
                .serverPort(mqttSinkConfig.getServerPort());

        if (StringUtils.isNotBlank(mqttSinkConfig.getClientId())) {
            builder = builder.identifier(mqttSinkConfig.getClientId());
        }
        if (mqttSinkConfig.isSslEnabled()) {
            builder = builder.sslWithDefaultConfig();
        }

        mqttClient = buildClient(builder);
        if (StringUtils.isNotBlank(mqttSinkConfig.getUsername())) {
            var authBuilder = mqttClient.connectWith()
                    .cleanStart(mqttSinkConfig.isCleanStart())
                    .keepAlive(mqttSinkConfig.getKeepAliveIntervalSec())
                    .simpleAuth()
                    .username(mqttSinkConfig.getUsername());
            if (mqttSinkConfig.getPassword() != null) {
                authBuilder = authBuilder.password(mqttSinkConfig.getPassword().getBytes(StandardCharsets.UTF_8));
            }
            authBuilder.applySimpleAuth()
                    .send()
                    .get(mqttSinkConfig.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            mqttClient.connectWith()
                    .cleanStart(mqttSinkConfig.isCleanStart())
                    .keepAlive(mqttSinkConfig.getKeepAliveIntervalSec())
                    .send()
                    .get(mqttSinkConfig.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        log.info("MQTT sink connected to {}:{}.",
                mqttSinkConfig.getServerHost(), mqttSinkConfig.getServerPort());
    }

    Mqtt5AsyncClient buildClient(Mqtt5ClientBuilder builder) {
        return builder.buildAsync();
    }

    @Override
    public void write(Record<byte[]> record) {
        try {
            byte[] payload = record.getValue() == null ? new byte[0] : record.getValue();
            mqttClient.publishWith()
                    .topic(mqttSinkConfig.getTopic())
                    .qos(mqttQos)
                    .payload(payload)
                    .send()
                    .whenComplete((result, throwable) -> {
                        if (throwable == null) {
                            record.ack();
                        } else {
                            record.fail();
                            log.warn("Failed to publish message to MQTT topic {}",
                                    mqttSinkConfig.getTopic(), throwable);
                        }
                    });
        } catch (Exception e) {
            record.fail();
            log.warn("Failed to schedule MQTT publish for topic {}", mqttSinkConfig.getTopic(), e);
        }
    }

    @Override
    public void close() {
        if (mqttClient == null) {
            return;
        }

        try {
            mqttClient.disconnectWith()
                    .send()
                    .get(mqttSinkConfig.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to disconnect MQTT client cleanly", e);
        }
    }
}
