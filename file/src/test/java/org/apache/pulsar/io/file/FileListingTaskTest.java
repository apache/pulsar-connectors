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

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.fail;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.testng.annotations.Test;


public class FileListingTaskTest extends AbstractFileTest {

    // Generous ceiling for the asynchronous listing thread to enqueue the expected files.
    // The assertion passes as soon as the count is reached, so a healthy run is still fast;
    // this only prevents failures when a loaded CI runner is slow to schedule the thread.
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    // Fixed wait used only by "nothing should be offered" assertions, where there is no
    // positive event to await; it gives the listing thread ample time to (wrongly) act.
    private static final long NEGATIVE_ASSERTION_WAIT_MS = 2000;

    /** Waits until the listing thread has offered exactly {@code expectedCount} files. */
    private void awaitOffers(int expectedCount) {
        await().atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> verify(workQueue, times(expectedCount)).offer(any(File.class)));
    }

    @Test
    public final void singleFileTest() throws IOException {

        Map<String, Object> map = new HashMap<> ();
        map.put("inputDirectory", directory.toString());

        try {
            generateFiles(1);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(1);
            verify(producedFiles, times(1)).put(any(File.class));

            for (File produced : producedFiles) {
                verify(workQueue, times(1)).offer(produced);
            }

        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        }
    }

    @Test
    public final void fiftyFileTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());

        try {
            generateFiles(50);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(50);

            for (File produced : producedFiles) {
                verify(workQueue, times(1)).offer(produced);
            }

        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        }
    }

    @Test
    public final void minimumSizeTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());

        try {
            // Create 50 zero size files
            generateFiles(50, 0);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            Thread.sleep(NEGATIVE_ASSERTION_WAIT_MS);
            verify(workQueue, times(0)).offer(any(File.class));
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        }
    }

    @Test
    public final void maximumSizeTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("maximumSize", "1000");

        try {
            // Create 5 files that exceed the limit and 45 that don't
            generateFiles(5, 1000);
            generateFiles(45, 10);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(45);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void minimumAgeTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("minimumFileAge", "5000");

        try {
            // Create 5 files that will be too "new" for processing
            generateFiles(5);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            Thread.sleep(NEGATIVE_ASSERTION_WAIT_MS);
            verify(workQueue, times(0)).offer(any(File.class));
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void maximumAgeTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("maximumFileAge", "5000");

        try {
            // Create 5 files that will be processed
            generateFiles(5);
            // Deliberately age these files past maximumFileAge so they are skipped below.
            Thread.sleep(5000);

            // Create 5 files that will be too "old" for processing
            generateFiles(5);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(5);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public void pollingIntervalTest() throws IOException {
        int pollingInterval = 100;

        Map<String, Object> map = new HashMap<>();
        map.put("inputDirectory", directory.toString());
        map.put("pollingInterval", pollingInterval);

        try {
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);

            // A file dropped in after start must be picked up on a subsequent polling cycle.
            generateFiles(1);
            awaitOffers(1);

            // A second file dropped in later must be picked up on a later cycle, proving the
            // listing loop keeps polling rather than scanning only once.
            generateFiles(1);
            awaitOffers(2);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void doRecurseTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("recurse", Boolean.TRUE);

        try {
            // Create 5 files in the root folder
            generateFiles(5);

            // Create 5 files in a sub-folder
            generateFiles(5, 1, directory.toString() + File.separator + "sub-dir");
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(10);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void doNotRecurseTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("recurse", Boolean.FALSE);

        try {
            // Create 5 files in the root folder
            generateFiles(5);

            // Create 5 files in a sub-folder
            generateFiles(5, 1, directory.toString() + File.separator + "sub-dir");
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(5);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void pathFilterTest() throws IOException {

        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("recurse", Boolean.TRUE);
        map.put("pathFilter", "sub-.*");

        try {
            // Create 5 files in a sub-folder
            generateFiles(5, 1, directory.toString() + File.separator + "sub-dir-a");
            generateFiles(5, 1, directory.toString() + File.separator + "dir-b");
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(5);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void processedFileFilterTest() throws IOException {

        String processedFileSuffix = ".file_process_done";
        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("keepFile", Boolean.FALSE);
        map.put("processedFileSuffix", processedFileSuffix);

        try {
            generateFiles(5, 1, directory.toString(), ".txt");
            generateFiles(1, 1, directory.toString(), processedFileSuffix);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(5);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }

    @Test
    public final void processedFileFilterTest2() throws IOException {

        String processedFileSuffix = ".file_process_done";
        Map<String, Object> map = new HashMap<String, Object> ();
        map.put("inputDirectory", directory.toString());
        map.put("keepFile", Boolean.TRUE);
        map.put("processedFileSuffix", processedFileSuffix);

        try {
            generateFiles(5, 1, directory.toString(), ".txt");
            generateFiles(1, 1, directory.toString(), processedFileSuffix);
            listingTask = new FileListingTask(FileSourceConfig.load(map), workQueue, inProcess, recentlyProcessed);
            executor.execute(listingTask);
            awaitOffers(6);
        } catch (InterruptedException | ExecutionException e) {
            fail("Unable to generate files" + e.getLocalizedMessage());
        } finally {
            cleanUp();
        }
    }
}
