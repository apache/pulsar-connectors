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
package org.apache.pulsar.io.nsq;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test for {@link NSQSource} that exercises the full path from a real NSQ cluster
 * (nsqlookupd + nsqd running in Testcontainers) through the source's push queue.
 *
 * <p>The nsq-j {@code Subscriber} discovers nsqd instances by querying nsqlookupd's HTTP
 * {@code /lookup} endpoint, which returns each nsqd's {@code broadcast_address} and
 * {@code tcp_port}; the client then opens a direct TCP connection to that address. For a client
 * running on the host (outside the Docker network) to reach nsqd, the advertised
 * {@code broadcast_address:tcp_port} must be host-reachable. We therefore run nsqd with
 * {@code --broadcast-address=127.0.0.1} and pin its TCP port 4150 to the same host port, so the
 * advertised {@code 127.0.0.1:4150} resolves back to the container. nsqlookupd's HTTP port and
 * nsqd's HTTP port are ordinary mapped ports because the test addresses them directly.
 */
@Slf4j
public class NSQSourceIntegrationTest {

    private static final DockerImageName NSQ_IMAGE = DockerImageName.parse("nsqio/nsq:v1.3.0");

    private static final String TOPIC = "pulsar-nsq-it";
    private static final String CHANNEL = "pulsar-nsq-it-channel";

    // nsqd's advertised TCP port must equal the host port the consumer dials, so it is pinned.
    private static final int NSQD_HTTP_PORT = 4151;
    private static final int LOOKUPD_TCP_PORT = 4160;
    private static final int LOOKUPD_HTTP_PORT = 4161;

    private static final int EXPECTED_RECORDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private static Network network;
    private static GenericContainer<?> nsqlookupd;
    private static GenericContainer<?> nsqd;
    // Chosen free host port for nsqd's TCP listener. nsqd advertises broadcast_address:tcp_port to
    // the client via nsqlookupd, and the client dials it directly, so the advertised port must be a
    // real, host-reachable port — hence a fixed binding rather than Testcontainers' random mapping.
    // Picking a free port (rather than hard-coding 4150) avoids collisions on busy/shared runners.
    private static int nsqdTcpPort;

    private NSQSource source;
    private ExecutorService readerExecutor;

    @BeforeClass(alwaysRun = true)
    public void beforeClass() {
        nsqdTcpPort = findFreePort();
        network = Network.newNetwork();

        nsqlookupd = new GenericContainer<>(NSQ_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("nsqlookupd")
                .withCommand("/nsqlookupd")
                .withExposedPorts(LOOKUPD_TCP_PORT, LOOKUPD_HTTP_PORT)
                .waitingFor(Wait.forHttp("/ping").forPort(LOOKUPD_HTTP_PORT));
        nsqlookupd.start();

        nsqd = new GenericContainer<>(NSQ_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("nsqd")
                .withCommand(
                        "/nsqd",
                        "--lookupd-tcp-address=nsqlookupd:" + LOOKUPD_TCP_PORT,
                        "--broadcast-address=127.0.0.1",
                        // Listen on the chosen free port so the advertised tcp_port matches the host
                        // binding below.
                        "--tcp-address=0.0.0.0:" + nsqdTcpPort)
                .withExposedPorts(nsqdTcpPort, NSQD_HTTP_PORT)
                // Bind the nsqd TCP port 1:1 to the host so the broadcast_address:tcp_port
                // (127.0.0.1:<nsqdTcpPort>) advertised to the client is reachable. Mutate (not
                // replace) the existing HostConfig so Testcontainers' network and other bindings
                // survive.
                .withCreateContainerCmdModifier(cmd -> {
                    HostConfig hostConfig = cmd.getHostConfig();
                    Ports portBindings = hostConfig.getPortBindings();
                    if (portBindings == null) {
                        portBindings = new Ports();
                    }
                    portBindings.bind(new ExposedPort(nsqdTcpPort),
                            Ports.Binding.bindPort(nsqdTcpPort));
                    hostConfig.withPortBindings(portBindings);
                })
                .waitingFor(Wait.forHttp("/ping").forPort(NSQD_HTTP_PORT));
        nsqd.start();

        log.info("nsqlookupd http {}:{}, nsqd http {}:{}, nsqd tcp {}:{}",
                nsqlookupd.getHost(), nsqlookupd.getMappedPort(LOOKUPD_HTTP_PORT),
                nsqd.getHost(), nsqd.getMappedPort(NSQD_HTTP_PORT),
                nsqd.getHost(), nsqd.getMappedPort(nsqdTcpPort));
    }

    @AfterClass(alwaysRun = true)
    public void afterClass() {
        if (nsqd != null) {
            nsqd.stop();
        }
        if (nsqlookupd != null) {
            nsqlookupd.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @BeforeMethod
    public void setup() {
        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nsq-it-reader");
            t.setDaemon(true);
            return t;
        });
        source = new NSQSource();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        if (readerExecutor != null) {
            // A reader may still be blocked in read(), which never returns null.
            readerExecutor.shutdownNow();
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
    }

    @Test(timeOut = 300_000)
    public void testReadFromNsq() throws Exception {
        // Pre-create the topic and channel so published messages are retained in the channel
        // queue even before the source's consumer connects.
        nsqdPost("/topic/create?topic=" + TOPIC, null);
        nsqdPost("/channel/create?topic=" + TOPIC + "&channel=" + CHANNEL, null);

        // Seed the messages; with the channel already created they are buffered until drained.
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            String payload = "nsq-message-" + i;
            expected.add(payload);
            nsqdPost("/pub?topic=" + TOPIC, payload);
        }

        // Wait until nsqlookupd advertises a producer for the topic, so the source's immediate
        // lookup on subscribe() succeeds.
        Awaitility.await("nsqlookupd to advertise a producer for " + TOPIC)
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> lookupHasProducer(TOPIC));

        SourceContext sourceContext = mock(SourceContext.class);
        source.open(buildConfig(TOPIC, CHANNEL), sourceContext);

        Set<String> received = new HashSet<>();
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            Record<byte[]> record = readOne();
            assertNotNull(record.getValue(), "read() returned a record with a null value");
            String value = new String(record.getValue(), StandardCharsets.UTF_8);
            log.info("Received NSQ record: {}", value);
            received.add(value);
            record.ack();
        }

        assertEquals(received.size(), EXPECTED_RECORDS,
                "Expected " + EXPECTED_RECORDS + " distinct records but got " + received);
        assertTrue(received.containsAll(expected),
                "Received records " + received + " did not contain all published " + expected);
    }

    /**
     * Reads a single record on a bounded worker thread, failing (rather than hanging) if none
     * arrives in time. {@link org.apache.pulsar.io.core.PushSource#read()} blocks forever on an
     * empty queue, so it must never be called on the test thread nor inside an Awaitility
     * assertion (Awaitility cannot interrupt a blocked read).
     */
    private Record<byte[]> readOne() throws Exception {
        Future<Record<byte[]>> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a record from NSQ. The source produced no record; "
                    + "see the nsqd/consumer logs above.", e);
        }
    }

    private Map<String, Object> buildConfig(String topic, String channel) {
        Map<String, Object> config = new HashMap<>();
        config.put("topic", topic);
        config.put("channel", channel);
        config.put("lookupds",
                nsqlookupd.getHost() + ":" + nsqlookupd.getMappedPort(LOOKUPD_HTTP_PORT));
        return config;
    }

    /** Picks an ephemeral free port on the host for nsqd's advertised TCP listener. */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate a free port for nsqd", e);
        }
    }

    private boolean lookupHasProducer(String topic) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + nsqlookupd.getHost() + ":"
                    + nsqlookupd.getMappedPort(LOOKUPD_HTTP_PORT) + "/lookup?topic=" + topic);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return body.contains("\"broadcast_address\"");
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void nsqdPost(String path, String body) throws IOException {
        URL url = new URL("http://" + nsqd.getHost() + ":"
                + nsqd.getMappedPort(NSQD_HTTP_PORT) + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (body != null) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("nsqd POST " + path + " failed with HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                in.readAllBytes();
            }
        } finally {
            conn.disconnect();
        }
    }
}
