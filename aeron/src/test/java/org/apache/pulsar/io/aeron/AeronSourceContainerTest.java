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
import static org.assertj.core.api.Assertions.fail;
import io.aeron.Aeron;
import io.aeron.Publication;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.UnsafeBuffer;
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
 * Proves the full path: an Aeron publication, through {@link AeronSource}, into a real
 * Pulsar broker running in a container, and back out through a consumer.
 *
 * <p>Only the broker is containerised. Aeron clients reach their media driver through
 * memory-mapped files in a shared directory, so a driver inside a container cannot serve a
 * client on the host; the Aeron leg therefore runs against a local driver over IPC. What the
 * container adds over {@link AeronSourceIntegrationTest} is the assertion that payloads, keys
 * and Aeron metadata survive a real publish and a real consume, rather than only reaching an
 * in-memory list.
 *
 * <p>The drain loop here stands in for the function framework, which is what calls
 * {@code read()} and publishes in production.
 */
public class AeronSourceContainerTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private static final String CHANNEL = "aeron:ipc";
    private static final String PUBLICATION_CHANNEL = "aeron:ipc?term-length=1048576";
    private static final int STREAM_ID = 1001;
    private static final long TIMEOUT_SECONDS = 60L;
    private static final int LARGER_THAN_MTU = 64 * 1024;

    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private PulsarAdmin admin;
    private Producer<byte[]> producer;

    private Path aeronDir;
    private MediaDriver mediaDriver;
    private Aeron publisherClient;
    private Publication publication;

    private AeronSource source;
    private Thread drainThread;
    private volatile Exception drainFailure;
    private String topicName;

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

        // Bootstrap creates the "public" tenant and the "public/default" namespace
        // asynchronously, and the container's wait strategy can return before either lands.
        // Waiting on the tenant alone is not enough: the namespace is created after it, so a
        // producer can still fail with "Namespace not found". Enabling the functions worker
        // shifted the timing enough to make that visible in CI.
        Awaitility.await("public tenant created")
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> admin.tenants().getTenants().contains("public"));
        Awaitility.await("public/default namespace created")
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .ignoreExceptions()
                .until(() -> admin.namespaces().getNamespaces("public").contains("public/default"));

        topicName = "persistent://public/default/aeron-in-" + System.nanoTime();
        producer = pulsarClient.newProducer(Schema.BYTES).topic(topicName).create();

        Path parent = Path.of(System.getProperty("aeron.test.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(parent);
        aeronDir = Files.createTempDirectory(parent, "aeron-source-ct-");
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(aeronDir.toString())
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (drainThread != null) {
            drainThread.interrupt();
            try {
                drainThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        closeQuietly(publication);
        closeQuietly(publisherClient);
        closeQuietly(source);
        source = null;
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
            // Ignored during teardown so it cannot mask the test result.
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

    /**
     * Starts the source and drains it into Pulsar, mirroring what the function framework
     * does in production: read a record, publish it, then ack it.
     */
    private void startSourceAndDrain(Map<String, Object> config) throws Exception {
        source = new AeronSource();
        source.open(config, null);

        drainThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Record<byte[]> record = source.read();
                    var message = producer.newMessage().value(record.getValue());
                    record.getKey().ifPresent(message::key);
                    record.getProperties().forEach(message::property);
                    message.send();
                    record.ack();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Surface a genuine publish failure instead of letting the test time out
                // with a misleading "no messages arrived".
                drainFailure = e;
            }
        }, "aeron-ct-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("channel", CHANNEL);
        config.put("streamId", STREAM_ID);
        // Attach to the driver the test owns, so the publisher and the source share it.
        config.put("useEmbeddedMediaDriver", false);
        config.put("aeronDirectoryName", mediaDriver.aeronDirectoryName());
        config.put("idleStrategy", "sleeping");
        return config;
    }

    private void connectPublisher() {
        publisherClient = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
        publication = publisherClient.addPublication(PUBLICATION_CHANNEL, STREAM_ID);
        Awaitility.await("publication connected")
                .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> publication.isConnected());
    }

    private void offer(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadlineNanos) {
            long result = publication.offer(buffer, 0, payload.length);
            if (result > 0) {
                return;
            }
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                fail("Aeron publication is unusable, offer returned " + result);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Timed out offering a " + payload.length + " byte payload to Aeron");
    }

    private List<Message<byte[]>> consume(int expected) throws Exception {
        List<Message<byte[]>> received = new ArrayList<>();
        try (Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topicName)
                .subscriptionName("aeron-ct-" + System.nanoTime())
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (received.size() < expected && System.nanoTime() < deadline) {
                if (drainFailure != null) {
                    throw new AssertionError("Draining the source into Pulsar failed", drainFailure);
                }
                Message<byte[]> message = consumer.receive(2, TimeUnit.SECONDS);
                if (message != null) {
                    received.add(message);
                    consumer.acknowledge(message);
                }
            }
        }
        return received;
    }

    @Test
    public void testAeronMessagesReachAPulsarTopic() throws Exception {
        startSourceAndDrain(baseConfig());
        connectPublisher();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String payload = "{\"seq\":" + i + ",\"symbol\":\"DEMO\"}";
            sent.add(payload);
            offer(payload.getBytes(StandardCharsets.UTF_8));
        }

        List<Message<byte[]>> received = consume(sent.size());

        assertThat(received).hasSize(sent.size());
        List<String> values = received.stream()
                .map(m -> new String(m.getValue(), StandardCharsets.UTF_8))
                .toList();
        assertThat(values).containsExactlyElementsOf(sent);
    }

    @Test
    public void testAeronMetadataSurvivesAsMessageProperties() throws Exception {
        startSourceAndDrain(baseConfig());
        connectPublisher();

        offer("payload".getBytes(StandardCharsets.UTF_8));

        List<Message<byte[]>> received = consume(1);

        assertThat(received).hasSize(1);
        Map<String, String> properties = received.get(0).getProperties();
        assertThat(properties)
                .containsEntry(AeronRecord.PROP_SESSION_ID, Integer.toString(publication.sessionId()))
                .containsEntry(AeronRecord.PROP_STREAM_ID, Integer.toString(STREAM_ID))
                .containsEntry(AeronRecord.PROP_CHANNEL, CHANNEL)
                .containsKey(AeronRecord.PROP_POSITION)
                .containsKey(AeronRecord.PROP_INGEST_TS);
    }

    @Test
    public void testKeyBySessionIdReachesPulsarAsTheMessageKey() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("keyBySessionId", true);
        startSourceAndDrain(config);
        connectPublisher();

        offer("keyed".getBytes(StandardCharsets.UTF_8));

        List<Message<byte[]>> received = consume(1);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getKey()).isEqualTo(Integer.toString(publication.sessionId()));
    }

    @Test
    public void testFragmentedMessageArrivesWholeInPulsar() throws Exception {
        startSourceAndDrain(baseConfig());
        connectPublisher();

        String large = RandomStringUtils.insecure().nextAlphanumeric(LARGER_THAN_MTU);
        offer(large.getBytes(StandardCharsets.UTF_8));

        List<Message<byte[]>> received = consume(1);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getValue()).hasSize(LARGER_THAN_MTU);
        assertThat(new String(received.get(0).getValue(), StandardCharsets.UTF_8)).isEqualTo(large);
    }
}
