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

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A sink connector that publishes each Pulsar record's value onto an Aeron channel.
 *
 * <p>The intended shape is Pulsar (or a Flink job writing into Pulsar) computing a result and
 * handing it to latency-sensitive consumers that already speak Aeron, without those consumers
 * taking a broker hop.
 *
 * <p><b>Delivery semantics.</b> From Pulsar's side this is at-least-once: a record is acked only
 * once Aeron has accepted it, so a crash between the offer and the ack causes a redelivery and a
 * duplicate on the Aeron side. There is no deduplication. From the subscriber's side there is no
 * guarantee at all — Aeron does not persist, so a subscriber that is not connected when a message
 * is offered never sees it.
 *
 * <p><b>Only the record's value is published.</b> Aeron messages are raw bytes with no header or
 * metadata slots, so the record key and properties cannot be carried without inventing a framing
 * format. They are dropped, deliberately and visibly, rather than silently half-supported.
 */
@Connector(
        name = "aeron",
        type = IOType.SINK,
        help = "An Aeron sink connector that publishes Pulsar messages onto an Aeron channel",
        configClass = AeronSinkConfig.class)
public class AeronSink implements Sink<byte[]> {

    private static final Logger LOG = LoggerFactory.getLogger(AeronSink.class);

    static final String METRIC_RECORDS_PUBLISHED = "aeron-records-published";
    static final String METRIC_BYTES_PUBLISHED = "aeron-bytes-published";
    static final String METRIC_RECORDS_DROPPED = "aeron-records-dropped";
    static final String METRIC_OFFER_TIMEOUTS = "aeron-offer-timeouts";

    private AeronSinkConfig config;
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Publication publication;
    private AeronPublisher publisher;
    private SinkContext sinkContext;

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        this.config = AeronSinkConfig.load(config);
        this.config.validate();
        this.sinkContext = sinkContext;

        try {
            String aeronDirectoryName = this.config.getAeronDirectoryName();

            if (this.config.isUseEmbeddedMediaDriver()) {
                final MediaDriver.Context driverContext = new MediaDriver.Context()
                        .threadingMode(ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true);
                if (StringUtils.isNotBlank(aeronDirectoryName)) {
                    driverContext.aeronDirectoryName(aeronDirectoryName);
                }
                mediaDriver = MediaDriver.launchEmbedded(driverContext);
                aeronDirectoryName = mediaDriver.aeronDirectoryName();
                LOG.info("Launched embedded Aeron media driver in {}", aeronDirectoryName);
            }

            final Aeron.Context aeronContext = new Aeron.Context();
            if (StringUtils.isNotBlank(aeronDirectoryName)) {
                aeronContext.aeronDirectoryName(aeronDirectoryName);
            }
            aeron = Aeron.connect(aeronContext);

            // A concurrent Publication, not an ExclusivePublication: the framework makes no
            // promise that write() is called from a single thread, and ExclusivePublication is
            // only safe when it is.
            publication = aeron.addPublication(
                    this.config.getChannel(), this.config.getStreamId());
            publisher = new AeronPublisher(publication::offer, this.config);

            LOG.info("Aeron sink publishing to channel {} stream {}",
                    this.config.getChannel(), this.config.getStreamId());
        } catch (Exception e) {
            // The framework does not call close() when open() throws, so an embedded driver
            // started above would otherwise be left running.
            closeQuietly();
            throw e;
        }
    }

    @Override
    public void write(Record<byte[]> record) throws Exception {
        final byte[] payload = record.getValue();
        if (payload == null) {
            // Nothing to put on the wire. Ack rather than fail: redelivering a null-valued
            // record would just produce the same nothing forever.
            LOG.debug("Skipping record with null value");
            record.ack();
            return;
        }

        final AeronPublisher.OfferOutcome outcome;
        try {
            outcome = publisher.publish(payload);
        } catch (IllegalStateException e) {
            // The publication is unusable. Fail the record so it is redelivered once the sink
            // has been restarted, and let the exception surface rather than hiding a dead sink.
            record.fail();
            throw e;
        }

        if (outcome == AeronPublisher.OfferOutcome.PUBLISHED) {
            recordMetric(METRIC_RECORDS_PUBLISHED, 1);
            recordMetric(METRIC_BYTES_PUBLISHED, payload.length);
            record.ack();
            return;
        }

        recordMetric(METRIC_OFFER_TIMEOUTS, 1);
        if (config.isDropOnTimeout()) {
            // Aeron has no store to fall back on, so this really is a discarded message.
            // Counting it keeps that visible instead of looking like a successful write.
            recordMetric(METRIC_RECORDS_DROPPED, 1);
            LOG.warn("Dropping a {} byte record: Aeron did not accept it within {} ms "
                            + "(no subscriber, or sustained back pressure)",
                    payload.length, config.getOfferTimeoutMs());
            record.ack();
        } else {
            LOG.warn("Failing a {} byte record for redelivery: Aeron did not accept it "
                            + "within {} ms (no subscriber, or sustained back pressure)",
                    payload.length, config.getOfferTimeoutMs());
            record.fail();
        }
    }

    private void recordMetric(String name, double value) {
        if (sinkContext != null) {
            sinkContext.recordMetric(name, value);
        }
    }

    @Override
    public void close() throws Exception {
        closeQuietly();
    }

    /** Tears down in reverse order of construction. */
    private void closeQuietly() {
        publisher = null;
        if (publication != null) {
            try {
                publication.close();
            } catch (Exception e) {
                LOG.warn("Failed to close Aeron publication", e);
            }
            publication = null;
        }
        if (aeron != null) {
            try {
                aeron.close();
            } catch (Exception e) {
                LOG.warn("Failed to close Aeron client", e);
            }
            aeron = null;
        }
        if (mediaDriver != null) {
            try {
                // Configured with dirDeleteOnShutdown, so this removes the directory too.
                mediaDriver.close();
            } catch (Exception e) {
                LOG.warn("Failed to close embedded Aeron media driver", e);
            }
            mediaDriver = null;
        }
    }
}
