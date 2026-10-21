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
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.core.PushSource;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A source connector that subscribes to an Aeron channel and publishes each message to a
 * Pulsar topic.
 *
 * <p>This is a {@link PushSource} rather than a plain {@code Source} because Aeron delivers
 * only to a caller that polls: a dedicated thread runs the poll loop and pushes into the
 * source's internal queue, which the function framework drains via {@code read()}.
 *
 * <p><b>Delivery semantics depend on the configured mode, and they differ materially.</b>
 *
 * <ul>
 *   <li><b>{@code transport}</b> (default) reads the live Aeron stream and is
 *       <b>at-most-once</b>. Plain Aeron has no persistence and no resumable position, so nothing
 *       can be replayed: messages are lost across connector restarts, and a multicast subscriber
 *       that falls behind loses data once the publisher's term buffer rotates.
 *   <li><b>{@code archive}</b> replays from an Aeron Archive recording, so a position in durable
 *       storage exists and data missed while the connector was down can be re-read. Records carry
 *       the archive position as their record sequence, which broker deduplication can use.
 * </ul>
 *
 * <p>Because the guarantees differ, the mode is an explicit setting rather than a boolean flag,
 * and archive settings supplied in transport mode are rejected rather than ignored — a
 * half-configured archive setup that quietly runs at-most-once is the worst available outcome.
 *
 * <p>A single instance owns one subscription; parallelism greater than 1 would give every
 * instance the same full stream rather than partitioning it.
 */
@Connector(
        name = "aeron",
        type = IOType.SOURCE,
        help = "An Aeron source connector that bridges an Aeron subscription into a Pulsar topic",
        configClass = AeronSourceConfig.class)
public class AeronSource extends PushSource<byte[]> {

    private static final Logger LOG = LoggerFactory.getLogger(AeronSource.class);

    private static final long POLLER_JOIN_TIMEOUT_MS = 5_000L;

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Subscription subscription;
    private AeronArchive archive;
    private AeronPoller poller;
    private Thread pollerThread;

    @Override
    public void open(Map<String, Object> config, SourceContext sourceContext) throws Exception {
        final AeronSourceConfig aeronSourceConfig = AeronSourceConfig.load(config);
        aeronSourceConfig.validate();

        try {
            String aeronDirectoryName = aeronSourceConfig.getAeronDirectoryName();

            if (aeronSourceConfig.isUseEmbeddedMediaDriver()) {
                final MediaDriver.Context driverContext = new MediaDriver.Context()
                        // SHARED runs the driver's duties on one thread. A connector is not
                        // chasing single-digit-microsecond latency, and this keeps the core
                        // count down in the containers connectors usually run in.
                        .threadingMode(ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true);
                if (StringUtils.isNotBlank(aeronDirectoryName)) {
                    driverContext.aeronDirectoryName(aeronDirectoryName);
                }
                mediaDriver = MediaDriver.launchEmbedded(driverContext);
                // launchEmbedded generates a unique directory when one was not supplied, so
                // read it back rather than assuming the configured value.
                aeronDirectoryName = mediaDriver.aeronDirectoryName();
                LOG.info("Launched embedded Aeron media driver in {}", aeronDirectoryName);
            }

            final Aeron.Context aeronContext = new Aeron.Context();
            if (StringUtils.isNotBlank(aeronDirectoryName)) {
                aeronContext.aeronDirectoryName(aeronDirectoryName);
            }
            aeron = Aeron.connect(aeronContext);

            if (aeronSourceConfig.isArchiveMode()) {
                archive = AeronArchive.connect(new AeronArchive.Context()
                        .aeron(aeron)
                        // The source owns the Aeron client; the archive must not close it.
                        .ownsAeronClient(false)
                        .controlRequestChannel(aeronSourceConfig.getArchiveControlRequestChannel())
                        .controlResponseChannel(
                                aeronSourceConfig.getArchiveControlResponseChannel()));
                poller = new ArchivePollingRunner(
                        archive, aeronSourceConfig, this::consume, sourceContext);
            } else {
                subscription = aeron.addSubscription(
                        aeronSourceConfig.getChannel(), aeronSourceConfig.getStreamId());
                poller = new AeronPollingRunner(
                        subscription, aeronSourceConfig, this::consume, sourceContext);
            }
            // A dedicated thread, not a shared pool: the loop runs until close() and would
            // otherwise occupy a pool thread indefinitely.
            pollerThread = new Thread(poller, threadName(sourceContext));
            pollerThread.setDaemon(true);
            pollerThread.start();

            // Log the mode: it determines the delivery guarantee, so it must be visible in the
            // logs rather than only in the config someone has to go and look up.
            LOG.info("Aeron source started in '{}' mode on channel {} stream {}",
                    aeronSourceConfig.getMode(), aeronSourceConfig.getChannel(),
                    aeronSourceConfig.getStreamId());
        } catch (Exception e) {
            // open() failing part-way would otherwise leak a media driver process and its
            // directory, since the framework does not call close() on a failed open().
            closeQuietly();
            throw e;
        }
    }

    private static String threadName(SourceContext sourceContext) {
        if (sourceContext == null) {
            return "aeron-source-poller";
        }
        return "aeron-source-poller-" + sourceContext.getSourceName() + "-" + sourceContext.getInstanceId();
    }

    @Override
    public void close() throws Exception {
        closeQuietly();
    }

    /**
     * Tears down in reverse order of construction: stop polling first so nothing touches the
     * subscription while it is being closed.
     */
    private void closeQuietly() {
        if (poller != null) {
            poller.stop();
        }
        if (pollerThread != null) {
            try {
                pollerThread.join(POLLER_JOIN_TIMEOUT_MS);
                if (pollerThread.isAlive()) {
                    // The loop can be parked inside a blocking consume() when the downstream
                    // queue is full. Interrupt so shutdown is not held hostage by it.
                    LOG.warn("Aeron poll thread did not stop within {} ms; interrupting",
                            POLLER_JOIN_TIMEOUT_MS);
                    pollerThread.interrupt();
                    // Wait again rather than falling straight through: closing the
                    // subscription while the loop is still inside poll() races with Aeron's
                    // own teardown and surfaces as spurious exceptions during shutdown.
                    pollerThread.join(POLLER_JOIN_TIMEOUT_MS);
                    if (pollerThread.isAlive()) {
                        LOG.warn("Aeron poll thread still alive after interrupt; "
                                + "closing the subscription anyway");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollerThread = null;
        }
        poller = null;

        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                LOG.warn("Failed to close Aeron subscription", e);
            }
            subscription = null;
        }
        if (archive != null) {
            try {
                // Constructed with ownsAeronClient(false), so this closes only the archive's own
                // control session and leaves the Aeron client for the block below.
                archive.close();
            } catch (Exception e) {
                LOG.warn("Failed to close Aeron archive client", e);
            }
            archive = null;
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
                // Configured with dirDeleteOnShutdown, so this also removes the directory.
                mediaDriver.close();
            } catch (Exception e) {
                LOG.warn("Failed to close embedded Aeron media driver", e);
            }
            mediaDriver = null;
        }
    }
}
