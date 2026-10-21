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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.testng.annotations.Test;

/**
 * Covers what each sink accepts and rejects at {@code open()} before it reaches the cluster, where
 * the three sinks share one {@link CassandraSinkConfig} but do not share one contract.
 *
 * <p>{@code keyname} and {@code columnName} are the divergence. They name the two columns the
 * {@code cassandra} sink writes, and mean nothing to the table-mapping sinks, which read the column
 * list from the table itself. Marking them {@code required = true} would have
 * {@code IOConfigUtils.loadWithSecrets} reject a table-sink config that omits them — before the sink
 * gets to say it does not need them — so they are optional on the config and enforced by
 * {@link CassandraAbstractSink} for the sink that does need them. Both halves of that are asserted
 * here, because either alone would be wrong.
 *
 * <p>No container: {@code roots} points at a port nothing listens on. A configuration that gets as
 * far as failing to connect is one that passed validation, which is what makes the accepting cases
 * below say anything.
 */
public class CassandraSinkConfigValidationTest {

    @Test
    public void genericRecordSinkDoesNotRequireKeynameOrColumnName() {
        assertValidationPasses(new CassandraGenericRecordSink(), tableSinkConfig());
    }

    @Test
    public void jsonStringSinkDoesNotRequireKeynameOrColumnName() {
        assertValidationPasses(new CassandraJsonStringSink(), tableSinkConfig());
    }

    @Test
    public void stringSinkStillRequiresKeyname() {
        Map<String, Object> config = tableSinkConfig();
        config.put("columnName", "value");

        assertRejected(new CassandraStringSink(), config, "Required property not set.");
    }

    @Test
    public void stringSinkStillRequiresColumnName() {
        Map<String, Object> config = tableSinkConfig();
        config.put("keyname", "key");

        assertRejected(new CassandraStringSink(), config, "Required property not set.");
    }

    @Test
    public void genericRecordSinkRejectsUsernameWithoutPassword() {
        Map<String, Object> config = tableSinkConfig();
        config.put("userName", "cassandra");

        assertRejected(new CassandraGenericRecordSink(), config,
                "userName and password must be supplied together");
    }

    @Test
    public void jsonStringSinkRejectsPasswordWithoutUsername() {
        Map<String, Object> config = tableSinkConfig();
        config.put("password", "cassandra");

        assertRejected(new CassandraJsonStringSink(), config,
                "userName and password must be supplied together");
    }

    private void assertValidationPasses(Sink<?> sink, Map<String, Object> config) {
        try {
            sink.open(config, mock(SinkContext.class));
            fail("Expected the unreachable contact point to fail the connection");
        } catch (IllegalArgumentException e) {
            fail("Rejected as invalid rather than reaching the cluster: " + e.getMessage());
        } catch (Exception e) {
            // Anything else means validation passed and the driver got as far as the network.
        }
    }

    private void assertRejected(Sink<?> sink, Map<String, Object> config, String expected) {
        try {
            sink.open(config, mock(SinkContext.class));
            fail("Expected open() to reject this configuration");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(expected),
                    "Rejected for some other reason: " + e.getMessage());
        } catch (Exception e) {
            fail("Expected IllegalArgumentException, got: " + e);
        }
    }

    /**
     * The settings a table-mapping sink needs, and nothing else: no {@code keyname}, no
     * {@code columnName}. Nothing listens on the port named here.
     */
    private Map<String, Object> tableSinkConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("roots", "127.0.0.1:1");
        config.put("keyspace", "any_keyspace");
        config.put("columnFamily", "any_table");
        return config;
    }
}
