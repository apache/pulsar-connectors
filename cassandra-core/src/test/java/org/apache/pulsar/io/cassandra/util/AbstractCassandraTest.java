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
package org.apache.pulsar.io.cassandra.util;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pulsar.io.cassandra.CassandraSinkConfig;
import org.testcontainers.containers.CassandraContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

/**
 * Starts a Cassandra container once per test class and applies the {@code init.cql} schema that
 * {@link CassandraConnectorTest} and {@link TableMetadataProviderTest} assert against.
 *
 * <p>The schema is applied through the Datastax driver rather than the container's own
 * {@code withInitScript} support: the script delegate in the {@code org.testcontainers.cassandra}
 * module is compiled against shaded classes that the Testcontainers core version resolved here no
 * longer ships, so it fails at runtime. Going through the driver keeps this independent of that.
 */
public class AbstractCassandraTest {

    private static final String INIT_SCRIPT = "init.cql";

    protected CassandraSinkConfig config;
    protected CassandraContainer<?> cassandraContainer;

    @BeforeClass
    public void startCassandraContainer() {
        cassandraContainer = new CassandraContainer<>("cassandra:4.1")
                .withStartupTimeout(Duration.ofMinutes(3));
        cassandraContainer.start();
        applyInitScript();
    }

    @AfterClass(alwaysRun = true)
    public void stopCassandraContainer() {
        if (cassandraContainer != null) {
            cassandraContainer.stop();
            cassandraContainer = null;
        }
    }

    protected void createSinkConfig() {
        config = new CassandraSinkConfig();
        config.setRoots(cassandraContainer.getHost() + ":"
                + cassandraContainer.getMappedPort(CassandraContainer.CQL_PORT));
        config.setUserName(cassandraContainer.getUsername());
        config.setPassword(cassandraContainer.getPassword());
    }

    private void applyInitScript() {
        try (Cluster cluster = cassandraContainer.getCluster();
             Session session = cluster.connect()) {
            for (String statement : readInitStatements()) {
                session.execute(statement);
            }
        }
    }

    private List<String> readInitStatements() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(INIT_SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException(INIT_SCRIPT + " not found on the test classpath");
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Strip line comments before splitting: a ';' never appears inside one in this script.
            String stripped = Arrays.stream(script.split("\n"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(stripped.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
