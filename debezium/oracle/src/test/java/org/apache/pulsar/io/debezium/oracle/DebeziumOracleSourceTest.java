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
package org.apache.pulsar.io.debezium.oracle;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.time.Duration;
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
import org.testcontainers.utility.MountableFile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class DebeziumOracleSourceTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private static final String ORACLE_IMAGE = "gvenzl/oracle-free:23-slim-faststart";

    private static final int ORACLE_PORT = 1521;

    private GenericContainer<?> oracleContainer;
    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private DebeziumOracleSource source;

    @BeforeMethod
    public void setup() throws Exception {
        // Use GenericContainer instead of the testcontainers oracle-free module:
        // debezium-bom 3.4.x pins testcontainers core to 2.x, which is binary
        // incompatible with the 1.x oracle-free module (and no 2.x release of it
        // exists yet).
        // All of the Oracle-side preparation (archive log mode, supplemental logging,
        // the c##dbzuser LogMiner user and the seeded DEBEZIUM.PRODUCTS test table)
        // is performed by the init script below, which the gvenzl image executes as
        // SYSDBA on first startup, before it prints "DATABASE IS READY TO USE!".
        oracleContainer = new GenericContainer<>(DockerImageName.parse(ORACLE_IMAGE))
                .withExposedPorts(ORACLE_PORT)
                .withEnv("ORACLE_PASSWORD", "top_secret")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("debezium-oracle-setup.sql"),
                        "/container-entrypoint-initdb.d/01-debezium-setup.sql")
                // The init script restarts the database to enable archive log mode,
                // so allow a generous startup timeout.
                .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*\\s", 1)
                        .withStartupTimeout(Duration.ofMinutes(6)));
        oracleContainer.start();

        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();

        source = new DebeziumOracleSource();
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
        if (oracleContainer != null) {
            oracleContainer.stop();
        }
    }

    @Test(timeOut = 600_000)
    public void testOracleCdcEvents() throws Exception {
        String pulsarServiceUrl = pulsarContainer.getPulsarBrokerUrl();

        SourceContext sourceContext = mock(SourceContext.class);
        when(sourceContext.getPulsarClient()).thenReturn(pulsarClient);
        when(sourceContext.getPulsarClientBuilder()).thenReturn(
                PulsarClient.builder().serviceUrl(pulsarServiceUrl));
        when(sourceContext.getTenant()).thenReturn("public");
        when(sourceContext.getNamespace()).thenReturn("default");
        when(sourceContext.getSourceName()).thenReturn("debezium-oracle-test");
        when(sourceContext.getSecret(anyString())).thenReturn(null);

        Map<String, Object> config = new HashMap<>();
        config.put("database.hostname", oracleContainer.getHost());
        config.put("database.port", String.valueOf(oracleContainer.getMappedPort(ORACLE_PORT)));
        config.put("database.user", "c##dbzuser");
        config.put("database.password", "dbz");
        config.put("database.dbname", "FREE");
        config.put("database.pdb.name", "FREEPDB1");
        config.put("topic.prefix", "oracle1");
        config.put("table.include.list", "DEBEZIUM.PRODUCTS");
        config.put("include.schema.changes", "false");
        // Read the data dictionary from the online catalog instead of mining it
        // from the redo logs; this keeps connector startup lightweight for tests.
        config.put("log.mining.strategy", "online_catalog");
        config.put("schema.history.internal.pulsar.service.url", pulsarServiceUrl);

        source.open(config, sourceContext);

        // Debezium performs an initial snapshot of existing data.
        // We should receive CDC records for the 2 rows seeded by the init script.
        Awaitility.await()
                .atMost(5, TimeUnit.MINUTES)
                .pollInterval(1, TimeUnit.SECONDS)
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
