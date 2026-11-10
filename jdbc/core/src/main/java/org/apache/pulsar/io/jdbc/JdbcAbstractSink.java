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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
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
    // How long close() waits for an interrupted flush to stop
    private static final long CLOSE_INTERRUPTED_FLUSH_WAIT_MS = 1000;

    protected JdbcUtils.TableDefinition tableDefinition;

    // for flush
    private LinkedBlockingDeque<Record<T>> incomingList;
    private AtomicBoolean isFlushing;
    // True while an on-demand flush is scheduled but has not started yet. Used to schedule at most
    // one such flush at a time instead of one per write().
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    private int batchSize;
    private int maxQueueSize;
    private volatile ScheduledExecutorService flushExecutor;
    private ScheduledFuture<?> scheduledFlushTask;
    private SinkContext sinkContext;
    private Properties connectionProperties;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    // Timed-out queue waits since the last "queue is full" warning (reset once a blocked write()
    // gets queue space), and when (System.nanoTime()) that warning was logged. The latter starts
    // one warn interval in the past so that the first warning is logged at once: nanoTime() has
    // no value that could mean "never".
    private final AtomicLong queueFullTimeouts = new AtomicLong();
    private final AtomicLong queueFullLastWarnNanos =
            new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(QUEUE_FULL_WARN_INTERVAL_SECONDS));

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
                    this::flushOrFatal, timeoutMs, timeoutMs, TimeUnit.MILLISECONDS);
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
                        interruptFlush();
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for the in-flight flush to finish, interrupting it");
                    Thread.currentThread().interrupt();
                    interruptFlush();
                }
                flushExecutor = null;
            }
        } finally {
            // failQueuedRecords() rethrows an Error from Record.fail(), after failing every record.
            // Close the statements and the connection before it propagates.
            try {
                failQueuedRecords();
            } finally {
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
        }
        log.info("Closed jdbc connection: {}", jdbcUrl);
    }

    /**
     * Interrupt the in-flight flush and wait for it to stop, so that close() does not fail the
     * queue, close the statements and commit while the flush is still using them. An interrupt
     * status the closing thread already has does not cut the wait short, and is restored after it.
     */
    private void interruptFlush() {
        flushExecutor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!flushExecutor.awaitTermination(CLOSE_INTERRUPTED_FLUSH_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.error("Interrupted flush did not stop within {} ms, closing the sink anyway",
                        CLOSE_INTERRUPTED_FLUSH_WAIT_MS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Fail the records still in the queue. incomingList is null if open() did not get far enough
     * to create it. Records are polled one at a time rather than drained into a list so that a
     * large (or unbounded) queue is not copied during shutdown. failRecords() keeps draining when
     * Record.fail() throws, so that close() can finish the cleanup.
     */
    private void failQueuedRecords() {
        if (incomingList == null) {
            return;
        }
        failRecords(incomingList, Integer.MAX_VALUE, null, "queued while closing the sink");
    }

    /**
     * Remove up to {@code limit} records from the head of {@code records} and fail each of them.
     * A record is removed before it is failed, so it is never failed twice. Record.fail() may throw
     * (the Pulsar source's fail function always throws under EFFECTIVELY_ONCE), even an Error: the
     * remaining records are still failed, and one warning with the first exception and the number
     * of failing fail() calls is logged at the end.
     *
     * @param error the error the records are failed for, or null. Each exception or Error thrown
     *              by fail() is added to it as suppressed. Without it, the first Error thrown by
     *              fail() is rethrown once every record has been failed.
     * @param description completes the warning, e.g. "queued while closing the sink"
     */
    private void failRecords(Queue<Record<T>> records, int limit, Throwable error, String description) {
        int failErrors = 0;
        Throwable firstFailError = null;
        Error firstError = null;
        Record<T> record;
        for (int i = 0; i < limit && (record = records.poll()) != null; i++) {
            try {
                record.fail();
            } catch (Throwable failEx) {
                if (firstFailError == null) {
                    firstFailError = failEx;
                }
                failErrors++;
                if (error != null) {
                    // addSuppressed() rejects the error itself, which a fail function may throw
                    // (e.g. the same instance a record's ack() threw)
                    if (failEx != error) {
                        error.addSuppressed(failEx);
                    }
                } else if (failEx instanceof Error && firstError == null) {
                    firstError = (Error) failEx;
                }
            }
        }
        if (firstFailError != null) {
            log.warn("Record.fail() threw for {} record(s) {}", failErrors, description, firstFailError);
        }
        if (firstError != null) {
            throw firstError;
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
            boolean timedOut = false;
            while (!incomingList.offer(record, QUEUE_OFFER_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                current = state.get();
                if (current != State.OPEN) {
                    log.warn("Sink became {} while waiting for queue space, failing record", current);
                    record.fail();
                    return;
                }
                timedOut = true;
                logQueueFullTimeout();
                // Safety net: schedule a flush, so that a full queue drains even when none is due
                scheduleFlush();
            }
            if (timedOut) {
                // The queue had space again, so a later full-queue warning does not count the
                // timeouts of this wait
                queueFullTimeouts.set(0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.fail();
            return;
        }

        // close() or fatal() may have run while this thread was waiting in offer(). If so, the
        // queue may never be flushed again, so take this record instance back and fail it. It was
        // just added at the tail, so search from there. If it is not found, flush() or close()
        // has already drained the record and owns its ack/fail.
        current = state.get();
        if (current != State.OPEN) {
            if (removeQueuedRecord(record)) {
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
     * Remove {@code record} itself from the queue, searching from the tail. Unlike
     * removeLastOccurrence(), this compares identity rather than equals(): a Record implementation
     * may consider different records equal, and removing another queued record would lose it while
     * {@code record} stays queued and is failed twice.
     */
    private boolean removeQueuedRecord(Record<T> record) {
        for (Iterator<Record<T>> it = incomingList.descendingIterator(); it.hasNext(); ) {
            if (it.next() == record) {
                it.remove();
                return true;
            }
        }
        return false;
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
        if (now - lastWarn >= TimeUnit.SECONDS.toNanos(QUEUE_FULL_WARN_INTERVAL_SECONDS)
                && queueFullLastWarnNanos.compareAndSet(lastWarn, now)) {
            log.warn("Internal queue is full (capacity {}), blocking writes until space frees up; "
                    + "{} wait(s) of {} s timed out since the last warning",
                    maxQueueSize, queueFullTimeouts.getAndSet(0), QUEUE_OFFER_INTERVAL_SECONDS);
        }
    }

    /**
     * Schedule an on-demand flush unless one is already pending. A pending flush has not started
     * draining yet, so it also picks up the records queued after it was scheduled.
     */
    private void scheduleFlush() {
        // close() may have shut the executor down, or cleared the field, since write() checked the
        // state. close() fails the queued records, so there is nothing left to flush then.
        ScheduledExecutorService executor = flushExecutor;
        if (executor == null || !flushPending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(this::flushOnDemand, 0, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushPending.set(false);
            // A no-op after close(), since fatal() only acts on an OPEN sink
            fatal(e);
        }
    }

    private void flushOnDemand() {
        // Clear the flag before draining, so that a write() that lands after the drain schedules a
        // new flush instead of relying on this one. The periodic flush leaves the flag alone:
        // clearing it while an on-demand flush is still pending would let write() schedule another.
        flushPending.set(false);
        flushOrFatal();
    }

    /**
     * Run flush() as a task of the flush executor, reporting anything that escapes it through
     * fatal(). An exception escaping a periodic task cancels it without notice, and the executor
     * keeps the exception of an on-demand task in a Future that nobody reads. fatal() only acts on
     * an OPEN sink, so an error that flush() already reported is not reported again, and fatal()
     * cancelling the periodic task from inside it just stops further runs.
     */
    private void flushOrFatal() {
        try {
            flush();
        } catch (Throwable t) {
            log.error("Flush task failed with {}", t.toString(), t);
            fatal(t);
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
                    // failRecords() keeps failing the other records when Record.fail() throws, even
                    // an Error, and adds what fail() threw to e as suppressed. The finally blocks
                    // still roll back and call fatal() should anything else throw, e.g. an Error
                    // from rollback().
                    try {
                        failRecords(swapList, Integer.MAX_VALUE, e, "of the failed flush");
                    } finally {
                        try {
                            if (jdbcSinkConfig.isUseTransactions()) {
                                connection.rollback();
                            }
                        } catch (Exception ex) {
                            log.error("Failed to rollback transaction", ex);
                        } finally {
                            fatal(e);
                        }
                    }
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
            ackCommitted(swapList, swapList.size());
        }
    }

    /**
     * Remove the first {@code count} records, which are committed, from {@code swapList} and ack
     * each of them. If ack() throws, the remaining committed records are removed without being
     * acked or failed, and the exception is rethrown: flush() then fails only the records that are
     * not written yet and calls fatal(). The unacked records are redelivered after the instance
     * restarts. Failing them could redeliver them after newer messages with the same key, which
     * would then be overwritten by the older records.
     */
    private void ackCommitted(Deque<Record<T>> swapList, int count) {
        for (int acked = 0; acked < count; acked++) {
            try {
                swapList.removeFirst().ack();
            } catch (Throwable e) {
                int unacked = count - acked - 1;
                for (int i = 0; i < unacked; i++) {
                    swapList.removeFirst();
                }
                log.warn("Record.ack() threw after the records were committed, leaving the {} committed "
                        + "record(s) after it unacked", unacked);
                throw e;
            }
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
            // Committed either way, with auto-commit by executeBatch()
            ackCommitted(swapList, results.length);
        } else {
            if (useTransactions) {
                connection.rollback();
            }
            String msg = "Batch failed, got error results (error_code->count): " + failuresMapping;
            SQLException batchError = new SQLException(msg);
            // Keep failing when Record.fail() throws, so that batchError is reported to fatal(), with
            // what fail() threw suppressed
            failRecords(swapList, results.length, batchError, "of the failed JDBC batch");
            // flush() catches this, fails the remaining records of this round and calls fatal()
            throw batchError;
        }
    }

    private static boolean isBatchItemFailed(int returnCode) {
        if (returnCode == Statement.SUCCESS_NO_INFO || returnCode >= 0) {
            return false;
        }
        return true;
    }

    /**
     * Move an OPEN sink to FAILED, stop the time-based flush and signal the fatal exception to the
     * framework, which terminates the function instance properly. Without a SinkContext the sink
     * still becomes FAILED, so that it stops flushing and write() fails records instead of
     * blocking on a queue that is no longer flushed. Does nothing if the sink is not OPEN.
     *
     * @param e the fatal exception
     */
    private void fatal(Throwable e) {
        if (state.compareAndSet(State.OPEN, State.FAILED)) {
            log.error("Fatal error in JDBC sink, signaling framework for shutdown", e);
            // close() may clear the field concurrently
            ScheduledFuture<?> flushTask = scheduledFlushTask;
            if (flushTask != null) {
                flushTask.cancel(false);
            }
            if (sinkContext != null) {
                sinkContext.fatal(e);
            }
        }
    }

}
