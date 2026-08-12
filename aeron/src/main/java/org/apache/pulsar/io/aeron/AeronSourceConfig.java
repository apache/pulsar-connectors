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
package org.apache.pulsar.io.aeron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.aeron.ChannelUri;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.core.annotations.FieldDoc;

/**
 * Aeron Source Connector Config.
 */
@Data
@Accessors(chain = true)
public class AeronSourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The Aeron channel URI to subscribe to, for example "
                    + "'aeron:udp?endpoint=0.0.0.0:40123' or 'aeron:ipc'")
    private String channel;

    @FieldDoc(
            required = false,
            defaultValue = "1001",
            help = "The Aeron stream id to subscribe to within the channel. Must not be 0")
    private int streamId = 1001;

    @FieldDoc(
            required = false,
            defaultValue = "true",
            help = "Whether to launch an embedded Aeron media driver. When false, "
                    + "'aeronDirectoryName' must point at the directory of an externally "
                    + "managed media driver")
    private boolean useEmbeddedMediaDriver = true;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "The Aeron media driver directory. Required when "
                    + "'useEmbeddedMediaDriver' is false; optional otherwise, in which case "
                    + "the embedded driver picks its own directory")
    private String aeronDirectoryName;

    @FieldDoc(
            required = false,
            defaultValue = "backoff",
            help = "The idle strategy used by the polling thread when no fragments are "
                    + "available. Supported values are 'backoff', 'sleeping', 'yielding' "
                    + "and 'busyspin'. 'busyspin' gives the lowest latency but pins a core")
    private String idleStrategy = IdleStrategies.BACKOFF;

    @FieldDoc(
            required = false,
            defaultValue = "256",
            help = "The maximum number of fragments to read in a single poll")
    private int fragmentLimit = 256;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "When true, the record key is set to the Aeron session id, which lets "
                    + "key_shared subscriptions and topic compaction group by Aeron session")
    private boolean keyBySessionId = false;

    @FieldDoc(
            required = false,
            defaultValue = "0",
            help = "Initial buffer length, in bytes, used by the fragment assembler for "
                    + "reassembling messages larger than the MTU. 0 uses the Aeron default")
    private int fragmentAssemblyBufferLength = 0;

    public static AeronSourceConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(map), AeronSourceConfig.class);
    }

    public static AeronSourceConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), AeronSourceConfig.class);
    }

    /**
     * Validates the configuration, failing fast at {@code open()} time rather than letting
     * a bad channel URI surface later as an obscure Aeron driver error.
     *
     * @throws IllegalArgumentException if any value is missing or unusable
     */
    public void validate() {
        if (StringUtils.isBlank(channel)) {
            throw new IllegalArgumentException("channel must be set");
        }
        try {
            ChannelUri.parse(channel);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("channel is not a valid Aeron URI: " + channel, e);
        }
        // Aeron reserves stream id 0, so a 0 here is nearly always an unset field rather
        // than a deliberate choice.
        if (streamId == 0) {
            throw new IllegalArgumentException("streamId must not be 0");
        }
        if (!IdleStrategies.isSupported(idleStrategy)) {
            throw new IllegalArgumentException("idleStrategy must be one of "
                    + IdleStrategies.supportedStrategies() + " but was: " + idleStrategy);
        }
        if (fragmentLimit <= 0) {
            throw new IllegalArgumentException("fragmentLimit must be positive but was: " + fragmentLimit);
        }
        if (fragmentAssemblyBufferLength < 0) {
            throw new IllegalArgumentException(
                    "fragmentAssemblyBufferLength must not be negative but was: " + fragmentAssemblyBufferLength);
        }
        // Without an embedded driver there is nothing to point the Aeron client at unless
        // the caller names the external driver's directory.
        if (!useEmbeddedMediaDriver && StringUtils.isBlank(aeronDirectoryName)) {
            throw new IllegalArgumentException(
                    "aeronDirectoryName must be set when useEmbeddedMediaDriver is false");
        }
    }
}
