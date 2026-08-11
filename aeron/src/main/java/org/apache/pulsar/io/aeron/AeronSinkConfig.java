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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.core.annotations.FieldDoc;

/**
 * Aeron Sink Connector Config.
 */
@Data
@Accessors(chain = true)
public class AeronSinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Fail the record so Pulsar redelivers it. */
    public static final String ON_TIMEOUT_FAIL = "fail";
    /** Acknowledge the record and count it as dropped. */
    public static final String ON_TIMEOUT_DROP = "drop";

    private static final List<String> TIMEOUT_POLICIES =
            Arrays.asList(ON_TIMEOUT_FAIL, ON_TIMEOUT_DROP);

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The Aeron channel URI to publish to, for example "
                    + "'aeron:udp?endpoint=consumer:40124' or 'aeron:ipc'")
    private String channel;

    @FieldDoc(
            required = false,
            defaultValue = "1002",
            help = "The Aeron stream id to publish on within the channel. Must not be 0")
    private int streamId = 1002;

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
                    + "'useEmbeddedMediaDriver' is false")
    private String aeronDirectoryName;

    @FieldDoc(
            required = false,
            defaultValue = "backoff",
            help = "The idle strategy used while retrying an offer that Aeron could not "
                    + "accept immediately. Supported values are 'backoff', 'sleeping', "
                    + "'yielding' and 'busyspin'")
    private String idleStrategy = IdleStrategies.BACKOFF;

    @FieldDoc(
            required = false,
            defaultValue = "30000",
            help = "How long, in milliseconds, to keep retrying an offer that Aeron rejects "
                    + "as back-pressured or not-connected before applying 'onOfferTimeout'")
    private long offerTimeoutMs = 30_000L;

    @FieldDoc(
            required = false,
            defaultValue = "fail",
            help = "What to do when an offer could not be accepted within 'offerTimeoutMs'. "
                    + "'fail' fails the record so Pulsar redelivers it; 'drop' acknowledges "
                    + "the record and counts it as dropped. Aeron does not persist, so "
                    + "'drop' means the message is gone")
    private String onOfferTimeout = ON_TIMEOUT_FAIL;

    public static AeronSinkConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(map), AeronSinkConfig.class);
    }

    public static AeronSinkConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), AeronSinkConfig.class);
    }

    public boolean isDropOnTimeout() {
        return ON_TIMEOUT_DROP.equalsIgnoreCase(StringUtils.trimToEmpty(onOfferTimeout));
    }

    /**
     * Validates the configuration, failing fast at {@code open()} time rather than letting a
     * bad channel URI surface later as an obscure Aeron driver error.
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
        // Aeron reserves stream id 0, so a 0 here is nearly always an unset field.
        if (streamId == 0) {
            throw new IllegalArgumentException("streamId must not be 0");
        }
        if (!IdleStrategies.isSupported(idleStrategy)) {
            throw new IllegalArgumentException("idleStrategy must be one of "
                    + IdleStrategies.supportedStrategies() + " but was: " + idleStrategy);
        }
        if (offerTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "offerTimeoutMs must be positive but was: " + offerTimeoutMs);
        }
        if (onOfferTimeout == null
                || !TIMEOUT_POLICIES.contains(onOfferTimeout.toLowerCase(Locale.ROOT).trim())) {
            throw new IllegalArgumentException("onOfferTimeout must be one of "
                    + TIMEOUT_POLICIES + " but was: " + onOfferTimeout);
        }
        if (!useEmbeddedMediaDriver && StringUtils.isBlank(aeronDirectoryName)) {
            throw new IllegalArgumentException(
                    "aeronDirectoryName must be set when useEmbeddedMediaDriver is false");
        }
    }
}
