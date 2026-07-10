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
package org.apache.pulsar.io.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * When the Kafka consumer thread hits a fatal error it must interrupt the instance thread,
 * which is otherwise blocked on Pulsar network I/O and would deadlock.
 *
 * <p>The test plays the part of the instance thread. Coordination is by latch rather than by
 * sleeping: every wait either completes or fails the test, so a regression surfaces as a
 * timeout with a clear message instead of a hang.
 */
public class KafkaSourceDeadlockTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Test(timeOut = 60_000)
    public void testConnectorBreaksInstanceThreadDeadlock() throws Exception {
        KafkaBytesSource source = new KafkaBytesSource();

        // Inject dependencies using package-private @VisibleForTesting methods
        source.setInstanceThread(Thread.currentThread());

        KafkaSourceConfig config = Mockito.mock(KafkaSourceConfig.class);
        Mockito.when(config.getTopic()).thenReturn("test-topic");
        Mockito.when(config.isAutoCommitEnabled()).thenReturn(false);
        source.setKafkaSourceConfig(config);

        // Signals the consumer thread has entered poll() and is blocked there.
        final CountDownLatch pollEntered = new CountDownLatch(1);
        // Never counted down: poll() leaves this wait only by being interrupted.
        final CountDownLatch pollReleased = new CountDownLatch(1);
        // The consumer thread identifies itself, so the test need not scan JVM threads by name.
        final AtomicReference<Thread> consumerThread = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        Consumer<Object, Object> mockConsumer = Mockito.mock(Consumer.class);
        source.setConsumer(mockConsumer);

        Mockito.when(mockConsumer.poll(Mockito.any(Duration.class))).thenAnswer(invocation -> {
            consumerThread.set(Thread.currentThread());
            pollEntered.countDown();
            try {
                // Interruptible, unlike Thread.sleep with a guessed duration.
                pollReleased.await();
            } catch (InterruptedException e) {
                // Stand in for a fatal Kafka error surfacing on the consumer thread.
                throw new RuntimeException("Simulated fatal Kafka network error", e);
            }
            return new ConsumerRecords<>(Collections.emptyMap());
        });

        boolean wasInterrupted = false;
        try {
            source.start();

            Assert.assertTrue(pollEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the consumer thread never reached poll()");

            // Fail the consumer thread. Its fatal-error handling must interrupt this thread.
            consumerThread.get().interrupt();

            try {
                // Stands in for the instance thread blocking on Pulsar network I/O. The interrupt
                // may already have arrived, in which case this throws immediately.
                Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                Assert.fail("the instance thread remained deadlocked and was never interrupted");
            } catch (InterruptedException e) {
                wasInterrupted = true;
            }
        } finally {
            // Clear the interrupt flag so close() runs cleanly, and close even if we failed above.
            Thread.interrupted();
            source.close();
        }

        Assert.assertTrue(wasInterrupted,
                "the failing consumer thread should have interrupted the instance thread");
    }
}
