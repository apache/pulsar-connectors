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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link AeronRecord} metadata exposure.
 */
public class AeronRecordTest {

    private static Map<String, String> properties() {
        Map<String, String> props = new HashMap<>();
        props.put(AeronRecord.PROP_SESSION_ID, "42");
        props.put(AeronRecord.PROP_STREAM_ID, "1001");
        props.put(AeronRecord.PROP_CHANNEL, "aeron:ipc");
        props.put(AeronRecord.PROP_POSITION, "4096");
        props.put(AeronRecord.PROP_INGEST_TS, "1754870400000");
        return props;
    }

    @Test
    public void testValueAndProperties() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

        AeronRecord record = new AeronRecord(payload, null, properties());

        assertThat(record.getValue()).isEqualTo(payload);
        assertThat(record.getProperties())
                .containsEntry(AeronRecord.PROP_SESSION_ID, "42")
                .containsEntry(AeronRecord.PROP_STREAM_ID, "1001")
                .containsEntry(AeronRecord.PROP_CHANNEL, "aeron:ipc")
                .containsEntry(AeronRecord.PROP_POSITION, "4096")
                .containsEntry(AeronRecord.PROP_INGEST_TS, "1754870400000");
    }

    @Test
    public void testNoKeyByDefault() {
        AeronRecord record = new AeronRecord(new byte[0], null, properties());

        assertThat(record.getKey()).isEmpty();
    }

    @Test
    public void testKeyIsExposedWhenSet() {
        AeronRecord record = new AeronRecord(new byte[0], "42", properties());

        assertThat(record.getKey()).contains("42");
    }

    @Test
    public void testPropertiesAreImmutable() {
        AeronRecord record = new AeronRecord(new byte[0], null, properties());

        assertThatThrownBy(() -> record.getProperties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void testPropertiesAreCopiedFromCaller() {
        // The polling thread reuses its own maps between fragments in some shapes of this
        // code; the record must not alias whatever it was handed.
        Map<String, String> mutable = properties();
        AeronRecord record = new AeronRecord(new byte[0], null, mutable);

        mutable.put(AeronRecord.PROP_SESSION_ID, "999");

        assertThat(record.getProperties()).containsEntry(AeronRecord.PROP_SESSION_ID, "42");
    }

    @Test
    public void testAckAndFailAreNoOps() {
        // Plain Aeron has nothing to acknowledge. These must not throw, because the
        // framework calls ack() on every successfully published record.
        AeronRecord record = new AeronRecord(new byte[0], null, properties());

        record.ack();
        record.fail();
    }
}
