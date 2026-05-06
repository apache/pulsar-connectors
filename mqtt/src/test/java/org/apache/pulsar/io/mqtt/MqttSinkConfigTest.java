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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.io.core.SinkContext;
import org.mockito.Mockito;
import org.testng.annotations.Test;

public class MqttSinkConfigTest {

    @Test
    public void loadFromYamlFileTest() throws IOException {
        File yamlFile = getFile("sinkConfig.yaml");
        MqttSinkConfig config = MqttSinkConfig.load(yamlFile.getAbsolutePath());
        assertNotNull(config);
        assertEquals(config.getServerHost(), "localhost");
        assertEquals(config.getServerPort(), 1883);
        assertEquals(config.getTopic(), "test/topic");
        assertEquals(config.getClientId(), "pulsar-mqtt-test");
        assertEquals(config.getUsername(), "mqtt-user");
        assertEquals(config.getPassword(), "mqtt-password");
        assertEquals(config.getQos(), 1);
        assertEquals(config.getKeepAliveIntervalSec(), 45);
        assertEquals(config.getConnectionTimeoutMs(), 15000L);
        assertTrue(config.isCleanStart());
        assertFalse(config.isSslEnabled());
    }

    @Test
    public void loadFromMapTest() throws IOException {
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        MqttSinkConfig config = MqttSinkConfig.load(baseConfigMap(), sinkContext);

        assertNotNull(config);
        assertEquals(config.getServerHost(), "localhost");
        assertEquals(config.getServerPort(), 1883);
        assertEquals(config.getTopic(), "test/topic");
        assertEquals(config.getClientId(), "pulsar-mqtt-test");
        assertEquals(config.getQos(), 1);
    }

    @Test
    public void loadFromMapCredentialsFromSecretTest() throws IOException {
        Map<String, Object> map = baseConfigMap();
        map.remove("username");
        map.remove("password");

        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        Mockito.when(sinkContext.getSecret("username")).thenReturn("mqtt-user");
        Mockito.when(sinkContext.getSecret("password")).thenReturn("mqtt-password");

        MqttSinkConfig config = MqttSinkConfig.load(map, sinkContext);
        assertEquals(config.getUsername(), "mqtt-user");
        assertEquals(config.getPassword(), "mqtt-password");
    }

    @Test
    public void validValidateTest() throws IOException {
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        MqttSinkConfig config = MqttSinkConfig.load(baseConfigMap(), sinkContext);
        config.validate();
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "qos must be one of 0, 1, 2")
    public void invalidQosValidateTest() throws IOException {
        Map<String, Object> map = baseConfigMap();
        map.put("qos", 3);
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        MqttSinkConfig config = MqttSinkConfig.load(map, sinkContext);
        config.validate();
    }

    private static Map<String, Object> baseConfigMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("serverHost", "localhost");
        map.put("serverPort", 1883);
        map.put("topic", "test/topic");
        map.put("clientId", "pulsar-mqtt-test");
        map.put("username", "mqtt-user");
        map.put("password", "mqtt-password");
        map.put("qos", 1);
        map.put("keepAliveIntervalSec", 45);
        map.put("connectionTimeoutMs", 15000);
        map.put("cleanStart", true);
        map.put("sslEnabled", false);
        return map;
    }

    private File getFile(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(name).getFile());
    }
}
