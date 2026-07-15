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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Helper class that provides helper methods for working with
 * zip-formatted files.
 */
public class ZipFiles {

    /**
     * Returns true if the given file is a gzip file.
     */
    public static boolean isZip(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
            int test = in.readInt();
            return test == 0x504b0304;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Get a stream of lines from every file entry of a zip file, similar to
     * {@link Files#lines(java.nio.file.Path)}.
     *
     * @param path
     *          The path to the zipped file.
     * @return stream with the lines of all file entries, in entry order.
     */
    public static Stream<String> lines(Path path) {
        // A ZipInputStream returns no data until it is positioned onto an entry via
        // getNextEntry(); without that call the reader sees an empty stream and no lines are
        // produced. Read through every file entry so multi-entry archives contribute all of
        // their lines, then return the collected lines as a stream (the archive must be fully
        // read to walk its entries, so this cannot be lazy the way GZipFiles.lines is).
        List<String> lines = new ArrayList<>();
        try (ZipInputStream zipStream = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // Read the current entry to its end. read() returns -1 at the entry boundary,
                // so the reader stops before the next entry; do not close it, as that would
                // close the shared zipStream mid-iteration.
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines.stream();
    }
}
