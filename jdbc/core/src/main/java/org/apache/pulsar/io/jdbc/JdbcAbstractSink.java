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
package org.apache.pulsar.io.jdbc;

import com.google.common.collect.Lists;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;

/**
 * A Simple abstract class for Jdbc sink.
 */
@Slf4j
public abstract class JdbcAbstractSink<T> implements Sink<T> {

    private enum State {
        OPEN, FAILED, CLOSED
    }

    // ----- Runtime fields
    protected JdbcSinkConfig jdbcSinkConfig;
    @Getter
    private Connection connection;
    private String jdbcUrl;
    private String tableName;

    private JdbcUtils.TableId tableId;
    private PreparedStatement insertStatement;
    private PreparedStatement updateStatement;
    private PreparedStatement upsertStatement;
    private PreparedStatement deleteStatement;


    protected static final String ACTION_PROPERTY = "ACTION";

    // How long a write() blocked on a full queue waits at a time before re-checking that the sink
    // is still OPEN. It does not limit how long write() blocks in total.
    private static final long QUEUE_OFFER_INTERVAL_SECONDS = 1;
    // Minimum interval between warnings while writes are blocked on a full queue
    private static final long QUEUE_FULL_WARN_INTERVAL_SECONDS = 60;
    // Minimum time close() waits for an in-flight flush before interrupting it. close() waits
    // at least twice timeoutMs.
    private static final long MIN_CLOSE_FLUSH_WAIT_MS = 5000;

    protected JdbcUtils.TableDefinition tableDefinition;

    // for flush
    private LinkedBlockingDeque<Record<T>> incomingList;
    private AtomicBoolean isFlushing;
    private int batchSize;
    private int maxQueueSize;
    private volatile ScheduledExecutorService flushExecutor;
    private ScheduledFuture<?> scheduledFlushTask;
    private SinkContext sinkContext;
    private Properties connectionProperties;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    // Timed-out queue waits since the last "queue is full" warning, and when (System.nanoTime(),
    // 0 if never) that warning was logged
    private final AtomicLong queueFullTimeouts = new AtomicLong();
    private final AtomicLong queueFullLastWarnNanos = new AtomicLong();

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        this.sinkContext = sinkContext;
        jdbcSinkConfig = JdbcSinkConfig.load(config, sinkContext);
        jdbcSinkConfig.validate();

        jdbcUrl = jdbcSinkConfig.getJdbcUrl();
        if (jdbcSinkConfig.getJdbcUrl() == null) {
            throw new IllegalArgumentException("Required jdbc Url not set.");
        }

        connectionProperties = new Properties();
        String username = jdbcSinkConfig.getUserName();
        String password = jdbcSinkConfig.getPassword();
        if (username != null) {
            connectionProperties.setProperty("user", username);
        }
        if (password != null) {
            connectionProperties.setProperty("password", password);
        }

        connection = DriverManager.getConnection(jdbcSinkConfig.getJdbcUrl(), connectionProperties);
        connection.setAutoCommit(!jdbcSinkConfig.isUseTransactions());
        log.info("Opened jdbc connection: {}, autoCommit: {}", jdbcUrl, connection.getAutoCommit());

        tableName = jdbcSinkConfig.getTableName();
        tableId = JdbcUtils.getTableId(connection, tableName);
        // Init PreparedStatement include insert, delete, update
        initStatement();

        int timeoutMs = jdbcSinkConfig.getTimeoutMs();
        batchSize = jdbcSinkConfig.getBatchSize();
        maxQueueSize = jdbcSinkConfig.getMaxQueueSize();
        if (maxQueueSize == 0) {
            // Auto-size: default to 10x batch size (overflow-safe)
            long calculated = batchSize > 0 ? (long) batchSize * 10L : 10000L;
            maxQueueSize = calculated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) calculated;
        }
        // maxQueueSize < 0 (i.e. -1) means unbounded (legacy behavior)
        log.info("JDBC sink queue capacity: {}", maxQueueSize > 0 ? maxQueueSize : "unbounded");
        if (maxQueueSize > 0) {
            incomingList = new LinkedBlockingDeque<>(maxQueueSize);
        } else {
            incomingList = new LinkedBlockingDeque<>();
        }
        isFlushing = new AtomicBoolean(false);

        flushExecutor = Executors.newScheduledThreadPool(1);
        if (timeoutMs > 0) {
            scheduledFlushTask = flushExecutor.scheduleAtFixedRate(
                    this::flush, timeoutMs, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void initStatement()  throws Exception {
        List<String> keyList = getListFromConfig(jdbcSinkConfig.getKey());
        List<String> nonKeyList = getListFromConfig(jdbcSinkConfig.getNonKey());

        tableDefinition = JdbcUtils.getTableDefinition(connection, tableId,
                keyList, nonKeyList, jdbcSinkConfig.isExcludeNonDeclaredFields());
        insertStatement = connection.prepareStatement(generateInsertQueryStatement());

        if (jdbcSinkConfig.getInsertMode() == JdbcSinkConfig.InsertMode.UPSERT) {
            if (nonKeyList.isEmpty() || keyList.isEmpty()) {
                throw new IllegalStateException("UPSERT mode is not configured if 'key' and 'nonKey' "
                        + "config are not set.");
            }
            upsertStatement = connection.prepareStatement(generateUpsertQueryStatement());
        }
        if (!nonKeyList.isEmpty()) {
            updateStatement = connection.prepareStatement(generateUpdateQueryStatement());
        }
        if (!keyList.isEmpty()) {
            deleteStatement = connection.prepareStatement(generateDeleteQueryStatement());
        }
    }

    private static List<String> getListFromConfig(String jdbcSinkConfig) {
        List<String> nonKeyList = Lists.newArrayList();
        String nonKey = jdbcSinkConfig;
        if (nonKey != null && !nonKey.isEmpty()) {
            nonKeyList = Arrays.asList(nonKey.split(","));
        }
        return nonKeyList;
    }

    @Override
    public void close() throws Exception {
        state.set(State.CLOSED);
        if (scheduledFlushTask != null) {
            scheduledFlushTask.cancel(false);
            scheduledFlushTask = null;
        }
        try {
            // Let an in-flight flush finish its current round (flush() does not start another one
            // once the sink is CLOSED) before the queued records are failed and the connection is
            // closed under it. A flush that does not finish in time is interrupted.
            // flushExecutor is null if open() did not get far enough to create it.
            if (flushExecutor != null) {
                long awaitMs = Math.max(2L * jdbcSinkConfig.getTimeoutMs(), MIN_CLOSE_FLUSH_WAIT_MS);
                flushExecutor.shutdown();
                try {
                    if (!flushExecutor.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)) {
                        log.warn("In-flight flush did not finish within {} ms of closing the sink, "
                                + "interrupting it", awaitMs);
                        flushExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for the in-flight flush to finish, interrupting it");
                    Thread.currentThread().interrupt();
                    flushExecutor.shutdownNow();
                }
                flushExecutor = null;
            }
        } finally {
            failQueuedRecords();
            if (insertStatement != null) {
                insertStatement.close();
            }
            if (updateStatement != null) {
                updateStatement.close();
            }
            if (upsertStatement != null) {
                upsertStatement.close();
            }
            if (deleteStatement != null) {
                deleteStatement.close();
            }
            if (connection != null && jdbcSinkConfig.isUseTransactions()) {
                connection.commit();
            }
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
        log.info("Closed jdbc connection: {}", jdbcUrl);
    }

    /**
     * Fail the records still in the queue. incomingList is null if open() did not get far enough
     * to create it. Records are polled one at a time rather than drained into a list so that a
     * large (or unbounded) queue is not copied during shutdown.
     * Record.fail() may throw (the Pulsar source's fail function always throws under
     * EFFECTIVELY_ONCE). Keep draining so that close() can finish the cleanup; log once at the end.
     */
    private void failQueuedRecords() {
        if (incomingList == null) {
            return;
        }
        Record<T> remaining;
        int failErrors = 0;
        RuntimeException firstFailError = null;
        while ((remaining = incomingList.poll()) != null) {
            try {
                remaining.fail();
            } catch (RuntimeException e) {
                if (firstFailError == null) {
                    firstFailError = e;
                }
                failErrors++;
            }
        }
        if (firstFailError != null) {
            log.warn("Record.fail() threw for {} queued record(s) while closing the sink",
                    failErrors, firstFailError);
        }
    }

    @Override
    public void write(Record<T> record) throws Exception {
        State current = state.get();
        if (current != State.OPEN) {
            log.warn("Sink is not in OPEN state (current: {}), failing record", current);
            record.fail();
            return;
        }

        // Block until there is space in the queue. Blocking the Pulsar IO thread IS the
        // back-pressure: it pauses message consumption for this sink. The record is not failed
        // just because the queue stays full: a negative ack may redeliver it after newer messages
        // with the same key (e.g. on a Key_Shared subscription), so an older record could
        // overwrite a newer row. The offer is timed only so that a waiting write() notices when
        // the sink is closed or fails.
        try {
            while (!incomingList.offer(record, QUEUE_OFFER_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                current = state.get();
                if (current != State.OPEN) {
                    log.warn("Sink became {} while waiting for queue space, failing record", current);
                    record.fail();
                    return;
                }
                logQueueFullTimeout();
                // Safety net: schedule a flush, so that a full queue drains even when none is due
                scheduleFlush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.fail();
            return;
        }

        // close() or fatal() may have run while this thread was waiting in offer(). If so, the
        // queue may never be flushed again, so take the record back and fail it. The record was
        // just added at the tail, so search from there. If it is not found, flush() or close()
        // has already drained the record and owns its ack/fail.
        current = state.get();
        if (current != State.OPEN) {
            if (incomingList.removeLastOccurrence(record)) {
                log.warn("Sink became {} after record was queued, failing record", current);
                record.fail();
            }
            return;
        }

        int number = incomingList.size();
        if (batchSize > 0 && number >= batchSize) {
            if (log.isDebugEnabled()) {
                log.debug("flushing by batches, hit batch size {}", batchSize);
            }
            scheduleFlush();
        }
    }

    /**
     * Called each time a write() blocked on a full queue has waited one offer interval. Logs every
     * timeout at DEBUG, and a WARN with the number of timeouts at most once per
     * {@link #QUEUE_FULL_WARN_INTERVAL_SECONDS}.
     */
    private void logQueueFullTimeout() {
        queueFullTimeouts.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("Internal queue is still full (capacity {}) after waiting {} s, write() keeps waiting",
                    maxQueueSize, QUEUE_OFFER_INTERVAL_SECONDS);
        }
        long now = System.nanoTime();
        long lastWarn = queueFullLastWarnNanos.get();
        if ((lastWarn == 0 || now - lastWarn >= TimeUnit.SECONDS.toNanos(QUEUE_FULL_WARN_INTERVAL_SECONDS))
                && queueFullLastWarnNanos.compareAndSet(lastWarn, now)) {
            log.warn("Internal queue is full (capacity {}), blocking writes until space frees up; "
                    + "{} wait(s) of {} s timed out since the last warning",
                    maxQueueSize, queueFullTimeouts.getAndSet(0), QUEUE_OFFER_INTERVAL_SECONDS);
        }
    }

    private void scheduleFlush() {
        // close() may have shut the executor down, or cleared the field, since write() checked the
        // state. close() fails the queued records, so there is nothing left to flush then.
        ScheduledExecutorService executor = flushExecutor;
        if (executor == null) {
            return;
        }
        try {
            executor.schedule(this::flush, 0, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // A no-op after close(), since fatal() only acts on an OPEN sink
            fatal(e);
        }
    }

    public String generateInsertQueryStatement() {
        return JdbcUtils.buildInsertSql(tableDefinition);
    }

    public String generateUpdateQueryStatement() {
        return JdbcUtils.buildUpdateSql(tableDefinition);
    }

    public abstract String generateUpsertQueryStatement();

    public abstract List<JdbcUtils.ColumnId> getColumnsForUpsert();

    public String generateDeleteQueryStatement() {
        return JdbcUtils.buildDeleteSql(tableDefinition);
    }

    // bind value with a PreparedStetement
    public abstract void bindValue(
        PreparedStatement statement,
        Mutation mutation) throws Exception;

    public abstract Mutation createMutation(Record<T> message);

    @Data
    @AllArgsConstructor
    protected static class Mutation {
        private MutationType type;
        private Function<String, Object> values;
    }
    protected enum MutationType {
        INSERT,
        UPDATE,
        UPSERT,
        DELETE
    }


    private void flush() {
        // Do not flush once the sink has failed, e.g. a flush queued before an earlier one failed:
        // it would ack records newer than the failed batch, which is redelivered and could then
        // overwrite them. The records stay queued and close() fails them.
        if (state.get() != State.OPEN) {
            return;
        }
        if (!isFlushing.compareAndSet(false, true)) {
            if (log.isDebugEnabled()) {
                log.debug("Already in flushing state, will not flush, queue size: {}", incomingList.size());
            }
            return;
        }
        // Drain queued batches iteratively. This was previously a tail-recursive call
        // (flush() calling itself when another full batch was queued), which under sustained
        // catch-up load never unwinds the stack and eventually throws StackOverflowError.
        try {
            boolean needAnotherRound = false;
            do {
                final Deque<Record<T>> swapList = new LinkedList<>();

                // Re-check before each round: the sink may have been closed, or have failed,
                // while the previous round was running. The records stay queued and close()
                // fails them.
                if (state.get() != State.OPEN) {
                    return;
                }
                int drainSize = batchSize > 0 ? batchSize : Integer.MAX_VALUE;
                incomingList.drainTo(swapList, drainSize);
                if (swapList.isEmpty()) {
                    return;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Starting flush, drained {} records, queue remaining: {}",
                            swapList.size(), incomingList.size());
                }
                needAnotherRound = batchSize > 0 && !incomingList.isEmpty()
                        && incomingList.size() >= batchSize;
                long start = System.nanoTime();

                int count = 0;
                try {
                    ensureConnection();

                    PreparedStatement currentBatch = null;
                    final List<Mutation> mutations = swapList
                            .stream()
                            .map(this::createMutation)
                            .collect(Collectors.toList());
                    // bind each record value
                    PreparedStatement statement;
                    for (Mutation mutation : mutations) {
                        switch (mutation.getType()) {
                            case DELETE:
                                statement = deleteStatement;
                                break;
                            case UPDATE:
                                statement = updateStatement;
                                break;
                            case INSERT:
                                statement = insertStatement;
                                break;
                            case UPSERT:
                                statement = upsertStatement;
                                break;
                            default:
                                String msg = String.format(
                                        "Unsupported action %s, can be one of %s, or not set which indicate %s",
                                        mutation.getType(), Arrays.toString(MutationType.values()),
                                        MutationType.INSERT);
                                throw new IllegalArgumentException(msg);
                        }
                        bindValue(statement, mutation);
                        count += 1;
                        if (jdbcSinkConfig.isUseJdbcBatch()) {
                            if (currentBatch != null && statement != currentBatch) {
                                internalFlushBatch(swapList, currentBatch, count, start);
                                start = System.nanoTime();
                            }
                            statement.addBatch();
                            currentBatch = statement;
                        } else {
                            statement.execute();
                            if (!jdbcSinkConfig.isUseTransactions()) {
                                swapList.removeFirst().ack();
                            }
                        }
                    }

                    if (jdbcSinkConfig.isUseJdbcBatch()) {
                        internalFlushBatch(swapList, currentBatch, count, start);
                    } else {
                        internalFlush(swapList);
                    }
                } catch (Throwable e) {
                    // Catch Throwable (not just Exception) so an Error such as StackOverflowError
                    // is surfaced to the framework via fatal() and the instance is terminated and
                    // restarted, instead of the flush thread dying silently while the pod keeps
                    // reporting healthy.
                    // Log toString() rather than getMessage(): an Error such as
                    // StackOverflowError usually carries no message, which would render as
                    // "null" and hide what actually failed.
                    log.error("Got throwable {} after {} ms, failing {} messages",
                            e.toString(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                            swapList.size(),
                            e);
                    // Record.fail() may throw (the Pulsar source's fail function always throws
                    // under EFFECTIVELY_ONCE). Keep failing the other records and still roll back
                    // and call fatal(). e may itself have been thrown by fail() in executeBatch(),
                    // and a fail function may throw the same instance again, which
                    // addSuppressed() rejects.
                    for (Record<T> r : swapList) {
                        try {
                            r.fail();
                        } catch (RuntimeException failEx) {
                            if (failEx != e) {
                                e.addSuppressed(failEx);
                            }
                        }
                    }
                    try {
                        if (jdbcSinkConfig.isUseTransactions()) {
                            connection.rollback();
                        }
                    } catch (Exception ex) {
                        log.error("Failed to rollback transaction", ex);
                    }
                    fatal(e);
                    needAnotherRound = false;
                }
            } while (needAnotherRound);
        } finally {
            // Always release the flag, even if an Error escaped the inner try, otherwise the
            // sink would be permanently stuck in the flushing state.
            isFlushing.set(false);
        }
    }

    private void ensureConnection() throws Exception {
        try {
            if (connection != null && connection.isValid(2)) {
                return;
            }
        } catch (SQLException e) {
            log.warn("Connection validation failed: {}", e.getMessage());
        }

        log.info("JDBC connection is invalid, attempting to reconnect to: {}", jdbcUrl);
        closeConnectionQuietly();

        connection = DriverManager.getConnection(jdbcSinkConfig.getJdbcUrl(), connectionProperties);
        connection.setAutoCommit(!jdbcSinkConfig.isUseTransactions());

        tableId = JdbcUtils.getTableId(connection, tableName);
        initStatement();

        log.info("Successfully reconnected to: {}", jdbcUrl);
    }

    private void closeConnectionQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("Error closing stale connection", e);
            }
            connection = null;
        }
    }

    private void internalFlush(Deque<Record<T>> swapList) throws SQLException {
        if (jdbcSinkConfig.isUseTransactions()) {
            connection.commit();
            swapList.forEach(Record::ack);
        }
    }

    private void internalFlushBatch(
            Deque<Record<T>> swapList,
            PreparedStatement currentBatch,
            int count,
            long start
    ) throws SQLException {
        executeBatch(swapList, currentBatch);
        if (log.isDebugEnabled()) {
            log.debug("Flushed {} messages in {} ms", count, (System.nanoTime() - start) / 1000 / 1000);
        }
    }

    private void executeBatch(Deque<Record<T>> swapList, PreparedStatement statement) throws SQLException {
        final int[] results = statement.executeBatch();
        Map<Integer, Integer> failuresMapping = null;
        final boolean useTransactions = jdbcSinkConfig.isUseTransactions();

        for (int r: results) {
            if (isBatchItemFailed(r)) {
                if (failuresMapping == null) {
                    failuresMapping = new HashMap<>();
                }
                final Integer current = failuresMapping.computeIfAbsent(r, code -> 1);
                failuresMapping.put(r, current + 1);
            }
        }
        if (failuresMapping == null || failuresMapping.isEmpty()) {
            if (useTransactions) {
                connection.commit();
            }
            for (int r: results) {
                swapList.removeFirst().ack();
            }
        } else {
            if (useTransactions) {
                connection.rollback();
            }
            for (int r: results) {
                swapList.removeFirst().fail();
            }
            String msg = "Batch failed, got error results (error_code->count): " + failuresMapping;
            // throwing an exception here means the main loop cycle will nack the messages in the next batch
            throw new SQLException(msg);
        }
    }

    private static boolean isBatchItemFailed(int returnCode) {
        if (returnCode == Statement.SUCCESS_NO_INFO || returnCode >= 0) {
            return false;
        }
        return true;
    }

    /**
     * Signal a fatal exception to the framework.
     * This will cause the function instance to terminate properly.
     *
     * @param e the fatal exception
     */
    private void fatal(Throwable e) {
        if (sinkContext != null && state.compareAndSet(State.OPEN, State.FAILED)) {
            log.error("Fatal error in JDBC sink, signaling framework for shutdown", e);
            // close() may clear the field concurrently
            ScheduledFuture<?> flushTask = scheduledFlushTask;
            if (flushTask != null) {
                flushTask.cancel(false);
            }
            sinkContext.fatal(e);
        }
    }

}
