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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.pulsar.io.core.annotations.FieldDoc;

@Data
@Accessors(chain = true)
public class CassandraSinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @FieldDoc(
        required = false,
        defaultValue = "",
        sensitive = true,
        help = "Username used to authenticate against the cluster specified by `roots`. "
                + "Must be set together with the other half of the pair; leave both unset "
                + "for a cluster that does not require authentication.")
    private String userName;
    @FieldDoc(
        required = false,
        defaultValue = "",
        sensitive = true,
        help = "Password used to authenticate against the cluster specified by `roots`. "
                + "Must be set together with the other half of the pair; leave both unset "
                + "for a cluster that does not require authentication.")
    private String password;
    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "A comma-separated list of cassandra hosts to connect to")
    private String roots;
    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "The key space used for writing pulsar messages to")
    private String keyspace;
    // Not required = true: that is enforced at load time for every sink sharing this config, and the
    // table-mapping sinks have no use for it. `cassandra` still requires it, and checks so itself in
    // CassandraAbstractSink.open().
    @FieldDoc(
        required = false,
        defaultValue = "",
        help = "The key name of the cassandra column family. Required by the `cassandra` sink. "
                + "Unused by `cassandra-generic-record` and `cassandra-json`, which map record "
                + "fields onto columns by name.")
    private String keyname;
    @FieldDoc(
        required = true,
        defaultValue = "",
        help = "The cassandra column family name")
    private String columnFamily;
    @FieldDoc(
        required = false,
        defaultValue = "",
        help = "The column name of the cassandra column family. Required by the `cassandra` sink. "
                + "Unused by `cassandra-generic-record` and `cassandra-json`, which map record "
                + "fields onto columns by name.")
    private String columnName;

    public static CassandraSinkConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), CassandraSinkConfig.class);
    }

    public static CassandraSinkConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(map), CassandraSinkConfig.class);
    }

    /**
     * Rejects a credential pair with only one half set. Half a pair is never a meaningful
     * configuration — whichever half is present, the intent was to authenticate — and connecting
     * unauthenticated instead turns a typo into either a rejection from the server that names
     * neither setting, or a silent success against a cluster that does not yet require
     * authentication. Sinks call this before connecting, so the failure names what is wrong.
     */
    public void validateCredentials() {
        if (hasText(userName) != hasText(password)) {
            throw new IllegalArgumentException("userName and password must be supplied together: "
                    + "set both to authenticate, or neither to connect to a cluster that does not "
                    + "require authentication.");
        }
    }

    /**
     * Whether to authenticate at all. Only ever true for a complete pair, and
     * {@link #validateCredentials()} has already rejected an incomplete one.
     */
    public boolean hasCredentials() {
        return hasText(userName) && hasText(password);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}