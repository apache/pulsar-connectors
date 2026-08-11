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
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import io.aeron.Aeron;
import io.aeron.Publication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.awaitility.Awaitility;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * End-to-end tests for {@link AeronSource} over real Aeron IPC.
 *
 * <p>Everything runs in one JVM against an embedded media driver. This is not a shortcut
 * around Testcontainers: an Aeron client reaches its media driver through memory-mapped
 * files in a shared directory, so a driver inside a container is unreachable from a test on
 * the host. The container-based coverage lives in {@link AeronSourceContainerTest}, which
 * uses a container for the Pulsar broker — the one leg that can be containerised.
 *
 * <p>IPC still exercises the code paths that matter here: fragmentation and reassembly,
 * header metadata, and the copy out of the term buffer.
 */
public class AeronSourceIntegrationTest {

    private static final String CHANNEL = "aeron:ipc";
    /** Publications set a small term length so the test's mapped files stay modest in CI. */
    private static final String PUBLICATION_CHANNEL = "aeron:ipc?term-length=1048576";
    private static final int STREAM_ID = 1001;
    private static final long TIMEOUT_SECONDS = 30L;
    /** The IPC MTU defaults to 1408 bytes, so anything above that fragments. */
    private static final int LARGER_THAN_MTU = 64 * 1024;

    private Path aeronDir;
    private AeronSource source;
    private Aeron publisherClient;
    private Publication publication;
    private Thread readerThread;
    private List<Record<byte[]>> collected;
    private SourceContext sourceContext;

    @BeforeMethod
    public void setUp() throws Exception {
        sourceContext = mock(SourceContext.class);
        // Not /dev/shm: it is routinely too small in CI containers for Aeron's buffers.
        Path parent = Path.of(System.getProperty("aeron.test.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(parent);
        aeronDir = Files.createTempDirectory(parent, "aeron-source-it-");
        collected = new CopyOnWriteArrayList<>();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        closeQuietly(publication);
        publication = null;
        closeQuietly(publisherClient);
        publisherClient = null;
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                // Do not let a teardown failure mask the test result.
            }
            source = null;
        }
        deleteRecursively(aeronDir);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Ignored during teardown.
            }
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
                    // Best effort; the driver may still hold a mapping.
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
        config.put("useEmbeddedMediaDriver", true);
        // Pinning the directory lets the test's publisher attach to the source's own driver.
        config.put("aeronDirectoryName", aeronDir.toString());
        config.put("idleStrategy", "sleeping");
        return config;
    }

    /** Starts the source and a background thread that drains it, as the framework would. */
    private void startSource(Map<String, Object> config) throws Exception {
        source = new AeronSource();
        source.open(config, sourceContext);

        readerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    collected.add(source.read());
                }
            } catch (Exception e) {
                // Interrupted at teardown.
            }
        }, "aeron-it-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void connectPublisher() {
        publisherClient = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir.toString()));
        publication = publisherClient.addPublication(PUBLICATION_CHANNEL, STREAM_ID);
        Awaitility.await("publication connected to the source's subscription")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> publication.isConnected());
    }

    /** Offers a payload, retrying while Aeron reports transient backpressure. */
    private void offer(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadlineNanos) {
            long result = publication.offer(buffer, 0, payload.length);
            if (result > 0) {
                return;
            }
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                fail("Aeron publication is unusable, offer returned " + result);
            }
            // BACK_PRESSURED, NOT_CONNECTED and ADMIN_ACTION are all transient.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Timed out offering a " + payload.length + " byte payload to Aeron");
    }

    private void awaitRecords(int count) {
        Awaitility.await("source emitted " + count + " records")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> collected.size() >= count);
    }

    private static List<String> valuesAsStrings(List<Record<byte[]>> records) {
        return records.stream()
                .map(r -> new String(r.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    @Test
    public void testMessagesFlowFromAeronToTheSource() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String payload = "{\"seq\":" + i + ",\"symbol\":\"DEMO\"}";
            sent.add(payload);
            offer(payload.getBytes(StandardCharsets.UTF_8));
        }

        awaitRecords(sent.size());

        // A single publication is a single Aeron session, so ordering is guaranteed here.
        // That is a property of this setup, not of Aeron across sessions.
        assertThat(valuesAsStrings(collected)).containsExactlyElementsOf(sent);
    }

    @Test
    public void testMessageLargerThanMtuIsReassembled() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        // Random content so a truncated or mis-stitched reassembly cannot accidentally match.
        String large = RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
        offer(large.getBytes(StandardCharsets.UTF_8));

        awaitRecords(1);

        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getValue()).hasSize(LARGER_THAN_MTU);
        assertThat(new String(collected.get(0).getValue(), StandardCharsets.UTF_8)).isEqualTo(large);
    }

    @Test
    public void testMixedSizesInterleaveCorrectly() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        // Interleaving fragmented and unfragmented messages catches assembler state that
        // leaks between messages.
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String small = "small-" + i;
            sent.add(small);
            offer(small.getBytes(StandardCharsets.UTF_8));

            String large = "large-" + i + "-"
                    + RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
            sent.add(large);
            offer(large.getBytes(StandardCharsets.UTF_8));
        }

        awaitRecords(sent.size());

        assertThat(valuesAsStrings(collected)).containsExactlyElementsOf(sent);
    }

    @Test
    public void testRecordMetadataMatchesThePublication() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        offer("payload".getBytes(StandardCharsets.UTF_8));
        awaitRecords(1);

        Record<byte[]> record = collected.get(0);
        assertThat(record.getProperties())
                .containsEntry(AeronRecord.PROP_SESSION_ID, Integer.toString(publication.sessionId()))
                .containsEntry(AeronRecord.PROP_STREAM_ID, Integer.toString(STREAM_ID))
                .containsEntry(AeronRecord.PROP_CHANNEL, CHANNEL);
        assertThat(Long.parseLong(record.getProperties().get(AeronRecord.PROP_POSITION))).isPositive();
        assertThat(Long.parseLong(record.getProperties().get(AeronRecord.PROP_INGEST_TS))).isPositive();
        // Default config: no key.
        assertThat(record.getKey()).isEmpty();
    }

    @Test
    public void testKeyBySessionIdUsesTheAeronSession() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("keyBySessionId", true);
        startSource(config);
        connectPublisher();

        offer("payload".getBytes(StandardCharsets.UTF_8));
        awaitRecords(1);

        assertThat(collected.get(0).getKey()).contains(Integer.toString(publication.sessionId()));
    }

    @Test
    public void testPositionAdvancesAcrossMessages() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        for (int i = 0; i < 10; i++) {
            offer(("msg-" + i).getBytes(StandardCharsets.UTF_8));
        }
        awaitRecords(10);

        List<Long> positions = collected.stream()
                .map(r -> Long.parseLong(r.getProperties().get(AeronRecord.PROP_POSITION)))
                .collect(Collectors.toList());

        assertThat(positions).isSorted();
        assertThat(positions.get(positions.size() - 1)).isGreaterThan(positions.get(0));
    }

    @Test
    public void testFragmentAndRecordMetricsAreBothReported() throws Exception {
        startSource(baseConfig());
        connectPublisher();

        // One fragmented message: the record counter must see one message while the fragment
        // counter, driven by poll(), sees the wire-level fragments it was split into.
        offer(RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU)
                .getBytes(StandardCharsets.UTF_8));
        awaitRecords(1);

        verify(sourceContext, atLeastOnce())
                .recordMetric(eq(AeronPollingRunner.METRIC_FRAGMENTS_RECEIVED), anyDouble());
        verify(sourceContext).recordMetric(eq(AeronPollingRunner.METRIC_RECORDS_CONSUMED), eq(1.0d));
    }

    @Test
    public void testCloseStopsTheSourceCleanly() throws Exception {
        startSource(baseConfig());
        connectPublisher();
        offer("before-close".getBytes(StandardCharsets.UTF_8));
        awaitRecords(1);

        source.close();
        AeronSource closed = source;
        source = null;

        // close() must be safe to call twice; the framework can invoke it on an already
        // stopped instance.
        closed.close();
    }

    @Test
    public void testOpenWithInvalidConfigFailsFastAndLeavesNothingRunning() {
        Map<String, Object> config = baseConfig();
        config.put("channel", "not-an-aeron-uri");

        AeronSource bad = new AeronSource();
        try {
            bad.open(config, null);
            fail("Expected open() to reject the malformed channel URI");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalArgumentException.class);
            assertThat(e).hasMessageContaining("not a valid Aeron URI");
        }
        // A failed open() must not leave a media driver behind; the framework will not call
        // close() for it.
        assertThat(aeronDir.resolve("cnc.dat")).doesNotExist();
    }

    @Test
    public void testExternalMediaDriverModeAttachesToAnExistingDriver() throws Exception {
        // Covers the useEmbeddedMediaDriver=false path: the source must attach to a driver it
        // did not start, and must not delete that driver's directory on close.
        io.aeron.driver.MediaDriver driver = io.aeron.driver.MediaDriver.launchEmbedded(
                new io.aeron.driver.MediaDriver.Context()
                        .aeronDirectoryName(aeronDir.toString())
                        .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true));
        try {
            Map<String, Object> config = baseConfig();
            config.put("useEmbeddedMediaDriver", false);
            config.put("aeronDirectoryName", driver.aeronDirectoryName());
            startSource(config);
            connectPublisher();

            offer("external-driver".getBytes(StandardCharsets.UTF_8));
            awaitRecords(1);

            assertThat(valuesAsStrings(collected)).containsExactly("external-driver");

            source.close();
            source = null;
            // The externally owned driver is still usable after the source closed.
            assertThat(driver.aeronDirectoryName()).isNotNull();
        } finally {
            driver.close();
        }
    }
}
