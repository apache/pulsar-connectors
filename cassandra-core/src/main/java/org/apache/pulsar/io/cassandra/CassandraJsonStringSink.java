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

import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.cassandra.util.RecordWrapper;
import org.apache.pulsar.io.cassandra.util.StringRecordWrapper;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;

/**
 * Cassandra sink for topics carrying raw JSON strings with no registered schema.
 *
 * <p>Each message value is parsed as a JSON object and its top-level fields are matched by
 * name against the columns of the target table.
 *
 * <p>This is distinct from the {@code cassandra} sink ({@link CassandraStringSink}), which
 * treats the message as an opaque string and writes it to a single configured column. A
 * deployment wanting JSON-to-column mapping has to select this sink explicitly; the
 * behaviour of the existing {@code cassandra} sink is unchanged.
 */
@Connector(
    name = "cassandra-json",
    type = IOType.SINK,
    help = "Writes raw JSON string messages to Cassandra, mapping top-level JSON fields onto table columns by name. "
            + "The target table's columns must all be of type text, varchar, ascii, int, double, float or boolean; "
            + "any other column type is rejected when the sink starts.",
    configClass = CassandraSinkConfig.class)
public class CassandraJsonStringSink extends CassandraTableSink<String> {

    @Override
    RecordWrapper<String> wrapRecord(Record<String> record) {
        return new StringRecordWrapper(record.getValue());
    }
}
