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
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
     * Get a lazily loaded stream of lines from every file entry of a zip file, similar to
     * {@link Files#lines(java.nio.file.Path)}.
     *
     * <p>A {@link ZipInputStream} yields no data until it is positioned onto an entry via
     * {@link ZipInputStream#getNextEntry()}; without that call the reader sees an empty stream
     * and no lines are produced. The returned stream lazily walks every file entry and emits
     * their lines in entry order, so multi-entry archives contribute all of their lines without
     * buffering the whole archive in memory. The caller must close the returned stream to
     * release the underlying file.
     *
     * @param path
     *          The path to the zipped file.
     * @return stream with the lines of all file entries, in entry order.
     */
    public static Stream<String> lines(Path path) {
        ZipInputStream zipStream;
        try {
            zipStream = new ZipInputStream(Files.newInputStream(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Spliterator<String> spliterator = Spliterators.spliteratorUnknownSize(
                new ZipLineIterator(zipStream), Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false).onClose(() -> closeSafely(zipStream));
    }

    /**
     * Iterates the lines of every non-directory entry of a zip stream in entry order, advancing
     * to the next entry when the current one is exhausted. Entry boundaries are line boundaries:
     * a fresh {@link BufferedReader} is used for each entry, so an entry whose content does not
     * end with a newline does not merge its trailing text into the next entry's first line.
     */
    private static final class ZipLineIterator implements Iterator<String> {
        private final ZipInputStream zipStream;
        private BufferedReader reader;
        private String nextLine;

        ZipLineIterator(ZipInputStream zipStream) {
            this.zipStream = zipStream;
        }

        @Override
        public boolean hasNext() {
            if (nextLine == null) {
                nextLine = readNextLine();
            }
            return nextLine != null;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String line = nextLine;
            nextLine = null;
            return line;
        }

        private String readNextLine() {
            try {
                while (true) {
                    if (reader != null) {
                        String line = reader.readLine();
                        if (line != null) {
                            return line;
                        }
                        // Current entry is exhausted; drop its reader but keep the shared
                        // zipStream open so we can position onto the next entry.
                        reader = null;
                    }
                    ZipEntry entry = zipStream.getNextEntry();
                    if (entry == null) {
                        return null;
                    }
                    if (!entry.isDirectory()) {
                        // Wrap the shared zipStream in a fresh reader for this entry. read()
                        // returns -1 at the entry boundary, so the reader stops before the next
                        // entry; the reader is intentionally not closed, as that would close the
                        // shared zipStream mid-iteration.
                        reader = new BufferedReader(new InputStreamReader(zipStream));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void closeSafely(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
