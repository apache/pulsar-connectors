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

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;

@SuppressWarnings("rawtypes")
public class BoundStatementProvider {
    final TableMetadataProvider.TableDefinition tableDefinition;

    public BoundStatementProvider(TableMetadataProvider.TableDefinition tableDefinition) {
        this.tableDefinition = tableDefinition;
    }

    public BoundStatement bindStatement(PreparedStatement stmt, RecordWrapper wrapper) {
        Object[] boundValues = new Object[tableDefinition.getColumns().size()];
        boolean[] supplied = new boolean[boundValues.length];
        int idx = 0;

        for (TableMetadataProvider.ColumnId column : tableDefinition.getColumns()) {
            if (wrapper.containsKey(column.getName())) {
                // A field the record carries as null stays an explicit null: saying a column has no
                // value is not the same as not mentioning the column.
                boundValues[idx] = wrapper.get(column);
                supplied[idx] = true;
            }
            idx++;
        }

        BoundStatement bound = stmt.bind(boundValues);
        // bind(Object...) writes a real null into every position the record had no field for, and in
        // Cassandra a null is a deletion: re-writing a row would erase the columns this record says
        // nothing about, and every insert would leave a tombstone per absent column for compaction to
        // deal with. Unsetting those positions restores "leave whatever is there alone", which is what
        // mapping fields onto the columns they match should mean.
        for (int i = 0; i < supplied.length; i++) {
            if (!supplied[i]) {
                bound.unset(i);
            }
        }
        return bound;
    }

}
