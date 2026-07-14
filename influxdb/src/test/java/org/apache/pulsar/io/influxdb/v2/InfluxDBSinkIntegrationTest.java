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
package org.apache.pulsar.io.influxdb.v2;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.schema.JSONSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.source.PulsarRecord;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for the InfluxDB v2 sink, exercised against a real InfluxDB server via
 * Testcontainers.
 *
 * <p>The existing {@link InfluxDBSinkTest} verifies only that the sink calls
 * {@code WriteApiBlocking.writePoints()} on a mock. That leaves the parts most likely to break
 * untested: whether the points the sink builds are actually accepted by InfluxDB, and whether the
 * measurement, tags, fields and timestamp survive the round trip. This test writes through the
 * real client and reads the data back with Flux.
 */
@Slf4j
public class InfluxDBSinkIntegrationTest {

    private static final DockerImageName INFLUXDB_IMAGE = DockerImageName.parse("influxdb:2.7");

    private static final int INFLUXDB_PORT = 8086;
    private static final String ORG = "example-org";
    private static final String BUCKET = "example-bucket";
    private static final String TOKEN = "test-token-0123456789";

    /** The sink flushes asynchronously once this many records are queued. */
    private static final int BATCH_SIZE = 2;

    private GenericContainer<?> influxdbContainer;
    private InfluxDBClient queryClient;
    private InfluxDBSink sink;
    private String influxdbUrl;

    /** Mirrors the POJO used by the mock-based test, so both cover the same record shape. */
    @Data
    public static class Cpu {
        private String measurement;
        private long timestamp;
        private Map<String, String> tags;

        @org.apache.avro.reflect.AvroSchema("{\"type\": \"map\", \"values\": "
                + "[\"string\", \"int\", \"bytes\", \"long\",\"float\", \"double\", \"boolean\"]}")
        private Map<String, Object> fields;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        influxdbContainer = new GenericContainer<>(INFLUXDB_IMAGE)
                .withExposedPorts(INFLUXDB_PORT)
                .withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
                .withEnv("DOCKER_INFLUXDB_INIT_USERNAME", "admin")
                .withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", "adminpassword")
                .withEnv("DOCKER_INFLUXDB_INIT_ORG", ORG)
                .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", BUCKET)
                .withEnv("DOCKER_INFLUXDB_INIT_ADMIN_TOKEN", TOKEN)
                .waitingFor(Wait.forHttp("/health").forPort(INFLUXDB_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3));
        influxdbContainer.start();

        influxdbUrl = "http://" + influxdbContainer.getHost() + ":"
                + influxdbContainer.getMappedPort(INFLUXDB_PORT);
        queryClient = InfluxDBClientFactory.create(influxdbUrl, TOKEN.toCharArray(), ORG);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (sink != null) {
            sink.close();
        }
        if (queryClient != null) {
            queryClient.close();
        }
        if (influxdbContainer != null) {
            influxdbContainer.stop();
        }
    }

    @Test(timeOut = 300_000)
    public void testWritesReachInfluxDb() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("influxdbUrl", influxdbUrl);
        config.put("token", TOKEN);
        config.put("organization", ORG);
        config.put("bucket", BUCKET);
        config.put("precision", "ns");
        config.put("logLevel", "NONE");
        config.put("gzipEnable", false);
        // Large enough that the timer does not flush for us: the batch-size path must.
        config.put("batchTimeMs", 60_000);
        config.put("batchSize", BATCH_SIZE);

        sink = new InfluxDBSink();
        sink.open(config, null);

        long baseTimestamp = Instant.now().toEpochMilli() * 1_000_000L;
        sink.write(cpuRecord("server-1", "us-west", 10, baseTimestamp));
        sink.write(cpuRecord("server-2", "us-east", 20, baseTimestamp + 1));

        // The sink flushes on a background executor once batchSize records are queued, so poll
        // rather than assuming the write has landed by the time we query.
        List<FluxRecord> records = Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(this::queryCpuRecords, found -> found.size() >= 2);

        assertEquals(records.size(), 2, "both points should have been written");

        FluxRecord first = records.stream()
                .filter(r -> "server-1".equals(r.getValueByKey("host")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for host=server-1"));
        assertEquals(first.getMeasurement(), "cpu");
        assertEquals(first.getValueByKey("region"), "us-west");
        assertEquals(((Number) first.getValue()).longValue(), 10L);
        assertNotNull(first.getTime(), "timestamp should have been persisted");
    }

    /** Reads back the {@code value} field of the {@code cpu} measurement. */
    private List<FluxRecord> queryCpuRecords() {
        String flux = "from(bucket: \"" + BUCKET + "\") "
                + "|> range(start: -1h) "
                + "|> filter(fn: (r) => r._measurement == \"cpu\") "
                + "|> filter(fn: (r) => r._field == \"value\")";
        List<FluxTable> tables = queryClient.getQueryApi().query(flux, ORG);
        return tables.stream().flatMap(t -> t.getRecords().stream()).toList();
    }

    @SuppressWarnings("unchecked")
    private Record<GenericRecord> cpuRecord(String host, String region, int value, long timestamp) {
        Cpu cpu = new Cpu();
        cpu.setMeasurement("cpu");
        cpu.setTimestamp(timestamp);
        cpu.setTags(Map.of("host", host, "region", region));
        cpu.setFields(Map.of("value", value, "model", "lenovo"));

        JSONSchema<Cpu> schema = JSONSchema.of(Cpu.class);
        GenericSchema<GenericRecord> genericSchema = GenericSchemaImpl.of(schema.getSchemaInfo());

        Message<GenericRecord> message = Mockito.mock(MessageImpl.class);
        Mockito.when(message.getValue()).thenReturn(genericSchema.decode(schema.encode(cpu)));

        return PulsarRecord.<GenericRecord>builder()
                .message(message)
                .topicName("influx_cpu")
                .build();
    }
}
