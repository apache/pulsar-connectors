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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for the OpenMLDB JDBC sink, exercised against a real OpenMLDB cluster via
 * Testcontainers, mirroring {@code ClickHouseJdbcSinkIntegrationTest}: create the table, open the
 * sink, write an Avro {@code GenericRecord} through it, read the row back and assert.
 *
 * <p><b>Why this test is Linux-x86-64-only.</b> The OpenMLDB JDBC driver is a thin JNI wrapper over
 * a native SDK ({@code libsql_jsdk.so}) shipped in {@code openmldb-native}, which publishes no
 * macOS/arm64 binary — the driver cannot initialise on Apple Silicon, so this test only runs on a
 * Linux x86-64 host (and in CI, which is Linux x86-64). See issue #46.
 *
 * <p><b>Why cluster mode + host networking.</b> The OpenMLDB JDBC driver mandates {@code zk} and
 * {@code zkPath} params ({@code jdbc:openmldb:///db?zk=host:port&zkPath=/path}); the host after
 * {@code //} is ignored. It reads tablet/nameserver endpoints from ZooKeeper and dials them
 * directly, so OpenMLDB must run in <b>cluster mode</b> (standalone has no zk) and its advertised
 * endpoints must be reachable from the test JVM. Host networking (Linux only) is the simplest way
 * to guarantee that — the container binds the OpenMLDB ports on localhost and zk advertises
 * host-reachable addresses.
 *
 * <p>The image tag matches the pinned OpenMLDB client version; the sink-driving logic mirrors the
 * ClickHouse test, minus key/nonKey config and label-based ResultSet getters, which the OpenMLDB
 * driver does not support.
 */
@Slf4j
public class OpenMLDBJdbcSinkIntegrationTest {

    /** Match the module's pinned client (libs.openmldb == 0.9.2). */
    private static final DockerImageName OPENMLDB_IMAGE = DockerImageName.parse("4pdosc/openmldb:0.9.2");

    /** ZooKeeper coordinates the JDBC driver discovers cluster endpoints through. */
    private static final String ZK_ENDPOINT = "127.0.0.1:2181";
    private static final String ZK_PATH = "/openmldb";

    private static final String DATABASE = "pulsar_test";
    private static final String TABLE_NAME = "pulsar_messages";

    private GenericContainer<?> openmldb;
    private BaseJdbcAutoSchemaSink jdbcSink;

    /** A simple record class matching the {@link #TABLE_NAME} columns. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Foo {
        private String field1;
        private String field2;
        private int field3;
    }

    /** JDBC URL for the OpenMLDB driver — zk-based; the host after {@code //} is ignored. */
    private static String jdbcUrl(String database) {
        return "jdbc:openmldb:///" + database + "?zk=" + ZK_ENDPOINT + "&zkPath=" + ZK_PATH;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        // Host networking (Linux only): binds OpenMLDB's ports on localhost so the endpoints zk
        // advertises are reachable from this JVM. The image's init.sh deploys and starts the
        // cluster (zk on localhost:2181, zkPath /openmldb) and prints "OpenMLDB start success".
        openmldb = new GenericContainer<>(OPENMLDB_IMAGE)
                .withNetworkMode("host")
                .withCommand("bash", "-c", "/work/init.sh && tail -f /dev/null")
                .waitingFor(Wait.forLogMessage(".*OpenMLDB start success.*", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        openmldb.start();

        // Admin connection (a separate driver instance from the one under test) creates the
        // database and table. OpenMLDB needs online execute mode for inserts/reads to be visible.
        try (Connection conn = DriverManager.getConnection(jdbcUrl(""));
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET @@execute_mode='online'");
            stmt.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE);
            stmt.execute("USE " + DATABASE);
            stmt.execute("CREATE TABLE " + TABLE_NAME
                    + " (field1 string, field2 string, field3 int)");
        }

        Map<String, Object> conf = new HashMap<>();
        conf.put("jdbcUrl", jdbcUrl(DATABASE));
        conf.put("tableName", TABLE_NAME);
        // No key/nonKey: OpenMLDB's JDBC driver only supports INSERT prepared statements, and
        // configuring key/nonKey makes the sink also prepare UPDATE/DELETE ("unsupported sql").
        // OpenMLDB does not support JDBC transactions; run in auto-commit mode.
        conf.put("useTransactions", false);
        // Flush on each write.
        conf.put("batchSize", 1);

        jdbcSink = new OpenMLDBJdbcAutoSchemaSink();
        jdbcSink.open(conf, null);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (jdbcSink != null) {
            jdbcSink.close();
        }
        if (openmldb != null) {
            openmldb.stop();
        }
    }

    @Test(timeOut = 600_000)
    public void testOpenWritesAndReadsBack() throws Exception {
        Foo insertObj = new Foo("ValueOfField1", "ValueOfField2", 3);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        final Record<GenericObject> record = createMockFooRecord(insertObj, future);

        jdbcSink.write(record);
        log.info("executed write");

        Assert.assertTrue(future.get(60, TimeUnit.SECONDS), "record should be acknowledged");

        // Read the row back through a fresh admin connection and assert it landed.
        try (Connection conn = DriverManager.getConnection(jdbcUrl(DATABASE));
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET @@execute_mode='online'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT field1, field2, field3 FROM " + TABLE_NAME + " WHERE field3 = 3")) {
                Assert.assertTrue(rs.next(), "exactly one matching row should have been written");
                // Positional getters: OpenMLDB's SQLResultSet does not support access by column
                // label ("current do not support this method").
                Assert.assertEquals(rs.getString(1), insertObj.getField1());
                Assert.assertEquals(rs.getString(2), insertObj.getField2());
                Assert.assertEquals(rs.getInt(3), insertObj.getField3());
                Assert.assertFalse(rs.next(), "there should be exactly one matching row");
            }
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
