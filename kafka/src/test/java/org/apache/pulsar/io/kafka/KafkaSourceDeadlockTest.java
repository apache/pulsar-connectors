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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Collections;

public class KafkaSourceDeadlockTest {

    @Test(timeOut = 15000)
    public void testConnectorBreaksInstanceThreadDeadlock() throws Exception {
        KafkaBytesSource source = new KafkaBytesSource();

        // Inject dependencies using package-private @VisibleForTesting methods
        source.setInstanceThread(Thread.currentThread());

        KafkaSourceConfig config = Mockito.mock(KafkaSourceConfig.class);
        Mockito.when(config.getTopic()).thenReturn("test-topic");
        Mockito.when(config.isAutoCommitEnabled()).thenReturn(false);
        source.setKafkaSourceConfig(config);

        @SuppressWarnings("unchecked")
        Consumer<Object, Object> mockConsumer = Mockito.mock(Consumer.class);
        source.setConsumer(mockConsumer);

        // Make the mocked poll() block safely so the background thread stays alive in its loop
        Mockito.when(mockConsumer.poll(Mockito.any(Duration.class))).thenAnswer(invocation -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // Trigger the fatal error handling block when interrupted by the simulator
                throw new RuntimeException("Simulated fatal Kafka network error", e);
            }
            return new ConsumerRecords<>(Collections.emptyMap());
        });

        // Start the background Kafka polling thread
        source.start();

        // Simulate a fatal exception in the background consumer thread
        Thread fatalErrorSimulator = new Thread(() -> {
            try {
                // Wait to ensure the instance thread enters its blocked state first
                Thread.sleep(1000);

                // Find the Kafka Source Thread in the JVM and interrupt it
                Thread runner = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "Kafka Source Thread".equals(t.getName()))
                        .findFirst()
                        .orElse(null);

                if (runner != null) {
                    runner.interrupt();
                }
            } catch (Exception e) {
                // Ignore simulator exceptions
            }
        });
        fatalErrorSimulator.start();

        // Simulate the instance thread blocking on Pulsar network I/O
        boolean wasInterrupted = false;
        try {
            Thread.sleep(10000);
            Assert.fail("Test failed: The instance thread remained deadlocked and was never interrupted.");
        } catch (InterruptedException e) {
            // Expected behavior: The consumer thread successfully interrupted this thread to break the deadlock
            wasInterrupted = true;
        }

        // Cleanup the interrupt flag so close() works cleanly
        Thread.interrupted();
        source.close();

        // Verify that the deadlock resolution logic executed successfully
        Assert.assertTrue(wasInterrupted,
                "The instance thread should have been interrupted by the failing consumer thread to prevent deadlock.");
    }
}