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
package org.apache.pulsar.io.hdfs3.sink.text;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.hdfs3.sink.HdfsAbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for HDFS Sinks that writes there contents to HDFS as Text Files.
 *
 * @param <K>
 * @param <V>
 */
public abstract class HdfsAbstractTextFileSink<K, V> extends HdfsAbstractSink<K, V> implements Sink<V> {

    private static final Logger LOG = LoggerFactory.getLogger(HdfsAbstractTextFileSink.class);

    protected OutputStreamWriter writer;
    // The compressing stream between the writer and the HDFS stream, or null without compression
    private CompressionOutputStream compressedStream;
    // Held while a record is written and while the buffers are flushed, so that a flush never
    // writes part of a record
    private final Object writeLock = new Object();

    @Override
    protected void createWriter() throws IOException {
        writer = new OutputStreamWriter(new BufferedOutputStream(openHdfsStream()), getEncoding());
    }

    @Override
    public void close() throws Exception {
        // The superclass halts the sync thread and runs its final sync, which flushes the writer
        // (see flushBuffers()), while the stream is still open. Closing the writer first closed the
        // stream before that final hsync(), which then threw ClosedChannelException whenever
        // unacked records remained at close time. The writer is closed in finally so the
        // underlying stream is released even if super.close() throws.
        try {
            super.close();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Push the records written so far through the writer's buffers and, with compression, out of
     * the compressor. A compressor keeps data back on flush(), so the compressed stream is ended
     * and a new one started: the file then holds compressed streams back to back, which the Hadoop
     * codecs read as one.
     */
    @Override
    protected void flushBuffers() throws IOException {
        synchronized (writeLock) {
            writer.flush();
            if (compressedStream != null) {
                compressedStream.finish();
                compressedStream.resetState();
            }
        }
    }

    @Override
    public void write(Record<V> record) {
       try {
           KeyValue<K, V> kv = extractKeyValue(record);
           synchronized (writeLock) {
               writer.write(kv.getValue().toString());

               if (hdfsSinkConfig.getSeparator() != '\u0000') {
                  writer.write(hdfsSinkConfig.getSeparator());
               }
           }
           // Queued after it is written, and outside the lock: put() can block until the sync
           // thread, which needs the lock to flush, has acked records
           unackedRecords.put(record);
        } catch (IOException | InterruptedException e) {
            LOG.error("Unable to write to file " + getPath(), e);
            record.fail();
        }
    }

    private OutputStream openHdfsStream() throws IOException {
       if (hdfsSinkConfig.getCompression() != null) {
          compressedStream = getCompressionCodec().createOutputStream(getHdfsStream());
          return compressedStream;
       } else {
          return getHdfsStream();
       }
    }
}