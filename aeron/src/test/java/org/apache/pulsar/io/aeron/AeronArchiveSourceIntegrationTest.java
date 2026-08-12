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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Exercises {@link AeronSource} in {@code archive} mode against a real Aeron Archive.
 *
 * <p>An {@link ArchivingMediaDriver} hosts a media driver and an archive in-process, so the whole
 * record-then-replay cycle runs without external services. As with the transport tests, the Aeron
 * leg cannot be containerised — a client reaches its driver through memory-mapped files.
 *
 * <p>What this pins down is the property the transport mode cannot offer: messages published
 * <em>before the source existed</em> are still delivered, because they were recorded.
 */
public class AeronArchiveSourceIntegrationTest {

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1001;
    private static final int REPLAY_STREAM_ID = 1002;
    private static final String REPLAY_CHANNEL = "aeron:ipc";
    private static final long TIMEOUT_SECONDS = 60L;

    private Path rootDir;
    private ArchivingMediaDriver archivingMediaDriver;
    private Aeron aeron;
    private AeronArchive archive;
    private AeronSource source;
    private Thread readerThread;
    private List<Record<byte[]>> collected;
    private SourceContext sourceContext;
    /** Stands in for the function state store; a bare mock would swallow checkpoints. */
    private Map<String, ByteBuffer> state;
    /** Lets a test withhold acknowledgements to model records stuck mid-publish. */
    private volatile boolean ackRecords = true;
    private String controlRequestChannel;
    private String controlResponseChannel;

    @BeforeMethod
    public void setUp() throws Exception {
        Path parent = Path.of(System.getProperty("aeron.test.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(parent);
        rootDir = Files.createTempDirectory(parent, "aeron-archive-it-");
        collected = new CopyOnWriteArrayList<>();
        ackRecords = true;
        state = new ConcurrentHashMap<>();
        sourceContext = mock(SourceContext.class);
        // Real read/write semantics, so checkpointing and resume are genuinely exercised rather
        // than silently no-oping through a mock.
        doAnswer(inv -> {
            state.put(inv.getArgument(0), ((ByteBuffer) inv.getArgument(1)).duplicate());
            return null;
        }).when(sourceContext).putState(anyString(), any());
        when(sourceContext.getState(anyString())).thenAnswer(inv -> {
            ByteBuffer stored = state.get(inv.<String>getArgument(0));
            return stored == null ? null : stored.duplicate();
        });
        // deleteState was previously left as Mockito's no-op, which made every reset test
        // vacuous: they would have passed even if clear() deleted nothing at all.
        doAnswer(inv -> {
            state.remove(inv.<String>getArgument(0));
            return null;
        }).when(sourceContext).deleteState(anyString());

        // Ports are picked from the ephemeral range so parallel test JVMs do not collide on a
        // fixed archive control port.
        final int controlPort = freePort();
        controlRequestChannel = "aeron:udp?endpoint=localhost:" + controlPort;
        controlResponseChannel = "aeron:udp?endpoint=localhost:" + freePort();

        archivingMediaDriver = ArchivingMediaDriver.launch(
                new MediaDriver.Context()
                        .aeronDirectoryName(rootDir.resolve("driver").toString())
                        .threadingMode(ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true),
                new Archive.Context()
                        .aeronDirectoryName(rootDir.resolve("driver").toString())
                        .archiveDir(rootDir.resolve("archive").toFile())
                        .controlChannel(controlRequestChannel)
                        .replicationChannel("aeron:udp?endpoint=localhost:" + freePort())
                        .threadingMode(io.aeron.archive.ArchiveThreadingMode.SHARED)
                        .deleteArchiveOnStart(true));

        aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(archivingMediaDriver.mediaDriver().aeronDirectoryName()));
        archive = AeronArchive.connect(new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .controlRequestChannel(controlRequestChannel)
                .controlResponseChannel(controlResponseChannel));
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
        closeQuietly(source);
        source = null;
        closeQuietly(archive);
        archive = null;
        closeQuietly(aeron);
        aeron = null;
        closeQuietly(archivingMediaDriver);
        archivingMediaDriver = null;
        deleteRecursively(rootDir);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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

    /** Records a publication and writes the given payloads into it, then stops recording. */
    private void recordMessages(List<String> payloads) {
        archive.startRecording(CHANNEL, STREAM_ID, SourceLocation.LOCAL);
        try (Publication publication = aeron.addPublication(CHANNEL, STREAM_ID)) {
            Awaitility.await("publication connected to the archive recorder")
                    .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .until(publication::isConnected);

            for (String payload : payloads) {
                offer(publication, payload.getBytes(StandardCharsets.UTF_8));
            }

            // Wait for the archive to durably catch up with the publication before replaying;
            // otherwise the recording's stop position may still be behind what was published.
            final long recordingId = Awaitility.await("recording registered")
                    .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .until(this::findRecordingId, id -> id != Aeron.NULL_VALUE);

            final long target = publication.position();
            Awaitility.await("archive caught up to " + target)
                    .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(50, TimeUnit.MILLISECONDS)
                    .until(() -> archive.getRecordingPosition(recordingId) >= target);
        } finally {
            archive.stopRecording(CHANNEL, STREAM_ID);
        }
    }

    /**
     * Finds the recording by channel and stream.
     *
     * <p>Not {@code findLastMatchingRecording}: it matches its sessionId argument literally rather
     * than treating {@link Aeron#NULL_VALUE} as a wildcard, so it returns nothing unless the
     * session id is already known. Same trap the connector has to avoid.
     */
    private long findRecordingId() {
        final org.agrona.collections.MutableLong latest =
                new org.agrona.collections.MutableLong(Aeron.NULL_VALUE);
        archive.listRecordingsForUri(0, 100, CHANNEL, STREAM_ID,
                (a, b, recordingId, c, d, e, f, g, h, i, j, k, l, m, n, o) -> {
                    if (recordingId > latest.get()) {
                        latest.set(recordingId);
                    }
                });
        return latest.get();
    }

    private static void offer(Publication publication, byte[] payload) {
        final UnsafeBuffer buffer = new UnsafeBuffer(payload);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            final long result = publication.offer(buffer, 0, payload.length);
            if (result > 0) {
                return;
            }
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                fail("Aeron publication unusable: " + result);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Timed out offering to the recorded publication");
    }

    private Map<String, Object> archiveConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("mode", "archive");
        config.put("channel", CHANNEL);
        config.put("streamId", STREAM_ID);
        config.put("useEmbeddedMediaDriver", false);
        config.put("aeronDirectoryName", archivingMediaDriver.mediaDriver().aeronDirectoryName());
        config.put("idleStrategy", "sleeping");
        config.put("archiveControlRequestChannel", controlRequestChannel);
        config.put("archiveControlResponseChannel",
                "aeron:udp?endpoint=localhost:0");
        config.put("replayChannel", REPLAY_CHANNEL);
        config.put("replayStreamId", REPLAY_STREAM_ID);
        return config;
    }

    private void startSource(Map<String, Object> config) throws Exception {
        source = new AeronSource();
        source.open(config, sourceContext);
        readerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Record<byte[]> record = source.read();
                    collected.add(record);
                    // The framework acknowledges after a successful publish, and only an
                    // acknowledgement advances the commit watermark. A reader that collected
                    // without acking modelled a world where publishing always succeeds
                    // instantly — which is precisely why the earlier revision's checkpointing
                    // bug was invisible to these tests.
                    if (ackRecords) {
                        record.ack();
                    }
                }
            } catch (Exception e) {
                // Interrupted at teardown.
            }
        }, "aeron-archive-it-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void awaitRecords(int count) {
        Awaitility.await("source emitted " + count + " records")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> collected.size() >= count);
    }

    private static List<String> valuesOf(List<Record<byte[]>> records) {
        return records.stream()
                .map(r -> new String(r.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    @Test
    public void testReplaysMessagesPublishedBeforeTheSourceExisted() throws Exception {
        // The whole point of archive mode: these are written and recorded while no source is
        // running at all. Transport mode would deliver none of them.
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sent.add("{\"seq\":" + i + ",\"symbol\":\"DEMO\"}");
        }
        recordMessages(sent);

        startSource(archiveConfig());
        awaitRecords(sent.size());

        assertThat(valuesOf(collected)).containsExactlyElementsOf(sent);
    }

    @Test
    public void testNoRecordSequenceIsExposed() throws Exception {
        recordMessages(List.of("alpha", "beta", "gamma"));

        startSource(archiveConfig());
        awaitRecords(3);

        // Deliberately absent. Archive positions restart with each recording, so after a rotation
        // the same producer would emit decreasing sequence ids and Pulsar's deduplication would
        // discard the new recording as already-seen — turning an optional optimisation into active
        // message loss. A sequence monotonic across rotation is follow-up work.
        assertThat(collected).allSatisfy(r ->
                assertThat(r.getRecordSequence()).isEmpty());

        // The position is still available as metadata; only the dedup affordance is withheld.
        List<Long> positions = collected.stream()
                .map(r -> Long.parseLong(r.getProperties().get(AeronRecord.PROP_POSITION)))
                .collect(Collectors.toList());
        assertThat(positions).isSorted();
        assertThat(positions.get(positions.size() - 1)).isGreaterThan(positions.get(0));
    }

    @Test
    public void testRecordsCarryTheRecordingId() throws Exception {
        recordMessages(List.of("one"));

        startSource(archiveConfig());
        awaitRecords(1);

        assertThat(collected.get(0).getProperties())
                .containsKey(AeronRecord.PROP_RECORDING_ID)
                .containsEntry(AeronRecord.PROP_CHANNEL, CHANNEL)
                .containsEntry(AeronRecord.PROP_STREAM_ID, Integer.toString(STREAM_ID));
        assertThat(Long.parseLong(
                collected.get(0).getProperties().get(AeronRecord.PROP_RECORDING_ID)))
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    public void testLargerThanMtuMessagesAreReassembledOnReplay() throws Exception {
        String large = RandomStringUtils.insecure().nextAlphanumeric(64 * 1024);
        recordMessages(List.of("small", large, "also-small"));

        startSource(archiveConfig());
        awaitRecords(3);

        assertThat(valuesOf(collected)).containsExactly("small", large, "also-small");
    }

    @Test
    public void testStartPositionAppliesOnlyWhenThereIsNoCheckpoint() throws Exception {
        recordMessages(List.of("first", "second", "third"));

        // Replay everything once to learn where the first message ended.
        startSource(archiveConfig());
        awaitRecords(3);
        final long afterFirst =
                Long.parseLong(collected.get(0).getProperties().get(AeronRecord.PROP_POSITION));

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        collected.clear();

        // A stored checkpoint outranks a configured startPosition, so clear it to model a genuinely
        // fresh deployment. Without this the source would resume from the checkpoint and deliver
        // nothing, which is the correct behaviour and the reason the precedence is that way round:
        // honouring startPosition over a checkpoint would re-ingest on every restart.
        state.clear();

        // Positions on a record are end-of-message, so starting from the first record's position
        // begins with the message after it.
        Map<String, Object> config = archiveConfig();
        config.put("startPosition", afterFirst);
        startSource(config);
        awaitRecords(2);

        assertThat(valuesOf(collected)).containsExactly("second", "third");
    }

    @Test
    public void testCheckpointOutranksConfiguredStartPosition() throws Exception {
        // The precedence, asserted directly rather than left implicit: an operator who leaves a
        // startPosition in the config must not cause a re-ingest on every restart.
        recordMessages(List.of("alpha", "beta"));

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(2);

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        collected.clear();

        // Restart with startPosition pointing back at the beginning; the checkpoint should win.
        config.put("startPosition", 0L);
        startSource(config);

        // Nothing to replay, so nothing should arrive.
        Thread.sleep(3000);
        assertThat(collected).isEmpty();
    }

    @Test
    public void testExplicitRecordingIdIsUsed() throws Exception {
        recordMessages(List.of("x", "y"));
        final long recordingId = findRecordingId();

        Map<String, Object> config = archiveConfig();
        config.put("recordingId", recordingId);
        startSource(config);
        awaitRecords(2);

        assertThat(valuesOf(collected)).containsExactly("x", "y");
    }

    @Test
    public void testResumesFromCheckpointAfterRestart() throws Exception {
        // The property this whole mode exists for: a restart continues rather than re-ingesting.
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            first.add("first-" + i);
        }
        recordMessages(first);

        Map<String, Object> config = archiveConfig();
        // Checkpoint aggressively so the restart has something recent to resume from.
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(first.size());

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        assertThat(state).as("a checkpoint should have been written").isNotEmpty();
        collected.clear();

        // More data arrives while the source is down — exactly the window transport mode loses.
        List<String> second = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            second.add("second-" + i);
        }
        recordMessages(second);

        startSource(config);
        awaitRecords(second.size());

        // Only the new messages: the first batch was checkpointed past.
        assertThat(valuesOf(collected)).containsExactlyElementsOf(second);
    }

    @Test
    public void testStartsFreshWhenNoCheckpointExists() throws Exception {
        recordMessages(List.of("a", "b", "c"));

        startSource(archiveConfig());
        awaitRecords(3);

        assertThat(valuesOf(collected)).containsExactly("a", "b", "c");
        assertThat(state).as("running should have produced a checkpoint").isNotEmpty();
    }

    @Test
    public void testCheckpointSurvivesAsRecordingIdAndPosition() throws Exception {
        recordMessages(List.of("one", "two"));

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(2);

        // recordingId + position, not a bare position: positions restart per recording, so a
        // position alone would be meaningless after a publisher restart.
        ByteBuffer stored = state.values().iterator().next().duplicate();
        assertThat(stored.remaining()).isEqualTo(Long.BYTES * 2);
        long recordingId = stored.getLong();
        long position = stored.getLong();
        assertThat(recordingId).isGreaterThanOrEqualTo(0L);
        assertThat(position).isPositive();
    }

    @Test
    public void testResetCheckpointReprocessesFromTheBeginning() throws Exception {
        List<String> sent = List.of("alpha", "beta", "gamma");
        recordMessages(sent);

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(sent.size());

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        assertThat(state).as("first run should have checkpointed").isNotEmpty();
        collected.clear();

        // The escape hatch: reprocess history despite a checkpoint being present.
        config.put("resetCheckpoint", true);
        startSource(config);
        awaitRecords(sent.size());

        assertThat(valuesOf(collected)).containsExactlyElementsOf(sent);
    }

    @Test
    public void testResetCheckpointDiscardsRatherThanIgnoresTheStoredPosition() throws Exception {
        recordMessages(List.of("one", "two"));

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        config.put("resetCheckpoint", true);
        startSource(config);
        awaitRecords(2);

        // Deleted, then rewritten as the run progresses — so removing the flag leaves a usable
        // checkpoint behind rather than a stale one from before the reset.
        assertThat(state).isNotEmpty();
        ByteBuffer stored = state.values().iterator().next().duplicate();
        stored.getLong();
        assertThat(stored.getLong()).as("checkpoint rewritten by the reset run").isPositive();
    }

    @Test
    public void testUnacknowledgedRecordsAreNotCheckpointed() throws Exception {
        // The regression test for the defect this design exists to prevent. consume() only queues
        // a record; the framework publishes and acks later. If the checkpoint tracked the replay
        // cursor instead of the acknowledged watermark, a crash here would resume PAST records
        // that never reached Pulsar.
        ackRecords = false;
        recordMessages(List.of("un-acked-1", "un-acked-2", "un-acked-3"));

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(3);

        // Read and delivered, but never acknowledged — so nothing may be committed.
        Thread.sleep(2000);
        assertThat(state)
                .as("no checkpoint may exist while every record is still unacknowledged")
                .isEmpty();
    }

    @Test
    public void testCheckpointOnlyAdvancesOverTheAcknowledgedPrefix() throws Exception {
        recordMessages(List.of("a", "b", "c"));

        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(3);

        // With every record acked, the watermark reaches the last position, so a restart has
        // nothing left to replay.
        Awaitility.await("checkpoint written")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !state.isEmpty());

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        collected.clear();

        startSource(config);
        Thread.sleep(3000);
        assertThat(collected).as("everything was acknowledged, so nothing should replay").isEmpty();
    }

    @Test
    public void testResetFailsLoudlyWhenTheCheckpointCannotBeDeleted() throws Exception {
        recordMessages(List.of("x"));
        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(1);
        Awaitility.await("checkpoint written")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !state.isEmpty());

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;

        // A state store that accepts the delete but does not actually remove anything: silently
        // continuing would resume from the old position while reporting a reset.
        doAnswer(inv -> null).when(sourceContext).deleteState(anyString());

        config.put("resetCheckpoint", true);
        AeronSource bad = new AeronSource();
        try {
            bad.open(config, sourceContext);
            fail("Expected open() to fail when the checkpoint could not be deleted");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalStateException.class);
            assertThat(e).hasMessageContaining("could not be deleted");
        } finally {
            closeQuietly(bad);
        }
    }

    @Test
    public void testResetActuallyDeletesTheStoredCheckpoint() throws Exception {
        // Guards the stub itself: with deleteState left as a Mockito no-op this passed vacuously.
        recordMessages(List.of("p", "q"));
        Map<String, Object> config = archiveConfig();
        config.put("checkpointEveryRecords", 1);
        startSource(config);
        awaitRecords(2);
        Awaitility.await("checkpoint written")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !state.isEmpty());

        readerThread.interrupt();
        readerThread = null;
        source.close();
        source = null;
        collected.clear();

        // Withhold acks so the reset run cannot immediately write a replacement checkpoint,
        // leaving the deletion itself observable.
        ackRecords = false;
        config.put("resetCheckpoint", true);
        startSource(config);
        awaitRecords(2);

        assertThat(state).as("the stored checkpoint must actually be gone").isEmpty();
    }

    @Test
    public void testMissingRecordingFailsFast() {
        // Nothing has been recorded for this stream, so discovery must fail loudly rather than
        // sit silently delivering nothing.
        Map<String, Object> config = archiveConfig();
        config.put("streamId", 999);

        AeronSource bad = new AeronSource();
        try {
            bad.open(config, sourceContext);
            fail("Expected open() to fail when no recording exists");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalStateException.class);
            assertThat(e).hasMessageContaining("No Aeron Archive recording found");
        }
    }
}
