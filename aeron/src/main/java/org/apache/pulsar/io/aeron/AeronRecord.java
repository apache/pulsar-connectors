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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.pulsar.functions.api.Record;

/**
 * A {@link Record} carrying one reassembled Aeron message together with the Aeron
 * header metadata it arrived with.
 *
 * <p>The payload is a private copy taken while the Aeron fragment handler callback was on
 * the stack: Aeron hands out a view into a term buffer that it reuses as soon as the
 * callback returns, so the bytes must be copied before the record leaves the polling
 * thread. See {@link AeronPollingRunner}.
 *
 * <p>{@link #ack()} and {@link #fail()} are deliberately no-ops. Plain Aeron is a transport
 * with no persistence and no resumable position, so there is nothing to acknowledge and
 * nothing to redeliver. This is what makes the connector at-most-once.
 */
public class AeronRecord implements Record<byte[]> {

    public static final String PROP_SESSION_ID = "aeron.session-id";
    public static final String PROP_STREAM_ID = "aeron.stream-id";
    public static final String PROP_CHANNEL = "aeron.channel";
    public static final String PROP_POSITION = "aeron.position";
    public static final String PROP_INGEST_TS = "aeron.ingest-ts";

    private final byte[] value;
    private final String key;
    private final Map<String, String> properties;

    /**
     * @param value      the reassembled payload; must already be a copy owned by this record
     * @param key        the record key, or null for none
     * @param properties Aeron metadata; copied defensively
     */
    public AeronRecord(byte[] value, String key, Map<String, String> properties) {
        this.value = value;
        this.key = key;
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
    }

    @Override
    public Optional<String> getKey() {
        return Optional.ofNullable(key);
    }

    @Override
    public byte[] getValue() {
        return value;
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public void ack() {
        // No-op: see class javadoc. Plain Aeron has nothing to acknowledge.
    }

    @Override
    public void fail() {
        // No-op: see class javadoc. Plain Aeron cannot redeliver a message.
    }
}
