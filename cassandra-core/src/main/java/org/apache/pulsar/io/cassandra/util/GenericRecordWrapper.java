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

import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;

public class GenericRecordWrapper extends RecordWrapper<GenericRecord> {

    public GenericRecordWrapper(GenericRecord value) {
        super(value);
    }

    @Override
    public Object get(TableMetadataProvider.ColumnId column) {
        return getValueAsExpectedType(recordValue.getField(column.getName()), column);
    }

    @Override
    public boolean containsKey(String name) {
        // Ask the schema rather than the value. GenericAvroRecord.getField(name) delegates to Avro,
        // which throws AvroRuntimeException for a name the schema does not carry instead of returning
        // null. A table column the record has no field for is the ordinary case here — the table
        // decides the column list, not the record — so this has to answer false, not fail the write.
        // Iterated rather than streamed: this is asked once per table column for every record
        // written, and a record's field list is short enough that the stream would cost more than
        // the scan.
        for (Field field : recordValue.getFields()) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
