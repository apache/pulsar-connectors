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
package org.apache.pulsar.io.debezium.mysql;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class DebeziumMysqlSourceTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private static final int MYSQL_PORT = 3306;

    private GenericContainer<?> mysqlContainer;
    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private DebeziumMysqlSource source;

    @BeforeMethod
    public void setup() throws Exception {
        // Use GenericContainer instead of MySQLContainer to get full root access
        // which is required for Debezium's REPLICATION SLAVE/CLIENT privileges
        mysqlContainer = new GenericContainer<>(DockerImageName.parse("mysql:8.0"))
                .withExposedPorts(MYSQL_PORT)
                .withEnv("MYSQL_ROOT_PASSWORD", "rootpw")
                .withEnv("MYSQL_DATABASE", "testdb")
                .withCommand(
                        "--default-authentication-plugin=mysql_native_password",
                        "--log-bin=mysql-bin",
                        "--binlog-format=ROW",
                        "--server-id=1",
                        "--gtid-mode=ON",
                        "--enforce-gtid-consistency=ON"
                )
                .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2));
        mysqlContainer.start();

        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();

        String jdbcUrl = "jdbc:mysql://" + mysqlContainer.getHost() + ":"
                + mysqlContainer.getMappedPort(MYSQL_PORT) + "/testdb";

        // Create test table and insert initial data (using root which has all privileges)
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "root", "rootpw");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE products ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "description VARCHAR(512))");
            stmt.execute("INSERT INTO products (name, description) VALUES ('widget', 'A small widget')");
            stmt.execute("INSERT INTO products (name, description) VALUES ('gadget', 'A fancy gadget')");
        }

        source = new DebeziumMysqlSource();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() throws Exception {
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
        if (pulsarClient != null) {
            pulsarClient.close();
        }
        if (pulsarContainer != null) {
            pulsarContainer.stop();
        }
        if (mysqlContainer != null) {
            mysqlContainer.stop();
        }
    }

    @Test
    public void testMysqlCdcEvents() throws Exception {
        String pulsarServiceUrl = pulsarContainer.getPulsarBrokerUrl();

        SourceContext sourceContext = mock(SourceContext.class);
        when(sourceContext.getPulsarClient()).thenReturn(pulsarClient);
        when(sourceContext.getPulsarClientBuilder()).thenReturn(
                PulsarClient.builder().serviceUrl(pulsarServiceUrl));
        when(sourceContext.getTenant()).thenReturn("public");
        when(sourceContext.getNamespace()).thenReturn("default");
        when(sourceContext.getSourceName()).thenReturn("debezium-mysql-test");
        when(sourceContext.getSecret(anyString())).thenReturn(null);

        Map<String, Object> config = new HashMap<>();
        config.put("database.hostname", mysqlContainer.getHost());
        config.put("database.port", String.valueOf(mysqlContainer.getMappedPort(MYSQL_PORT)));
        // Use root user which has all required CDC privileges
        config.put("database.user", "root");
        config.put("database.password", "rootpw");
        config.put("database.server.id", "12345");
        config.put("topic.prefix", "dbserver1");
        config.put("include.schema.changes", "false");
        config.put("schema.history.internal.pulsar.service.url", pulsarServiceUrl);

        source.open(config, sourceContext);

        // Debezium performs an initial snapshot of existing data.
        // We should receive CDC records for the 2 rows we inserted.
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    int recordCount = 0;
                    Record<KeyValue<byte[], byte[]>> record;
                    while ((record = source.read()) != null) {
                        assertNotNull(record.getValue());
                        log.info("Received CDC record: key={}", record.getKey().orElse(null));
                        recordCount++;
                        record.ack();
                        if (recordCount >= 2) {
                            break;
                        }
                    }
                    assertTrue(recordCount >= 2,
                            "Expected at least 2 CDC records from initial snapshot, got " + recordCount);
                });
    }
}
