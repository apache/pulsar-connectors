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
import io.aeron.Publication;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * Tests how {@link AeronPublisher} handles each of Aeron's offer result codes.
 *
 * <p>A real {@code Publication} cannot be driven into back-pressure or a closed state on demand,
 * so these substitute the offer call. That is the point of {@link AeronPublisher.OfferFunction}.
 */
public class AeronPublisherTest {

    private static final byte[] PAYLOAD = "aggregate-result".getBytes(StandardCharsets.UTF_8);

    private static AeronSinkConfig config(long timeoutMs) {
        return new AeronSinkConfig()
                .setChannel("aeron:ipc")
                .setStreamId(1002)
                // 'sleeping' keeps the retry tests from pinning a core while they wait.
                .setIdleStrategy(IdleStrategies.SLEEPING)
                .setOfferTimeoutMs(timeoutMs);
    }

    @Test
    public void testSuccessfulOfferPublishes() {
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> 128L, config(30_000));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
    }

    @Test
    public void testPayloadIsOfferedWithItsFullLength() {
        AtomicInteger observedLength = new AtomicInteger(-1);
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            observedLength.set(l);
            return 128L;
        }, config(30_000));

        publisher.publish(PAYLOAD);

        assertThat(observedLength.get()).isEqualTo(PAYLOAD.length);
    }

    @Test
    public void testPayloadContentReachesTheBuffer() {
        StringBuilder seen = new StringBuilder();
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            byte[] copy = new byte[l];
            b.getBytes(o, copy);
            seen.append(new String(copy, StandardCharsets.UTF_8));
            return 128L;
        }, config(30_000));

        publisher.publish(PAYLOAD);

        assertThat(seen.toString()).isEqualTo("aggregate-result");
    }

    @Test
    public void testBackPressureIsRetriedUntilItClears() {
        // The realistic case: a subscriber catches up and the offer succeeds on a later attempt.
        AtomicInteger attempts = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) ->
                attempts.incrementAndGet() < 4 ? Publication.BACK_PRESSURED : 128L,
                config(30_000));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    public void testNotConnectedIsRetriedUntilASubscriberAppears() {
        AtomicInteger attempts = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) ->
                attempts.incrementAndGet() < 3 ? Publication.NOT_CONNECTED : 128L,
                config(30_000));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
    }

    @Test
    public void testAdminActionIsRetried() {
        // ADMIN_ACTION means a term is rotating; it clears on its own almost immediately.
        AtomicInteger attempts = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) ->
                attempts.incrementAndGet() < 2 ? Publication.ADMIN_ACTION : 128L,
                config(30_000));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
    }

    @Test
    public void testSustainedBackPressureTimesOut() {
        AeronPublisher publisher =
                new AeronPublisher((b, o, l) -> Publication.BACK_PRESSURED, config(100));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.TIMED_OUT);
    }

    @Test
    public void testNeverConnectedTimesOut() {
        AeronPublisher publisher =
                new AeronPublisher((b, o, l) -> Publication.NOT_CONNECTED, config(100));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.TIMED_OUT);
    }

    @Test
    public void testTimeoutIsRoughlyRespected() {
        AeronPublisher publisher =
                new AeronPublisher((b, o, l) -> Publication.BACK_PRESSURED, config(200));

        long start = System.nanoTime();
        publisher.publish(PAYLOAD);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Bounded loosely on both sides: it must actually wait, and must not hang.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150L).isLessThan(10_000L);
    }

    @Test
    public void testClosedPublicationThrowsImmediately() {
        // Retrying a closed publication until the timeout would stall the sink for no reason.
        AtomicInteger attempts = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            attempts.incrementAndGet();
            return Publication.CLOSED;
        }, config(30_000));

        assertThatThrownBy(() -> publisher.publish(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    public void testMaxPositionExceededThrowsImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            attempts.incrementAndGet();
            return Publication.MAX_POSITION_EXCEEDED;
        }, config(30_000));

        assertThatThrownBy(() -> publisher.publish(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum position");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    public void testUnknownNegativeResultThrows() {
        // Guards against a future Aeron result code being silently treated as retryable.
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> -99L, config(30_000));

        assertThatThrownBy(() -> publisher.publish(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected Aeron offer result: -99");
    }

    @Test
    public void testEmptyPayloadIsOffered() {
        AtomicInteger observedLength = new AtomicInteger(-1);
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            observedLength.set(l);
            return 128L;
        }, config(30_000));

        assertThat(publisher.publish(new byte[0]))
                .isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
        assertThat(observedLength.get()).isZero();
    }

    @Test
    public void testConcurrentPublishesDoNotShareIdleState() throws Exception {
        // The sink uses a concurrent Publication precisely because the framework may call
        // write() from several threads. An IdleStrategy accumulates spin/yield/park state, so
        // a single shared instance would let threads drive each other's backoff. Each thread
        // must get its own.
        final int threads = 8;
        final int perThread = 50;
        // Back-pressure every third offer so the retry path — and the idle strategy — is
        // actually exercised rather than every call succeeding first time.
        final AtomicInteger offers = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher(
                (b, o, l) -> offers.incrementAndGet() % 3 == 0 ? Publication.BACK_PRESSURED : 128L,
                config(30_000));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int published = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (publisher.publish(PAYLOAD) == AeronPublisher.OfferOutcome.PUBLISHED) {
                            published++;
                        }
                    }
                    return published;
                }));
            }
            start.countDown();

            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(60, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testConsecutivePublishesEachGetAFullTimeoutBudget() {
        // The idle strategy is stateful and the deadline is per-call. If either leaked between
        // calls, a publish following a slow one could time out early.
        AtomicInteger calls = new AtomicInteger();
        AeronPublisher publisher = new AeronPublisher((b, o, l) -> {
            int n = calls.incrementAndGet();
            // First publish: back-pressured a while. Second: immediate.
            if (n <= 3) {
                return Publication.BACK_PRESSURED;
            }
            return 128L;
        }, config(2_000));

        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
        assertThat(publisher.publish(PAYLOAD)).isEqualTo(AeronPublisher.OfferOutcome.PUBLISHED);
    }
}
