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
package org.apache.pulsar.io.canal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test for {@link CanalStringSource} that stands up a full MySQL-binlog CDC pipeline
 * with Testcontainers:
 *
 * <pre>
 *   MySQL (mysql:8.0, ROW binlog)  &lt;--dump--  canal-server (canal/canal-server:v1.1.7)  &lt;--tcp--  CanalStringSource
 * </pre>
 *
 * <p>The two containers share a {@link Network}; canal reaches MySQL through the {@code mysql}
 * network alias, and the source (running in this JVM) reaches canal through the mapped 11111 port.
 * canal-server v1.1.7 matches the {@code canal.client}/{@code canal.protocol} 1.1.7 dependency of
 * the module. The image does no env-var substitution, so the instance configuration is mounted from
 * {@code src/test/resources/canal/instance.properties}.
 */
@Slf4j
public class CanalStringSourceIntegrationTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0");
    private static final DockerImageName CANAL_IMAGE = DockerImageName.parse("canal/canal-server:v1.1.7");

    private static final int MYSQL_PORT = 3306;
    private static final int CANAL_PORT = 11111;

    /**
     * Deadline for a single bounded {@code read()}. {@code PushSource.read()} blocks until a record
     * is pushed, so it must run on a worker thread with a deadline: an under-delivering pipeline then
     * surfaces as a prompt, diagnosable failure instead of hanging the CI job to its own timeout.
     */
    private static final int READ_TIMEOUT_SECONDS = 180;

    private Network network;
    private GenericContainer<?> mysqlContainer;
    private GenericContainer<?> canalContainer;
    private CanalStringSource source;
    private ExecutorService readerExecutor;

    @BeforeMethod
    public void setup() throws Exception {
        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "canal-test-reader");
            t.setDaemon(true);
            return t;
        });

        network = Network.newNetwork();

        // GenericContainer (not MySQLContainer) so we keep full root access with the native
        // password plugin canal 1.1.x expects for the binlog dump handshake.
        mysqlContainer = new GenericContainer<>(MYSQL_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("mysql")
                .withExposedPorts(MYSQL_PORT)
                .withEnv("MYSQL_ROOT_PASSWORD", "rootpw")
                .withEnv("MYSQL_DATABASE", "testdb")
                .withCommand(
                        "--default-authentication-plugin=mysql_native_password",
                        "--log-bin=mysql-bin",
                        "--binlog-format=ROW",
                        "--server-id=1")
                .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        mysqlContainer.start();

        // Create the tracked table BEFORE canal starts, so its DDL is not part of the binlog
        // stream canal reads; only the INSERT made after subscription should be delivered.
        try (Connection conn = openMysql();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE testdb.products ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "description VARCHAR(512))");
        }

        canalContainer = new GenericContainer<>(CANAL_IMAGE)
                .withNetwork(network)
                .withExposedPorts(CANAL_PORT)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("canal/instance.properties"),
                        "/home/admin/canal-server/conf/example/instance.properties")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("canal-server"))
                .waitingFor(Wait.forLogMessage(".*START SUCCESSFUL.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        canalContainer.start();

        source = new CanalStringSource();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() throws Exception {
        if (readerExecutor != null) {
            // shutdownNow: a reader may still be blocked in read(), which never returns null.
            readerExecutor.shutdownNow();
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
        if (canalContainer != null) {
            canalContainer.stop();
        }
        if (mysqlContainer != null) {
            mysqlContainer.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test(timeOut = 600_000)
    public void testCanalCdcEvents() throws Exception {
        SourceContext sourceContext = mock(SourceContext.class);
        when(sourceContext.getSourceName()).thenReturn("canal-string-source-test");
        // Sensitive fields (username/password) are looked up as secrets first; return null so the
        // loader falls back to the plain config values below.
        when(sourceContext.getSecret(anyString())).thenReturn(null);

        Map<String, Object> config = new HashMap<>();
        config.put("cluster", false);
        // zkServers is a @FieldDoc(required = true) with an empty default, so it must be present in
        // the map even for single (non-cluster) mode or the config loader rejects it.
        config.put("zkServers", "");
        config.put("destination", "example");
        config.put("singleHostname", canalContainer.getHost());
        config.put("singlePort", canalContainer.getMappedPort(CANAL_PORT));
        // canal.user/canal.passwd are unset in the server, so no client auth is required.
        config.put("username", "");
        config.put("password", "");
        config.put("batchSize", 1000);

        source.open(config, sourceContext);

        // Give the source thread time to connect + subscribe to canal before the mutation, so the
        // INSERT lands in the stream canal delivers to this subscriber.
        Thread.sleep(5000);

        try (Connection conn = openMysql();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO testdb.products (name, description) "
                    + "VALUES ('canal-widget', 'inserted after subscription')");
        }

        Record<CanalMessage> record = readOne();
        assertNotNull(record.getValue(), "CDC record value should not be null");
        String message = record.getValue().getMessage();
        log.info("Received CDC record: key={}, message={}", record.getKey().orElse(null), message);
        assertNotNull(message, "CDC message payload should not be null");
        // The FlatMessage JSON carries the table name and the inserted row values.
        assertTrue(message.contains("products"),
                "CDC message should reference the products table, but was: " + message);
        assertTrue(message.contains("canal-widget"),
                "CDC message should carry the inserted row value, but was: " + message);
        record.ack();
    }

    private String jdbcUrl() {
        return "jdbc:mysql://" + mysqlContainer.getHost() + ":"
                + mysqlContainer.getMappedPort(MYSQL_PORT) + "/testdb";
    }

    /**
     * Opens a MySQL connection, retrying briefly: the container logs "ready for connections" during
     * its temporary init server before the real TCP listener is fully up, so the first attempts can
     * hit an EOF mid-handshake.
     */
    private Connection openMysql() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return DriverManager.getConnection(jdbcUrl(), "root", "rootpw");
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        throw new AssertionError("Could not connect to MySQL after retries", last);
    }

    /**
     * Reads a single record on a worker thread, failing (not hanging) if none arrives in time.
     */
    private Record<CanalMessage> readOne() throws Exception {
        Future<Record<CanalMessage>> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a CDC record from canal. The pipeline produced no record; "
                    + "see the canal-server logs above.", e);
        }
    }
}
