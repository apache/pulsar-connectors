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
 * <p>In transport mode {@link #ack()} and {@link #fail()} are no-ops: plain Aeron has no
 * persistence and no resumable position, so there is nothing to acknowledge and nothing to
 * redeliver, which is what makes that mode at-most-once.
 *
 * <p>In archive mode they are not decoration. The framework queues a record via {@code consume()}
 * and only publishes it later, so an acknowledgement is the sole evidence that a record reached
 * Pulsar. Committing a replay position on anything earlier would checkpoint past records that were
 * never published.
 */
public class AeronRecord implements Record<byte[]> {

    public static final String PROP_SESSION_ID = "aeron.session-id";
    public static final String PROP_STREAM_ID = "aeron.stream-id";
    public static final String PROP_CHANNEL = "aeron.channel";
    public static final String PROP_POSITION = "aeron.position";
    public static final String PROP_INGEST_TS = "aeron.ingest-ts";
    /** Only present in archive mode. */
    public static final String PROP_RECORDING_ID = "aeron.recording-id";

    /**
     * Told whether the framework managed to publish a record.
     *
     * <p>Archive mode needs this: the framework publishes and acknowledges <em>after</em>
     * {@code consume()} has queued the record, so only an acknowledgement proves a record is
     * safely in Pulsar and its position safe to checkpoint.
     */
    interface Outcome {
        void acked();

        void failed();
    }

    private final byte[] value;
    private final String key;
    private final Map<String, String> properties;
    private final Outcome outcome;

    /**
     * @param value      the reassembled payload; must already be a copy owned by this record
     * @param key        the record key, or null for none
     * @param properties Aeron metadata; copied defensively
     */
    public AeronRecord(byte[] value, String key, Map<String, String> properties) {
        this(value, key, properties, null);
    }

    /**
     * @param outcome notified when the framework publishes or fails this record, or null for the
     *                live transport, where there is no position to commit and nothing to redeliver
     */
    AeronRecord(byte[] value, String key, Map<String, String> properties, Outcome outcome) {
        this.value = value;
        this.key = key;
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
        this.outcome = outcome;
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
        // Transport mode has nothing to acknowledge; archive mode commits its position here,
        // which is the only point at which the record is known to be in Pulsar.
        if (outcome != null) {
            outcome.acked();
        }
    }

    @Override
    public void fail() {
        // Transport mode cannot redeliver. Archive mode must not checkpoint past this record.
        if (outcome != null) {
            outcome.failed();
        }
    }
}
