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
 * Tests {@link AeronSinkConfig} deserialization and validation.
 */
public class AeronSinkConfigTest {

    private static Map<String, Object> validMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("channel", "aeron:udp?endpoint=consumer:40124");
        return map;
    }

    @Test
    public void testDefaults() throws Exception {
        AeronSinkConfig config = AeronSinkConfig.load(validMap());

        assertThat(config.getChannel()).isEqualTo("aeron:udp?endpoint=consumer:40124");
        // Defaults to a different stream id than the source, so a source and a sink pointed at
        // the same channel do not silently form a loop.
        assertThat(config.getStreamId()).isEqualTo(1002);
        assertThat(config.isUseEmbeddedMediaDriver()).isTrue();
        assertThat(config.getIdleStrategy()).isEqualTo(IdleStrategies.BACKOFF);
        assertThat(config.getOfferTimeoutMs()).isEqualTo(30_000L);
        assertThat(config.getOnOfferTimeout()).isEqualTo(AeronSinkConfig.ON_TIMEOUT_FAIL);
        assertThat(config.isDropOnTimeout()).isFalse();

        config.validate();
    }

    @Test
    public void testLoadAllFields() throws Exception {
        Map<String, Object> map = validMap();
        map.put("streamId", 7);
        map.put("useEmbeddedMediaDriver", false);
        map.put("aeronDirectoryName", "/tmp/aeron-sink");
        map.put("idleStrategy", "busyspin");
        map.put("offerTimeoutMs", 5000);
        map.put("onOfferTimeout", "drop");

        AeronSinkConfig config = AeronSinkConfig.load(map);

        assertThat(config.getStreamId()).isEqualTo(7);
        assertThat(config.isUseEmbeddedMediaDriver()).isFalse();
        assertThat(config.getAeronDirectoryName()).isEqualTo("/tmp/aeron-sink");
        assertThat(config.getIdleStrategy()).isEqualTo("busyspin");
        assertThat(config.getOfferTimeoutMs()).isEqualTo(5000L);
        assertThat(config.isDropOnTimeout()).isTrue();

        config.validate();
    }

    @Test
    public void testDropPolicyIsCaseInsensitive() throws Exception {
        Map<String, Object> map = validMap();
        map.put("onOfferTimeout", "DROP");

        AeronSinkConfig config = AeronSinkConfig.load(map);
        config.validate();

        assertThat(config.isDropOnTimeout()).isTrue();
    }

    @Test
    public void testMissingChannelIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.remove("channel");

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel must be set");
    }

    @Test
    public void testMalformedChannelUriIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("channel", "udp://consumer:40124");

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid Aeron URI");
    }

    @Test
    public void testZeroStreamIdIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("streamId", 0);

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamId must not be 0");
    }

    @Test
    public void testUnknownIdleStrategyIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("idleStrategy", "spin-forever");

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleStrategy must be one of");
    }

    @Test
    public void testNonPositiveOfferTimeoutIsRejected() throws Exception {
        Map<String, Object> map = validMap();
        map.put("offerTimeoutMs", 0);

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offerTimeoutMs must be positive");
    }

    @Test
    public void testUnknownTimeoutPolicyIsRejected() throws Exception {
        // A typo here would otherwise silently fall through to the 'fail' behaviour, which is
        // the opposite of what someone writing "discard" intended.
        Map<String, Object> map = validMap();
        map.put("onOfferTimeout", "discard");

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onOfferTimeout must be one of");
    }

    @Test
    public void testExternalDriverRequiresDirectory() throws Exception {
        Map<String, Object> map = validMap();
        map.put("useEmbeddedMediaDriver", false);

        assertThatThrownBy(() -> AeronSinkConfig.load(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aeronDirectoryName must be set");
    }
}
