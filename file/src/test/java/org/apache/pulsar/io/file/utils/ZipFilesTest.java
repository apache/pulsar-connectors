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
package org.apache.pulsar.io.file.utils;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

public class ZipFilesTest {

    @Test
    public final void validZipFileTest() {
        assertTrue(ZipFiles.isZip(getFile("org/apache/pulsar/io/file/validZip.zip")));
    }

    @Test
    public final void nonZipFileTest() {
        assertFalse(ZipFiles.isZip(getFile("org/apache/pulsar/io/file/nonGzipFile.txt")));
    }

    @Test
    public final void mislabelledZipFileTest() {
        assertFalse(ZipFiles.isZip(getFile("org/apache/pulsar/io/file/mislabelled.gz")));
    }

    @Test
    public final void nonExistantGzipFileTest() {
        assertFalse(ZipFiles.isZip(null));
    }

    @Test
    public final void streamZipFileTest() {
        Path path = Paths.get(getFile("org/apache/pulsar/io/file/validZip.zip").getAbsolutePath(), "");

        // validZip.zip contains a single entry with the nine lines "Line 1".."Line 9".
        try (Stream<String> lines = ZipFiles.lines(path)) {
            List<String> collected = lines.collect(Collectors.toList());
            assertEquals(collected.size(), 9, "expected nine lines from the zip entry");
            for (int i = 0; i < collected.size(); i++) {
                assertEquals(collected.get(i), "Line " + (i + 1));
            }
        }
    }

    @Test
    public final void streamMultiEntryZipFileTest() throws Exception {
        // Build a two-entry archive in a temp file so the test is self-contained and proves
        // that lines from every entry are returned, in entry order.
        Path zip = Files.createTempFile("pulsar-io-file-ziptest", ".zip");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("first.txt"));
                out.write("a1\na2".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("second.txt"));
                out.write("b1\nb2\nb3".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }

            assertTrue(ZipFiles.isZip(zip.toFile()));
            try (Stream<String> lines = ZipFiles.lines(zip)) {
                assertEquals(lines.collect(Collectors.toList()),
                        List.of("a1", "a2", "b1", "b2", "b3"));
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private File getFile(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(name).getFile());
    }
}
