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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.io.core.SourceContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.kinesis.model.EncryptionType;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.exceptions.ThrottlingException;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

public class KinesisRecordProcessorTest {

    private KinesisSourceConfig config;
    private SourceContext sourceContext;
    private LinkedBlockingQueue<KinesisRecord> queue;
    private KinesisRecordProcessor recordProcessor;
    private RecordProcessorCheckpointer checkpointer;
    private ScheduledExecutorService checkpointExecutor;
    private ArgumentCaptor<Runnable> scheduledTaskCaptor;

    @BeforeMethod
    public void setup() {
        config = Mockito.mock(KinesisSourceConfig.class);
        sourceContext = Mockito.mock(SourceContext.class);
        queue = new LinkedBlockingQueue<>();
        checkpointer = Mockito.mock(RecordProcessorCheckpointer.class);
        checkpointExecutor = Mockito.mock(ScheduledExecutorService.class);
        scheduledTaskCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(config.getCheckpointInterval()).thenReturn(60000L);
        when(config.getNumRetries()).thenReturn(1);
        when(config.getBackoffTime()).thenReturn(100L);
        when(config.getPropertiesToInclude()).thenReturn(Collections.emptySet());
        when(config.getMessageKeyMode()).thenReturn(KinesisSourceConfig.MessageKeyMode.PARTITION_KEY);

        recordProcessor = new KinesisRecordProcessor(queue, config, sourceContext, checkpointExecutor);
    }

    @Test
    public void testScheduledCheckpointAfterAck() throws Exception {
        // Arrange: Initialize the processor, which schedules the first checkpoint task.
        recordProcessor.initialize(createMockInitializationInput());
        verify(checkpointExecutor).schedule(scheduledTaskCaptor.capture(), anyLong(), any(TimeUnit.class));
        Runnable scheduledCheckpointTask = scheduledTaskCaptor.getValue();

        // Act: Process a record and ack it.
        ProcessRecordsInput processRecordsInput = createMockProcessRecordsInput(
                createMockKinesisRecord("seq-1", 0L));
        recordProcessor.processRecords(processRecordsInput);
        KinesisRecord recordFromQueue = queue.take();
        recordFromQueue.ack();

        // Simulate the scheduler firing the checkpoint task.
        scheduledCheckpointTask.run();

        // Assert: Verify the checkpoint was called with the correct sequence and sub-sequence numbers.
        verify(checkpointer, times(1)).checkpoint("seq-1", 0L);
    }

    @Test
    public void testOutOfOrderAcksDoNotAdvancePastGap() throws Exception {
        // Arrange: deliver three records of the same shard IN ORDER via processRecords. KCL delivery order
        // is authoritative, so the contiguous prefix is seq-1/0 -> seq-1/1 -> seq-1/2.
        recordProcessor.initialize(createMockInitializationInput());
        verify(checkpointExecutor, Mockito.times(1))
                .schedule(scheduledTaskCaptor.capture(), anyLong(), any(TimeUnit.class));
        Runnable scheduledCheckpointTask = scheduledTaskCaptor.getValue();
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-1", 0L),
                createMockKinesisRecord("seq-1", 1L),
                createMockKinesisRecord("seq-1", 2L)));

        // Act & Assert 1: ack ONLY the highest (seq-1/2). There is still a gap at seq-1/0, so the
        // contiguous prefix is empty and nothing must be checkpointed.
        recordProcessor.updateSequenceNumberToCheckpoint("seq-1", 2L);
        scheduledCheckpointTask.run();
        verify(checkpointer, never()).checkpoint(any(String.class), anyLong());

        // Act & Assert 2: ack the lowest (seq-1/0). The contiguous prefix now covers exactly seq-1/0,
        // because seq-1/1 is still un-acked. Checkpoint must be seq-1/0, NOT the already-acked seq-1/2.
        recordProcessor.updateSequenceNumberToCheckpoint("seq-1", 0L);
        scheduledCheckpointTask.run();
        verify(checkpointer, times(1)).checkpoint("seq-1", 0L);

        // Act & Assert 3: ack the middle (seq-1/1). The prefix now collapses through seq-1/1 and the
        // already-acked seq-1/2, advancing the checkpoint to seq-1/2.
        recordProcessor.updateSequenceNumberToCheckpoint("seq-1", 1L);
        scheduledCheckpointTask.run();
        verify(checkpointer, times(1)).checkpoint("seq-1", 2L);
    }

    @Test
    public void testOutOfOrderAcksAcrossSequenceNumbers() throws Exception {
        // Distinct sequence numbers are delivered in order seq-100, seq-101, seq-102.
        recordProcessor.initialize(createMockInitializationInput());
        verify(checkpointExecutor, Mockito.times(1))
                .schedule(scheduledTaskCaptor.capture(), anyLong(), any(TimeUnit.class));
        Runnable scheduledCheckpointTask = scheduledTaskCaptor.getValue();
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-100", 0L),
                createMockKinesisRecord("seq-101", 0L),
                createMockKinesisRecord("seq-102", 0L)));

        // Ack 102 then 100; 101 is still in-flight. The checkpoint must be the contiguous head seq-100,
        // never the higher seq-102 (which would skip the un-acked seq-101 and lose it on resume).
        recordProcessor.updateSequenceNumberToCheckpoint("seq-102", 0L);
        recordProcessor.updateSequenceNumberToCheckpoint("seq-100", 0L);
        scheduledCheckpointTask.run();
        verify(checkpointer, times(1)).checkpoint("seq-100", 0L);
        verify(checkpointer, never()).checkpoint("seq-102", 0L);

        // Now ack 101: the prefix collapses through 101 and the already-acked 102, advancing to seq-102.
        recordProcessor.updateSequenceNumberToCheckpoint("seq-101", 0L);
        scheduledCheckpointTask.run();
        verify(checkpointer, times(1)).checkpoint("seq-102", 0L);
    }

    @Test
    public void testNonRetryableCheckpointFailureDoesNotCrashConnector() throws Exception {
        // Arrange: initialize schedules the first checkpoint task.
        recordProcessor.initialize(createMockInitializationInput());
        verify(checkpointExecutor, times(1))
                .schedule(scheduledTaskCaptor.capture(), anyLong(), any(TimeUnit.class));
        Runnable scheduledCheckpointTask = scheduledTaskCaptor.getValue();
        recordProcessor.processRecords(createMockProcessRecordsInput(checkpointer));

        // Simulate KCL rejecting a backward / out-of-range checkpoint (an IllegalArgumentException).
        Mockito.doThrow(new IllegalArgumentException("Could not checkpoint ... did not fall into acceptable range"))
                .when(checkpointer).checkpoint(any(String.class), anyLong());
        recordProcessor.updateSequenceNumberToCheckpoint("seq-1", 5L);

        // Act
        scheduledCheckpointTask.run();

        // Assert: the connector must not be terminated, and the checkpoint loop must be rescheduled
        // (one schedule from initialize + one from the failed round).
        verify(sourceContext, never()).fatal(any());
        verify(checkpointExecutor, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void testExhaustedRetriableCheckpointFailureDoesNotCrashConnector() throws Exception {
        // Arrange: numRetries == 1 (see setup), so the first throttling failure already exhausts retries.
        recordProcessor.initialize(createMockInitializationInput());
        verify(checkpointExecutor, times(1))
                .schedule(scheduledTaskCaptor.capture(), anyLong(), any(TimeUnit.class));
        Runnable scheduledCheckpointTask = scheduledTaskCaptor.getValue();
        recordProcessor.processRecords(createMockProcessRecordsInput(checkpointer));

        Mockito.doThrow(new ThrottlingException("throttled"))
                .when(checkpointer).checkpoint(any(String.class), anyLong());
        recordProcessor.updateSequenceNumberToCheckpoint("seq-1", 1L);

        // Act
        scheduledCheckpointTask.run();

        // Assert: retries exhausted, but the connector survives and the loop is rescheduled.
        verify(sourceContext, never()).fatal(any());
        verify(checkpointExecutor, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test(timeOut = 3000)
    public void testShardEndedTimeoutPathPerformsBestEffortCheckpoint() throws Exception {
        // Arrange: Process two records, but only ack one.
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-A", 0L),
                createMockKinesisRecord("seq-B", 1L)
        ));
        queue.take().ack(); // Ack "seq-A", leaves "seq-B" in-flight
        // Do not take/ack the second record.

        RecordProcessorCheckpointer shardEndCheckpointer = Mockito.mock(RecordProcessorCheckpointer.class);
        ShardEndedInput shardEndedInput = createMockShardEndedInput(shardEndCheckpointer);

        // Act: Call shardEnded. This should block for 10 seconds then time out.
        recordProcessor.shardEnded(shardEndedInput);

        // Assert: After timeout, a best-effort checkpoint should be made with the last ack'd sequence number.
        verify(shardEndCheckpointer, times(1)).checkpoint("seq-A", 0L);
        verify(shardEndCheckpointer, never()).checkpoint();
    }

    @Test(timeOut = 3000)
    public void testShutdownRequestedWaitsAndPerformsBestEffortCheckpoint() throws Exception {
        // Arrange: Process two records, only ack one.
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-shutdown-1", 0L),
                createMockKinesisRecord("seq-shutdown-2", 1L)
        ));
        queue.take().ack();

        RecordProcessorCheckpointer shutdownCheckpointer = Mockito.mock(RecordProcessorCheckpointer.class);
        ShutdownRequestedInput shutdownInput = createMockShutdownRequestedInput(shutdownCheckpointer);

        recordProcessor.shutdownRequested(shutdownInput);

        // Assert: A best-effort checkpoint is made with the last ack'd sequence number.
        verify(shutdownCheckpointer, times(1)).checkpoint("seq-shutdown-1", 0L);
    }

    @Test(timeOut = 5000)
    public void testShardEndedAllAckedPerformsFullCheckpoint() throws Exception {
        // Arrange: deliver two records and ack BOTH, so the contiguous prefix fully drains (deque empty).
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-A", 0L),
                createMockKinesisRecord("seq-B", 1L)
        ));
        queue.take().ack();
        queue.take().ack();

        RecordProcessorCheckpointer shardEndCheckpointer = Mockito.mock(RecordProcessorCheckpointer.class);
        ShardEndedInput shardEndedInput = createMockShardEndedInput(shardEndCheckpointer);

        // Act
        recordProcessor.shardEnded(shardEndedInput);

        // Assert: everything is acked, so KCL is told the shard is fully consumed via the no-arg checkpoint().
        verify(shardEndCheckpointer, times(1)).checkpoint();
        verify(shardEndCheckpointer, never()).checkpoint(any(String.class), anyLong());
    }

    @Test(timeOut = 5000)
    public void testShardEndedWithGapDoesNotPerformFullCheckpoint() throws Exception {
        // Arrange: deliver three records; ack the 1st and 3rd, fail the 2nd. in-flight reaches 0 but a gap
        // (seq-B) remains in the delivery-order deque, so SHARD_END must NOT mark the shard fully consumed.
        recordProcessor.processRecords(createMockProcessRecordsInput(
                createMockKinesisRecord("seq-A", 0L),
                createMockKinesisRecord("seq-B", 1L),
                createMockKinesisRecord("seq-C", 2L)
        ));
        KinesisRecord recA = queue.take();
        KinesisRecord recB = queue.take();
        KinesisRecord recC = queue.take();
        recA.ack();
        recC.ack();   // acked ahead of the gap; must not collapse past the un-acked seq-B
        recB.fail();  // sourceContext is a mock, so fatal() is a no-op and the processor keeps running

        RecordProcessorCheckpointer shardEndCheckpointer = Mockito.mock(RecordProcessorCheckpointer.class);
        ShardEndedInput shardEndedInput = createMockShardEndedInput(shardEndCheckpointer);

        // Act
        recordProcessor.shardEnded(shardEndedInput);

        // Assert: best-effort checkpoint at the contiguous prefix (seq-A) only; no full SHARD_END checkpoint.
        verify(shardEndCheckpointer, times(1)).checkpoint("seq-A", 0L);
        verify(shardEndCheckpointer, never()).checkpoint();
    }

    private KinesisClientRecord createMockKinesisRecord(String sequenceNumber, long subSequenceNumber) {
        KinesisClientRecord mockRecord = Mockito.mock(KinesisClientRecord.class);
        when(mockRecord.partitionKey()).thenReturn("test-key");
        when(mockRecord.sequenceNumber()).thenReturn(sequenceNumber);
        when(mockRecord.subSequenceNumber()).thenReturn(subSequenceNumber);
        when(mockRecord.approximateArrivalTimestamp()).thenReturn(Instant.now());
        when(mockRecord.encryptionType()).thenReturn(EncryptionType.NONE);
        when(mockRecord.data()).thenReturn(ByteBuffer.wrap("data".getBytes()));
        return mockRecord;
    }

    private ProcessRecordsInput createMockProcessRecordsInput(KinesisClientRecord... records) {
        return createMockProcessRecordsInput(this.checkpointer, records);
    }

    private ProcessRecordsInput createMockProcessRecordsInput(RecordProcessorCheckpointer checkpointer,
                                                              KinesisClientRecord... records) {
        ProcessRecordsInput input = Mockito.mock(ProcessRecordsInput.class);
        when(input.records()).thenReturn(Arrays.asList(records));
        when(input.checkpointer()).thenReturn(checkpointer);
        return input;
    }

    private InitializationInput createMockInitializationInput() {
        return Mockito.mock(InitializationInput.class);
    }

    private ShardEndedInput createMockShardEndedInput(RecordProcessorCheckpointer checkpointer) {
        ShardEndedInput input = Mockito.mock(ShardEndedInput.class);
        when(input.checkpointer()).thenReturn(checkpointer);
        return input;
    }

    private ShutdownRequestedInput createMockShutdownRequestedInput(RecordProcessorCheckpointer checkpointer) {
        ShutdownRequestedInput input = Mockito.mock(ShutdownRequestedInput.class);
        when(input.checkpointer()).thenReturn(checkpointer);
        return input;
    }
}
