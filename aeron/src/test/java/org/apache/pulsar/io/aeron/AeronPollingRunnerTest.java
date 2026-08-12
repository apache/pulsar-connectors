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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests the fragment-handling half of {@link AeronPollingRunner} without standing up a
 * media driver, by handing it a hand-built Aeron data frame.
 */
public class AeronPollingRunnerTest {

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1001;
    private static final int SESSION_ID = 77;
    private static final int TERM_LENGTH = 64 * 1024;

    private List<Record<byte[]>> consumed;
    private SourceContext sourceContext;

    @BeforeMethod
    public void setUp() {
        consumed = new ArrayList<>();
        sourceContext = mock(SourceContext.class);
    }

    private AeronPollingRunner newRunner(AeronSourceConfig config) {
        // The subscription is only touched by run(); these tests drive onFragment directly.
        return new AeronPollingRunner(null, config, consumed::add, sourceContext);
    }

    private static AeronSourceConfig config() {
        return new AeronSourceConfig().setChannel(CHANNEL).setStreamId(STREAM_ID);
    }

    /**
     * Builds a real Aeron data frame: a {@link Header} is final and reads its fields straight
     * out of the buffer it is attached to, so the frame has to be genuine rather than mocked.
     *
     * @return the frame buffer; the payload starts at {@link DataHeaderFlyweight#HEADER_LENGTH}
     */
    private static UnsafeBuffer frameWith(byte[] payload, Header header) {
        UnsafeBuffer frame = new UnsafeBuffer(new byte[DataHeaderFlyweight.HEADER_LENGTH + payload.length]);
        DataHeaderFlyweight flyweight = new DataHeaderFlyweight(frame);
        flyweight.frameLength(DataHeaderFlyweight.HEADER_LENGTH + payload.length);
        flyweight.sessionId(SESSION_ID);
        flyweight.streamId(STREAM_ID);
        flyweight.termId(0);
        flyweight.termOffset(0);
        frame.putBytes(DataHeaderFlyweight.HEADER_LENGTH, payload);

        header.buffer(frame);
        header.offset(0);
        return frame;
    }

    private static Header newHeader() {
        return new Header(0, LogBufferDescriptor.positionBitsToShift(TERM_LENGTH));
    }

    @Test
    public void testPayloadIsCopiedOutOfTheTermBuffer() {
        // The whole point of the copy in onFragment: Aeron reuses term buffers as soon as the
        // callback returns. Without the copy this record's value would change underneath the
        // consumer. Simulating that reuse here is the only cheap way to catch a regression.
        byte[] payload = "original-payload".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        byte[] overwrite = "CLOBBEREDCLOBBERE".substring(0, payload.length)
                .getBytes(StandardCharsets.UTF_8);
        frame.putBytes(DataHeaderFlyweight.HEADER_LENGTH, overwrite);

        assertThat(consumed).hasSize(1);
        assertThat(new String(consumed.get(0).getValue(), StandardCharsets.UTF_8))
                .isEqualTo("original-payload");
    }

    @Test
    public void testOnlyTheFragmentRangeIsCopied() {
        // A fragment is a window into a larger frame; copying the whole buffer would smear
        // the header (and any neighbouring fragment) into the payload.
        byte[] payload = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed.get(0).getValue()).isEqualTo(payload);
        assertThat(consumed.get(0).getValue()).hasSize(payload.length);
    }

    @Test
    public void testZeroLengthFragmentProducesEmptyPayload() {
        byte[] payload = new byte[0];
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, 0, header);

        assertThat(consumed).hasSize(1);
        assertThat(consumed.get(0).getValue()).isEmpty();
    }

    @Test
    public void testMetadataIsTakenFromTheAeronHeader() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);
        long expectedPosition = header.position();

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed.get(0).getProperties())
                .containsEntry(AeronRecord.PROP_SESSION_ID, Integer.toString(SESSION_ID))
                .containsEntry(AeronRecord.PROP_STREAM_ID, Integer.toString(STREAM_ID))
                .containsEntry(AeronRecord.PROP_CHANNEL, CHANNEL)
                .containsEntry(AeronRecord.PROP_POSITION, Long.toString(expectedPosition))
                .containsKey(AeronRecord.PROP_INGEST_TS);
    }

    @Test
    public void testNoKeyUnlessKeyBySessionIdIsSet() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed.get(0).getKey()).isEmpty();
    }

    @Test
    public void testKeyBySessionIdSetsTheRecordKey() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config().setKeyBySessionId(true))
                .onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed.get(0).getKey()).contains(Integer.toString(SESSION_ID));
    }

    @Test
    public void testEachAssembledMessageCountsAsOneRecord() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        verify(sourceContext).recordMetric(eq(AeronPollingRunner.METRIC_RECORDS_CONSUMED), eq(1.0d));
        // The fragment counter belongs to the poll loop, not the handler: the assembler calls
        // the handler once per whole message, so counting fragments here would under-report
        // every message that arrived split across the MTU.
        verify(sourceContext, never())
                .recordMetric(eq(AeronPollingRunner.METRIC_FRAGMENTS_RECEIVED), anyDouble());
    }

    @Test
    public void testEachFragmentGetsItsOwnPayloadArray() {
        Header header = newHeader();
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        UnsafeBuffer frame = frameWith(first, header);
        AeronPollingRunner runner = newRunner(config());

        runner.onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, first.length, header);
        runner.onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, first.length, header);

        assertThat(consumed).hasSize(2);
        assertThat(consumed.get(0).getValue()).isNotSameAs(consumed.get(1).getValue());
    }

    @Test
    public void testStopIsIdempotent() {
        AeronPollingRunner runner = newRunner(config());

        runner.stop();
        runner.stop();
    }

    @Test
    public void testNullSourceContextIsTolerated() {
        // localrun and unit harnesses can hand the source a null context; metric recording
        // must not be the thing that breaks the pipeline.
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        new AeronPollingRunner(null, config(), consumed::add, null)
                .onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed).hasSize(1);
    }

    @Test
    public void testLargePayloadIsCopiedIntact() {
        byte[] payload = new byte[8192];
        Arrays.fill(payload, (byte) 0x5A);
        Header header = newHeader();
        UnsafeBuffer frame = frameWith(payload, header);

        newRunner(config()).onFragment(frame, DataHeaderFlyweight.HEADER_LENGTH, payload.length, header);

        assertThat(consumed.get(0).getValue()).isEqualTo(payload);
    }
}
