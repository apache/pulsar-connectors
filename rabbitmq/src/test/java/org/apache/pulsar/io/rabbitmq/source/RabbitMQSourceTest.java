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
package org.apache.pulsar.io.rabbitmq.source;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.rabbitmq.RabbitMQBrokerManager;
import org.apache.pulsar.io.rabbitmq.RabbitMQSource;
import org.awaitility.Awaitility;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class RabbitMQSourceTest {

    private RabbitMQBrokerManager rabbitMQBrokerManager;

    @BeforeMethod
    public void setUp() throws Exception {
        rabbitMQBrokerManager = new RabbitMQBrokerManager();
        rabbitMQBrokerManager.startBroker();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        rabbitMQBrokerManager.stopBroker();
    }

    @Test
    public void testOpenAndWriteSink() throws Exception {
        Map<String, Object> configs = baseConfigs("test-queue");

        RabbitMQSource source = new RabbitMQSource();

        // open should success
        // rabbitmq service may need time to initialize
        SourceContext sourceContext = mock(SourceContext.class);
        Awaitility.await().ignoreExceptions().pollDelay(Duration.ofSeconds(1))
                .untilAsserted(() -> source.open(configs, sourceContext));
        source.close();
    }

    @Test(timeOut = 60000)
    public void testBindToExchangeAndConsumeMessage() throws Exception {
        String exchange = "test-exchange";
        String queue = "test-bind-queue";
        String routingKey = "test.key";

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(rabbitMQBrokerManager.getPort());
        factory.setUsername(rabbitMQBrokerManager.getUser());
        factory.setPassword(rabbitMQBrokerManager.getPassword());
        factory.setVirtualHost("default");

        RabbitMQSource source = new RabbitMQSource();
        SourceContext sourceContext = mock(SourceContext.class);

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            // the exchange must already exist before the source binds the queue to it
            channel.exchangeDeclare(exchange, "topic", true);

            Map<String, Object> configs = baseConfigs(queue);
            configs.put("exchangeName", exchange);
            configs.put("routingKey", routingKey);
            configs.put("durable", "true");

            // rabbitmq service may need time to initialize
            Awaitility.await().ignoreExceptions().pollDelay(Duration.ofSeconds(1))
                    .untilAsserted(() -> source.open(configs, sourceContext));

            channel.basicPublish(exchange, routingKey, null, "hello".getBytes(StandardCharsets.UTF_8));

            Record<byte[]> record = source.read();
            assertEquals("hello", new String(record.getValue(), StandardCharsets.UTF_8));
            assertEquals(queue, record.getProperties().get("queueName"));
            record.ack();
        } finally {
            source.close();
        }
    }

    private Map<String, Object> baseConfigs(String queueName) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("host", "localhost");
        configs.put("port", String.valueOf(rabbitMQBrokerManager.getPort()));
        configs.put("virtualHost", "default");
        configs.put("username", rabbitMQBrokerManager.getUser());
        configs.put("password", rabbitMQBrokerManager.getPassword());
        configs.put("queueName", queueName);
        configs.put("connectionName", "test-connection");
        configs.put("requestedChannelMax", "0");
        configs.put("requestedFrameMax", "0");
        configs.put("connectionTimeout", "60000");
        configs.put("handshakeTimeout", "10000");
        configs.put("requestedHeartbeat", "60");
        configs.put("prefetchCount", "0");
        configs.put("prefetchGlobal", "false");
        configs.put("passive", "false");
        return configs;
    }
}
