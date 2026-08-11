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
import static org.mockito.Mockito.mock;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.awaitility.Awaitility;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * End-to-end tests for {@link AeronSink} against a real Aeron subscription over IPC.
 *
 * <p>As with the source, the Aeron leg cannot be containerised: a client reaches its media
 * driver through memory-mapped files. {@link AeronSinkContainerTest} covers the broker leg.
 */
public class AeronSinkIntegrationTest {

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1002;
    private static final long TIMEOUT_SECONDS = 30L;
    private static final int LARGER_THAN_MTU = 64 * 1024;

    private Path aeronDir;
    private MediaDriver mediaDriver;
    private AeronSink sink;
    private Aeron subscriberClient;
    private Subscription subscription;
    private Thread pollThread;
    private final AtomicBoolean polling = new AtomicBoolean();
    private List<String> received;
    private SinkContext sinkContext;

    @BeforeMethod
    public void setUp() throws Exception {
        Path parent = Path.of(System.getProperty("aeron.test.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(parent);
        aeronDir = Files.createTempDirectory(parent, "aeron-sink-it-");
        received = new CopyOnWriteArrayList<>();
        sinkContext = mock(SinkContext.class);

        // The test owns the driver so both the sink and the subscriber can attach to it.
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(aeronDir.toString())
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        polling.set(false);
        if (pollThread != null) {
            try {
                pollThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
        closeQuietly(subscription);
        subscription = null;
        closeQuietly(subscriberClient);
        subscriberClient = null;
        closeQuietly(sink);
        sink = null;
        closeQuietly(mediaDriver);
        mediaDriver = null;
        deleteRecursively(aeronDir);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Ignored during teardown.
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    // Best effort.
                }
            });
        } catch (Exception e) {
            // Best effort.
        }
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("channel", CHANNEL);
        config.put("streamId", STREAM_ID);
        config.put("useEmbeddedMediaDriver", false);
        config.put("aeronDirectoryName", mediaDriver.aeronDirectoryName());
        config.put("idleStrategy", "sleeping");
        return config;
    }

    /** Starts an Aeron subscriber that collects whatever the sink publishes. */
    private void startSubscriber() {
        subscriberClient = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        subscription = subscriberClient.addSubscription(CHANNEL, STREAM_ID);

        polling.set(true);
        pollThread = new Thread(() -> {
            FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                byte[] payload = new byte[length];
                buffer.getBytes(offset, payload);
                received.add(new String(payload, StandardCharsets.UTF_8));
            });
            SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);
            while (polling.get()) {
                idle.idle(subscription.poll(assembler, 100));
            }
        }, "aeron-sink-it-subscriber");
        pollThread.setDaemon(true);
        pollThread.start();

        Awaitility.await("subscription connected")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> subscription.isConnected());
    }

    private void openSink(Map<String, Object> config) throws Exception {
        sink = new AeronSink();
        sink.open(config, sinkContext);
    }

    /** A minimal Record that records whether it was acked or failed. */
    private static final class TestRecord implements Record<byte[]> {
        private final byte[] value;
        private final AtomicInteger acks = new AtomicInteger();
        private final AtomicInteger fails = new AtomicInteger();

        TestRecord(byte[] value) {
            this.value = value;
        }

        @Override
        public byte[] getValue() {
            return value;
        }

        @Override
        public Optional<String> getKey() {
            return Optional.of("ignored-by-aeron");
        }

        @Override
        public void ack() {
            acks.incrementAndGet();
        }

        @Override
        public void fail() {
            fails.incrementAndGet();
        }
    }

    private static TestRecord record(String value) {
        return new TestRecord(value.getBytes(StandardCharsets.UTF_8));
    }

    private void awaitReceived(int count) {
        Awaitility.await("subscriber received " + count + " messages")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> received.size() >= count);
    }

    @Test
    public void testRecordsReachTheAeronSubscriber() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String payload = "{\"symbol\":\"DEMO\",\"vwap\":" + i + "}";
            sent.add(payload);
            sink.write(record(payload));
        }

        awaitReceived(sent.size());

        // One publication is one Aeron session, so ordering holds for this setup.
        assertThat(received).containsExactlyElementsOf(sent);
    }

    @Test
    public void testRecordsAreAckedOnlyAfterAeronAcceptsThem() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        TestRecord rec = record("aggregate");
        sink.write(rec);

        awaitReceived(1);
        assertThat(rec.acks.get()).isEqualTo(1);
        assertThat(rec.fails.get()).isZero();
    }

    @Test
    public void testMessageLargerThanMtuIsFragmentedAndReassembled() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        String large = RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
        sink.write(record(large));

        awaitReceived(1);

        assertThat(received.get(0)).hasSize(LARGER_THAN_MTU);
        assertThat(received.get(0)).isEqualTo(large);
    }

    @Test
    public void testMixedSizesArriveIntact() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String small = "small-" + i;
            sent.add(small);
            sink.write(record(small));

            String large = "large-" + i + "-"
                    + RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
            sent.add(large);
            sink.write(record(large));
        }

        awaitReceived(sent.size());

        assertThat(received).containsExactlyElementsOf(sent);
    }

    @Test
    public void testNullValuedRecordIsAckedAndNotPublished() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        TestRecord rec = new TestRecord(null);
        sink.write(rec);

        assertThat(rec.acks.get()).isEqualTo(1);
        assertThat(rec.fails.get()).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    public void testEmptyValuedRecordIsPublished() throws Exception {
        openSink(baseConfig());
        startSubscriber();

        TestRecord rec = new TestRecord(new byte[0]);
        sink.write(rec);

        awaitReceived(1);
        assertThat(received.get(0)).isEmpty();
        assertThat(rec.acks.get()).isEqualTo(1);
    }

    @Test
    public void testWriteWithNoSubscriberFailsTheRecordForRedelivery() throws Exception {
        // No startSubscriber(): Aeron has no broker, so with nobody listening the offer can
        // never succeed. The default policy hands the record back to Pulsar.
        Map<String, Object> config = baseConfig();
        config.put("offerTimeoutMs", 500);
        openSink(config);

        TestRecord rec = record("nobody-is-listening");
        sink.write(rec);

        assertThat(rec.fails.get()).isEqualTo(1);
        assertThat(rec.acks.get()).isZero();
    }

    @Test
    public void testDropPolicyAcksInsteadOfFailing() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("offerTimeoutMs", 500);
        config.put("onOfferTimeout", "drop");
        openSink(config);

        TestRecord rec = record("dropped-on-the-floor");
        sink.write(rec);

        assertThat(rec.acks.get()).isEqualTo(1);
        assertThat(rec.fails.get()).isZero();
    }

    @Test
    public void testOpenWithInvalidConfigFailsFastAndLeavesNothingRunning() {
        Map<String, Object> config = baseConfig();
        config.put("channel", "not-an-aeron-uri");

        AeronSink bad = new AeronSink();
        assertThatThrownBy(() -> bad.open(config, sinkContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid Aeron URI");
    }

    @Test
    public void testCloseIsIdempotent() throws Exception {
        openSink(baseConfig());
        startSubscriber();
        sink.write(record("before-close"));
        awaitReceived(1);

        sink.close();
        AeronSink closed = sink;
        sink = null;

        // The framework can call close() on an already stopped instance.
        closed.close();
    }

    @Test
    public void testEmbeddedMediaDriverModeWorks() throws Exception {
        // The sink launches and owns its driver. A subscriber attaches to that same directory,
        // which is the only way to observe it from the test.
        Path embeddedDir = Files.createTempDirectory(aeronDir, "embedded-");
        Map<String, Object> config = new HashMap<>();
        config.put("channel", CHANNEL);
        config.put("streamId", STREAM_ID);
        config.put("useEmbeddedMediaDriver", true);
        config.put("aeronDirectoryName", embeddedDir.toString());
        config.put("idleStrategy", "sleeping");
        openSink(config);

        subscriberClient = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(embeddedDir.toString()));
        subscription = subscriberClient.addSubscription(CHANNEL, STREAM_ID);
        polling.set(true);
        pollThread = new Thread(() -> {
            FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                byte[] payload = new byte[length];
                buffer.getBytes(offset, payload);
                received.add(new String(payload, StandardCharsets.UTF_8));
            });
            SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);
            while (polling.get()) {
                idle.idle(subscription.poll(assembler, 100));
            }
        }, "aeron-sink-it-embedded-subscriber");
        pollThread.setDaemon(true);
        pollThread.start();
        Awaitility.await("subscription connected")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> subscription.isConnected());

        sink.write(record("via-embedded-driver"));
        awaitReceived(1);

        assertThat(received).containsExactly("via-embedded-driver");
    }
}
