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
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.Header;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.IdleStrategy;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays an Aeron Archive recording instead of reading the live transport, checkpointing progress
 * so a restart resumes where it left off.
 *
 * <p>This is the recoverable counterpart to {@link AeronPollingRunner}. Where that reads a live
 * stream and can never replay what it missed, this reads durable storage and remembers its place.
 *
 * <h2>Continuous replay rather than ReplayMerge</h2>
 *
 * <p>The loop repeatedly replays {@code [checkpoint, current recorded position)} and advances the
 * checkpoint, rather than using {@link io.aeron.archive.client.ReplayMerge} to catch up and then
 * hand over to the live stream. Three reasons:
 *
 * <ul>
 *   <li><b>It cannot outrun durability.</b> Only what the archive has already recorded is consumed,
 *       so the connector never publishes to Pulsar something that is not yet durable in the
 *       archive. For a bridge whose purpose is durable capture, that is the safer behaviour.
 *   <li><b>It works over IPC.</b> {@code ReplayMerge} needs a multi-destination subscription and a
 *       UDP live destination, so it cannot serve an {@code aeron:ipc} channel at all.
 *   <li><b>The latency it costs does not matter here.</b> {@code ReplayMerge} wins on steady-state
 *       latency, but the Pulsar write path already spends hundreds of microseconds on fsync.
 * </ul>
 *
 * <h2>Where replay starts</h2>
 *
 * <p>In precedence order:
 *
 * <ol>
 *   <li>{@code resetCheckpoint: true} — discard the stored checkpoint and use {@code recordingId}
 *       and {@code startPosition}. A <b>one-shot operational flag</b> for deliberately
 *       reprocessing history: set it, start once, then remove it. Left in place it fires on every
 *       restart and re-ingests the whole recording each time.
 *   <li>A stored checkpoint, if one exists.
 *   <li>{@code recordingId} and {@code startPosition}, or discovery of the newest matching
 *       recording.
 * </ol>
 *
 * <p>The checkpoint deliberately outranks a configured {@code startPosition}. The other order
 * would silently re-ingest on every restart for anyone who left a {@code startPosition} in their
 * config, which is the failure this mode exists to prevent — hence a separate, explicit flag for
 * the rare case where starting over is actually what is wanted.
 *
 * <h2>Delivery semantics: at-least-once</h2>
 *
 * <p>Two cursors are tracked, and the distinction is the whole guarantee. The <b>replay cursor</b>
 * is how far the loop has read. The <b>commit watermark</b>, maintained by {@link CommitTracker},
 * is the highest position whose record — and every record before it — the framework has
 * acknowledged, meaning it actually reached Pulsar. <b>Only the watermark is checkpointed.</b>
 *
 * <p>This matters because {@code consume()} merely queues a record; the framework publishes and
 * acknowledges later. Checkpointing the replay cursor would commit positions for records still
 * sitting in memory, and a crash in that window would resume past records that were never
 * published — silent loss in the mode meant to prevent exactly that.
 *
 * <p>A crash between publish and checkpoint replays the window since the last checkpoint, so
 * duplicates are possible; {@code checkpointEveryRecords} bounds it.
 *
 * <p>A record the framework <em>fails</em> stalls the watermark <em>and</em> fails the source.
 * Stalling alone would not be enough: the replay cursor has already moved past the record, so
 * nothing would re-emit it and the message would stay absent from Pulsar while the connector
 * looked healthy. Failing the instance lets the runtime restart it and replay from the
 * checkpoint.
 *
 * <h2>One active recording at a time</h2>
 *
 * <p>Transport mode receives from every publication on a channel and stream. <b>Archive mode does
 * not support concurrent publishers</b>: the archive records each session separately, so they
 * produce concurrent recordings and this replays one at a time. Rather than silently dropping the
 * others, it fails at {@code open()} when more than one is active.
 *
 * <p>The remedy is a distinct channel or stream id per publisher, or {@code mode: transport} where
 * multi-publisher delivery works. Pinning {@code recordingId} and running a source per recording is
 * <em>not</em> a workaround: the guard runs before the start position is resolved so it rejects
 * pinned configurations too, and pinned runners auto-advance on rotation and would walk into each
 * other's recordings.
 *
 * <p><b>No record sequence is set.</b> An earlier revision exposed the archive position as
 * {@link Record#getRecordSequence()} so broker deduplication could give effectively-once, but
 * archive positions restart with each recording. After a rotation the same producer would emit
 * <em>decreasing</em> sequence ids, and Pulsar's deduplication would then discard the new
 * recording as already-seen — turning an optional optimisation into active message loss. Doing
 * this safely needs a sequence that is monotonic across rotation, which is follow-up work.
 */
public class ArchivePollingRunner implements AeronPoller {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivePollingRunner.class);

    static final String METRIC_FRAGMENTS_RECEIVED = "aeron-archive-fragments-received";
    static final String METRIC_RECORDS_CONSUMED = "aeron-archive-records-consumed";
    static final String METRIC_CHECKPOINTS_WRITTEN = "aeron-archive-checkpoints-written";
    static final String METRIC_RECORDINGS_ADVANCED = "aeron-archive-recordings-advanced";

    /** Page size for walking the archive catalog. */
    private static final int LIST_PAGE_SIZE = 100;

    /** Bound on waiting for a replay image, so a stuck replay retries instead of hanging. */
    private static final long REPLAY_CONNECT_TIMEOUT_SECONDS = 30L;

    /** Throttle for the rotation-blocked warning, which would otherwise spin. */
    private static final long ROTATION_STALL_LOG_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private final AeronArchive archive;
    private final AeronSourceConfig config;
    private final Consumer<Record<byte[]>> consumer;
    private final SourceContext sourceContext;
    private final ArchiveCheckpoint checkpoint;
    private final IdleStrategy idleStrategy;
    private CommitTracker commitTracker;
    /**
     * Reassembly state, deliberately held across chunks rather than created per chunk.
     *
     * <p>On a live recording the chunk bound comes from {@code getRecordingPosition()}, which can
     * fall <em>between</em> fragments of a larger-than-MTU message. A per-chunk assembler would
     * hold the BEGIN fragment, be discarded at the end of the chunk, and the next chunk would
     * start on a continuation fragment with a fresh assembler that drops it — losing the message
     * silently. Reset only when the cursor jumps to a different recording.
     */
    private FragmentAssembler assembler;

    /** Mutable replay cursor, advanced by the fragment handler. */
    private long currentRecordingId;
    private long currentPosition;
    private long recordsSinceCheckpoint;
    private long lastCheckpointedPosition = Long.MIN_VALUE;
    private long lastCheckpointedRecording = Aeron.NULL_VALUE;
    private long lastRotationStallLogNanos;

    private volatile boolean running = true;

    public ArchivePollingRunner(AeronArchive archive,
                                AeronSourceConfig config,
                                Consumer<Record<byte[]>> consumer,
                                SourceContext sourceContext) {
        this(archive, config, consumer, sourceContext,
                new ArchiveCheckpoint(sourceContext, config.getChannel(), config.getStreamId()));
    }

    ArchivePollingRunner(AeronArchive archive,
                         AeronSourceConfig config,
                         Consumer<Record<byte[]>> consumer,
                         SourceContext sourceContext,
                         ArchiveCheckpoint checkpoint) {
        this.archive = archive;
        this.config = config;
        this.consumer = consumer;
        this.sourceContext = sourceContext;
        this.checkpoint = checkpoint;
        this.idleStrategy = IdleStrategies.create(config.getIdleStrategy());

        checkpoint.requireAvailable();
        rejectConcurrentRecordings();
        resumeOrStart();
        this.commitTracker = new CommitTracker(currentPosition);
        this.assembler = newAssembler();
    }

    /**
     * Fails when more than one recording for this channel and stream is currently active.
     *
     * <p>Transport mode subscribes to a channel and stream and receives from <em>every</em>
     * publication on it. Archive mode cannot match that: the archive records each session
     * separately, so concurrent publishers produce concurrent recordings, and this replays one at
     * a time. The others would never be replayed — and because discovery only ever moves to
     * higher recording ids, lower ones could never be picked up later either.
     *
     * <p>Rather than silently dropping those publishers' data, archive mode requires a single
     * active recording and says so. Historical recordings are fine: rotation walks them in id
     * order, though it replays each in full rather than interleaving them as the live transport
     * would.
     */
    private void rejectConcurrentRecordings() {
        final MutableLong active = new MutableLong(0);
        final StringBuilder ids = new StringBuilder();
        long from = 0;
        int matched;
        final MutableLong highestSeen = new MutableLong(Aeron.NULL_VALUE);
        do {
            highestSeen.set(Aeron.NULL_VALUE);
            matched = archive.listRecordingsForUri(from, LIST_PAGE_SIZE,
                    config.getChannel(), config.getStreamId(),
                    (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                     startPosition, stopPosition, initialTermId, segmentFileLength,
                     termBufferLength, mtuLength, sessionId, streamId, strippedChannel,
                     originalChannel, sourceIdentity) -> {
                        highestSeen.set(Math.max(highestSeen.get(), recordingId));
                        if (stopPosition == AeronArchive.NULL_POSITION) {
                            active.set(active.get() + 1);
                            ids.append(ids.length() == 0 ? "" : ", ").append(recordingId);
                        }
                    });
            if (matched > 0 && highestSeen.get() != Aeron.NULL_VALUE) {
                from = highestSeen.get() + 1;
            }
        } while (matched == LIST_PAGE_SIZE);

        if (active.get() > 1) {
            // Deliberately does NOT suggest pinning 'recordingId' and running a source per
            // recording. This guard runs before the start position is resolved, so it rejects a
            // pinned configuration too — and even if it did not, pinned runners auto-advance on
            // rotation and would walk into each other's recordings. Suggesting a workaround that
            // cannot work is worse than stating the limitation.
            throw new IllegalStateException(
                    "Archive mode found " + active.get() + " concurrently active recordings for "
                            + "channel '" + config.getChannel() + "' streamId "
                            + config.getStreamId() + " (recordingIds: " + ids + "). Concurrent "
                            + "publishers on one channel and stream are not supported in archive "
                            + "mode: the archive records each session separately and this replays "
                            + "one recording at a time, so the others would never be replayed. "
                            + "Give each publisher a distinct channel or streamId, or use "
                            + "'mode: transport', which does receive from every publication.");
        }
    }

    /**
     * Picks the starting point: a stored checkpoint wins, then explicit config, then discovery.
     *
     * <p>The checkpoint takes precedence deliberately. Honouring a configured {@code startPosition}
     * over a stored one would silently re-ingest on every restart, which is the failure this whole
     * mode exists to prevent.
     */
    private void resumeOrStart() {
        if (config.isResetCheckpoint()) {
            // Loud on purpose. Left in the config this fires on every restart, and the connector
            // re-ingests the whole recording each time it comes back — which looks like a
            // duplicate-message problem long before anyone suspects the flag.
            LOG.warn("resetCheckpoint is set: discarding any stored checkpoint and restarting from "
                            + "recordingId={} startPosition={}. This is a one-shot operational "
                            + "flag — REMOVE it from the config after this run, or every restart "
                            + "will re-ingest the recording from the beginning.",
                    config.getRecordingId(), config.getStartPosition());
            checkpoint.clear();
        }

        final Optional<ArchiveCheckpoint.Position> stored =
                config.isResetCheckpoint() ? Optional.empty() : checkpoint.read();
        if (stored.isPresent()) {
            currentRecordingId = stored.get().recordingId();
            currentPosition = stored.get().position();
            LOG.info("Resuming Aeron archive replay from checkpoint: recordingId={} position={}",
                    currentRecordingId, currentPosition);
            return;
        }

        currentRecordingId = config.getRecordingId() >= 0
                ? config.getRecordingId()
                : discoverRecording(Aeron.NULL_VALUE);
        // Default to where the recording actually begins rather than assuming zero.
        currentPosition = config.getStartPosition() >= 0
                ? config.getStartPosition()
                : recordingStartPosition(currentRecordingId);
        LOG.info("No checkpoint found; starting Aeron archive replay at recordingId={} position={}",
                currentRecordingId, currentPosition);
    }

    /**
     * Finds a recording for the configured channel and stream.
     *
     * <p>Deliberately <em>not</em> {@code findLastMatchingRecording}: it matches its
     * {@code sessionId} argument literally rather than treating {@link Aeron#NULL_VALUE} as a
     * wildcard, so it only works when the session id is already known — which a connector
     * configured with a channel and a stream id never is. {@code listRecordingsForUri} takes no
     * session id and filters server-side.
     *
     * @param after return the lowest recording id strictly greater than this, or the highest
     *              overall when {@link Aeron#NULL_VALUE}
     */
    private long discoverRecording(long after) {
        final MutableLong best = new MutableLong(Aeron.NULL_VALUE);
        final MutableLong highestSeen = new MutableLong(Aeron.NULL_VALUE);
        long from = 0;
        int matched;
        do {
            highestSeen.set(Aeron.NULL_VALUE);
            matched = archive.listRecordingsForUri(from, LIST_PAGE_SIZE,
                    config.getChannel(), config.getStreamId(),
                    (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                     startPosition, stopPosition, initialTermId, segmentFileLength,
                     termBufferLength, mtuLength, sessionId, streamId, strippedChannel,
                     originalChannel, sourceIdentity) -> {
                        highestSeen.set(Math.max(highestSeen.get(), recordingId));
                        if (after == Aeron.NULL_VALUE) {
                            if (recordingId > best.get()) {
                                best.set(recordingId);
                            }
                        } else if (recordingId > after
                                && (best.get() == Aeron.NULL_VALUE || recordingId < best.get())) {
                            best.set(recordingId);
                        }
                    });
            // The first argument is an inclusive recording ID, not an offset. Advancing it by the
            // match count re-reads the same page whenever matching IDs have gaps, which loops
            // forever; step past the highest ID this page actually returned instead.
            if (matched > 0 && highestSeen.get() != Aeron.NULL_VALUE) {
                from = highestSeen.get() + 1;
            }
        } while (matched == LIST_PAGE_SIZE);

        if (best.get() == Aeron.NULL_VALUE && after == Aeron.NULL_VALUE) {
            throw new IllegalStateException(
                    "No Aeron Archive recording found for channel '" + config.getChannel()
                            + "' streamId " + config.getStreamId()
                            + ". Set recordingId explicitly, or check the archive is recording it");
        }
        return best.get();
    }

    /**
     * Where the given recording actually begins.
     *
     * <p>Not necessarily zero: Aeron supports recordings with a non-zero start position, and
     * asking {@code replay()} to start before one fails asynchronously rather than at the call.
     */
    private long recordingStartPosition(long recordingId) {
        final MutableLong start = new MutableLong(0L);
        final int found = archive.listRecording(recordingId,
                (controlSessionId, correlationId, id, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel,
                 sourceIdentity) -> start.set(startPosition));
        if (found == 0) {
            // Returning a synthetic 0 here would be worse than failing: every subsequent position
            // query answers NULL_POSITION, the loop treats the recording as merely still being
            // written, and the source idles forever while reporting itself healthy.
            throw new IllegalStateException(
                    "Aeron Archive has no recording with id " + recordingId
                            + ". Check 'recordingId', or leave it unset to discover the newest "
                            + "recording for the configured channel and stream");
        }
        return start.get();
    }

    /**
     * How far the given recording can currently be replayed.
     *
     * <p>A recording still being written has no stop position — the archive reports
     * {@link AeronArchive#NULL_POSITION} — so fall back to how far it has been recorded. Without
     * this, a live recording would replay nothing at all, which is the common case rather than an
     * edge one.
     */
    private long replayableBound(long recordingId) {
        final long stop = archive.getStopPosition(recordingId);
        return stop != AeronArchive.NULL_POSITION ? stop : archive.getRecordingPosition(recordingId);
    }

    @Override
    public void run() {
        LOG.info("Aeron archive replay loop started for channel {} stream {}",
                config.getChannel(), config.getStreamId());
        try {
            while (running) {
                final long bound = replayableBound(currentRecordingId);

                if (bound > currentPosition) {
                    replayChunk(bound);
                } else if (!advanceRecordingIfFinished()) {
                    // Caught up on a recording that is still being written: wait for more.
                    idleStrategy.idle(0);
                }

                // Attempted on every pass, including idle ones. Acknowledgements arrive on
                // framework threads, so the watermark can advance long after the last record was
                // read — and once caught up there is no further chunk to trigger a write. Only
                // checkpointing after a chunk left the committed position unpersisted until
                // shutdown whenever the acks landed a moment too late.
                writeCheckpointIfAdvanced();
            }
        } catch (Throwable t) {
            if (running) {
                LOG.error("Aeron archive replay terminated unexpectedly at recordingId={} "
                        + "position={}", currentRecordingId, currentPosition, t);
                // Without this the thread dies while AeronSource stays open and read() blocks
                // forever, so a dead connector reports itself healthy. Telling the runtime lets it
                // restart the instance, which resumes from the checkpoint.
                if (sourceContext != null) {
                    sourceContext.fatal(t);
                }
            }
        } finally {
            // Best effort: a checkpoint here shrinks the replay window on a clean restart.
            try {
                writeCheckpointIfAdvanced();
            } catch (Exception e) {
                LOG.warn("Could not write a final archive checkpoint", e);
            }
            LOG.info("Aeron archive replay loop stopped at recordingId={} position={}",
                    currentRecordingId, currentPosition);
        }
    }

    /**
     * Builds the reassembly buffer. Held across chunks rather than per chunk — see the field.
     */
    private FragmentAssembler newAssembler() {
        return config.getFragmentAssemblyBufferLength() > 0
                ? new FragmentAssembler(this::onFragment, config.getFragmentAssemblyBufferLength())
                : new FragmentAssembler(this::onFragment);
    }

    /** Replays from the current position up to {@code bound}, advancing the cursor as it goes. */
    private void replayChunk(long bound) {
        final long length = bound - currentPosition;
        try (Subscription replay = archive.replay(
                currentRecordingId, currentPosition, length,
                config.getReplayChannel(), config.getReplayStreamId())) {

            // "No image" means two opposite things — not connected yet, and finished then gone
            // away — and they are only distinguishable by whether an image was ever seen. Getting
            // this wrong ends the chunk before it starts.
            boolean sawImage = false;
            // Tracks what the replay image actually consumed, which is not the same as what was
            // delivered: poll() advances over padding frames without calling the handler.
            long imagePosition = currentPosition;
            final long connectDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(REPLAY_CONNECT_TIMEOUT_SECONDS);

            while (running) {
                if (replay.imageCount() > 0) {
                    sawImage = true;
                    final Image image = replay.imageAtIndex(0);
                    imagePosition = Math.max(imagePosition, image.position());
                    if (image.isEndOfStream()) {
                        break;
                    }
                } else if (sawImage) {
                    break;  // the replay image finished and was removed
                } else if (System.nanoTime() > connectDeadline) {
                    LOG.warn("Replay of recording {} from position {} did not connect within {}s; "
                                    + "retrying", currentRecordingId, currentPosition,
                            REPLAY_CONNECT_TIMEOUT_SECONDS);
                    break;
                }

                final int fragments = replay.poll(assembler, config.getFragmentLimit());
                if (fragments > 0) {
                    recordMetric(METRIC_FRAGMENTS_RECEIVED, fragments);
                }
                idleStrategy.idle(fragments);
            }

            // Advance the replay cursor over anything the image consumed but did not deliver.
            // A range ending in padding delivers no fragment, so a cursor driven only by fragment
            // headers would stay short of the bound and the outer loop would replay the same
            // padding-only range forever, never rotating. Safe precisely because the cursor and
            // the commit watermark are separate: this moves only what has been *read*, while the
            // checkpoint still tracks what has been acknowledged.
            if (imagePosition > currentPosition) {
                currentPosition = imagePosition;
            }
        }
    }

    /**
     * Moves to the next recording once the current one is finished and fully consumed.
     *
     * <p>A publisher restart opens a new recording numbered from zero, which is routine rather than
     * exceptional, so the source follows rather than stopping.
     *
     * @return true if it moved on, false if there is nothing further yet
     */
    private boolean advanceRecordingIfFinished() {
        final long stop = archive.getStopPosition(currentRecordingId);
        if (stop == AeronArchive.NULL_POSITION || currentPosition < stop) {
            return false;  // still being written, or not caught up
        }

        // Rotating with records still awaiting acknowledgement would be unsafe twice over.
        // Resetting the tracker discards those pending entries, so their positions could never be
        // committed; and because positions restart in the successor, a late acknowledgement for an
        // old position can collide with a successor position and advance ITS watermark, skipping
        // unpublished successor records after a crash. So drain first.
        final int inFlight = commitTracker.pending();
        if (inFlight > 0) {
            final long now = System.nanoTime();
            if (now - lastRotationStallLogNanos > ROTATION_STALL_LOG_INTERVAL_NANOS) {
                lastRotationStallLogNanos = now;
                LOG.warn("Recording {} is complete but {} record(s) are still awaiting "
                                + "acknowledgement, so rotation is waiting. A record the framework "
                                + "never acknowledges will hold this indefinitely, which is "
                                + "deliberate: advancing would skip unpublished data.",
                        currentRecordingId, inFlight);
            }
            return false;
        }

        // Persist the old recording's final watermark before the cursor moves, so a crash during
        // the switch resumes at its end rather than somewhere earlier.
        writeCheckpointIfAdvanced();

        final long next = discoverRecording(currentRecordingId);
        if (next == Aeron.NULL_VALUE) {
            return false;  // this recording is done and no successor exists yet
        }

        LOG.info("Recording {} complete at {}; advancing to recording {}",
                currentRecordingId, stop, next);
        currentRecordingId = next;
        // Positions restart per recording — but not necessarily at zero, so ask the successor
        // where it begins rather than assuming.
        currentPosition = recordingStartPosition(next);
        commitTracker.reset(currentPosition);
        // A partially assembled message cannot continue into a different recording.
        assembler = newAssembler();
        recordsSinceCheckpoint = 0;
        writeCheckpointIfAdvanced();
        recordMetric(METRIC_RECORDINGS_ADVANCED, 1);
        return true;
    }

    /**
     * Handles one reassembled message. As in the transport runner, the payload must be copied out
     * of the buffer before it leaves this callback — Aeron reuses the storage once this returns.
     */
    void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        final byte[] payload = new byte[length];
        buffer.getBytes(offset, payload);

        final Map<String, String> properties = new HashMap<>();
        properties.put(AeronRecord.PROP_SESSION_ID, Integer.toString(header.sessionId()));
        properties.put(AeronRecord.PROP_STREAM_ID, Integer.toString(config.getStreamId()));
        properties.put(AeronRecord.PROP_CHANNEL, config.getChannel());
        properties.put(AeronRecord.PROP_POSITION, Long.toString(header.position()));
        properties.put(AeronRecord.PROP_INGEST_TS, Long.toString(System.currentTimeMillis()));
        properties.put(AeronRecord.PROP_RECORDING_ID, Long.toString(currentRecordingId));

        final String key = config.isKeyBySessionId() ? Integer.toString(header.sessionId()) : null;
        final long position = header.position();

        // Registered before handing the record over: an acknowledgement can land on a framework
        // thread the instant consume() returns, and the tracker must already know about it.
        commitTracker.emitted(position);
        consumer.accept(new AeronRecord(payload, key, properties, new AeronRecord.Outcome() {
            @Override
            public void acked() {
                commitTracker.acked(position);
            }

            @Override
            public void failed() {
                commitTracker.failed(position);
                // Stalling the watermark stops the failure being checkpointed past, but nothing
                // re-emits the record in this process: the replay cursor has moved on, so the
                // message would stay absent from Pulsar while the source looked healthy. That is
                // not at-least-once. Failing the instance lets the runtime restart it, which
                // resumes from the checkpoint and replays the record. Same approach as the
                // Kinesis source.
                LOG.error("Publishing a record at recordingId={} position={} failed; failing the "
                                + "source so it restarts and replays from the last checkpoint",
                        currentRecordingId, position);
                if (sourceContext != null) {
                    sourceContext.fatal(new IllegalStateException(
                            "Failed to publish Aeron archive record at recordingId="
                                    + currentRecordingId + " position=" + position));
                }
            }
        }));
        recordMetric(METRIC_RECORDS_CONSUMED, 1);

        // The replay cursor advances here so the loop knows how far it has read. What gets
        // *checkpointed* is the commit watermark, which only moves on acknowledgement.
        currentPosition = position;
        if (++recordsSinceCheckpoint >= config.getCheckpointEveryRecords()) {
            writeCheckpointIfAdvanced();
        }
    }

    /** Writes only when the watermark has actually moved, so idle passes stay cheap. */
    private void writeCheckpointIfAdvanced() {
        // The committed watermark, never the replay cursor: positions between the two belong to
        // records that are queued but not yet published, and committing those would resume past
        // data that never reached Pulsar.
        if (!commitTracker.hasCommitted()) {
            return;
        }
        final long committed = commitTracker.committedPosition();
        if (committed == lastCheckpointedPosition && currentRecordingId == lastCheckpointedRecording) {
            return;
        }
        try {
            checkpoint.write(currentRecordingId, committed);
            lastCheckpointedPosition = committed;
            lastCheckpointedRecording = currentRecordingId;
            recordsSinceCheckpoint = 0;
            recordMetric(METRIC_CHECKPOINTS_WRITTEN, 1);
        } catch (Exception e) {
            // Losing a checkpoint costs a replay, not data, so keep going rather than stopping the
            // source — but say so loudly, because a persistently failing store means every restart
            // re-ingests from the last good position.
            LOG.warn("Failed to write Aeron archive checkpoint at recordingId={} position={}",
                    currentRecordingId, committed, e);
        }
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
