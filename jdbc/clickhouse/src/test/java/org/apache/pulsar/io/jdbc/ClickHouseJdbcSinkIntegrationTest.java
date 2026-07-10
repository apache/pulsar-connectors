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
package org.apache.pulsar.io.jdbc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.schema.AutoConsumeSchema;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericAvroSchema;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.source.PulsarRecord;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the ClickHouse JDBC sink, exercised against a real ClickHouse server
 * via Testcontainers.
 *
 * <p>This test reproduces and guards against <a
 * href="https://github.com/apache/pulsar-connectors/issues/32">issue #32</a>: with the old
 * ClickHouse JDBC driver (0.4.6), {@code DatabaseMetaData.getTables()} returns no rows for an
 * existing table on recent ClickHouse servers, so {@link JdbcUtils#getTableId} throws
 * {@code "Not able to find table: pulsar_messages"} during {@code open()}. With the upgraded
 * driver (0.9.8) the table is discovered and the sink writes successfully.
 *
 * <p>Because the failure is driver-side, this test is RED on driver 0.4.6 and GREEN on 0.9.8.
 * The table is created and the results are verified over ClickHouse's HTTP interface rather than
 * over JDBC, so the driver under test is exercised <em>only</em> by the sink itself — keeping the
 * reproduction faithful to the {@code getTableId} failure in #32.
 */
@Slf4j
public class ClickHouseJdbcSinkIntegrationTest {

    private static final DockerImageName CLICKHOUSE_IMAGE =
            DockerImageName.parse("clickhouse/clickhouse-server:24.3");

    private static final String TABLE_NAME = "pulsar_messages";

    private ClickHouseContainer clickhouse;
    private BaseJdbcAutoSchemaSink jdbcSink;

    /**
     * A simple record class matching the {@link #TABLE_NAME} columns.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Foo {
        private String field1;
        private String field2;
        private int field3;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        clickhouse = new ClickHouseContainer(CLICKHOUSE_IMAGE)
                .withStartupTimeout(Duration.ofMinutes(5));
        clickhouse.start();

        // Create the destination table over the HTTP interface (ClickHouse requires an explicit
        // engine). Deliberately not using JDBC here so the driver under test is exercised only by
        // the sink.
        httpQuery("CREATE TABLE " + TABLE_NAME + " ("
                + "    field1 String,"
                + "    field2 String,"
                + "    field3 Int32"
                + ") ENGINE = MergeTree ORDER BY field3");

        Map<String, Object> conf = new HashMap<>();
        conf.put("jdbcUrl", clickhouse.getJdbcUrl());
        conf.put("userName", clickhouse.getUsername());
        conf.put("password", clickhouse.getPassword());
        conf.put("tableName", TABLE_NAME);
        conf.put("key", "field3");
        conf.put("nonKey", "field1,field2");
        // ClickHouse does not support JDBC transactions; run in auto-commit mode.
        conf.put("useTransactions", false);
        // Flush on each write.
        conf.put("batchSize", 1);

        jdbcSink = new ClickHouseJdbcAutoSchemaSink();
        // open() is where issue #32 manifested: getTableId() could not find the table
        // with the old driver. This call must succeed with the upgraded driver.
        jdbcSink.open(conf, null);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (jdbcSink != null) {
            jdbcSink.close();
        }
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    @Test
    public void testOpenDiscoversTableAndWrites() throws Exception {
        // prepare a foo Record
        Foo insertObj = new Foo("ValueOfField1", "ValueOfField2", 3);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        final Record<GenericObject> record = createMockFooRecord(insertObj, future);

        jdbcSink.write(record);
        log.info("executed write");

        // wait for the backend flush to complete and the record to be acked
        Assert.assertTrue(future.get(30, TimeUnit.SECONDS), "record should be acknowledged");

        // value has been written to ClickHouse, read it back over HTTP and verify.
        String result = httpQuery("SELECT field1, field2, field3 FROM " + TABLE_NAME
                + " WHERE field3 = 3 FORMAT TabSeparated").trim();
        Assert.assertEquals(result, insertObj.getField1() + "\t" + insertObj.getField2() + "\t"
                + insertObj.getField3(), "exactly one matching row should have been written");
    }

    /**
     * Runs a query against ClickHouse over its native HTTP interface and returns the response body.
     * Avoids the JDBC driver so the connector's driver is not implicitly relied upon by the harness.
     */
    private String httpQuery(String sql) throws Exception {
        URL url = new URL("http://" + clickhouse.getHost() + ":"
                + clickhouse.getMappedPort(8123) + "/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("X-ClickHouse-User", clickhouse.getUsername());
        conn.setRequestProperty("X-ClickHouse-Key", clickhouse.getPassword());
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        try (InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new RuntimeException("ClickHouse HTTP query failed (" + code + "): " + body);
            }
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private Record<GenericObject> createMockFooRecord(Foo record, CompletableFuture<Boolean> future) {
        Message<GenericRecord> insertMessage = mock(MessageImpl.class);
        AvroSchema<Foo> schema = AvroSchema.of(SchemaDefinition.<Foo>builder()
                .withPojo(Foo.class).withAlwaysAllowNull(true).build());
        AutoConsumeSchema autoConsumeSchema = new AutoConsumeSchema();
        autoConsumeSchema.setSchema(schema);

        byte[] insertBytes = schema.encode(record);

        Record<? extends GenericObject> insertRecord = PulsarRecord.<GenericRecord>builder()
                .message(insertMessage)
                .topicName("fake_topic_name")
                .schema(autoConsumeSchema)
                .ackFunction(() -> future.complete(true))
                .failFunction(() -> future.complete(false))
                .build();

        GenericAvroSchema genericAvroSchema = new GenericAvroSchema(schema.getSchemaInfo());
        when(insertMessage.getValue()).thenReturn(genericAvroSchema.decode(insertBytes));
        when(insertMessage.getProperties()).thenReturn(new HashMap<>());
        return (Record<GenericObject>) insertRecord;
    }
}
