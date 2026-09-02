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

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.cassandra.util.BoundStatementProvider;
import org.apache.pulsar.io.cassandra.util.CassandraConnector;
import org.apache.pulsar.io.cassandra.util.RecordWrapper;
import org.apache.pulsar.io.cassandra.util.TableMetadataProvider;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;

/**
 * Base class for Cassandra sinks that map a structured record onto the columns of the
 * target table.
 *
 * <p>Unlike {@link CassandraAbstractSink}, which writes a fixed key/value column pair named
 * by {@code keyname} and {@code columnName}, this base reads the table definition from the
 * cluster metadata and binds every column it can find a matching field for. Subclasses only
 * have to say how to read a field out of their record type, by returning the appropriate
 * {@link RecordWrapper}.
 *
 * <p>{@link CassandraAbstractSink} and its {@code cassandra} sink are deliberately left
 * alone: their key/value behaviour is what existing deployments are configured against.
 */
@Slf4j
public abstract class CassandraTableSink<T> implements Sink<T> {

    CassandraConnector connector;
    CassandraSinkConfig cassandraSinkConfig;
    PreparedStatement stmt;
    BoundStatementProvider boundStatementProvider;

    @Override
    public void open(Map<String, Object> config, SinkContext ctx) throws Exception {

        cassandraSinkConfig = IOConfigUtils.loadWithSecrets(config, CassandraSinkConfig.class, ctx);

        if (cassandraSinkConfig.getRoots() == null
                || cassandraSinkConfig.getKeyspace() == null
                || cassandraSinkConfig.getColumnFamily() == null) {
            throw new IllegalArgumentException("Required property not set.");
        }
        cassandraSinkConfig.validateCredentials();

        connector = new CassandraConnector(cassandraSinkConfig);
        connector.connect();

        TableMetadataProvider.TableDefinition table = TableMetadataProvider.getTableDefinition(
                connector.getTableMetadata(),
                cassandraSinkConfig.getKeyspace(),
                cassandraSinkConfig.getColumnFamily());
        rejectUnsupportedColumnTypes(table);
        boundStatementProvider = new BoundStatementProvider(table);
    }

    @Override
    public void write(Record<T> record) throws Exception {

        BoundStatement bs;
        try {
            bs = boundStatementProvider.bindStatement(getStatement(), wrapRecord(record));
        } catch (Exception e) {
            // Everything before the statement is handed to the driver can fail on the record's own
            // content: malformed JSON, a null value, a field that will not coerce to its column's type.
            // Without this the exception leaves write() having neither acked nor failed the record, so
            // the sink dies, Pulsar redelivers the same message, and one bad message becomes a restart
            // loop. Failing it explicitly keeps the poison message the broker's problem, not ours.
            log.error("Discarding record that could not be bound to {}.{}",
                    cassandraSinkConfig.getKeyspace(), cassandraSinkConfig.getColumnFamily(), e);
            record.fail();
            return;
        }

        ResultSetFuture future = connector.getSession().executeAsync(bs);

        Futures.addCallback(future,
                new FutureCallback<ResultSet>() {
                    @Override
                    public void onSuccess(ResultSet result) {
                        record.ack();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        record.fail();
                    }
                }, MoreExecutors.directExecutor());
    }

    @Override
    public void close() {
        if (connector != null) {
            connector.close();
        }
    }

    /**
     * Refuses a table holding a column this sink cannot bind a value to. Every column is a candidate
     * for every record, so one unsupported column means writes fail — either on every record, or, for
     * the integer widths, on whichever records happen to carry a value of the wrong magnitude. Saying
     * so at {@code open()} names the column and its type once, instead of leaving an operator to read
     * an {@code InvalidTypeException} per message.
     */
    private void rejectUnsupportedColumnTypes(TableMetadataProvider.TableDefinition table) {
        for (TableMetadataProvider.ColumnId column : table.getColumns()) {
            if (!RecordWrapper.supports(column.getType())) {
                throw new IllegalArgumentException("Column '" + column.getName() + "' of "
                        + cassandraSinkConfig.getKeyspace() + "." + cassandraSinkConfig.getColumnFamily()
                        + " has type '" + column.getType()
                        + "', which this sink cannot map a record field onto. Supported column types: "
                        + RecordWrapper.supportedColumnTypes() + ".");
            }
        }
    }

    abstract RecordWrapper<T> wrapRecord(Record<T> record);

    PreparedStatement getStatement() {
        if (stmt == null) {
            stmt = connector.getPreparedStatement();
        }
        return stmt;
    }
}
