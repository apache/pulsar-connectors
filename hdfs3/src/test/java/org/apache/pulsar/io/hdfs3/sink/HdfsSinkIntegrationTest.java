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

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.hdfs3.sink.text.HdfsStringSink;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for the HDFS sink, exercised against a real, in-JVM Hadoop
 * {@link MiniDFSCluster}.
 *
 * <p>Unlike the existing sink unit tests (which point at the local filesystem and only verify that
 * records are acknowledged against a Mockito mock), this test drives the {@link HdfsStringSink}
 * end-to-end against an actual HDFS namenode/datanode: it opens the sink, writes several records,
 * closes the sink to flush, then reads the produced file back <em>from the mini cluster's own
 * {@link FileSystem}</em> and asserts the bytes on HDFS match exactly what was written.
 *
 * <p>No Docker container is required — {@code MiniDFSCluster} runs entirely inside the test JVM.
 */
public class HdfsSinkIntegrationTest {

    private static final String DIRECTORY = "/hdfs-sink-integration-test";
    private static final String FILENAME_PREFIX = "records";
    private static final String FILE_EXTENSION = ".txt";
    private static final char SEPARATOR = '\n';

    private MiniDFSCluster cluster;
    private FileSystem clusterFs;
    private File baseDir;
    private File coreSite;

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("hdfs-sink-it").toFile();

        Configuration conf = new Configuration();
        conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.getAbsolutePath());
        cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
        cluster.waitActive();
        clusterFs = cluster.getFileSystem();

        // The sink reads its Hadoop configuration from files named in `hdfsConfigResources`. Write a
        // core-site.xml whose fs.defaultFS points at the running mini cluster so the sink connects to
        // it rather than to the local filesystem.
        String defaultFs = clusterFs.getUri().toString();
        coreSite = new File(baseDir, "core-site.xml");
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<configuration>\n"
                + "  <property>\n"
                + "    <name>fs.defaultFS</name>\n"
                + "    <value>" + defaultFs + "</value>\n"
                + "  </property>\n"
                + "</configuration>\n";
        Files.write(coreSite.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (cluster != null) {
            cluster.shutdown(true);
        }
    }

    @Test(timeOut = 300_000)
    public void testRecordsAreWrittenToHdfs() throws Exception {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            values.add("integration-record-" + i);
        }

        Map<String, Object> config = new HashMap<>();
        config.put("hdfsConfigResources", coreSite.getAbsolutePath());
        config.put("directory", DIRECTORY);
        config.put("filenamePrefix", FILENAME_PREFIX);
        config.put("fileExtension", FILE_EXTENSION);
        config.put("separator", SEPARATOR);
        // Let the background sync thread flush and ack on a modest interval.
        config.put("syncInterval", 200L);

        SinkContext sinkContext = mock(SinkContext.class);
        AtomicInteger ackCount = new AtomicInteger(0);

        HdfsStringSink sink = new HdfsStringSink();
        sink.open(config, sinkContext);
        for (String value : values) {
            sink.write(mockRecord(value, ackCount));
        }

        // Wait until every record has been acked. The sink acks a record only after its background
        // sync thread has hsync'd the stream, which also guarantees the sink's unacked-record queue
        // has drained — so the subsequent close() flushes the writer and commits the file cleanly
        // instead of racing an hsync against an already-closed stream.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assert.assertEquals(ackCount.get(), values.size(),
                        "all records should be acknowledged by the sink"));

        // close() flushes the buffered writer and commits the file to HDFS.
        sink.close();

        // Build the exact content we expect to find on HDFS: each value followed by the separator.
        StringBuilder expected = new StringBuilder();
        for (String value : values) {
            expected.append(value).append(SEPARATOR);
        }

        // Read the committed file back from the mini cluster's own filesystem and assert it matches.
        String actual = readSinkOutput();
        Assert.assertEquals(actual, expected.toString(),
                "content read back from HDFS must match what was written to the sink");
    }

    /**
     * Reads and concatenates the content of every file produced by the sink under {@link #DIRECTORY}
     * on the mini cluster's filesystem. The sink writes all records from a single {@code open()} into
     * one file whose name starts with {@link #FILENAME_PREFIX}.
     */
    private String readSinkOutput() throws Exception {
        Path dir = new Path(DIRECTORY);
        if (!clusterFs.exists(dir)) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (FileStatus status : clusterFs.listStatus(dir)) {
            String name = status.getPath().getName();
            if (!status.isFile() || !name.startsWith(FILENAME_PREFIX)) {
                continue;
            }
            try (FSDataInputStream in = clusterFs.open(status.getPath())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                content.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return content.toString();
    }

    @SuppressWarnings("unchecked")
    private Record<String> mockRecord(String value, AtomicInteger ackCount) {
        Record<String> record = mock(Record.class);
        when(record.getValue()).thenReturn(value);
        when(record.getKey()).thenReturn(Optional.of(value));
        // Count acks so the test can wait for the sink to fully drain before closing.
        doAnswer(invocation -> {
            ackCount.incrementAndGet();
            return null;
        }).when(record).ack();
        return record;
    }
}
