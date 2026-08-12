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
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.functions.api.Record;
import org.awaitility.Awaitility;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Proves the full sink path: messages produced to a real Pulsar broker running in a container,
 * consumed, handed to {@link AeronSink}, and received by a real Aeron subscriber.
 *
 * <p>This is the shape of the motivating use case — a Flink job writing aggregates to a Pulsar
 * topic that latency-sensitive Aeron consumers then read.
 *
 * <p>Only the broker is containerised. An Aeron client reaches its media driver through
 * memory-mapped files, so the Aeron leg runs against a local driver over IPC. The consume loop
 * here stands in for the function framework, which is what calls {@code write()} in production.
 */
public class AeronSinkContainerTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1002;
    private static final long TIMEOUT_SECONDS = 60L;
    private static final int LARGER_THAN_MTU = 64 * 1024;

    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private PulsarAdmin admin;
    private Producer<byte[]> producer;
    private String topicName;

    private Path aeronDir;
    private MediaDriver mediaDriver;
    private Aeron subscriberClient;
    private Subscription subscription;
    private Thread pollThread;
    private final AtomicBoolean polling = new AtomicBoolean();
    private List<String> received;

    private AeronSink sink;
    private Thread pumpThread;
    private volatile Exception pumpFailure;

    @BeforeMethod
    public void setUp() throws Exception {
        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE))
                // Starts the functions worker AND the stream storage (BookKeeper table) service.
                // The container's default command appends "--no-functions-worker -nss", and it is
                // the -nss ("no stream storage") that would leave the state store absent — which
                // the source's archive mode requires for checkpointing.
                .withFunctionsWorker()
                .withStartupTimeout(Duration.ofMinutes(5));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();
        admin = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarContainer.getHttpServiceUrl())
                .build();

        Awaitility.await("public tenant created")
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> admin.tenants().getTenants().contains("public"));

        topicName = "persistent://public/default/aggregates-" + System.nanoTime();
        producer = pulsarClient.newProducer(Schema.BYTES).topic(topicName).create();

        received = new CopyOnWriteArrayList<>();
        Path parent = Path.of(System.getProperty("aeron.test.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(parent);
        aeronDir = Files.createTempDirectory(parent, "aeron-sink-ct-");
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(aeronDir.toString())
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (pumpThread != null) {
            pumpThread.interrupt();
            try {
                pumpThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
        polling.set(false);
        if (pollThread != null) {
            try {
                pollThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
        closeQuietly(sink);
        sink = null;
        closeQuietly(subscription);
        closeQuietly(subscriberClient);
        closeQuietly(mediaDriver);
        closeQuietly(producer);
        closeQuietly(pulsarClient);
        closeQuietly(admin);
        if (pulsarContainer != null) {
            pulsarContainer.stop();
            pulsarContainer = null;
        }
        deleteRecursively(aeronDir);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Ignored during teardown.
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    // Best effort.
                }
            });
        } catch (Exception e) {
            // Best effort.
        }
    }

    private void startSubscriber() {
        subscriberClient = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        subscription = subscriberClient.addSubscription(CHANNEL, STREAM_ID);

        polling.set(true);
        pollThread = new Thread(() -> {
            FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) -> {
                byte[] payload = new byte[length];
                buffer.getBytes(offset, payload);
                received.add(new String(payload, StandardCharsets.UTF_8));
            });
            SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);
            while (polling.get()) {
                idle.idle(subscription.poll(assembler, 100));
            }
        }, "aeron-sink-ct-subscriber");
        pollThread.setDaemon(true);
        pollThread.start();

        Awaitility.await("subscription connected")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> subscription.isConnected());
    }

    /** Wraps a consumed Pulsar message as a Record, the way the framework does for a sink. */
    private static Record<byte[]> asRecord(Message<byte[]> message, Consumer<byte[]> consumer) {
        return new Record<>() {
            @Override
            public byte[] getValue() {
                return message.getValue();
            }

            @Override
            public Optional<String> getKey() {
                return Optional.ofNullable(message.getKey());
            }

            @Override
            public void ack() {
                consumer.acknowledgeAsync(message);
            }

            @Override
            public void fail() {
                consumer.negativeAcknowledge(message);
            }
        };
    }

    /**
     * Opens the sink, which creates the Aeron publication.
     *
     * <p>Must run before {@link #startSubscriber()}: a subscription only reports itself
     * connected once a matching publication exists, so starting the subscriber first would
     * wait for something that has not been created yet.
     */
    private void openSink(Map<String, Object> config) throws Exception {
        sink = new AeronSink();
        sink.open(config, null);
    }

    /** Starts a loop that pumps consumed Pulsar records into the sink. */
    private void startPump() throws Exception {
        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topicName)
                .subscriptionName("aeron-sink-ct")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        pumpThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Message<byte[]> message = consumer.receive(2, TimeUnit.SECONDS);
                    if (message != null) {
                        sink.write(asRecord(message, consumer));
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    pumpFailure = e;
                }
            } finally {
                // Owned by this thread, so closed here. Relying on pulsarClient.close() to
                // sweep it up leaves the subscription open if the pump exits early, which
                // makes teardown slower and flakier.
                try {
                    consumer.close();
                } catch (Exception e) {
                    // Ignored: teardown must not mask a real test failure.
                }
            }
        }, "aeron-sink-ct-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("channel", CHANNEL);
        config.put("streamId", STREAM_ID);
        config.put("useEmbeddedMediaDriver", false);
        config.put("aeronDirectoryName", mediaDriver.aeronDirectoryName());
        config.put("idleStrategy", "sleeping");
        return config;
    }

    private void awaitReceived(int count) {
        Awaitility.await("Aeron subscriber received " + count + " messages")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    if (pumpFailure != null) {
                        throw new AssertionError("Pumping records into the sink failed", pumpFailure);
                    }
                    return received.size() >= count;
                });
    }

    @Test
    public void testPulsarMessagesReachAnAeronSubscriber() throws Exception {
        openSink(baseConfig());
        startSubscriber();
        startPump();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String aggregate = "{\"window\":" + i + ",\"symbol\":\"DEMO\",\"vwap\":10" + i + "}";
            sent.add(aggregate);
            producer.send(aggregate.getBytes(StandardCharsets.UTF_8));
        }

        awaitReceived(sent.size());

        assertThat(received).containsExactlyElementsOf(sent);
    }

    @Test
    public void testLargeAggregateIsFragmentedAndArrivesWhole() throws Exception {
        openSink(baseConfig());
        startSubscriber();
        startPump();

        String large = RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
        producer.send(large.getBytes(StandardCharsets.UTF_8));

        awaitReceived(1);

        assertThat(received.get(0)).hasSize(LARGER_THAN_MTU);
        assertThat(received.get(0)).isEqualTo(large);
    }

    @Test
    public void testKeyedMessagesArriveAsBareValues() throws Exception {
        // Aeron has no header space, so keys cannot survive the hop. Asserting the value is
        // exactly the payload guards against a future change quietly framing the key into it.
        openSink(baseConfig());
        startSubscriber();
        startPump();

        producer.newMessage()
                .key("DEMO")
                .property("window", "42")
                .value("{\"vwap\":101.5}".getBytes(StandardCharsets.UTF_8))
                .send();

        awaitReceived(1);

        assertThat(received.get(0)).isEqualTo("{\"vwap\":101.5}");
    }
}
