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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the MariaDB JDBC sink, exercised against a real MariaDB server
 * via Testcontainers.
 *
 * <p>The sink is opened against a pre-created table, a record is written through the
 * connector's Avro schema path, and the resulting row is read back over plain JDBC
 * to verify that the write actually reached the database.
 */
@Slf4j
public class MariadbJdbcSinkIntegrationTest {

    private static final DockerImageName MARIADB_IMAGE = DockerImageName.parse("mariadb:11.4");

    private static final String TABLE_NAME = "pulsar_messages";

    private MariaDBContainer<?> mariadb;
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
        mariadb = new MariaDBContainer<>(MARIADB_IMAGE);
        mariadb.start();

        // Create the destination table over plain JDBC.
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE_NAME + " ("
                    + "    field1 VARCHAR(255),"
                    + "    field2 VARCHAR(255),"
                    + "    field3 INT PRIMARY KEY"
                    + ")");
        }

        Map<String, Object> conf = new HashMap<>();
        conf.put("jdbcUrl", mariadb.getJdbcUrl());
        conf.put("userName", mariadb.getUsername());
        conf.put("password", mariadb.getPassword());
        conf.put("tableName", TABLE_NAME);
        conf.put("key", "field3");
        conf.put("nonKey", "field1,field2");
        // MariaDB supports JDBC transactions; commit on each flush.
        conf.put("useTransactions", true);
        // Flush on each write.
        conf.put("batchSize", 1);

        jdbcSink = new MariadbJdbcAutoSchemaSink();
        jdbcSink.open(conf, null);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (jdbcSink != null) {
            jdbcSink.close();
        }
        if (mariadb != null) {
            mariadb.stop();
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

        // value has been written to MariaDB, read it back over JDBC and verify.
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT field1, field2, field3 FROM " + TABLE_NAME + " WHERE field3 = ?")) {
            statement.setInt(1, insertObj.getField3());
            try (ResultSet resultSet = statement.executeQuery()) {
                Assert.assertTrue(resultSet.next(), "a matching row should have been written");
                Assert.assertEquals(resultSet.getString("field1"), insertObj.getField1());
                Assert.assertEquals(resultSet.getString("field2"), insertObj.getField2());
                Assert.assertEquals(resultSet.getInt("field3"), insertObj.getField3());
                Assert.assertFalse(resultSet.next(), "exactly one matching row should have been written");
            }
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
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
