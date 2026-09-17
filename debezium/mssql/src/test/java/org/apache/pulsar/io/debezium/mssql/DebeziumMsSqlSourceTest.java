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
package org.apache.pulsar.io.debezium.mssql;

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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class DebeziumMsSqlSourceTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private static final int MSSQL_PORT = 1433;

    private MSSQLServerContainer<?> mssqlContainer;
    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private DebeziumMsSqlSource source;

    @BeforeMethod
    public void setup() throws Exception {
        mssqlContainer = new MSSQLServerContainer<>(
                DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                .acceptLicense()
                // The SQL Server Agent is required to run the CDC capture jobs
                .withEnv("MSSQL_AGENT_ENABLED", "true");
        mssqlContainer.start();

        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();

        String jdbcUrl = mssqlContainer.getJdbcUrl() + ";encrypt=false";

        // Create the test database, enable CDC on it and on the test table,
        // then insert initial data (using sa which has all required privileges)
        try (Connection conn = DriverManager.getConnection(
                jdbcUrl, mssqlContainer.getUsername(), mssqlContainer.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE testdb");
            stmt.execute("USE testdb");
            stmt.execute("EXEC sys.sp_cdc_enable_db");
            stmt.execute("CREATE TABLE products ("
                    + "id INT IDENTITY PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "description VARCHAR(512))");
            stmt.execute("EXEC sys.sp_cdc_enable_table "
                    + "@source_schema='dbo', @source_name='products', @role_name=NULL");
            stmt.execute("INSERT INTO products (name, description) VALUES ('widget', 'A small widget')");
            stmt.execute("INSERT INTO products (name, description) VALUES ('gadget', 'A fancy gadget')");
        }

        source = new DebeziumMsSqlSource();
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
        if (mssqlContainer != null) {
            mssqlContainer.stop();
        }
    }

    @Test
    public void testMsSqlCdcEvents() throws Exception {
        String pulsarServiceUrl = pulsarContainer.getPulsarBrokerUrl();

        SourceContext sourceContext = mock(SourceContext.class);
        when(sourceContext.getPulsarClient()).thenReturn(pulsarClient);
        when(sourceContext.getPulsarClientBuilder()).thenReturn(
                PulsarClient.builder().serviceUrl(pulsarServiceUrl));
        when(sourceContext.getTenant()).thenReturn("public");
        when(sourceContext.getNamespace()).thenReturn("default");
        when(sourceContext.getSourceName()).thenReturn("debezium-mssql-test");
        when(sourceContext.getSecret(anyString())).thenReturn(null);

        Map<String, Object> config = new HashMap<>();
        config.put("database.hostname", mssqlContainer.getHost());
        config.put("database.port", String.valueOf(mssqlContainer.getMappedPort(MSSQL_PORT)));
        // Use sa which has all required CDC privileges
        config.put("database.user", mssqlContainer.getUsername());
        config.put("database.password", mssqlContainer.getPassword());
        config.put("database.names", "testdb");
        // The SQL Server connector runs in multi-partition mode and requires a task id.
        // DebeziumSource starts the connector task directly (without going through
        // SqlServerConnector.taskConfigs which would normally assign it), so set it here.
        config.put("task.id", "0");
        config.put("database.encrypt", "false");
        config.put("topic.prefix", "sqlserver1");
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
