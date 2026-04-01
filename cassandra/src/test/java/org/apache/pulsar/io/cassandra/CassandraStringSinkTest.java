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
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.testcontainers.containers.CassandraContainer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class CassandraStringSinkTest {

    private static final String KEYSPACE = "test_keyspace";
    private static final String TABLE = "test_table";
    private static final String KEY_COLUMN = "key";
    private static final String VALUE_COLUMN = "value";

    private CassandraContainer<?> cassandraContainer;
    private CassandraStringSink sink;

    @BeforeMethod
    public void setUp() {
        cassandraContainer = new CassandraContainer<>("cassandra:4.1");
        cassandraContainer.start();

        // Create keyspace and table
        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect()) {
            session.execute("CREATE KEYSPACE " + KEYSPACE
                    + " WITH replication = {'class':'SimpleStrategy', 'replication_factor':'1'}");
            session.execute("CREATE TABLE " + KEYSPACE + "." + TABLE
                    + " (" + KEY_COLUMN + " text PRIMARY KEY, " + VALUE_COLUMN + " text)");
        }

        sink = new CassandraStringSink();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (cassandraContainer != null) {
            cassandraContainer.stop();
        }
    }

    @Test
    public void testWriteAndVerify() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("roots", cassandraContainer.getHost() + ":" + cassandraContainer.getMappedPort(9042));
        config.put("keyspace", KEYSPACE);
        config.put("keyname", KEY_COLUMN);
        config.put("columnFamily", TABLE);
        config.put("columnName", VALUE_COLUMN);

        sink.open(config, mock(SinkContext.class));

        // Send a few records through the sink
        int numRecords = 5;
        CompletableFuture<?>[] futures = new CompletableFuture[numRecords];
        for (int i = 0; i < numRecords; i++) {
            final String key = "key-" + i;
            final String value = "value-" + i;
            futures[i] = new CompletableFuture<>();
            final int idx = i;
            Record<byte[]> record = new Record<byte[]>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.of(key);
                }

                @Override
                public byte[] getValue() {
                    return value.getBytes();
                }

                @Override
                public void ack() {
                    futures[idx].complete(null);
                }

                @Override
                public void fail() {
                    futures[idx].completeExceptionally(new RuntimeException("Record failed"));
                }
            };
            sink.write(record);
        }

        // Wait for all records to be acknowledged
        CompletableFuture.allOf(futures).get();

        // Verify data in Cassandra
        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect(KEYSPACE)) {
            List<Row> rows = session.execute("SELECT * FROM " + TABLE).all();
            assertEquals(rows.size(), numRecords);

            for (int i = 0; i < numRecords; i++) {
                Row row = session.execute("SELECT * FROM " + TABLE + " WHERE " + KEY_COLUMN + " = 'key-" + i + "'")
                        .one();
                assertEquals(row.getString(VALUE_COLUMN), "value-" + i);
            }
        }
    }
}
