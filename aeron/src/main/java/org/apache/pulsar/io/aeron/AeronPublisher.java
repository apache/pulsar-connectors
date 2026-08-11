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

import io.aeron.Publication;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Offers payloads to an Aeron {@link Publication}, retrying while Aeron reports a condition
 * that can clear on its own.
 *
 * <p>The offer path is deliberately separated from {@link AeronSink} and expressed against
 * {@link OfferFunction} rather than {@code Publication} directly. {@code Publication} cannot
 * be driven into states like {@code BACK_PRESSURED} or {@code MAX_POSITION_EXCEEDED} on demand,
 * so the only practical way to test that each of Aeron's six result codes is handled correctly
 * is to substitute the offer call.
 */
public class AeronPublisher {

    /** The subset of {@link Publication} this class needs; matches {@code Publication::offer}. */
    @FunctionalInterface
    public interface OfferFunction {
        long offer(DirectBuffer buffer, int offset, int length);
    }

    /** What became of a payload that was handed to {@link #publish(byte[])}. */
    public enum OfferOutcome {
        /** Aeron accepted the message. */
        PUBLISHED,
        /** Aeron kept rejecting it until the configured timeout elapsed. */
        TIMED_OUT
    }

    // Wrapping a byte[] in an UnsafeBuffer allocates; reusing one per thread avoids doing that
    // on every record. ThreadLocal because the framework makes no single-thread guarantee.
    private static final ThreadLocal<UnsafeBuffer> BUFFER = ThreadLocal.withInitial(UnsafeBuffer::new);

    private final OfferFunction offerFunction;
    private final IdleStrategy idleStrategy;
    private final long offerTimeoutNanos;

    public AeronPublisher(OfferFunction offerFunction, AeronSinkConfig config) {
        this.offerFunction = offerFunction;
        this.idleStrategy = IdleStrategies.create(config.getIdleStrategy());
        this.offerTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.getOfferTimeoutMs());
    }

    /**
     * Offers one payload, retrying transient rejections until the configured timeout.
     *
     * @return {@link OfferOutcome#PUBLISHED} if Aeron took the message, or
     *         {@link OfferOutcome#TIMED_OUT} if it never became publishable in time
     * @throws IllegalStateException if the publication is permanently unusable, which no amount
     *         of retrying would fix
     */
    public OfferOutcome publish(byte[] payload) {
        final UnsafeBuffer buffer = BUFFER.get();
        buffer.wrap(payload);

        // Idle strategies carry spin/yield state across calls; a stale one would start this
        // offer already parked at its maximum backoff.
        idleStrategy.reset();

        final long deadline = System.nanoTime() + offerTimeoutNanos;
        while (true) {
            final long result = offerFunction.offer(buffer, 0, payload.length);

            if (result > 0) {
                return OfferOutcome.PUBLISHED;
            }
            if (result == Publication.CLOSED) {
                throw new IllegalStateException("Aeron publication is closed");
            }
            if (result == Publication.MAX_POSITION_EXCEEDED) {
                // The term buffer has run out of addressable positions. A new publication is
                // required; retrying this one would spin until the timeout for nothing.
                throw new IllegalStateException(
                        "Aeron publication reached its maximum position and must be recreated");
            }
            if (result != Publication.NOT_CONNECTED
                    && result != Publication.BACK_PRESSURED
                    && result != Publication.ADMIN_ACTION) {
                throw new IllegalStateException("Unexpected Aeron offer result: " + result);
            }

            // NOT_CONNECTED, BACK_PRESSURED and ADMIN_ACTION can all clear on their own:
            // a subscriber may appear, drain, or finish rotating a term.
            if (System.nanoTime() >= deadline) {
                return OfferOutcome.TIMED_OUT;
            }
            idleStrategy.idle();
        }
    }
}
