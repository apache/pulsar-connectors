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
package org.apache.pulsar.io.influxdb.v1;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import java.time.Duration;
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
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.source.PulsarRecord;
import org.awaitility.Awaitility;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for the InfluxDB v1 sink, exercised against a real InfluxDB 1.x server via
 * Testcontainers.
 *
 * <p>The v1 sink is a separate code path from v2: it uses the {@code org.influxdb} client, the
 * line-protocol {@code BatchPoints} API, and a database/retention-policy model rather than v2's
 * org/bucket/token model. {@link InfluxDBGenericRecordSinkTest} covers it only against a mocked
 * {@link InfluxDB}, so nothing verifies that InfluxDB accepts the points the sink builds. This
 * test writes through the real client and reads the data back.
 *
 * <p>Authentication is enabled on the container so the {@code enableAuth} branch of
 * {@link InfluxDBBuilderImpl} is exercised too.
 */
@Slf4j
public class InfluxDBGenericRecordSinkIntegrationTest {

    private static final DockerImageName INFLUXDB_IMAGE = DockerImageName.parse("influxdb:1.8");

    private static final int INFLUXDB_PORT = 8086;
    private static final String DATABASE = "test_db";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "adminpassword";

    /** The sink flushes asynchronously once this many records are queued. */
    private static final int BATCH_SIZE = 2;

    private GenericContainer<?> influxdbContainer;
    private InfluxDB queryClient;
    private InfluxDBAbstractSink<GenericRecord> sink;
    private String influxdbUrl;

    /** Fields other than {@code measurement} and {@code tags} become InfluxDB fields. */
    @Data
    public static class Cpu {
        private String measurement;
        private String model;
        private int value;
        private Map<String, String> tags;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() {
        influxdbContainer = new GenericContainer<>(INFLUXDB_IMAGE)
                .withExposedPorts(INFLUXDB_PORT)
                .withEnv("INFLUXDB_DB", DATABASE)
                .withEnv("INFLUXDB_ADMIN_USER", USERNAME)
                .withEnv("INFLUXDB_ADMIN_PASSWORD", PASSWORD)
                .withEnv("INFLUXDB_HTTP_AUTH_ENABLED", "true")
                .waitingFor(Wait.forHttp("/ping").forPort(INFLUXDB_PORT).forStatusCode(204))
                .withStartupTimeout(Duration.ofMinutes(3));
        influxdbContainer.start();

        influxdbUrl = "http://" + influxdbContainer.getHost() + ":"
                + influxdbContainer.getMappedPort(INFLUXDB_PORT);
        queryClient = InfluxDBFactory.connect(influxdbUrl, USERNAME, PASSWORD);
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
        config.put("username", USERNAME);
        config.put("password", PASSWORD);
        config.put("database", DATABASE);
        config.put("consistencyLevel", "ONE");
        config.put("logLevel", "NONE");
        config.put("retentionPolicy", "autogen");
        config.put("gzipEnable", false);
        // Large enough that the timer does not flush for us: the batch-size path must.
        config.put("batchTimeMs", 60_000);
        config.put("batchSize", BATCH_SIZE);

        sink = new InfluxDBGenericRecordSink();
        sink.open(config, null);

        sink.write(cpuRecord("server-1", "us-west", 10));
        sink.write(cpuRecord("server-2", "us-east", 20));

        // The sink flushes on a background executor once batchSize records are queued, so poll
        // rather than assuming the write has landed by the time we query.
        List<List<Object>> rows = Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(this::queryCpuRows, found -> found.size() >= 2);

        assertEquals(rows.size(), 2, "both points should have been written");

        QueryResult.Series series = cpuSeries();
        assertNotNull(series, "the cpu measurement should exist");
        int hostIdx = series.getColumns().indexOf("host");
        int valueIdx = series.getColumns().indexOf("value");
        int modelIdx = series.getColumns().indexOf("model");
        int timeIdx = series.getColumns().indexOf("time");

        List<Object> serverOne = rows.stream()
                .filter(r -> "server-1".equals(r.get(hostIdx)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for host=server-1"));
        assertEquals(((Number) serverOne.get(valueIdx)).longValue(), 10L);
        assertEquals(serverOne.get(modelIdx), "lenovo");
        assertNotNull(serverOne.get(timeIdx), "timestamp should have been persisted");
    }

    private QueryResult.Series cpuSeries() {
        QueryResult result = queryClient.query(new Query("SELECT * FROM cpu", DATABASE));
        if (result.getResults() == null || result.getResults().get(0).getSeries() == null) {
            return null;
        }
        return result.getResults().get(0).getSeries().get(0);
    }

    private List<List<Object>> queryCpuRows() {
        QueryResult.Series series = cpuSeries();
        return series == null ? List.of() : series.getValues();
    }

    @SuppressWarnings("unchecked")
    private Record<GenericRecord> cpuRecord(String host, String region, int value) {
        Cpu cpu = new Cpu();
        cpu.setMeasurement("cpu");
        cpu.setModel("lenovo");
        cpu.setValue(value);
        cpu.setTags(Map.of("host", host, "region", region));

        AvroSchema<Cpu> schema = AvroSchema.of(Cpu.class);
        GenericSchema<GenericRecord> genericSchema = GenericSchemaImpl.of(schema.getSchemaInfo());

        Message<GenericRecord> message = Mockito.mock(MessageImpl.class);
        Mockito.when(message.getValue()).thenReturn(genericSchema.decode(schema.encode(cpu)));

        return PulsarRecord.<GenericRecord>builder()
                .message(message)
                .topicName("influx_cpu")
                .build();
    }
}
