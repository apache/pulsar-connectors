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
package org.apache.pulsar.io.debezium;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import io.debezium.config.Configuration;
import io.debezium.connector.mysql.antlr.MySqlAntlrDdlParser;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.relational.history.SchemaHistory;
import io.debezium.relational.history.SchemaHistoryListener;
import io.debezium.text.ParsingException;
import io.debezium.util.Collect;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Test the implementation of {@link PulsarSchemaHistory} using Testcontainers.
 */
public class PulsarSchemaHistoryTest {

    private static final String PULSAR_IMAGE =
            System.getenv().getOrDefault("PULSAR_TEST_IMAGE", "apachepulsar/pulsar:4.1.3");

    private PulsarContainer pulsarContainer;
    private PulsarClient pulsarClient;
    private PulsarAdmin admin;
    private PulsarSchemaHistory history;
    private Map<String, Object> position;
    private Map<String, String> source;
    private String topicName;
    private String ddl;

    @BeforeMethod
    protected void setup() throws Exception {
        pulsarContainer = new PulsarContainer(DockerImageName.parse(PULSAR_IMAGE));
        pulsarContainer.start();

        pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.getPulsarBrokerUrl())
                .build();
        admin = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarContainer.getHttpServiceUrl())
                .build();

        // Create namespace used by tests
        admin.namespaces().createNamespace("public/my-ns");

        source = Collect.hashMapOf("server", "my-server");
        setLogPosition(0);
        this.topicName = "persistent://public/my-ns/schema-changes-topic";
        this.history = new PulsarSchemaHistory();
    }

    @AfterMethod(alwaysRun = true)
    protected void cleanup() throws Exception {
        if (history != null) {
            history.stop();
        }
        if (pulsarClient != null) {
            pulsarClient.close();
        }
        if (admin != null) {
            admin.close();
        }
        if (pulsarContainer != null) {
            pulsarContainer.stop();
        }
    }

    private void testHistoryTopicContent(boolean skipUnparseableDDL,
                                         boolean testWithReaderConfig) throws Exception {
        Configuration.Builder configBuilder = Configuration.create()
                .with(PulsarSchemaHistory.TOPIC, topicName)
                .with(PulsarSchemaHistory.SERVICE_URL, pulsarContainer.getPulsarBrokerUrl())
                .with(SchemaHistory.NAME, "my-db-history")
                .with(SchemaHistory.SKIP_UNPARSEABLE_DDL_STATEMENTS, skipUnparseableDDL);

        if (testWithReaderConfig) {
            configBuilder.with(PulsarSchemaHistory.READER_CONFIG, "{\"subscriptionName\":\"my-subscription\"}");
        }

        // Start up the history ...
        history.configure(configBuilder.build(), null, SchemaHistoryListener.NOOP, true);
        history.start();

        // Should be able to call start more than once ...
        history.start();

        history.initializeStorage();

        // Calling it another time to ensure we can work with the DB history topic already existing
        history.initializeStorage();

        DdlParser recoveryParser = new MySqlAntlrDdlParser();
        DdlParser ddlParser = new MySqlAntlrDdlParser();
        ddlParser.setCurrentSchema("db1"); // recover does this, so we need to as well
        Tables tables1 = new Tables();
        Tables tables2 = new Tables();
        Tables tables3 = new Tables();

        // Recover from the very beginning ...
        setLogPosition(0);
        history.recover(source, position, tables1, recoveryParser);

        // There should have been nothing to recover ...
        assertEquals(tables1.size(), 0);

        // Now record schema changes
        setLogPosition(10);
        ddl = "CREATE TABLE foo ( first VARCHAR(22) NOT NULL ); \n"
                + "CREATE TABLE customers ( id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL ); \n"
                + "CREATE TABLE products ( productId INTEGER NOT NULL PRIMARY KEY, "
                + "description VARCHAR(255) NOT NULL ); \n";
        history.record(source, position, "db1", ddl);

        // Parse the DDL statement 3x and each time update a different Tables object ...
        ddlParser.parse(ddl, tables1);
        assertEquals(3, tables1.size());
        ddlParser.parse(ddl, tables2);
        assertEquals(3, tables2.size());
        ddlParser.parse(ddl, tables3);
        assertEquals(3, tables3.size());

        // Record a drop statement and parse it for 2 of our 3 Tables...
        setLogPosition(39);
        ddl = "DROP TABLE foo;";
        history.record(source, position, "db1", ddl);
        ddlParser.parse(ddl, tables2);
        assertEquals(2, tables2.size());
        ddlParser.parse(ddl, tables3);
        assertEquals(2, tables3.size());

        // Record another DDL statement and parse it for 1 of our 3 Tables...
        setLogPosition(10003);
        ddl = "CREATE TABLE suppliers ( supplierId INTEGER NOT NULL PRIMARY KEY, "
                + "name VARCHAR(255) NOT NULL);";
        history.record(source, position, "db1", ddl);
        ddlParser.parse(ddl, tables3);
        assertEquals(3, tables3.size());

        // Stop the history (which should stop the producer) ...
        history.stop();
        history = new PulsarSchemaHistory();
        history.configure(configBuilder.build(), null, SchemaHistoryListener.NOOP, true);
        // no need to start

        // Recover from the very beginning to just past the first change ...
        Tables recoveredTables = new Tables();
        setLogPosition(15);
        history.recover(source, position, recoveredTables, recoveryParser);
        assertEquals(recoveredTables, tables1);

        // Recover from the very beginning to just past the second change ...
        recoveredTables = new Tables();
        setLogPosition(50);
        history.recover(source, position, recoveredTables, recoveryParser);
        assertEquals(recoveredTables, tables2);

        // Recover from the very beginning to just past the third change ...
        recoveredTables = new Tables();
        setLogPosition(10010);
        history.recover(source, position, recoveredTables, recoveryParser);
        assertEquals(recoveredTables, tables3);

        // Recover from the very beginning to way past the third change ...
        recoveredTables = new Tables();
        setLogPosition(100000010);
        history.recover(source, position, recoveredTables, recoveryParser);
        assertEquals(recoveredTables, tables3);
    }

    protected void setLogPosition(int index) {
        this.position = Collect.hashMapOf("filename", "my-txn-file.log",
            "position", index);
    }

    @Test
    public void shouldStartWithEmptyTopicAndStoreDataAndRecoverAllState() throws Exception {
        testHistoryTopicContent(false, false);
    }

    @Test
    public void shouldIgnoreUnparseableMessages() throws Exception {
        try (final Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
            .topic(topicName)
            .create()
        ) {
            producer.send("");
            producer.send("{\"position\":{\"filename\":\"my-txn-file.log\",\"position\":39},\"databaseName\":\"db1\","
                    + "\"ddl\":\"DROP TABLE foo;\"}");
            producer.send("{\"source\":{\"server\":\"my-server\"},\"databaseName\":\"db1\",\"ddl\":"
                    + "\"DROP TABLE foo;\"}");
            producer.send("{\"source\":{\"server\":\"my-server\"},\"position\":{\"filename\":\"my-txn-file.log\","
                    + "\"position\":39},\"databaseName\":\"db1\",\"ddl\":\"DROP TABLE foo;\"");
            producer.send("\"source\":{\"server\":\"my-server\"},\"position\":{\"filename\":\"my-txn-file.log\","
                    + "\"position\":39},\"databaseName\":\"db1\",\"ddl\":\"DROP TABLE foo;\"}");
            producer.send("{\"source\":{\"server\":\"my-server\"},\"position\":{\"filename\":\"my-txn-file.log\","
                    + "\"position\":39},\"databaseName\":\"db1\",\"ddl\":\"xxxDROP TABLE foo;\"}");
        }

        testHistoryTopicContent(true, false);
    }

    @Test(expectedExceptions = ParsingException.class)
    public void shouldStopOnUnparseableSQL() throws Exception {
        try (final Producer<String> producer = pulsarClient.newProducer(Schema.STRING).topic(topicName).create()) {
            producer.send("{\"source\":{\"server\":\"my-server\"},\"position\":{\"filename\":\"my-txn-file.log\","
                    + "\"position\":39},\"databaseName\":\"db1\",\"ddl\":\"xxxDROP TABLE foo;\"}");
        }

        testHistoryTopicContent(false, false);
    }

    @Test
    public void testExists() throws Exception {
        testHistoryTopicContent(true, false);
        assertTrue(history.exists());

        // Set history to use dummy topic
        Configuration config = Configuration.create()
            .with(PulsarSchemaHistory.SERVICE_URL, pulsarContainer.getPulsarBrokerUrl())
            .with(PulsarSchemaHistory.TOPIC, "persistent://public/my-ns/dummytopic")
            .with(SchemaHistory.NAME, "my-db-history")
            .with(SchemaHistory.SKIP_UNPARSEABLE_DDL_STATEMENTS, true)
            .build();

        history.configure(config, null, SchemaHistoryListener.NOOP, true);
        history.start();

        // dummytopic should not exist yet
        assertFalse(history.exists());
    }

    @Test
    public void testSubscriptionName() throws Exception {
        testHistoryTopicContent(true, true);
        assertTrue(history.exists());
        try (Reader<String> ignored = history.createHistoryReader()) {
            List<String> subscriptions = admin.topics().getSubscriptions(topicName);
            assertEquals(subscriptions.size(), 1);
            assertTrue(subscriptions.contains("my-subscription"));
        } catch (Exception e) {
            fail("Failed to create history reader");
        }
    }
}
