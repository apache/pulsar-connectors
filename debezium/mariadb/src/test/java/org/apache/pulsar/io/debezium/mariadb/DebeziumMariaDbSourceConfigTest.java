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
package org.apache.pulsar.io.debezium.mariadb;

import static org.testng.Assert.assertEquals;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.connect.runtime.ConnectorConfig;
import org.apache.kafka.connect.runtime.TaskConfig;
import org.testng.annotations.Test;

/**
 * Unit tests for the connector and task class wiring of {@link DebeziumMariaDbSource}.
 *
 * <p>These run without a database: they cover the defaulting and validation behavior that
 * {@code DebeziumSource#throwExceptionIfConfigNotMatch} applies to the connector/task class
 * config keys. Getting either class name wrong is a silent misconfiguration that would only
 * surface at runtime, so it is pinned here.
 */
public class DebeziumMariaDbSourceConfigTest {

    private static final String MARIADB_CONNECTOR = "io.debezium.connector.mariadb.MariaDbConnector";
    private static final String MARIADB_TASK = "io.debezium.connector.mariadb.MariaDbConnectorTask";

    @Test
    public void testConnectorClassDefaultedWhenAbsent() throws Exception {
        Map<String, Object> config = new HashMap<>();

        new DebeziumMariaDbSource().setDbConnectorClass(config);

        assertEquals(config.get(ConnectorConfig.CONNECTOR_CLASS_CONFIG), MARIADB_CONNECTOR);
    }

    @Test
    public void testTaskClassDefaultedWhenAbsent() throws Exception {
        Map<String, Object> config = new HashMap<>();

        new DebeziumMariaDbSource().setDbConnectorTask(config);

        assertEquals(config.get(TaskConfig.TASK_CLASS_CONFIG), MARIADB_TASK);
    }

    @Test
    public void testExplicitMatchingConnectorClassIsAccepted() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, MARIADB_CONNECTOR);

        new DebeziumMariaDbSource().setDbConnectorClass(config);

        assertEquals(config.get(ConnectorConfig.CONNECTOR_CLASS_CONFIG), MARIADB_CONNECTOR);
    }

    @Test
    public void testExplicitMatchingTaskClassIsAccepted() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(TaskConfig.TASK_CLASS_CONFIG, MARIADB_TASK);

        new DebeziumMariaDbSource().setDbConnectorTask(config);

        assertEquals(config.get(TaskConfig.TASK_CLASS_CONFIG), MARIADB_TASK);
    }

    /**
     * Guards against pointing the MariaDB source at the MySQL connector, which is an easy
     * mistake now that both connectors share Debezium's binlog base.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMismatchedConnectorClassIsRejected() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, "io.debezium.connector.mysql.MySqlConnector");

        new DebeziumMariaDbSource().setDbConnectorClass(config);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMismatchedTaskClassIsRejected() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(TaskConfig.TASK_CLASS_CONFIG, "io.debezium.connector.mysql.MySqlConnectorTask");

        new DebeziumMariaDbSource().setDbConnectorTask(config);
    }

    /**
     * The class names are string literals, so a typo would compile. Resolve them to prove
     * they name real classes on the connector's runtime classpath.
     */
    @Test
    public void testConnectorAndTaskClassesResolve() throws Exception {
        assertEquals(Class.forName(MARIADB_CONNECTOR).getName(), MARIADB_CONNECTOR);
        assertEquals(Class.forName(MARIADB_TASK).getName(), MARIADB_TASK);
    }
}
