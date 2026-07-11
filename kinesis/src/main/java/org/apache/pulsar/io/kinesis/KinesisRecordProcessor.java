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
package org.apache.pulsar.io.kinesis;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.io.core.SourceContext;
import software.amazon.kinesis.exceptions.KinesisClientLibDependencyException;
import software.amazon.kinesis.exceptions.ThrottlingException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

@Slf4j
public class KinesisRecordProcessor implements ShardRecordProcessor {

    private record CheckpointSequenceNumber(String sequenceNumber, long subSequenceNumber) {}

    private final int numRetries;
    private final long checkpointInterval;
    private final long backoffTime;
    private final LinkedBlockingQueue<KinesisRecord> queue;
    private final SourceContext sourceContext;
    private final Set<String> propertiesToInclude;
    private final KinesisSourceConfig.MessageKeyMode messageKeyMode;
    private final ScheduledExecutorService checkpointExecutor;
    private final AtomicReference<RecordProcessorCheckpointer> checkpointerRef = new AtomicReference<>();
    private final AtomicBoolean isCheckpointing = new AtomicBoolean(false);
    private String kinesisShardId;
    private final AtomicInteger numRecordsInFlight = new AtomicInteger(0);

    // All checkpoint-position state below is guarded by checkpointLock. Acks arrive concurrently on the
    // downstream sink's send-completion callback threads, processRecords runs on the KCL thread, and
    // triggerCheckpoint runs on the checkpointExecutor thread, so the deque/set/highest-acked triple must
    // be mutated atomically as a group.
    private final Object checkpointLock = new Object();
    // Positions appended in processRecords, in KCL delivery (sequence) order.
    private final ArrayDeque<CheckpointSequenceNumber> deliveredInOrder = new ArrayDeque<>();
    // Positions acked but not yet collapsed into the contiguous prefix (acked ahead of an earlier gap).
    private final HashSet<CheckpointSequenceNumber> ackedAwaitingCollapse = new HashSet<>();
    // The highest position whose entire delivery-order prefix has been acked. The ONLY thing we checkpoint.
    private CheckpointSequenceNumber highestContiguousAcked = null;

    private volatile CheckpointSequenceNumber lastCheckpointSequenceNumber = null;

    public KinesisRecordProcessor(LinkedBlockingQueue<KinesisRecord> queue, KinesisSourceConfig config,
                                  SourceContext sourceContext, ScheduledExecutorService checkpointExecutor) {
        this.queue = queue;
        this.checkpointInterval = config.getCheckpointInterval();
        this.numRetries = config.getNumRetries();
        this.backoffTime = config.getBackoffTime();
        this.propertiesToInclude = config.getPropertiesToInclude();
        this.messageKeyMode = config.getMessageKeyMode();
        this.sourceContext = sourceContext;
        this.checkpointExecutor = checkpointExecutor;
    }

    private void tryCheckpointWithRetry(RecordProcessorCheckpointer checkpointer,
                                        CheckpointSequenceNumber checkpoint, int attempt) {
        try {
            log.info("Attempting checkpoint {}/{} for shard {} at {}. In-flight records: {}",
                    attempt, numRetries, kinesisShardId, checkpoint, numRecordsInFlight.get());
            checkpointer.checkpoint(checkpoint.sequenceNumber(), checkpoint.subSequenceNumber());
            lastCheckpointSequenceNumber = checkpoint;
            log.info("Successfully checkpointed shard {} at {}", kinesisShardId, checkpoint);
            scheduleNextCheckpoint();
        } catch (ThrottlingException | KinesisClientLibDependencyException e) {
            if (attempt >= numRetries) {
                // A failed periodic checkpoint is recoverable: skip this round instead of terminating the
                // connector, and let the next scheduled checkpoint retry with the latest acked position.
                log.error("Checkpoint for shard {} failed after {} attempts at {}. Skipping this round; "
                        + "will retry on the next interval.", kinesisShardId, numRetries, checkpoint, e);
                scheduleNextCheckpoint();
            } else {
                log.warn("Throttling/Dependency error on checkpoint for shard {} at {}. Scheduling retry {} "
                                + "after {}ms.", kinesisShardId, checkpoint, attempt + 1, backoffTime);
                safeSchedule(() -> tryCheckpointWithRetry(checkpointer, checkpoint, attempt + 1), backoffTime);
            }
        } catch (IllegalArgumentException e) {
            // KCL rejects a backward / out-of-range checkpoint with IllegalArgumentException. With the
            // contiguous-prefix logic this should no longer happen, so surface it as a metric for alerting,
            // but still skip-and-reschedule rather than crashing the connector.
            log.warn("KCL rejected a backward/out-of-range checkpoint for shard {} at {}. Skipping this round; "
                    + "will retry on the next interval.", kinesisShardId, checkpoint, e);
            sourceContext.recordMetric("kinesisBackwardCheckpointDetected", 1);
            scheduleNextCheckpoint();
        } catch (Exception e) {
            // Other non-retryable errors must NOT crash the connector. Skip this round; a later ack with a
            // higher position will advance the checkpoint on a subsequent cycle.
            log.error("Non-retryable exception during checkpoint for shard {} at {}. Skipping this round; "
                    + "will retry on the next interval.", kinesisShardId, checkpoint, e);
            scheduleNextCheckpoint();
        }
    }

    private void scheduleNextCheckpoint() {
        isCheckpointing.set(false);
        safeSchedule(this::triggerCheckpoint, checkpointInterval);
    }

    private void safeSchedule(Runnable task, long delayMillis) {
        try {
            checkpointExecutor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The checkpoint executor has been shut down (connector is closing); nothing left to schedule.
            log.debug("Checkpoint executor rejected a task for shard {} (likely shutting down).", kinesisShardId);
        }
    }

    public void updateSequenceNumberToCheckpoint(String sequenceNumber, long subSequenceNumber) {
        CheckpointSequenceNumber acked = new CheckpointSequenceNumber(sequenceNumber, subSequenceNumber);
        log.debug("{} Record acked at {}", kinesisShardId, acked);
        synchronized (checkpointLock) {
            ackedAwaitingCollapse.add(acked);
            // Collapse the longest contiguous, fully-acked prefix (in delivery order) into highestContiguousAcked.
            // Stop at the first un-acked head: we must never advance the checkpoint past a still-in-flight
            // lower sequence, otherwise a crash would resume AFTER it and lose it.
            while (!deliveredInOrder.isEmpty()) {
                CheckpointSequenceNumber head = deliveredInOrder.peekFirst();
                if (!ackedAwaitingCollapse.remove(head)) {
                    break;
                }
                deliveredInOrder.pollFirst();
                highestContiguousAcked = head;
            }
        }
        this.numRecordsInFlight.decrementAndGet();
    }

    public void failed() {
        // Intentionally do NOT remove this record's position from deliveredInOrder: a record that failed to
        // be delivered downstream must keep blocking the contiguous-prefix collapse so the checkpoint can
        // never advance past it. fatal() terminates the connector, which reprocesses from the last checkpoint.
        numRecordsInFlight.decrementAndGet();
        sourceContext.fatal(new PulsarClientException("Failed to send Kinesis record to Pulsar topic"));
    }

    @Override
    public void initialize(InitializationInput initializationInput) {
        kinesisShardId = initializationInput.shardId();
        log.info("Initializing KinesisRecordProcessor for shard {}, extendedSequenceNumber: {}, pendingCheckSeq: {}",
                kinesisShardId, initializationInput.extendedSequenceNumber(),
                initializationInput.pendingCheckpointSequenceNumber());
        safeSchedule(this::triggerCheckpoint, checkpointInterval);
    }

    private void triggerCheckpoint() {
        try {
            if (isCheckpointing.compareAndSet(false, true)) {
                final RecordProcessorCheckpointer checkpointer = checkpointerRef.get();
                final CheckpointSequenceNumber currentCheckpoint;
                final int pendingGap;
                synchronized (checkpointLock) {
                    currentCheckpoint = this.highestContiguousAcked;
                    pendingGap = deliveredInOrder.size();
                }
                // Emit metrics outside the lock. A rising kinesisCheckpointPendingGap means acks are stalled
                // behind a gap (a data-loss exposure under the old last-writer-wins code; here it only delays
                // the checkpoint from advancing).
                sourceContext.recordMetric("kinesisRecordsInFlight", numRecordsInFlight.get());
                sourceContext.recordMetric("kinesisCheckpointPendingGap", pendingGap);
                if (checkpointer != null && currentCheckpoint != null && !currentCheckpoint.equals(
                        lastCheckpointSequenceNumber)) {
                    tryCheckpointWithRetry(checkpointer, currentCheckpoint, 1);
                } else {
                    scheduleNextCheckpoint();
                }
            }
        } catch (Throwable e) {
            // Defensive: never let a checkpoint-trigger failure crash the connector. Keep the loop alive
            // so the next scheduled cycle can make progress.
            log.error("Unexpected error while triggering checkpoint for shard {}. Skipping this round.",
                    kinesisShardId, e);
            scheduleNextCheckpoint();
        }
    }

    @Override
    public void processRecords(ProcessRecordsInput processRecordsInput) {
        this.checkpointerRef.set(processRecordsInput.checkpointer());
        log.info("Processing {} records from {}", processRecordsInput.records().size(), kinesisShardId);
        long millisBehindLatest = processRecordsInput.millisBehindLatest();

        for (KinesisClientRecord record : processRecordsInput.records()) {
            log.debug("Add record with sequence number {}:{} to queue for shard {}.",
                    record.sequenceNumber(), record.subSequenceNumber(), kinesisShardId);
            // Register the delivery-order position BEFORE the record can be read/acked from the queue, so a
            // fast ack can never race ahead of its own deliveredInOrder entry. KCL delivers each position at
            // most once to a given processor instance, so no de-duplication is needed here. queue.put may
            // block, so the lock is released before the put.
            synchronized (checkpointLock) {
                deliveredInOrder.addLast(
                        new CheckpointSequenceNumber(record.sequenceNumber(), record.subSequenceNumber()));
            }
            try {
                queue.put(new KinesisRecord(record, this.kinesisShardId, millisBehindLatest,
                        propertiesToInclude, this, messageKeyMode));
            } catch (Exception e) {
                // The record never made it onto the queue, so it will never be read or acked. Its position
                // stays in deliveredInOrder so the contiguous prefix cannot advance past this gap (no data
                // loss); fatal() terminates the connector, which then reprocesses from the last checkpoint.
                log.error("Unable to create and queue KinesisRecord for shard {}.", kinesisShardId, e);
                sourceContext.fatal(e);
                return;
            }
            numRecordsInFlight.incrementAndGet();
        }
    }

    @Override
    public void leaseLost(LeaseLostInput leaseLostInput) {
        log.info("Lease lost for shard {}. Last checkpointed at {}.", kinesisShardId, lastCheckpointSequenceNumber);
    }

    private void finalizeAndCheckpoint(RecordProcessorCheckpointer checkpointer, boolean isShardEnd) {
        boolean processedInTime = false;
        log.info("Waiting up to {}s for {} in-flight records on shard {}.", numRetries,
                numRecordsInFlight.get(), kinesisShardId);
        try {
            for (int i = 0; i < numRetries; i++) {
                // "Fully processed" means the entire delivery-order prefix has been acked (deque empty), not
                // merely that in-flight reached 0: a record that failed downstream decrements in-flight but
                // intentionally keeps its position in deliveredInOrder. Gating SHARD_END on deque-empty keeps
                // the full checkpoint() from ever marking the shard complete while a gap remains.
                boolean drained;
                synchronized (checkpointLock) {
                    drained = deliveredInOrder.isEmpty();
                }
                if (drained) {
                    processedInTime = true;
                    break;
                }
                Thread.sleep(2000L);
            }
        } catch (Exception e) {
            log.warn("Error while waiting for in-flight records on shard {}.", kinesisShardId, e);
        }

        try {
            if (processedInTime && isShardEnd) {
                log.info("All records processed for shard {}. Performing SHARD_END checkpoint.", kinesisShardId);
                for (int i = 0; i < numRetries; i++) {
                    try {
                        checkpointer.checkpoint();
                        log.info("Successfully checkpointed shard {} at SHARD_END.", kinesisShardId);
                        return;
                    } catch (ThrottlingException | KinesisClientLibDependencyException ex) {
                        if (i >= numRetries - 1) {
                            throw ex;
                        }
                        Thread.sleep(backoffTime);
                    }
                }
            } else {
                log.warn("Not all records for shard {} were processed or not a shard end. "
                                + "Performing best-effort checkpoint.", kinesisShardId);
                final CheckpointSequenceNumber finalCheckpoint;
                synchronized (checkpointLock) {
                    finalCheckpoint = this.highestContiguousAcked;
                }
                if (finalCheckpoint != null) {
                    checkpointer.checkpoint(finalCheckpoint.sequenceNumber(), finalCheckpoint.subSequenceNumber());
                }
            }
        } catch (Exception e) {
            // Defensive: a failed final checkpoint must not crash the connector. Under at-least-once
            // semantics the un-checkpointed records will simply be reprocessed by the next lease owner.
            log.error("Failed to perform final checkpoint for shard {}. Data may be reprocessed.", kinesisShardId, e);
        }
    }

    @Override
    public void shardEnded(ShardEndedInput shardEndedInput) {
        log.info("Reached end of shard {}, starting final checkpoint process.", kinesisShardId);
        finalizeAndCheckpoint(shardEndedInput.checkpointer(), true);
    }

    @Override
    public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {
        log.info("Shutdown requested for shard {}, starting final checkpoint process.", kinesisShardId);
        finalizeAndCheckpoint(shutdownRequestedInput.checkpointer(), false);
    }
}
