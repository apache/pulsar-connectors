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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataOutputStreamBuilder;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Syncable;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.KeyValue;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.hdfs3.AbstractHdfsConnector;
import org.apache.pulsar.io.hdfs3.HdfsResources;

/**
 * A Simple abstract class for HDFS sink.
 * Users need to implement extractKeyValue function to use this sink.
 */
public abstract class HdfsAbstractSink<K, V> extends AbstractHdfsConnector implements Sink<V> {

    protected HdfsSinkConfig hdfsSinkConfig;
    protected BlockingQueue<Record<V>> unackedRecords;
    protected HdfsSyncThread<V> syncThread;
    private Path path;
    private FSDataOutputStream hdfsStream;

    public abstract KeyValue<K, V> extractKeyValue(Record<V> record);
    protected abstract void createWriter() throws IOException;

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
       hdfsSinkConfig = HdfsSinkConfig.load(config);
       hdfsSinkConfig.validate();
       connectorConfig = hdfsSinkConfig;
       unackedRecords = new LinkedBlockingQueue<Record<V>> (hdfsSinkConfig.getMaxPendingRecords());
       connectToHdfs();
       createWriter();
       launchSyncThread();
    }

    @Override
    public void close() throws Exception {
       // Stops the sync thread, then syncs and acks the records still queued
       syncThread.halt();
    }

    protected final void connectToHdfs() throws IOException {
       try {
           HdfsResources resources = hdfsResources.get();

           if (resources.getConfiguration() == null) {
               resources = this.resetHDFSResources(hdfsSinkConfig);
               hdfsResources.set(resources);
           }
       } catch (IOException ex) {
          hdfsResources.set(new HdfsResources(null, null, null));
          throw ex;
       }
    }

    @SuppressWarnings("rawtypes")
    protected final FSDataOutputStreamBuilder getOutputStreamBuilder() throws IOException {
        Path path = getPath();
        FileSystem fs = getFileSystemAsUser(getConfiguration(), getUserGroupInformation());
        FSDataOutputStreamBuilder builder = fs.exists(path) ? fs.appendFile(path) :  fs.createFile(path);
        return builder.recursive().permission(new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL));
    }

    protected FSDataOutputStream getHdfsStream() throws IllegalArgumentException, IOException {
        if (hdfsStream == null) {
            hdfsStream = getOutputStreamBuilder().build();
        }
        return hdfsStream;
    }

    protected final Path getPath() {
        if (path == null) {
            String ext = "";
            if (StringUtils.isNotBlank(hdfsSinkConfig.getFileExtension())) {
                ext = hdfsSinkConfig.getFileExtension();
            } else if (getCompressionCodec() != null) {
                ext = getCompressionCodec().getDefaultExtension();
            }

            path = new Path(FilenameUtils.concat(hdfsSinkConfig.getDirectory(),
                    hdfsSinkConfig.getFilenamePrefix() + "-" + System.currentTimeMillis() + ext));
        }
        return path;
    }

    /**
     * Push the bytes of the records written so far, which the sink may still buffer on top of the
     * HDFS stream, into that stream, so that the next {@code hsync()} of the stream covers them. The
     * sync thread calls it before each {@code hsync()}, and acks records only after both. Does
     * nothing by default, for sinks that write straight to the stream.
     */
    protected void flushBuffers() throws IOException {
    }

    protected final void launchSyncThread() throws IOException {
        FSDataOutputStream stream = getHdfsStream();
        // What the sync thread syncs before it acks records: the sink's buffers, then the stream
        Syncable syncable = new Syncable() {
            @Override
            public void hflush() throws IOException {
                flushBuffers();
                stream.hflush();
            }

            @Override
            public void hsync() throws IOException {
                flushBuffers();
                stream.hsync();
            }
        };
        syncThread = new HdfsSyncThread<V>(syncable, unackedRecords, hdfsSinkConfig.getSyncInterval());
        syncThread.start();
    }
}