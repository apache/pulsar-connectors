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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link AeronSourceConfig} deserialization and validation.
 */
public class AeronSourceConfigTest {

    private static Map<String, Object> validMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("channel", "aeron:udp?endpoint=0.0.0.0:40123");
        map.put("streamId", 1001);
        return map;
    }

    @Test
    public void testDefaults() throws Exception {
        AeronSourceConfig config = AeronSourceConfig.load(validMap());

        assertThat(config.getChannel()).isEqualTo("aeron:udp?endpoint=0.0.0.0:40123");
        assertThat(config.getStreamId()).isEqualTo(1001);
        assertThat(config.isUseEmbeddedMediaDriver()).isTrue();
        assertThat(config.getAeronDirectoryName()).isNull();
        assertThat(config.getIdleStrategy()).isEqualTo(IdleStrategies.BACKOFF);
        assertThat(config.getFragmentLimit()).isEqualTo(256);
        assertThat(config.isKeyBySessionId()).isFalse();
        assertThat(config.getFragmentAssemblyBufferLength()).isZero();

        config.validate();
    }

    @Test
    public void testLoadAllFields() throws Exception {
        Map<String, Object> map = validMap();
        map.put("useEmbeddedMediaDriver", false);
        map.put("aeronDirectoryName", "/tmp/aeron-test");
        map.put("idleStrategy", "busyspin");
        map.put("fragmentLimit", 64);
        map.put("keyBySessionId", true);
        map.put("fragmentAssemblyBufferLength", 8192);

        AeronSourceConfig config = AeronSourceConfig.load(map);

        assertThat(config.isUseEmbeddedMediaDriver()).isFalse();
        assertThat(config.getAeronDirectoryName()).isEqualTo("/tmp/aeron-test");
        assertThat(config.getIdleStrategy()).isEqualTo("busyspin");
        assertThat(config.getFragmentLimit()).isEqualTo(64);
        assertThat(config.isKeyBySessionId()).isTrue();
        assertThat(config.getFragmentAssemblyBufferLength()).isEqualTo(8192);

        config.validate();
    }

    @Test
    public void testIpcChannelIsValid() throws Exception {
        Map<String, Object> map = validMap();
        map.put("channel", "aeron:ipc");

        AeronSourceConfig config = AeronSourceConfig.load(map);
        config.validate();

        assertThat(config.getChannel()).isEqualTo("aeron:ipc");
    }

    @Test
    public void testMissingChannelIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.remove("channel");

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel must be set");
    }

    @Test
    public void testBlankChannelIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("channel", "   ");

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel must be set");
    }

    @Test
    public void testMalformedChannelUriIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        // Aeron URIs must start with the "aeron:" scheme.
        map.put("channel", "udp://0.0.0.0:40123");

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid Aeron URI");
    }

    @Test
    public void testZeroStreamIdIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("streamId", 0);

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamId must not be 0");
    }

    @Test
    public void testNegativeStreamIdIsAllowed() throws Exception {
        Map<String, Object> map = validMap();
        // Aeron stream ids are signed ints; only 0 is reserved.
        map.put("streamId", -7);

        AeronSourceConfig config = AeronSourceConfig.load(map);
        config.validate();

        assertThat(config.getStreamId()).isEqualTo(-7);
    }

    @Test
    public void testUnknownIdleStrategyIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("idleStrategy", "spin-forever");

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleStrategy must be one of");
    }

    @Test
    public void testNonPositiveFragmentLimitIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("fragmentLimit", 0);

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragmentLimit must be positive");
    }

    @Test
    public void testNegativeAssemblyBufferLengthIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("fragmentAssemblyBufferLength", -1);

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragmentAssemblyBufferLength must not be negative");
    }

    @Test
    public void testExternalDriverRequiresDirectory() throws Exception {
        Map<String, Object> map = validMap();
        map.put("useEmbeddedMediaDriver", false);

        assertThatThrownBy(() -> AeronSourceConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aeronDirectoryName must be set");
    }

    @Test
    public void testEmbeddedDriverDoesNotRequireDirectory() throws Exception {
        Map<String, Object> map = validMap();
        map.put("useEmbeddedMediaDriver", true);

        AeronSourceConfig config = AeronSourceConfig.load(map);
        config.validate();

        assertThat(config.getAeronDirectoryName()).isNull();
    }
}
