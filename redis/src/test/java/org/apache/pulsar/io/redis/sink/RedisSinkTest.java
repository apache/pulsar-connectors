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
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.instance.SinkRecord;
import org.apache.pulsar.io.core.SinkContext;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * RedisSink test against a real redis-server started via Testcontainers.
 */
@Slf4j
public class RedisSinkTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final int REDIS_PORT = 6379;

    private static final int REDIS_DATABASE = 1;

    private static final String REDIS_PASSWORD = "redis-sink-test";

    private GenericContainer<?> redisContainer;

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Test
    public void testOpenAndWriteSink() throws Exception {
        startRedis();
        Map<String, Object> configs = sinkConfigs("");

        writeRecord(configs, "fakeKey", "fakeValue");

        assertEquals(awaitValue(null, "fakeKey"), "fakeValue");
    }

    @Test
    public void testOpenAndWriteSinkWithPassword() throws Exception {
        startRedis("--requirepass", REDIS_PASSWORD);
        Map<String, Object> configs = sinkConfigs(REDIS_PASSWORD);

        writeRecord(configs, "fakeKey", "fakeValue");

        assertEquals(awaitValue(REDIS_PASSWORD, "fakeKey"), "fakeValue");
    }

    private void startRedis(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "redis-server";
        System.arraycopy(args, 0, command, 1, args.length);
        redisContainer = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(REDIS_PORT)
            .withCommand(command)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
        redisContainer.start();
    }

    private Map<String, Object> sinkConfigs(String password) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("redisHosts", redisContainer.getHost() + ":" + redisContainer.getMappedPort(REDIS_PORT));
        configs.put("redisPassword", password);
        configs.put("redisDatabase", String.valueOf(REDIS_DATABASE));
        configs.put("clientMode", "Standalone");
        configs.put("operationTimeout", "3000");
        configs.put("batchSize", "10");
        return configs;
    }

    private void writeRecord(Map<String, Object> configs, String key, String value) throws Exception {
        RedisSink sink = new RedisSink();
        SinkContext sinkContext = Mockito.mock(SinkContext.class);
        sink.open(configs, sinkContext);
        try {
            sink.write(build("fakeTopic", key, value));
            log.info("executed write");
            // the record is flushed by the sink's batch timer, so wait until it is visible
            awaitValue((String) configs.get("redisPassword"), key);
        } finally {
            sink.close();
        }
    }

    private String awaitValue(String password, String key) throws InterruptedException {
        RedisURI.Builder uri = RedisURI.builder()
            .withHost(redisContainer.getHost())
            .withPort(redisContainer.getMappedPort(REDIS_PORT))
            .withDatabase(REDIS_DATABASE);
        if (password != null && !password.isEmpty()) {
            uri.withPassword(password.toCharArray());
        }
        RedisClient client = RedisClient.create(uri.build());
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            String stored = connection.sync().get(key);
            while (stored == null && System.nanoTime() < deadline) {
                Thread.sleep(100);
                stored = connection.sync().get(key);
            }
            return stored;
        } finally {
            client.shutdown();
        }
    }

    private Record<byte[]> build(String topic, String key, String value) {
        // prepare a SinkRecord
        SinkRecord<byte[]> record = new SinkRecord<>(new Record<byte[]>() {

            @Override
            public Optional<String> getKey() {
                return Optional.ofNullable(key);
            }

            @Override
            public byte[] getValue() {
                return value.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Optional<String> getDestinationTopic() {
                if (topic != null) {
                    return Optional.of(topic);
                } else {
                    return Optional.empty();
                }
            }
        }, value.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
