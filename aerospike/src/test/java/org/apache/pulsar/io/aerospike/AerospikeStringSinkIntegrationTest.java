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
package org.apache.pulsar.io.aerospike;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for {@link AerospikeStringSink}, exercised against a real Aerospike
 * Community Edition server started via Testcontainers.
 *
 * <p>Records are pushed through the sink and then read back with an independent
 * {@link AerospikeClient} to assert the bin values round-trip correctly. This is the first
 * test for the {@code aerospike} module (see
 * <a href="https://github.com/apache/pulsar-connectors/issues/47">issue #47</a>).
 *
 * <p>The container startup is bounded with an explicit two-minute timeout on a log-message wait
 * strategy so a stalled image pull can never hang the CI job.
 */
@Slf4j
public class AerospikeStringSinkIntegrationTest {

    // Aerospike Community Edition; the default packaged config exposes namespace "test" on port 3000.
    private static final DockerImageName AEROSPIKE_IMAGE =
            DockerImageName.parse("aerospike/aerospike-server:6.4.0.2");

    private static final int AEROSPIKE_PORT = 3000;
    private static final String NAMESPACE = "test";
    private static final String SET = "pulsar";
    private static final String BIN = "value";

    private GenericContainer<?> aerospike;
    private AerospikeClient client;
    private AerospikeStringSink sink;

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        aerospike = new GenericContainer<>(AEROSPIKE_IMAGE)
                .withExposedPorts(AEROSPIKE_PORT)
                // "service ready: soon there will be cake!" is logged once the node is accepting
                // client requests. Bounded so a stalled pull/start cannot hang the job.
                .waitingFor(Wait.forLogMessage(".*service ready.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        aerospike.start();

        final String host = aerospike.getHost();
        final int port = aerospike.getMappedPort(AEROSPIKE_PORT);

        // Independent client used only to read results back (never to write). The node logs
        // "service ready" slightly before its partitions finish rebalancing, so the first
        // connection attempts can fail with "node is not yet fully initialized" -- retry until
        // the client connects rather than failing fast.
        Awaitility.await("aerospike client connected")
                .atMost(60, TimeUnit.SECONDS)
                .ignoreExceptions()
                .until(() -> {
                    if (client == null) {
                        client = new AerospikeClient(host, port);
                    }
                    return client.isConnected();
                });

        // Even once connected, the namespace partition map can still be settling; a write issued
        // too early fails with INVALID_NODE ("not yet fully initialized"). Probe with a real
        // write+read round-trip until the namespace actually accepts operations, so the sink's
        // own client (opened next) starts against a fully-initialized cluster.
        final Key probe = new Key(NAMESPACE, SET, "__probe__");
        Awaitility.await("aerospike namespace ready")
                .atMost(60, TimeUnit.SECONDS)
                .ignoreExceptions()
                .until(() -> {
                    client.put(null, probe, new Bin(BIN, "ready"));
                    com.aerospike.client.Record r = client.get(null, probe);
                    return r != null && "ready".equals(r.getString(BIN));
                });
        client.delete(null, probe);

        Map<String, Object> config = new HashMap<>();
        config.put("seedHosts", host + ":" + port);
        config.put("keyspace", NAMESPACE);
        config.put("keySet", SET);
        config.put("columnName", BIN);

        sink = new AerospikeStringSink();
        sink.open(config, mock(SinkContext.class));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (client != null) {
            client.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Test(timeOut = 300_000)
    public void testWriteAndReadBack() throws Exception {
        final int numRecords = 5;

        // Push records through the sink. The sink acks a record only after the server confirms
        // the async write, so we use the ack callbacks to know when the data is durable.
        CompletableFuture<?>[] futures = new CompletableFuture[numRecords];
        for (int i = 0; i < numRecords; i++) {
            final int idx = i;
            final String key = "key-" + i;
            final String value = "value-" + i;
            futures[i] = new CompletableFuture<>();
            Record<byte[]> record = new Record<byte[]>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.of(key);
                }

                @Override
                public byte[] getValue() {
                    return value.getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public void ack() {
                    futures[idx].complete(null);
                }

                @Override
                public void fail() {
                    futures[idx].completeExceptionally(new RuntimeException("Record " + idx + " failed"));
                }
            };
            sink.write(record);
        }

        // Wait for every record to be acknowledged (i.e. written) rather than sleeping.
        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);

        // Read each record back with an independent client and assert the bin value round-trips.
        for (int i = 0; i < numRecords; i++) {
            final int idx = i;
            final Key key = new Key(NAMESPACE, SET, "key-" + idx);
            final String expected = "value-" + idx;
            Awaitility.await("record key-" + idx + " visible")
                    .atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        com.aerospike.client.Record read = client.get(null, key);
                        assertNotNull(read, "record for key-" + idx + " should exist in Aerospike");
                        assertEquals(read.getString(BIN), expected,
                                "bin value for key-" + idx + " should match what was written");
                    });
        }
    }
}
