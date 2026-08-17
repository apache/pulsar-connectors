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
package org.apache.pulsar.io.cassandra;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Data;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericAvroSchema;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.cassandra.util.AbstractCassandraTest;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.testng.annotations.Test;

/**
 * Drives {@link CassandraGenericRecordSink} and {@link CassandraJsonStringSink} end to end — real
 * sink, real cluster, real rows — where the rest of this module's coverage stops at the machinery
 * underneath them or at {@code open()}.
 *
 * <p>What these sinks promise is that the target table decides what gets written: fields are matched
 * to columns by name, a field the table has no column for is dropped, and a column the record has no
 * field for is left alone. A single-column write cannot show any of that, so both tests write to
 * {@code airquality.reading} from {@code init.cql} — seventeen columns, a compound primary key, and
 * {@code text} / {@code int} / {@code double} / {@code float} among them — populating seven of them
 * and asserting on both what landed and what did not.
 *
 * <p>The generic-record case builds its input the way the hbase and jdbc sink tests do: encode a
 * POJO with {@link AvroSchema}, decode it with {@link GenericAvroSchema}. That matters more than the
 * convenience — it is a real Avro-backed record, so {@code text} columns receive Avro's string type
 * rather than a {@link String} a hand-written test double would have handed over, and the coercion
 * in {@code RecordWrapper} is exercised as it would be in a deployment.
 */
public class CassandraTableSinkIntegrationTest extends AbstractCassandraTest {

    private static final String KEYSPACE = "airquality";
    private static final String TABLE = "reading";

    /**
     * A subset of {@code airquality.reading}'s columns. Field names are the column names: that
     * matching is the behaviour under test, and Cassandra folds unquoted identifiers to lower case,
     * so the underscores are the table's, not a style choice.
     */
    @Data
    public static class Reading {
        private String reporting_area;
        private String date_observed;
        private int hour_observed;
        private String readingid;
        private double avg_ozone;
        private float latitude;
        private String state_code;
    }

    @Test
    public void genericRecordSinkMapsFieldsOntoColumnsByName() throws Exception {
        Reading reading = newReading("generic-record-area");

        AvroSchema<Reading> schema =
                AvroSchema.of(SchemaDefinition.<Reading>builder().withPojo(Reading.class).build());
        GenericRecord value = new GenericAvroSchema(schema.getSchemaInfo()).decode(schema.encode(reading));

        writeThrough(new CassandraGenericRecordSink(), value);

        assertRowMatches(reading);
    }

    @Test
    public void jsonStringSinkMapsFieldsOntoColumnsByName() throws Exception {
        Reading reading = newReading("json-string-area");

        String json = "{"
                + "\"reporting_area\":\"" + reading.getReporting_area() + "\","
                + "\"date_observed\":\"" + reading.getDate_observed() + "\","
                + "\"hour_observed\":" + reading.getHour_observed() + ","
                + "\"readingid\":\"" + reading.getReadingid() + "\","
                + "\"avg_ozone\":" + reading.getAvg_ozone() + ","
                + "\"latitude\":" + reading.getLatitude() + ","
                + "\"state_code\":\"" + reading.getState_code() + "\","
                // No column of this name: the sink writes what the table has, and drops the rest.
                + "\"not_a_column\":\"ignored\""
                + "}";

        writeThrough(new CassandraJsonStringSink(), json);

        assertRowMatches(reading);
    }

    /**
     * A field that is present but null is a column written as null, not a failed write. Distinct from
     * the {@code not_a_column} case above: there the field has no column, here the column has no
     * value, and the two take different paths through {@code RecordWrapper}.
     */
    @Test
    public void jsonStringSinkWritesAnExplicitlyNullFieldAsNull() throws Exception {
        Reading reading = newReading("json-null-area");
        reading.setState_code(null);

        String json = "{"
                + "\"reporting_area\":\"" + reading.getReporting_area() + "\","
                + "\"date_observed\":\"" + reading.getDate_observed() + "\","
                + "\"hour_observed\":" + reading.getHour_observed() + ","
                + "\"readingid\":\"" + reading.getReadingid() + "\","
                + "\"avg_ozone\":" + reading.getAvg_ozone() + ","
                + "\"latitude\":" + reading.getLatitude() + ","
                + "\"state_code\":null"
                + "}";

        writeThrough(new CassandraJsonStringSink(), json);

        assertRowMatches(reading);
    }

    /**
     * A second write to the same primary key that omits a field must leave that column as it was, not
     * erase it. This is the promise the other tests describe but cannot check: on a fresh row an
     * absent column and a column written as null are indistinguishable, and it takes a re-write to
     * tell them apart. Binding an {@code Object[]} through {@code bind(Object...)} puts a real null in
     * every unmatched position, and in Cassandra a null is a deletion, so this failed before
     * {@link org.apache.pulsar.io.cassandra.util.BoundStatementProvider} began unsetting them.
     */
    @Test
    public void writingSameKeyAgainLeavesUnmatchedColumnsIntact() throws Exception {
        Reading first = newReading("rewrite-area");

        writeThrough(new CassandraJsonStringSink(), "{"
                + "\"reporting_area\":\"" + first.getReporting_area() + "\","
                + "\"date_observed\":\"" + first.getDate_observed() + "\","
                + "\"hour_observed\":" + first.getHour_observed() + ","
                + "\"state_code\":\"" + first.getState_code() + "\","
                + "\"readingid\":\"" + first.getReadingid() + "\""
                + "}");

        // Same primary key, and this time state_code is not mentioned at all.
        writeThrough(new CassandraJsonStringSink(), "{"
                + "\"reporting_area\":\"" + first.getReporting_area() + "\","
                + "\"date_observed\":\"" + first.getDate_observed() + "\","
                + "\"hour_observed\":" + first.getHour_observed() + ","
                + "\"readingid\":\"second-write\""
                + "}");

        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect(KEYSPACE)) {
            Row row = session.execute("SELECT * FROM " + TABLE + " WHERE reporting_area = '"
                    + first.getReporting_area() + "'").one();
            assertNotNull(row);
            assertEquals(row.getString("readingid"), "second-write", "The second write should have landed");
            assertEquals(row.getString("state_code"), first.getState_code(),
                    "A column the second record never mentioned was erased");
        }
    }

    /**
     * A record whose content cannot be bound — malformed JSON here — must be failed, not thrown out of
     * {@code write()}. An escaping exception leaves the record neither acked nor failed, which kills
     * the sink and has Pulsar redeliver the same message forever.
     */
    @Test
    public void unbindableRecordIsFailedRatherThanThrown() throws Exception {
        CassandraJsonStringSink sink = new CassandraJsonStringSink();
        CompletableFuture<Void> acked = new CompletableFuture<>();
        AtomicBoolean failed = new AtomicBoolean();
        try {
            sink.open(sinkConfig(), mock(SinkContext.class));
            sink.write(new Record<String>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.empty();
                }

                @Override
                public String getValue() {
                    return "this is not json";
                }

                @Override
                public void ack() {
                    acked.complete(null);
                }

                @Override
                public void fail() {
                    failed.set(true);
                }
            });
        } finally {
            sink.close();
        }

        assertTrue(failed.get(), "The record should have been failed");
        assertFalse(acked.isDone(), "The record should not have been acked");
    }

    /**
     * A table holding a column type the sink cannot bind is refused at {@code open()}, naming the
     * column and the type, rather than throwing an {@code InvalidTypeException} for every record it is
     * later asked to write.
     */
    @Test
    public void openIsRefusedForAnUnsupportedColumnType() throws Exception {
        Map<String, Object> config = sinkConfig();
        config.put("columnFamily", "unsupported_column_type");

        CassandraJsonStringSink sink = new CassandraJsonStringSink();
        try {
            sink.open(config, mock(SinkContext.class));
            fail("Expected open() to refuse a table with a timestamp column");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("observed_at") && e.getMessage().contains("timestamp"),
                    "Message should name the offending column and type, got: " + e.getMessage());
        } finally {
            sink.close();
        }
    }

    private Reading newReading(String area) {
        Reading reading = new Reading();
        reading.setReporting_area(area);
        reading.setDate_observed("2026-08-17");
        reading.setHour_observed(9);
        reading.setReadingid(area + "-1");
        reading.setAvg_ozone(0.031);
        reading.setLatitude(37.77f);
        reading.setState_code("CA");
        return reading;
    }

    private <T> void writeThrough(Sink<T> sink, T value) throws Exception {
        try {
            sink.open(sinkConfig(), mock(SinkContext.class));

            CompletableFuture<Void> acked = new CompletableFuture<>();
            sink.write(new Record<T>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.empty();
                }

                @Override
                public T getValue() {
                    return value;
                }

                @Override
                public void ack() {
                    acked.complete(null);
                }

                @Override
                public void fail() {
                    acked.completeExceptionally(new RuntimeException("Record failed"));
                }
            });
            acked.get(30, TimeUnit.SECONDS);
        } finally {
            sink.close();
        }
    }

    private void assertRowMatches(Reading expected) {
        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect(KEYSPACE)) {

            Row row = session.execute("SELECT * FROM " + TABLE + " WHERE reporting_area = '"
                    + expected.getReporting_area() + "'").one();
            assertNotNull(row, "The sink acknowledged the record but no row was written");

            assertEquals(row.getString("reporting_area"), expected.getReporting_area());
            assertEquals(row.getString("date_observed"), expected.getDate_observed());
            assertEquals(row.getInt("hour_observed"), expected.getHour_observed());
            assertEquals(row.getString("readingid"), expected.getReadingid());
            assertEquals(row.getString("state_code"), expected.getState_code());
            assertEquals(row.getDouble("avg_ozone"), expected.getAvg_ozone(), 0.000001);
            assertEquals(row.getFloat("latitude"), expected.getLatitude(), 0.000001f);

            // Columns the record said nothing about are left alone rather than written as some
            // default. Without this the assertions above would also pass for a sink that bound every
            // column it could reach.
            assertNull(row.getObject("max_ozone"), "A column with no matching field was written to");
            assertNull(row.getObject("local_time_zone"), "A column with no matching field was written to");
        }
    }

    private Map<String, Object> sinkConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("roots", cassandraContainer.getHost() + ":"
                + cassandraContainer.getMappedPort(org.testcontainers.containers.CassandraContainer.CQL_PORT));
        config.put("keyspace", KEYSPACE);
        config.put("columnFamily", TABLE);
        config.put("userName", cassandraContainer.getUsername());
        config.put("password", cassandraContainer.getPassword());
        return config;
    }
}
