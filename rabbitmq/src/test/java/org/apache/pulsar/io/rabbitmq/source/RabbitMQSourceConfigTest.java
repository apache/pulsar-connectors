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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import com.rabbitmq.client.ConnectionFactory;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.rabbitmq.RabbitMQSourceConfig;
import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * RabbitMQSourceConfig test.
 */
public class RabbitMQSourceConfigTest {
    @Test
    public final void loadFromYamlFileTest() throws IOException {
        File yamlFile = getFile("sourceConfig.yaml");
        String path = yamlFile.getAbsolutePath();
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(path);
        assertNotNull(config);
        assertEquals("localhost", config.getHost());
        assertEquals(Integer.parseInt("5672"), config.getPort());
        assertEquals("/", config.getVirtualHost());
        assertEquals("guest", config.getUsername());
        assertEquals("guest", config.getPassword());
        assertEquals("test-queue", config.getQueueName());
        assertEquals("test-connection", config.getConnectionName());
        assertEquals(Integer.parseInt("0"), config.getRequestedChannelMax());
        assertEquals(Integer.parseInt("0"), config.getRequestedFrameMax());
        assertEquals(Integer.parseInt("60000"), config.getConnectionTimeout());
        assertEquals(Integer.parseInt("10000"), config.getHandshakeTimeout());
        assertEquals(Integer.parseInt("60"), config.getRequestedHeartbeat());
        assertEquals(Integer.parseInt("0"), config.getPrefetchCount());
        assertFalse(config.isPrefetchGlobal());
        assertFalse(config.isPassive());
        assertFalse(config.isDurable());
        assertFalse(config.isExclusive());
        assertFalse(config.isAutoDelete());
        assertNull(config.getExchangeName());
        assertEquals("#", config.getRoutingKey());
        assertFalse(config.isSsl());
    }

    @Test
    public final void loadFromMapTest() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", "5672");
        map.put("virtualHost", "/");
        map.put("username", "guest");
        map.put("password", "guest");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("requestedChannelMax", "0");
        map.put("requestedFrameMax", "0");
        map.put("connectionTimeout", "60000");
        map.put("handshakeTimeout", "10000");
        map.put("requestedHeartbeat", "60");
        map.put("prefetchCount", "0");
        map.put("prefetchGlobal", "false");
        map.put("passive", "true");
        map.put("durable", "true");
        map.put("exclusive", "true");
        map.put("autoDelete", "true");
        map.put("exchangeName", "test-exchange");
        map.put("routingKey", "test.#");
        map.put("ssl", "true");

        SourceContext sourceContext = Mockito.mock(SourceContext.class);
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(map, sourceContext);
        assertNotNull(config);
        assertEquals("localhost", config.getHost());
        assertEquals(Integer.parseInt("5672"), config.getPort());
        assertEquals("/", config.getVirtualHost());
        assertEquals("guest", config.getUsername());
        assertEquals("guest", config.getPassword());
        assertEquals("test-queue", config.getQueueName());
        assertEquals("test-connection", config.getConnectionName());
        assertEquals(Integer.parseInt("0"), config.getRequestedChannelMax());
        assertEquals(Integer.parseInt("0"), config.getRequestedFrameMax());
        assertEquals(Integer.parseInt("60000"), config.getConnectionTimeout());
        assertEquals(Integer.parseInt("10000"), config.getHandshakeTimeout());
        assertEquals(Integer.parseInt("60"), config.getRequestedHeartbeat());
        assertEquals(Integer.parseInt("0"), config.getPrefetchCount());
        assertEquals(false, config.isPrefetchGlobal());
        assertEquals(false, config.isPrefetchGlobal());
        assertEquals(true, config.isPassive());
        assertEquals(true, config.isDurable());
        assertEquals(true, config.isExclusive());
        assertEquals(true, config.isAutoDelete());
        assertEquals("test-exchange", config.getExchangeName());
        assertEquals("test.#", config.getRoutingKey());
        assertEquals(true, config.isSsl());
    }

    @Test
    public final void loadFromMapCredentialsFromSecretTest() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", "5672");
        map.put("virtualHost", "/");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("requestedChannelMax", "0");
        map.put("requestedFrameMax", "0");
        map.put("connectionTimeout", "60000");
        map.put("handshakeTimeout", "10000");
        map.put("requestedHeartbeat", "60");
        map.put("prefetchCount", "0");
        map.put("prefetchGlobal", "false");
        map.put("passive", "true");

        SourceContext sourceContext = Mockito.mock(SourceContext.class);
        Mockito.when(sourceContext.getSecret("username"))
                .thenReturn("guest");
        Mockito.when(sourceContext.getSecret("password"))
                .thenReturn("guest");
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(map, sourceContext);
        assertNotNull(config);
        assertEquals("localhost", config.getHost());
        assertEquals(Integer.parseInt("5672"), config.getPort());
        assertEquals("/", config.getVirtualHost());
        assertEquals("guest", config.getUsername());
        assertEquals("guest", config.getPassword());
        assertEquals("test-queue", config.getQueueName());
        assertEquals("test-connection", config.getConnectionName());
        assertEquals(Integer.parseInt("0"), config.getRequestedChannelMax());
        assertEquals(Integer.parseInt("0"), config.getRequestedFrameMax());
        assertEquals(Integer.parseInt("60000"), config.getConnectionTimeout());
        assertEquals(Integer.parseInt("10000"), config.getHandshakeTimeout());
        assertEquals(Integer.parseInt("60"), config.getRequestedHeartbeat());
        assertEquals(Integer.parseInt("0"), config.getPrefetchCount());
        assertEquals(false, config.isPrefetchGlobal());
        assertEquals(false, config.isPrefetchGlobal());
        assertEquals(true, config.isPassive());
    }

    @Test
    public final void validValidateTest() throws IOException {
        RabbitMQSourceConfig config = validConfig();
        config.validate();
    }

    @Test
    public final void emptyQueueNameForNonPassiveDeclarationValidateTest() throws IOException {
        RabbitMQSourceConfig config = validConfig()
                .setQueueName("")
                .setPassive(false);
        config.validate();
    }

    @Test
    public final void emptyRoutingKeyWithExchangeValidateTest() throws IOException {
        RabbitMQSourceConfig config = validConfig()
                .setExchangeName("test-exchange")
                .setRoutingKey("");
        config.validate();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
        expectedExceptionsMessageRegExp = "queueName must be non-empty when passive is true\\.")
    public final void emptyQueueNameForPassiveDeclarationValidateTest() throws IOException {
        RabbitMQSourceConfig config = validConfig()
                .setQueueName("")
                .setPassive(true);
        config.validate();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
        expectedExceptionsMessageRegExp = "routingKey must not be null when exchangeName is set\\.")
    public final void nullRoutingKeyWithExchangeValidateTest() throws IOException {
        RabbitMQSourceConfig config = validConfig()
                .setExchangeName("test-exchange")
                .setRoutingKey(null);
        config.validate();
    }

    @Test
    public final void createConnectionFactoryWithSslTest() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", "5671");
        map.put("virtualHost", "/");
        map.put("username", "guest");
        map.put("password", "guest");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("ssl", "true");

        SourceContext sourceContext = Mockito.mock(SourceContext.class);
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(map, sourceContext);

        // building the factory with ssl enabled must set up the TLS context without throwing
        ConnectionFactory connectionFactory = config.createConnectionFactory();
        assertNotNull(connectionFactory);
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
        expectedExceptionsMessageRegExp = "host cannot be null")
    public final void missingHostValidateTest() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("port", "5672");
        map.put("virtualHost", "/");
        map.put("username", "guest");
        map.put("password", "guest");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("requestedChannelMax", "0");
        map.put("requestedFrameMax", "0");
        map.put("connectionTimeout", "60000");
        map.put("handshakeTimeout", "10000");
        map.put("requestedHeartbeat", "60");
        map.put("prefetchCount", "0");
        map.put("prefetchGlobal", "false");
        map.put("passive", "false");

        SourceContext sourceContext = Mockito.mock(SourceContext.class);
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(map, sourceContext);
        config.validate();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
        expectedExceptionsMessageRegExp = "prefetchCount must be non-negative.")
    public final void invalidPrefetchCountTest() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", "5672");
        map.put("virtualHost", "/");
        map.put("username", "guest");
        map.put("password", "guest");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("requestedChannelMax", "0");
        map.put("requestedFrameMax", "0");
        map.put("connectionTimeout", "60000");
        map.put("handshakeTimeout", "10000");
        map.put("requestedHeartbeat", "60");
        map.put("prefetchCount", "-100");
        map.put("prefetchGlobal", "false");
        map.put("passive", "false");

        SourceContext sourceContext = Mockito.mock(SourceContext.class);
        RabbitMQSourceConfig config = RabbitMQSourceConfig.load(map, sourceContext);
        config.validate();
    }

    private RabbitMQSourceConfig validConfig() throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("host", "localhost");
        map.put("port", "5672");
        map.put("virtualHost", "/");
        map.put("username", "guest");
        map.put("password", "guest");
        map.put("queueName", "test-queue");
        map.put("connectionName", "test-connection");
        map.put("requestedChannelMax", "0");
        map.put("requestedFrameMax", "0");
        map.put("connectionTimeout", "60000");
        map.put("handshakeTimeout", "10000");
        map.put("requestedHeartbeat", "60");
        map.put("prefetchCount", "0");
        map.put("prefetchGlobal", "false");
        map.put("passive", "false");
        return RabbitMQSourceConfig.load(map, Mockito.mock(SourceContext.class));
    }

    private File getFile(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(name).getFile());
    }
}
