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
package org.apache.pulsar.io.redis.sink;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import io.lettuce.core.RedisConnectionException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.redis.RedisSession;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test exercising RedisSink's TLS support against a real TLS-terminating
 * redis-server started via Testcontainers, rather than only building {@code SslOptions} in
 * isolation like {@link org.apache.pulsar.io.redis.RedisSessionTest} does. This proves the
 * redisTlsTrustStorePath/redisTlsTrustStoreType/redisTlsVerifyPeer config actually completes a
 * live handshake, and that an untrusted server certificate genuinely fails the connection rather
 * than being silently accepted.
 */
@Slf4j
public class RedisSinkTlsIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final int REDIS_TLS_PORT = 6390;

    // Matches the password used to generate both truststore fixtures; see tls/README.md.
    private static final String TEST_TRUSTSTORE_PASSWORD = "changeit";

    private GenericContainer<?> redisContainer;

    @BeforeMethod
    public void setUp() {
        redisContainer = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_TLS_PORT)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("tls/redis-integration-server.crt"), "/tls/server.crt")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("tls/redis-integration-server.key"), "/tls/server.key")
            .withCommand("redis-server", "--port", "0", "--tls-port", String.valueOf(REDIS_TLS_PORT),
                "--tls-cert-file", "/tls/server.crt", "--tls-key-file", "/tls/server.key",
                "--tls-ca-cert-file", "/tls/server.crt", "--tls-auth-clients", "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections tls.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
        redisContainer.start();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Test
    public void testTlsWriteLandsInRedis() throws Exception {
        Map<String, Object> configs = baseConfig();
        configs.put("redisTlsTrustStorePath", getFile("tls/redis-integration-truststore.jks").getAbsolutePath());

        RedisSink sink = new RedisSink();
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        sink.open(configs, sinkContext);

        CompletableFuture<Void> acked = new CompletableFuture<>();
        try {
            sink.write(build("fakeTopic", "fakeKey", "fakeValue", acked));
            acked.get(5, TimeUnit.SECONDS);
            log.info("executed TLS write");
        } finally {
            sink.close();
        }

        RedisSinkConfig verifyConfig = new RedisSinkConfig();
        verifyConfig.setRedisHosts((String) configs.get("redisHosts"));
        verifyConfig.setRedisDatabase(1);
        verifyConfig.setClientMode("Standalone");
        verifyConfig.setRedisUseTls(true);
        verifyConfig.setRedisTlsTrustStorePath((String) configs.get("redisTlsTrustStorePath"));
        verifyConfig.setRedisTlsTrustStorePassword((String) configs.get("redisTlsTrustStorePassword"));
        verifyConfig.validate();

        RedisSession verifySession = RedisSession.create(verifyConfig);
        try {
            byte[] value = verifySession.asyncCommands().get("fakeKey".getBytes(StandardCharsets.UTF_8))
                .get(5, TimeUnit.SECONDS);
            assertEquals(new String(value, StandardCharsets.UTF_8), "fakeValue");
        } finally {
            verifySession.close();
        }
    }

    @Test
    public void testTlsHandshakeFailsWhenServerCertNotTrusted() throws Exception {
        Map<String, Object> configs = baseConfig();
        // A truststore that trusts a different, unrelated self-signed cert: the handshake must
        // fail rather than silently accept the container's actual certificate.
        configs.put("redisTlsTrustStorePath",
            getFile("tls/redis-test-truststore.jks").getAbsolutePath());

        RedisSink sink = new RedisSink();
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        RedisConnectionException ex =
            expectThrows(RedisConnectionException.class, () -> sink.open(configs, sinkContext));

        // RedisConnectionException alone is Lettuce's generic connect-time wrapper and doesn't
        // prove the failure was actually about the untrusted certificate; walk the cause chain
        // for the JDK's PKIX path-building failure to be sure a future regression that breaks
        // trust validation differently wouldn't still pass this test.
        Throwable cause = ex;
        while (cause != null && !(cause instanceof SSLHandshakeException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "Expected an SSLHandshakeException in the cause chain of: " + ex);
        assertTrue(cause.getMessage().contains("unable to find valid certification path"),
            "Unexpected SSLHandshakeException message: " + cause.getMessage());
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("redisHosts", redisContainer.getHost() + ":" + redisContainer.getMappedPort(REDIS_TLS_PORT));
        configs.put("redisDatabase", "1");
        configs.put("clientMode", "Standalone");
        configs.put("redisUseTls", "true");
        configs.put("operationTimeout", "3000");
        configs.put("batchSize", "10");
        configs.put("redisTlsTrustStorePassword", TEST_TRUSTSTORE_PASSWORD);
        return configs;
    }

    private Record<byte[]> build(String topic, String key, String value, CompletableFuture<Void> acked) {
        return new Record<byte[]>() {
            @Override
            public Optional<String> getKey() {
                return Optional.of(key);
            }

            @Override
            public byte[] getValue() {
                return value.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Optional<String> getDestinationTopic() {
                return Optional.of(topic);
            }

            @Override
            public void ack() {
                acked.complete(null);
            }

            @Override
            public void fail() {
                acked.completeExceptionally(new RuntimeException("record was not acked"));
            }
        };
    }

    private File getFile(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(name).getFile());
    }
}
