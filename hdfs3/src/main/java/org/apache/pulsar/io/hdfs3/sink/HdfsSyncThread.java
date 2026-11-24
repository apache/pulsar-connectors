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
package org.apache.pulsar.io.hdfs3.sink;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.fs.Syncable;
import org.apache.pulsar.functions.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread that runs in the background and acknowledges Records
 * after they have been written to disk.
 *
 * <p>Only one thread drains {@code unackedRecords} at a time: this thread while it runs, then the
 * thread that calls {@link #halt()}, once this one has stopped.
 *
 * @param <V>
 */
public class HdfsSyncThread<V> extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(HdfsSyncThread.class);

    private final Syncable stream;
    private final BlockingQueue<Record<V>> unackedRecords;
    private final long syncInterval;
    // Counted down by halt(). Waiting on it between syncs, rather than sleeping, lets halt() stop
    // this thread without waiting for the rest of the interval.
    private final CountDownLatch halted = new CountDownLatch(1);

    public HdfsSyncThread(Syncable stream, BlockingQueue<Record<V>> unackedRecords, long syncInterval) {
      this.stream = stream;
      this.unackedRecords = unackedRecords;
      this.syncInterval = syncInterval;
    }

    @Override
    public void run() {
       try {
          while (!halted.await(syncInterval, TimeUnit.MILLISECONDS)) {
             try {
                ackRecords();
             } catch (IOException e) {
                LOG.error("Failed to sync records to HDFS, retrying in {} ms", syncInterval, e);
             }
          }
       } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
       }
    }

    /**
     * Stop this thread, wait for it to finish the sync it may be running, then sync and ack the
     * records still queued, on the calling thread.
     */
    public final void halt() throws IOException, InterruptedException {
       halted.countDown();
       join();
       ackRecords();
    }

    /**
     * Sync the stream, then ack the records that were queued before the sync. A record is queued
     * after it is written, so those records are covered by the sync. Records queued during or after
     * it are left for the next sync. Must not run on two threads at once.
     */
    private void ackRecords() throws IOException {
        int synced = unackedRecords.size();
        if (synced == 0) {
           return;
        }

        synchronized (stream) {
          stream.hsync();
        }

        for (int i = 0; i < synced; i++) {
          // Never null: only this method removes records, and not on two threads at once
          Record<V> record = unackedRecords.poll();
          if (record == null) {
             return;
          }
          record.ack();
        }
    }
}
