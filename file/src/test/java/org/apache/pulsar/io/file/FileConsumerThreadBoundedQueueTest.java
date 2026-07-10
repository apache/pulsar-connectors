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
package org.apache.pulsar.io.file;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.fail;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.PushSource;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * The consumer must survive a full {@code recentlyProcessed} queue.
 *
 * <p>{@code BlockingQueue.add()} throws {@link IllegalStateException} when a bounded queue is
 * full — it does not return {@code false}, so the surrounding {@code do/while(!added)} retry loop
 * never runs. Once the queues are bounded, a cleanup thread slower than the workers would kill
 * the consumer thread outright and stall processing. The producers must block instead.
 */
@SuppressWarnings("unchecked")
public class FileConsumerThreadBoundedQueueTest extends AbstractFileTest {

    /**
     * With no cleanup thread draining {@code recentlyProcessed}, the consumer fills it and must
     * then block rather than throw. Draining it lets the remaining files through.
     */
    @Test
    public final void consumerBlocksInsteadOfCrashingWhenDownstreamQueueIsFull() throws IOException {
        PushSource<byte[]> consumer = Mockito.mock(PushSource.class);
        Mockito.doNothing().when(consumer).consume((Record<byte[]>) any(Record.class));

        BlockingQueue<File> work = new LinkedBlockingQueue<>();
        BlockingQueue<File> inProcess = new LinkedBlockingQueue<>(10);
        // Nothing drains this, so it fills after one file.
        BlockingQueue<File> recentlyProcessed = new LinkedBlockingQueue<>(1);

        try {
            generateFiles(3);
            for (File f : producedFiles) {
                work.put(f);
            }

            executor.execute(new FileConsumerThread(consumer, work, inProcess, recentlyProcessed));

            // The first file lands in recentlyProcessed; the consumer then blocks on the second.
            Awaitility.await().atMost(10, TimeUnit.SECONDS)
                    .until(() -> recentlyProcessed.size() == 1);

            // Drain as the cleanup thread would. Every file must be handed over: the consumer
            // must still be alive, having blocked rather than thrown IllegalStateException.
            for (int i = 0; i < 3; i++) {
                File taken = recentlyProcessed.poll(10, TimeUnit.SECONDS);
                if (taken == null) {
                    fail("consumer thread died: expected 3 files through recentlyProcessed, got " + i);
                }
            }

            verify(consumer, atLeast(3)).consume(any(Record.class));
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }
}
