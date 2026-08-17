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

import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.cassandra.util.GenericRecordWrapper;
import org.apache.pulsar.io.cassandra.util.RecordWrapper;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;

/**
 * Cassandra sink for topics carrying a schema, e.g. Avro or JSON with a registered schema.
 *
 * <p>Each field of the incoming {@link GenericRecord} is matched by name against a column of
 * the target table, so the table definition decides what is written rather than a configured
 * key/value column pair.
 */
@Connector(
    name = "cassandra-generic-record",
    type = IOType.SINK,
    help = "Writes schema-carrying records to Cassandra, mapping record fields onto table columns by name. "
            + "The target table's columns must all be of type text, varchar, ascii, int, double, float or boolean; "
            + "any other column type is rejected when the sink starts.",
    configClass = CassandraSinkConfig.class)
public class CassandraGenericRecordSink extends CassandraTableSink<GenericRecord> {

    @Override
    RecordWrapper<GenericRecord> wrapRecord(Record<GenericRecord> record) {
        return new GenericRecordWrapper(record.getValue());
    }
}
