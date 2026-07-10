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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.testng.annotations.Test;

/**
 * The listing task must never lose a file when the work queue is bounded.
 *
 * <p>{@code workQueue} is unbounded today, so {@code offer()} always succeeds. If it is ever
 * bounded — as proposed in #28 to cap memory under a backlog — {@code offer()} starts returning
 * {@code false} when the queue is full. Recording a file as offered before the queue accepts it
 * would drop that file permanently: the tracking set only forgets files that leave the disk, so a
 * rejected file is never retried.
 *
 * <p>These tests bound the queue themselves, so they hold regardless of what {@code FileSource}
 * does, and they fail against the unfixed listing task.
 */
public class FileListingTaskBoundedQueueTest extends AbstractFileTest {

    private static final int POLLING_INTERVAL_MS = 100;

    private void startListing(BlockingQueue<File> queue, BlockingQueue<File> inProcess,
            BlockingQueue<File> recentlyProcessed) throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("inputDirectory", directory.toString());
        map.put("keepFile", Boolean.FALSE);
        map.put("pollingInterval", POLLING_INTERVAL_MS);

        executor.execute(new FileListingTask(FileSourceConfig.load(map), queue, inProcess, recentlyProcessed));
    }

    /**
     * A file rejected by a full queue must be offered again once space frees up, rather than
     * being silently forgotten.
     */
    @Test
    public final void rejectedFilesAreRetriedOnceTheQueueDrains() throws IOException {
        BlockingQueue<File> boundedQueue = new LinkedBlockingQueue<>(1);
        BlockingQueue<File> inProcess = new LinkedBlockingQueue<>();
        BlockingQueue<File> recentlyProcessed = new LinkedBlockingQueue<>();

        try {
            generateFiles(3);
            startListing(boundedQueue, inProcess, recentlyProcessed);

            // The queue holds one file; the other two were rejected.
            Awaitility.await().atMost(10, TimeUnit.SECONDS)
                    .until(() -> boundedQueue.size() == 1);

            // Drain the queue one file at a time, as a consumer would. Every file must eventually
            // be handed over: nothing may be dropped because an earlier offer was rejected.
            Set<File> drained = new HashSet<>();
            Awaitility.await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(100, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        File taken = boundedQueue.poll();
                        if (taken != null) {
                            drained.add(taken);
                        }
                        return drained.size() == 3;
                    });

            assertEquals(drained.size(), 3, "every file on disk must reach the work queue");
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    /**
     * Draining must not cause a file still on disk to be offered twice — the exactly-once
     * property has to survive the retry path added for bounded queues.
     */
    @Test
    public final void filesAreNotOfferedTwiceWhileTheyRemainOnDisk() throws IOException {
        BlockingQueue<File> boundedQueue = new LinkedBlockingQueue<>(10);
        BlockingQueue<File> inProcess = new LinkedBlockingQueue<>();
        BlockingQueue<File> recentlyProcessed = new LinkedBlockingQueue<>();

        try {
            generateFiles(3);
            startListing(boundedQueue, inProcess, recentlyProcessed);

            Awaitility.await().atMost(10, TimeUnit.SECONDS)
                    .until(() -> boundedQueue.size() == 3);

            // Several more listing passes run while the files remain on disk and queued.
            Thread.sleep(POLLING_INTERVAL_MS * 5L);

            assertEquals(boundedQueue.size(), 3, "files already queued must not be offered again");
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }
}
