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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertTrue;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5ConnectBuilder;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.mockito.Mockito;
import org.testng.annotations.Test;

public class MqttSinkTest {

    @Test
    public void writeShouldCallFailWhenPublishThrowsSynchronously() {
        Mqtt5AsyncClient mqttClient = mock(Mqtt5AsyncClient.class);
        when(mqttClient.publishWith()).thenThrow(new RuntimeException("publish failed"));
        MqttSink sink = newSinkWithOpenedClient(mqttClient);
        TestRecord record = new TestRecord("x".getBytes(), new CountDownLatch(1), new AtomicBoolean(false));

        sink.write(record);

        assertTrue(record.isFailed(), "record.fail() should be called");
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "password cannot be set when username is blank")
    public void openShouldPropagateConfigValidationFailure() throws Exception {
        Map<String, Object> invalidConfig = baseConfigMap();
        invalidConfig.put("username", "");
        invalidConfig.put("password", "pwd");
        try (MqttSink sink = new MqttSink()) {
            sink.open(invalidConfig, mock(SinkContext.class));
        }
    }

    @Test
    public void closeShouldBeSafeWhenSinkWasNeverOpened() {
        new MqttSink().close();
    }

    private MqttSink newSinkWithOpenedClient(Mqtt5AsyncClient mqttClient) {
        try {
            @SuppressWarnings("unchecked")
            Mqtt5ConnectBuilder.Send<CompletableFuture<Mqtt5ConnAck>> connectBuilder =
                    mock(Mqtt5ConnectBuilder.Send.class, Mockito.RETURNS_SELF);
            when(mqttClient.connectWith()).thenReturn(connectBuilder);
            when(connectBuilder.send())
                    .thenReturn(CompletableFuture.completedFuture(null));

            MqttSink sink = Mockito.spy(new MqttSink());
            doReturn(mqttClient).when(sink).buildClient(any());
            sink.open(baseConfigMap(), mock(SinkContext.class));
            return sink;
        } catch (Exception e) {
            throw new AssertionError("Failed to initialize MqttSink test fixture", e);
        }
    }

    private static Map<String, Object> baseConfigMap() {
        Map<String, Object> config = new HashMap<>();
        config.put("serverHost", "localhost");
        config.put("serverPort", 1883);
        config.put("topic", "test/topic");
        config.put("qos", 1);
        config.put("connectionTimeoutMs", 1000L);
        config.put("keepAliveIntervalSec", 60);
        config.put("cleanStart", true);
        return config;
    }

    private static final class TestRecord implements Record<byte[]> {
        private final byte[] value;
        private final CountDownLatch ackLatch;
        private final AtomicBoolean failCalled;

        private TestRecord(byte[] value, CountDownLatch ackLatch, AtomicBoolean failCalled) {
            this.value = value;
            this.ackLatch = ackLatch;
            this.failCalled = failCalled;
        }

        @Override
        public byte[] getValue() {
            return value;
        }

        @Override
        public void ack() {
            ackLatch.countDown();
        }

        @Override
        public void fail() {
            failCalled.set(true);
        }

        private boolean isFailed() {
            return failCalled.get();
        }
    }
}
