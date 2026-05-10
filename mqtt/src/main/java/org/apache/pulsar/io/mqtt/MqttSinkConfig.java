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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.FieldDoc;

@Data
@Accessors(chain = true)
public class MqttSinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The MQTT broker host.")
    private String serverHost;

    @FieldDoc(
            defaultValue = "1883",
            help = "The MQTT broker port.")
    private int serverPort = 1883;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The MQTT topic to publish messages to.")
    private String topic;

    @FieldDoc(
            defaultValue = "",
            help = "MQTT client id used for the broker connection.")
    private String clientId;

    @FieldDoc(
            defaultValue = "",
            sensitive = true,
            help = "MQTT username.")
    private String username;

    @FieldDoc(
            defaultValue = "",
            sensitive = true,
            help = "MQTT password.")
    private String password;

    @FieldDoc(
            defaultValue = "0",
            help = "MQTT QoS level for outgoing messages. Valid values: 0, 1, 2.")
    private int qos = 0;

    @FieldDoc(
            defaultValue = "60",
            help = "MQTT keep alive interval in seconds.")
    private int keepAliveIntervalSec = 60;

    @FieldDoc(
            defaultValue = "10000",
            help = "Timeout in milliseconds for MQTT connect/disconnect operations.")
    private long connectionTimeoutMs = 10000L;

    @FieldDoc(
            defaultValue = "true",
            help = "Whether to start with a clean session.")
    private boolean cleanStart = true;

    @FieldDoc(
            defaultValue = "false",
            help = "Enable SSL/TLS with the client default SSL configuration.")
    private boolean sslEnabled = false;

    public static MqttSinkConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), MqttSinkConfig.class);
    }

    public static MqttSinkConfig load(Map<String, Object> map, SinkContext sinkContext) throws IOException {
        return IOConfigUtils.loadWithSecrets(map, MqttSinkConfig.class, sinkContext);
    }

    public void validate() {
        Preconditions.checkArgument(StringUtils.isNotBlank(serverHost), "serverHost cannot be blank");
        Preconditions.checkArgument(serverPort > 0, "serverPort must be a positive integer");
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic cannot be blank");
        Preconditions.checkArgument(StringUtils.isNotBlank(username) || StringUtils.isBlank(password),
                "password cannot be set when username is blank");
        Preconditions.checkArgument(qos >= 0 && qos <= 2, "qos must be one of 0, 1, 2");
        Preconditions.checkArgument(keepAliveIntervalSec >= 0, "keepAliveIntervalSec must be >= 0");
        Preconditions.checkArgument(connectionTimeoutMs > 0, "connectionTimeoutMs must be > 0");
    }
}
