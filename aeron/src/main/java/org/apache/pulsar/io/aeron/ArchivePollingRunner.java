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
 * <p>The checkpoint is written after records are handed downstream, so a crash in between replays
 * that window on restart. Duplicates are therefore possible and are not deduplicated here.
 * Effectively-once is available on top by enabling broker deduplication on the destination topic —
 * every record carries its archive position as {@link Record#getRecordSequence()} — but that
 * depends on user-side configuration, so it is not promised.
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

    private final AeronArchive archive;
    private final AeronSourceConfig config;
    private final Consumer<Record<byte[]>> consumer;
    private final SourceContext sourceContext;
    private final ArchiveCheckpoint checkpoint;
    private final IdleStrategy idleStrategy;

    /** Mutable replay cursor, advanced by the fragment handler. */
    private long currentRecordingId;
    private long currentPosition;
    private long recordsSinceCheckpoint;

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
        resumeOrStart();
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
        currentPosition = config.getStartPosition() >= 0 ? config.getStartPosition() : 0L;
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
        long from = 0;
        int matched;
        do {
            matched = archive.listRecordingsForUri(from, LIST_PAGE_SIZE,
                    config.getChannel(), config.getStreamId(),
                    (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                     startPosition, stopPosition, initialTermId, segmentFileLength,
                     termBufferLength, mtuLength, sessionId, streamId, strippedChannel,
                     originalChannel, sourceIdentity) -> {
                        if (after == Aeron.NULL_VALUE) {
                            if (recordingId > best.get()) {
                                best.set(recordingId);
                            }
                        } else if (recordingId > after
                                && (best.get() == Aeron.NULL_VALUE || recordingId < best.get())) {
                            best.set(recordingId);
                        }
                    });
            from += matched;
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
                    writeCheckpoint();
                } else if (!advanceRecordingIfFinished()) {
                    // Caught up on a recording that is still being written: wait for more.
                    idleStrategy.idle(0);
                }
            }
        } catch (Throwable t) {
            if (running) {
                LOG.error("Aeron archive replay terminated unexpectedly at recordingId={} "
                        + "position={}", currentRecordingId, currentPosition, t);
            }
        } finally {
            // Best effort: a checkpoint here shrinks the replay window on a clean restart.
            try {
                writeCheckpoint();
            } catch (Exception e) {
                LOG.warn("Could not write a final archive checkpoint", e);
            }
            LOG.info("Aeron archive replay loop stopped at recordingId={} position={}",
                    currentRecordingId, currentPosition);
        }
    }

    /** Replays from the current position up to {@code bound}, advancing the cursor as it goes. */
    private void replayChunk(long bound) {
        final long length = bound - currentPosition;
        try (Subscription replay = archive.replay(
                currentRecordingId, currentPosition, length,
                config.getReplayChannel(), config.getReplayStreamId())) {

            final FragmentAssembler assembler = config.getFragmentAssemblyBufferLength() > 0
                    ? new FragmentAssembler(this::onFragment,
                            config.getFragmentAssemblyBufferLength())
                    : new FragmentAssembler(this::onFragment);

            // "No image" means two opposite things — not connected yet, and finished then gone
            // away — and they are only distinguishable by whether an image was ever seen. Getting
            // this wrong ends the chunk before it starts.
            boolean sawImage = false;
            final long connectDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(REPLAY_CONNECT_TIMEOUT_SECONDS);

            while (running) {
                if (replay.imageCount() > 0) {
                    sawImage = true;
                    if (replay.imageAtIndex(0).isEndOfStream()) {
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

        final long next = discoverRecording(currentRecordingId);
        if (next == Aeron.NULL_VALUE) {
            return false;  // this recording is done and no successor exists yet
        }

        LOG.info("Recording {} complete at {}; advancing to recording {}",
                currentRecordingId, stop, next);
        currentRecordingId = next;
        // Positions restart per recording, so the cursor must too.
        currentPosition = 0L;
        recordsSinceCheckpoint = 0;
        writeCheckpoint();
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

        // Positions are monotonic within a recording, which is what broker deduplication needs.
        consumer.accept(new AeronRecord(payload, key, properties, header.position()));
        recordMetric(METRIC_RECORDS_CONSUMED, 1);

        // The cursor advances only after the record is downstream, so a crash re-replays it rather
        // than skipping it. That is the at-least-once side of the trade.
        currentPosition = header.position();
        if (++recordsSinceCheckpoint >= config.getCheckpointEveryRecords()) {
            writeCheckpoint();
        }
    }

    private void writeCheckpoint() {
        if (recordsSinceCheckpoint == 0 && currentPosition == 0) {
            return;
        }
        try {
            checkpoint.write(currentRecordingId, currentPosition);
            recordsSinceCheckpoint = 0;
            recordMetric(METRIC_CHECKPOINTS_WRITTEN, 1);
        } catch (Exception e) {
            // Losing a checkpoint costs a replay, not data, so keep going rather than stopping the
            // source — but say so loudly, because a persistently failing store means every restart
            // re-ingests from the last good position.
            LOG.warn("Failed to write Aeron archive checkpoint at recordingId={} position={}",
                    currentRecordingId, currentPosition, e);
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
