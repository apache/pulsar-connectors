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

import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.pulsar.io.core.SourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores how far the archive replay has progressed, so a restart resumes instead of starting over.
 *
 * <p>Persisted through the source's state store, which requires {@code stateStorageServiceUrl} to
 * be configured on the cluster. Without it the guarantee cannot be met, so
 * {@link #requireAvailable()} fails at {@code open()} rather than letting the connector run and
 * silently re-ingest from the beginning on every restart.
 *
 * <p>The checkpoint is a <b>recording id and a position</b>, not a bare position. Archive positions
 * are only monotonic within one recording: when a publisher restarts, the archive opens a new
 * recording numbered from zero, and a position alone would then be meaningless.
 */
final class ArchiveCheckpoint {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveCheckpoint.class);

    /** recordingId (long) + position (long). */
    private static final int SERIALIZED_BYTES = Long.BYTES * 2;

    private final SourceContext sourceContext;
    private final String key;

    ArchiveCheckpoint(SourceContext sourceContext, String channel, int streamId) {
        this.sourceContext = sourceContext;
        // Keyed by channel and stream so two sources sharing a state namespace do not overwrite
        // each other's progress.
        this.key = "aeron-archive-position:" + channel + ":" + streamId;
    }

    /** A recorded position: which recording, and how far into it. */
    record Position(long recordingId, long position) { }

    /**
     * Fails unless the state store is actually usable.
     *
     * <p>Checked eagerly because the failure mode otherwise is silent and expensive: the connector
     * would appear healthy while replaying the whole recording after every restart.
     */
    void requireAvailable() {
        try {
            sourceContext.getState(key);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Archive mode needs the function state store, but it is unavailable. "
                            + "Configure 'stateStorageServiceUrl' on the cluster, or the connector "
                            + "cannot checkpoint and would re-ingest the whole recording on every "
                            + "restart", e);
        }
    }

    /**
     * Reads the stored checkpoint.
     *
     * <p>Returns empty <b>only</b> for a confirmed absent or unreadably short value. A store
     * failure propagates rather than being reported as "no checkpoint": treating a transient read
     * error as an absent checkpoint would silently restart from configured history and re-ingest
     * the recording, which is precisely the failure archive mode exists to prevent. Failing
     * startup is recoverable; silently re-ingesting looks like success.
     *
     * @throws IllegalStateException if the state store could not be read
     */
    Optional<Position> read() {
        final ByteBuffer buffer;
        try {
            buffer = sourceContext.getState(key);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the Aeron archive checkpoint under '" + key + "'. Refusing to "
                            + "continue, because treating this as an absent checkpoint would "
                            + "re-ingest the recording from the configured start position.", e);
        }
        if (buffer == null) {
            return Optional.empty();
        }
        if (buffer.remaining() < SERIALIZED_BYTES) {
            LOG.warn("Ignoring a truncated archive checkpoint under {} ({} bytes, expected {})",
                    key, buffer.remaining(), SERIALIZED_BYTES);
            return Optional.empty();
        }
        final ByteBuffer readable = buffer.duplicate();
        return Optional.of(new Position(readable.getLong(), readable.getLong()));
    }

    /**
     * Discards the stored checkpoint.
     *
     * <p>Deleted rather than merely ignored, so the state reflects what actually happened: if the
     * operator removes {@code resetCheckpoint} after one run, the next restart resumes normally
     * from wherever the reset run reached.
     */
    void clear() {
        Exception deleteFailure = null;
        try {
            sourceContext.deleteState(key);
        } catch (Exception e) {
            // Deleting an absent key can legitimately throw depending on the state
            // implementation, so the exception alone does not mean the reset failed. Verify by
            // reading instead of guessing.
            deleteFailure = e;
        }

        // Verify rather than assume. read() now propagates store failures, so a delete that
        // failed AND a verification read that failed can no longer be mistaken for a successful
        // reset — the read throws and the reset is reported as failed, which is correct.
        final boolean stillPresent;
        try {
            stillPresent = read().isPresent();
        } catch (Exception readFailure) {
            final IllegalStateException error = new IllegalStateException(
                    "resetCheckpoint was requested but the stored checkpoint under '" + key
                            + "' could neither be deleted nor verified, so whether the reset took "
                            + "effect is unknown. Failing rather than guessing.", readFailure);
            if (deleteFailure != null) {
                error.addSuppressed(deleteFailure);
            }
            throw error;
        }

        if (stillPresent) {
            throw new IllegalStateException(
                    "resetCheckpoint was requested but the stored checkpoint under '" + key
                            + "' could not be deleted. Continuing would resume from the old "
                            + "position while reporting a reset, so the source is failing instead.",
                    deleteFailure);
        }
        if (deleteFailure != null) {
            LOG.debug("deleteState threw for {} but no checkpoint remains, so the reset succeeded",
                    key, deleteFailure);
        }
    }

    void write(long recordingId, long position) {
        final ByteBuffer buffer = ByteBuffer.allocate(SERIALIZED_BYTES);
        buffer.putLong(recordingId);
        buffer.putLong(position);
        buffer.flip();
        sourceContext.putState(key, buffer);
    }

    String key() {
        return key;
    }
}
