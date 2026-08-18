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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.testcontainers.containers.CassandraContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Covers the {@code userName} / {@code password} settings on {@link CassandraSinkConfig} against a
 * cluster that does <em>not</em> require authentication — the image default,
 * {@code AllowAllAuthenticator}.
 *
 * <p>What that arrangement is good for is the compatibility half of these settings: credentials
 * survive config loading, an unset pair is still unset, and neither supplying credentials nor
 * omitting them stops the sink writing to a cluster that never asked for them. The last of those is
 * the regression this connector most needs guarded — an existing unauthenticated deployment must be
 * unaffected by the settings existing.
 *
 * <p>It also covers the one case the connector rejects on its own account, without asking a server
 * anything: a pair with only one half set.
 *
 * <p>That a server actually refuses a connection without credentials, and that the configured pair
 * is what gets past the refusal, is asserted in {@link CassandraSinkAuthEnforcementTest}, which runs
 * a container with {@code PasswordAuthenticator} enabled.
 */
public class CassandraSinkAuthTest {

    private static final String KEYSPACE = "auth_test_ks";
    private static final String TABLE = "auth_test_table";
    private static final String KEY_COLUMN = "key";
    private static final String VALUE_COLUMN = "value";

    private CassandraContainer<?> cassandraContainer;

    @BeforeClass
    public void setUp() {
        cassandraContainer = new CassandraContainer<>("cassandra:4.1")
                .withStartupTimeout(Duration.ofMinutes(3));
        cassandraContainer.start();

        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect()) {
            session.execute("CREATE KEYSPACE " + KEYSPACE
                    + " WITH replication = {'class':'SimpleStrategy', 'replication_factor':'1'}");
            session.execute("CREATE TABLE " + KEYSPACE + "." + TABLE
                    + " (" + KEY_COLUMN + " text PRIMARY KEY, " + VALUE_COLUMN + " text)");
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (cassandraContainer != null) {
            cassandraContainer.stop();
            cassandraContainer = null;
        }
    }

    @Test
    public void credentialsSurviveConfigLoading() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("userName", "cassandra");
        config.put("password", "cassandra");

        CassandraSinkConfig loaded = CassandraSinkConfig.load(config);

        assertEquals(loaded.getUserName(), "cassandra");
        assertEquals(loaded.getPassword(), "cassandra");
    }

    @Test
    public void credentialsAreUnsetWhenNotConfigured() throws Exception {
        CassandraSinkConfig loaded = CassandraSinkConfig.load(baseConfig());

        assertNull(loaded.getUserName());
        assertNull(loaded.getPassword());
    }

    @Test
    public void sinkWritesWithCredentialsSupplied() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("userName", cassandraContainer.getUsername());
        config.put("password", cassandraContainer.getPassword());

        assertWriteSucceeds(config, "with-credentials");
    }

    @Test
    public void sinkWritesWithoutCredentials() throws Exception {
        assertWriteSucceeds(baseConfig(), "no-credentials");
    }

    @Test
    public void openRejectsUsernameWithoutPassword() {
        Map<String, Object> config = unroutableConfig();
        config.put("userName", "cassandra");

        assertHalfConfiguredPairRejected(config);
    }

    @Test
    public void openRejectsPasswordWithoutUsername() {
        Map<String, Object> config = unroutableConfig();
        config.put("password", "cassandra");

        assertHalfConfiguredPairRejected(config);
    }

    private void assertHalfConfiguredPairRejected(Map<String, Object> config) {
        try {
            new CassandraStringSink().open(config, mock(SinkContext.class));
            fail("Expected open() to reject a credential pair with only one half set");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("userName and password must be supplied together"),
                    "Rejected for some other reason: " + e.getMessage());
        } catch (Exception e) {
            fail("Expected IllegalArgumentException, got: " + e);
        }
    }

    /**
     * Config whose {@code roots} point at a port nothing listens on. The rejection above has to
     * happen before any connection is attempted, and pointing somewhere unreachable is what makes
     * the test say so: drop the validation and this fails connecting rather than passing for the
     * wrong reason.
     */
    private Map<String, Object> unroutableConfig() {
        Map<String, Object> config = baseConfig();
        config.put("roots", "127.0.0.1:1");
        return config;
    }

    private void assertWriteSucceeds(Map<String, Object> config, String key) throws Exception {
        CassandraStringSink sink = new CassandraStringSink();
        try {
            sink.open(config, mock(SinkContext.class));

            CompletableFuture<Void> acked = new CompletableFuture<>();
            sink.write(new Record<byte[]>() {
                @Override
                public Optional<String> getKey() {
                    return Optional.of(key);
                }

                @Override
                public byte[] getValue() {
                    return ("value-" + key).getBytes();
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
            acked.get();
        } finally {
            sink.close();
        }

        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect(KEYSPACE)) {
            assertEquals(session
                    .execute("SELECT * FROM " + TABLE + " WHERE " + KEY_COLUMN + " = '" + key + "'")
                    .one()
                    .getString(VALUE_COLUMN), "value-" + key);
        }
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("roots", cassandraContainer.getHost() + ":" + cassandraContainer.getMappedPort(9042));
        config.put("keyspace", KEYSPACE);
        config.put("keyname", KEY_COLUMN);
        config.put("columnFamily", TABLE);
        config.put("columnName", VALUE_COLUMN);
        return config;
    }
}
