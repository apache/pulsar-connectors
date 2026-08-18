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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Drives {@link CassandraStringSink} against a Cassandra that actually enforces authentication, and
 * so asserts what {@link CassandraSinkAuthTest} cannot: that a connection without credentials — or
 * with the wrong ones — is refused by the server, and that the credentials the sink is configured
 * with are what gets it past that refusal.
 *
 * <p>The container runs {@code PasswordAuthenticator} instead of the image default
 * {@code AllowAllAuthenticator}. It gets there by editing the shipped {@code cassandra.yaml} in
 * place rather than by mounting a replacement: {@code withConfigurationOverride()} maps a whole
 * directory over {@code /etc/cassandra}, which would mean carrying a complete, version-matched copy
 * of every file Cassandra reads from there, while a one-line {@code sed} touches only the setting
 * under test. The {@code grep} that follows it makes a no-op substitution fatal — if a future image
 * tag expresses the authenticator differently, the container fails to start rather than quietly
 * reverting to {@code AllowAllAuthenticator} and leaving every assertion here vacuous.
 * {@link #serverRejectsUnauthenticatedConnection()} is the second half of that guard: it fails the
 * suite if the server ever stops enforcing, whatever the reason.
 */
public class CassandraSinkAuthEnforcementTest {

    private static final String IMAGE = "cassandra:4.1";
    private static final String CONF = "/etc/cassandra/cassandra.yaml";
    private static final String SUPERUSER = "cassandra";
    private static final String SUPERUSER_PASSWORD = "cassandra";

    private static final String ENABLE_PASSWORD_AUTHENTICATION =
            "sed -i 's/^authenticator:.*/authenticator: PasswordAuthenticator/' " + CONF
                    + " && grep -q '^authenticator: PasswordAuthenticator' " + CONF
                    + " && exec docker-entrypoint.sh cassandra -f";

    private static final String KEYSPACE = "auth_enforced_ks";
    private static final String TABLE = "auth_enforced_table";
    private static final String KEY_COLUMN = "key";
    private static final String VALUE_COLUMN = "value";

    private CassandraContainer<?> cassandraContainer;

    @BeforeClass
    public void setUp() {
        cassandraContainer = new CassandraContainer<>(IMAGE)
                .withCommand("sh", "-c", ENABLE_PASSWORD_AUTHENTICATION)
                // The default superuser role is created on a delay after the ring is joined, which is
                // later than the CQL port opening; waiting for the port alone would let a test connect
                // before there is anything to authenticate against.
                .waitingFor(new WaitAllStrategy()
                        .withStrategy(Wait.forListeningPort())
                        .withStrategy(new LogMessageWaitStrategy()
                                .withRegEx(".*Created default superuser role.*\\n"))
                        .withStartupTimeout(Duration.ofMinutes(3)));
        cassandraContainer.start();

        try (Cluster cluster = authenticatedCluster();
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

    /**
     * Establishes that the server under test enforces authentication at all, using the driver
     * directly so that nothing about the sink is involved. Every other assertion in this class is
     * only worth something while this one holds.
     */
    @Test
    public void serverRejectsUnauthenticatedConnection() {
        try (Cluster cluster = cassandraContainer.getCluster()) {
            cluster.connect();
            fail("Expected the server to reject a connection carrying no credentials");
        } catch (Exception e) {
            assertNotNull(authenticationFailure(e), "Not an authentication failure: " + e);
        }
    }

    @Test
    public void sinkFailsToOpenWithoutCredentials() {
        assertOpenIsRejected(baseConfig());
    }

    @Test
    public void sinkFailsToOpenWithWrongPassword() {
        Map<String, Object> config = baseConfig();
        config.put("userName", SUPERUSER);
        config.put("password", "not-the-password");

        assertOpenIsRejected(config);
    }

    @Test
    public void sinkFailsToOpenWithUnknownUser() {
        Map<String, Object> config = baseConfig();
        config.put("userName", "no-such-user");
        config.put("password", SUPERUSER_PASSWORD);

        assertOpenIsRejected(config);
    }

    @Test
    public void sinkWritesWhenCredentialsAreCorrect() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("userName", SUPERUSER);
        config.put("password", SUPERUSER_PASSWORD);

        String key = "authenticated";
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

        try (Cluster cluster = authenticatedCluster();
             Session session = cluster.connect(KEYSPACE)) {
            assertEquals(session
                    .execute("SELECT * FROM " + TABLE + " WHERE " + KEY_COLUMN + " = '" + key + "'")
                    .one()
                    .getString(VALUE_COLUMN), "value-" + key);
        }
    }

    private void assertOpenIsRejected(Map<String, Object> config) {
        CassandraStringSink sink = new CassandraStringSink();
        try {
            sink.open(config, mock(SinkContext.class));
            fail("Expected open() to be rejected by the server");
        } catch (Exception e) {
            assertNotNull(authenticationFailure(e), "Not an authentication failure: " + e);
        }
    }

    /**
     * Finds the {@link AuthenticationException} the driver raised, or {@code null} if the failure was
     * something else. The driver reports an unusable contact point either directly or wrapped in a
     * {@link NoHostAvailableException} holding the per-host cause, so both shapes are unwrapped here
     * rather than asserting on one and hoping.
     */
    private static AuthenticationException authenticationFailure(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof AuthenticationException) {
                return (AuthenticationException) current;
            }
            if (current instanceof NoHostAvailableException) {
                for (Throwable hostError : ((NoHostAvailableException) current).getErrors().values()) {
                    AuthenticationException found = authenticationFailure(hostError);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private Cluster authenticatedCluster() {
        return Cluster.builder()
                .addContactPoint(cassandraContainer.getHost())
                .withPort(cassandraContainer.getMappedPort(CassandraContainer.CQL_PORT))
                .withCredentials(SUPERUSER, SUPERUSER_PASSWORD)
                .withoutJMXReporting()
                .build();
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("roots", cassandraContainer.getHost() + ":"
                + cassandraContainer.getMappedPort(CassandraContainer.CQL_PORT));
        config.put("keyspace", KEYSPACE);
        config.put("keyname", KEY_COLUMN);
        config.put("columnFamily", TABLE);
        config.put("columnName", VALUE_COLUMN);
        return config;
    }
}
