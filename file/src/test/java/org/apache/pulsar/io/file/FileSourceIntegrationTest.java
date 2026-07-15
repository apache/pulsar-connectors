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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * End-to-end tests that drive the connector through its real public entry point
 * ({@link FileSource#open}) and assert the records it emits, rather than exercising
 * the internal worker threads in isolation as the unit tests do.
 *
 * <p>The source is a {@link org.apache.pulsar.io.core.PushSource}: its worker threads
 * call {@code consume(record)}, which the framework drains via {@link FileSource#read()}.
 * A background reader thread pulls those records into {@link #collected} so assertions can
 * run against what the connector actually delivered.
 *
 * <p>No external service is required; every case is backed by a temp directory. A short
 * polling interval keeps the tests fast, and every wait is deadline-bounded so an
 * under-delivering pipeline fails fast instead of hanging CI.
 */
public class FileSourceIntegrationTest {

    private static final long POLLING_INTERVAL_MS = 300L;
    private static final long DELIVERY_TIMEOUT_MS = 15_000L;

    private Path directory;
    private FileSource source;
    private Thread readerThread;
    private List<Record<byte[]>> collected;

    @BeforeMethod(alwaysRun = true)
    public void init() throws IOException {
        directory = Files.createTempDirectory("pulsar-io-file-it");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread.join(2000);
        }
        if (source != null) {
            source.close();
        }
        deleteRecursively(directory);
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void testPlainTextFileEmitsOneRecordPerLine() throws Exception {
        writeText("data.txt", "alpha", "bravo", "charlie");

        startSource(baseConfig());
        assertTrue(awaitAtLeast(3, DELIVERY_TIMEOUT_MS),
                "expected 3 records, got " + collected.size());

        Map<String, String> byKey = indexByKey();
        assertEquals(byKey.size(), 3);
        assertEquals(byKey.get("data.txt_1"), "alpha");
        assertEquals(byKey.get("data.txt_2"), "bravo");
        assertEquals(byKey.get("data.txt_3"), "charlie");
    }

    @Test
    public void testGzipFileIsDecoded() throws Exception {
        writeGzip("data.txt.gz", "one", "two");

        startSource(baseConfig());
        assertTrue(awaitAtLeast(2, DELIVERY_TIMEOUT_MS),
                "expected 2 records, got " + collected.size());

        Map<String, String> byKey = indexByKey();
        assertEquals(byKey.get("data.txt.gz_1"), "one");
        assertEquals(byKey.get("data.txt.gz_2"), "two");
    }

    @Test
    public void testRecursiveDirectoriesAreTraversed() throws Exception {
        writeText("top.txt", "top");
        Path nested = Files.createDirectories(directory.resolve("sub/deeper"));
        Files.write(nested.resolve("bottom.txt"), "bottom".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> config = baseConfig();
        config.put("recurse", true);
        startSource(config);

        assertTrue(awaitAtLeast(2, DELIVERY_TIMEOUT_MS),
                "expected 2 records across nested dirs, got " + collected.size());
        Map<String, String> byKey = indexByKey();
        assertEquals(byKey.get("top.txt_1"), "top");
        assertEquals(byKey.get("bottom.txt_1"), "bottom");
    }

    @Test
    public void testFileFilterExcludesNonMatchingFiles() throws Exception {
        writeText("keep.csv", "kept");
        writeText("skip.log", "skipped");

        Map<String, Object> config = baseConfig();
        config.put("fileFilter", ".*\\.csv");
        startSource(config);

        assertTrue(awaitAtLeast(1, DELIVERY_TIMEOUT_MS));
        // Give the listing loop a couple more cycles to (incorrectly) pick up the .log file.
        assertFalse(awaitAtLeast(2, 3 * POLLING_INTERVAL_MS),
                "only the .csv file should have been consumed");
        assertEquals(indexByKey().get("keep.csv_1"), "kept");
    }

    @Test
    public void testHiddenFilesAreIgnored() throws Exception {
        writeText("visible.txt", "seen");
        writeText(".hidden.txt", "unseen");

        startSource(baseConfig());

        assertTrue(awaitAtLeast(1, DELIVERY_TIMEOUT_MS));
        assertFalse(awaitAtLeast(2, 3 * POLLING_INTERVAL_MS),
                "hidden files should not be consumed");
        assertEquals(indexByKey().get("visible.txt_1"), "seen");
    }

    @Test
    public void testProcessedFileIsDeletedByDefault() throws Exception {
        File file = writeText("ephemeral.txt", "gone");

        startSource(baseConfig());
        assertTrue(awaitAtLeast(1, DELIVERY_TIMEOUT_MS));

        assertTrue(awaitFileGone(file, DELIVERY_TIMEOUT_MS),
                "default keepFile=false should delete the processed file");
    }

    @Test
    public void testProcessedFileSuffixRenamesFile() throws Exception {
        File file = writeText("record.txt", "kept-with-suffix");

        Map<String, Object> config = baseConfig();
        config.put("processedFileSuffix", ".done");
        startSource(config);

        assertTrue(awaitAtLeast(1, DELIVERY_TIMEOUT_MS));
        File renamed = new File(file.getParentFile(), file.getName() + ".done");
        assertTrue(awaitFileGone(file, DELIVERY_TIMEOUT_MS), "original should be moved away");
        assertTrue(renamed.exists(), "processed file should be renamed with the configured suffix");
    }

    @Test
    public void testFileAppearingAfterOpenIsPickedUpByPolling() throws Exception {
        startSource(baseConfig());
        // Nothing on disk yet: the reader must stay empty until a file appears.
        assertFalse(awaitAtLeast(1, 2 * POLLING_INTERVAL_MS));

        writeText("late.txt", "arrived-late");
        assertTrue(awaitAtLeast(1, DELIVERY_TIMEOUT_MS),
                "polling loop should pick up a file created after open()");
        assertEquals(indexByKey().get("late.txt_1"), "arrived-late");
    }

    @Test
    public void testEmptyDirectoryEmitsNothing() throws Exception {
        startSource(baseConfig());
        assertFalse(awaitAtLeast(1, 2 * POLLING_INTERVAL_MS),
                "an empty input directory should yield no records");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("inputDirectory", directory.toString());
        config.put("pollingInterval", POLLING_INTERVAL_MS);
        config.put("numWorkers", 1);
        return config;
    }

    private void startSource(Map<String, Object> config) throws Exception {
        source = new FileSource();
        source.open(config, Mockito.mock(SourceContext.class));
        collected = new CopyOnWriteArrayList<>();
        readerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Record<byte[]> record = source.read();
                    if (record != null) {
                        collected.add(record);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new RuntimeException("Unexpected exception from FileSource.read()", e);
            }
        }, "file-source-it-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Polls until at least {@code count} records have been collected or the deadline passes. */
    private boolean awaitAtLeast(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (collected.size() >= count) {
                return true;
            }
            Thread.sleep(25);
        }
        return collected.size() >= count;
    }

    private boolean awaitFileGone(File file, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!file.exists()) {
                return true;
            }
            Thread.sleep(25);
        }
        return !file.exists();
    }

    /** Collapses collected records into key -> value(UTF-8); duplicate keys keep the last value. */
    private Map<String, String> indexByKey() {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Record<byte[]> record : collected) {
            byKey.put(record.getKey().orElse(null), new String(record.getValue(), StandardCharsets.UTF_8));
        }
        return byKey;
    }

    private File writeText(String name, String... lines) throws IOException {
        Path path = directory.resolve(name);
        Files.write(path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return path.toFile();
    }

    private File writeGzip(String name, String... lines) throws IOException {
        Path path = directory.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        }
        return path.toFile();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
