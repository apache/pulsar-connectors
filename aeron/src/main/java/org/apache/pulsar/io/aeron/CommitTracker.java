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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which replayed positions have actually reached Pulsar, so only those are checkpointed.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code PushSource.consume()} merely queues a record. The framework drains that queue, publishes,
 * and only then calls {@link org.apache.pulsar.functions.api.Record#ack()}. Checkpointing at
 * <em>enqueue</em> time would therefore commit positions for records still sitting in memory, and a
 * crash in that window would resume <b>past records that were never published</b> — silent loss, in
 * the mode whose entire purpose is not losing anything.
 *
 * <p>So the replay cursor and the committed cursor are separate. This class turns
 * out-of-order acknowledgements into a contiguous committed watermark: the highest position such
 * that every position emitted before it has been acknowledged.
 *
 * <h2>Failures stall rather than skip</h2>
 *
 * <p>A failed record is left in place. The watermark stops behind it and stops advancing, so the
 * next restart replays from before the failure instead of stepping over it.
 *
 * <p>This is only half the answer, and on its own it would not deliver at-least-once: the replay
 * cursor has already moved past the record, so nothing re-emits it within this process. The caller
 * therefore also fails the source on a publish failure, so the runtime restarts it and the
 * stalled watermark decides where replay resumes. This class holds the position; it does not
 * decide the policy.
 *
 * <p>Acknowledgements arrive on framework threads while emissions happen on the poller thread, so
 * every method is synchronized. The critical sections are a few pointer moves and are dwarfed by
 * the Pulsar publish they accompany.
 */
final class CommitTracker {

    private final Deque<Long> emitted = new ArrayDeque<>();
    private final Set<Long> acked = new HashSet<>();

    private long committed;
    private boolean anyCommitted;
    private long failures;

    CommitTracker(long startPosition) {
        this.committed = startPosition;
    }

    /** Records that a position has been handed to the framework but is not yet published. */
    synchronized void emitted(long position) {
        emitted.addLast(position);
    }

    /** Marks a position published, then advances the watermark over any contiguous run. */
    synchronized void acked(long position) {
        acked.add(position);
        while (!emitted.isEmpty() && acked.remove(emitted.peekFirst())) {
            committed = emitted.removeFirst();
            anyCommitted = true;
        }
    }

    /**
     * Marks a position as failed to publish.
     *
     * <p>Deliberately does not remove it from the queue: leaving it there is what stalls the
     * watermark and keeps the record replayable.
     */
    synchronized void failed(long position) {
        failures++;
    }

    /** The highest position whose record — and every record before it — reached Pulsar. */
    synchronized long committedPosition() {
        return committed;
    }

    /** True once anything has been committed, so an untouched start position is not persisted. */
    synchronized boolean hasCommitted() {
        return anyCommitted;
    }

    /** Records handed downstream but neither acknowledged nor failed yet. */
    synchronized int pending() {
        return emitted.size();
    }

    synchronized long failureCount() {
        return failures;
    }

    /** Drops all tracking, for when the cursor jumps — a recording rotation or a reset. */
    synchronized void reset(long startPosition) {
        emitted.clear();
        acked.clear();
        committed = startPosition;
        anyCommitted = false;
    }
}
