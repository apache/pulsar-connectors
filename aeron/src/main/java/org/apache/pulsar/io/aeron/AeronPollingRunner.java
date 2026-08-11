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

import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls an Aeron {@link Subscription} and hands each reassembled message to a consumer.
 *
 * <p>Aeron has no callback-driven delivery: a subscriber must poll, and the idle strategy
 * decides what the thread does when there is nothing to read. One instance owns one thread.
 */
public class AeronPollingRunner implements AeronPoller {

    private static final Logger LOG = LoggerFactory.getLogger(AeronPollingRunner.class);

    static final String METRIC_FRAGMENTS_RECEIVED = "aeron-fragments-received";
    static final String METRIC_RECORDS_CONSUMED = "aeron-records-consumed";

    private final Subscription subscription;
    private final AeronSourceConfig config;
    private final Consumer<Record<byte[]>> consumer;
    private final SourceContext sourceContext;
    private final IdleStrategy idleStrategy;

    private volatile boolean running = true;

    public AeronPollingRunner(Subscription subscription,
                              AeronSourceConfig config,
                              Consumer<Record<byte[]>> consumer,
                              SourceContext sourceContext) {
        this.subscription = subscription;
        this.config = config;
        this.consumer = consumer;
        this.sourceContext = sourceContext;
        this.idleStrategy = IdleStrategies.create(config.getIdleStrategy());
    }

    @Override
    public void run() {
        // FragmentAssembler stitches messages that were split across MTU-sized fragments and
        // invokes the delegate once, with the whole message.
        final FragmentHandler handler = this::onFragment;
        final FragmentAssembler assembler = config.getFragmentAssemblyBufferLength() > 0
                ? new FragmentAssembler(handler, config.getFragmentAssemblyBufferLength())
                : new FragmentAssembler(handler);

        LOG.info("Aeron poll loop started for channel {} stream {}",
                config.getChannel(), config.getStreamId());
        try {
            while (running) {
                final int fragments = subscription.poll(assembler, config.getFragmentLimit());
                if (fragments > 0) {
                    // Counted here rather than in the handler: poll() returns wire-level
                    // fragments, whereas the assembler invokes the handler once per whole
                    // message. Comparing the two metrics is what shows how much traffic is
                    // arriving fragmented.
                    recordMetric(METRIC_FRAGMENTS_RECEIVED, fragments);
                }
                idleStrategy.idle(fragments);
            }
        } catch (Throwable t) {
            // Never let the poll loop die silently; without this the source would look
            // healthy while delivering nothing.
            if (running) {
                LOG.error("Aeron poll loop for channel {} stream {} terminated unexpectedly",
                        config.getChannel(), config.getStreamId(), t);
            }
        } finally {
            LOG.info("Aeron poll loop stopped for channel {} stream {}",
                    config.getChannel(), config.getStreamId());
        }
    }

    /**
     * Handles one reassembled Aeron message.
     *
     * <p>The {@code buffer} is a window onto an Aeron term buffer that Aeron reuses as soon
     * as this method returns. The payload copy below is therefore mandatory, not defensive:
     * without it the record handed downstream would mutate under the consumer and produce
     * intermittent, load-dependent corruption rather than a clean failure.
     */
    // Package-private so AeronPollingRunnerTest can assert the copy semantics directly,
    // without standing up a media driver.
    void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        final byte[] payload = new byte[length];
        buffer.getBytes(offset, payload);

        final Map<String, String> properties = new HashMap<>();
        properties.put(AeronRecord.PROP_SESSION_ID, Integer.toString(header.sessionId()));
        properties.put(AeronRecord.PROP_STREAM_ID, Integer.toString(header.streamId()));
        properties.put(AeronRecord.PROP_CHANNEL, config.getChannel());
        properties.put(AeronRecord.PROP_POSITION, Long.toString(header.position()));
        properties.put(AeronRecord.PROP_INGEST_TS, Long.toString(System.currentTimeMillis()));

        final String key = config.isKeyBySessionId() ? Integer.toString(header.sessionId()) : null;

        // consume() blocks once the source's internal queue is full. That block is the
        // backpressure mechanism: the poll loop stops polling, and Aeron flow control pushes
        // back on unicast publishers. Multicast publishers are not slowed and the
        // subscription will fall behind, which is a documented at-most-once loss window.
        consumer.accept(new AeronRecord(payload, key, properties));
        recordMetric(METRIC_RECORDS_CONSUMED, 1);
    }

    private void recordMetric(String name, int count) {
        if (sourceContext != null) {
            sourceContext.recordMetric(name, count);
        }
    }

    @Override
    public void stop() {
        running = false;
    }
}
