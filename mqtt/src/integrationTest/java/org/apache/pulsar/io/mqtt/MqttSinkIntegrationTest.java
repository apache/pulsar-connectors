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

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class MqttSinkIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MqttSinkIntegrationTest.class);

    private static final int MQTT_PORT = 1883;
    private static final String TEST_TOPIC = "pulsar/mqtt/e2e";
    private static final DockerImageName MOSQUITTO_IMAGE = DockerImageName.parse("eclipse-mosquitto:2");

    private final GenericContainer<?> mqttContainer = new GenericContainer<>(MOSQUITTO_IMAGE)
            .withExposedPorts(MQTT_PORT);

    @BeforeClass(alwaysRun = true)
    public void beforeClass() {
        mqttContainer.start();
    }

    @AfterClass(alwaysRun = true)
    public void afterClass() {
        mqttContainer.stop();
    }

    @Test
    public void testWriteE2EWithMosquitto() throws Exception {
        BlockingQueue<String> receivedPayloads = new LinkedBlockingQueue<>();
        CountDownLatch ackLatch = new CountDownLatch(3);
        AtomicBoolean failCalled = new AtomicBoolean(false);

        Mqtt5AsyncClient subscriber = MqttClient.builder()
                .useMqttVersion5()
                .serverHost(mqttContainer.getHost())
                .serverPort(mqttContainer.getMappedPort(MQTT_PORT))
                .identifier("mqtt-sink-e2e-subscriber")
                .buildAsync();

        subscriber.connectWith()
                .cleanStart(true)
                .send()
                .get(10, TimeUnit.SECONDS);
        subscriber.subscribeWith()
                .topicFilter(TEST_TOPIC)
                .callback(publish -> receivedPayloads.add(
                        new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8)))
                .send()
                .get(10, TimeUnit.SECONDS);

        Map<String, Object> config = new HashMap<>();
        config.put("serverHost", mqttContainer.getHost());
        config.put("serverPort", mqttContainer.getMappedPort(MQTT_PORT));
        config.put("topic", TEST_TOPIC);
        config.put("qos", 1);
        config.put("connectionTimeoutMs", 10000);
        config.put("clientId", "mqtt-sink-e2e-publisher");

        SinkContext sinkContext = mock(SinkContext.class);
        try (MqttSink sink = new MqttSink()) {
            sink.open(config, sinkContext);

            for (int i = 0; i < 3; i++) {
                sink.write(new TestRecord(("msg-" + i).getBytes(StandardCharsets.UTF_8), ackLatch, failCalled));
            }

            assertTrue(ackLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for record.ack()");
            assertFalse(failCalled.get(), "record.fail() should not be called on successful publish");

            assertEquals(receivedPayloads.poll(10, TimeUnit.SECONDS), "msg-0");
            assertEquals(receivedPayloads.poll(10, TimeUnit.SECONDS), "msg-1");
            assertEquals(receivedPayloads.poll(10, TimeUnit.SECONDS), "msg-2");
        } finally {
            try {
                subscriber.disconnectWith()
                        .sessionExpiryInterval(0)
                        .send()
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to disconnect MQTT subscriber in test cleanup", e);
            }
        }
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
    }
}
