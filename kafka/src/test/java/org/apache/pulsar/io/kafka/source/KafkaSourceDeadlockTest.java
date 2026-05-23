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
package org.apache.pulsar.io.kafka.source;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.kafka.KafkaBytesSource;
import org.mockito.Mockito;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class KafkaSourceDeadlockTest {

    public KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.3.0"))
            .withStartupTimeout(Duration.ofMinutes(5));

    private KafkaBytesSource source;
    private SourceContext mockContext;
    private final String TOPIC = "liveness-test-topic";

    @BeforeClass
    public void setup() throws Exception {
        kafka.start();

        // Send a dummy message into Kafka so the Consumer Thread has something to read
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(TOPIC, "key", "test-data".getBytes())).get();
        }
    }

    @AfterClass
    public void teardown() throws Exception {
        Thread.interrupted();

        if (source != null) {
            source.close();
        }
        kafka.stop();
    }

    @Test(timeOut = 15000)
    public void testConnectorBreaksInstanceThreadDeadlock() throws Exception {

        // Configure and OPEN the source inside the test method
        Map<String, Object> config = new HashMap<>();
        config.put("topic", TOPIC);
        config.put("bootstrapServers", kafka.getBootstrapServers());
        config.put("groupId", "test-group");
        config.put("autoCommitEnabled", false);

        mockContext = Mockito.mock(SourceContext.class);
        source = new KafkaBytesSource();
        source.open(config, mockContext);

        // Verify the consumer thread is working normally
        Record<java.nio.ByteBuffer> record = source.read();
        Assert.assertNotNull(record, "Should have read the initial message from Kafka");

        // THE SABOTAGE: Force the consumer thread to crash instantly.
        // Stopping the Kafka container takes too long
        // Instead, we directly find the background thread and interrupt it to trigger the fatal error block.
        Thread crashSimulator = new Thread(() -> {
            try {
                // Wait 1sec to let the Instance Thread (below) get trapped first and find the Kafka Source Thread
                Thread.sleep(1000);
                Thread runner = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "Kafka Source Thread".equals(t.getName()))
                        .findFirst()
                        .orElse(null);

                if (runner != null) {
                    // This instantly forces a java.lang.InterruptedException inside the consumer loop
                    runner.interrupt();
                }
            } catch (Exception e) {}
        });
        crashSimulator.start();

        // THE TRAP: We simulate the Instance Thread getting stuck on Pulsar I/O
        boolean wasInterrupted = false;
        try {
            // Simulate sendOutputMessage() hanging indefinitely due to a bad network
            Thread.sleep(10000);
            Assert.fail("Test failed: The instance thread was ignored and never interrupted.");
        } catch (InterruptedException e) {
            // It actively reached out and snapped this thread out of its deadlock
            System.out.println("BUG FIXED: The stuck instance thread was successfully interrupted!");
            wasInterrupted = true;
        }
        Assert.assertTrue(wasInterrupted, "The thread should have been interrupted by the dying consumer.");
    }
}