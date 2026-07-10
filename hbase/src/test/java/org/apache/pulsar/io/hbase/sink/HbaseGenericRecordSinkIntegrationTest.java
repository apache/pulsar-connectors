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
package org.apache.pulsar.io.hbase.sink;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.schema.AutoConsumeSchema;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericAvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.source.PulsarRecord;
import org.apache.pulsar.io.core.SinkContext;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Integration test for the HBase sink, exercised against a real HBase server via Testcontainers.
 *
 * <p><b>Why this needs a Linux host (see HANDOFF-hbase.md).</b> The all-in-one HBase images
 * (harisekhon/dajobe) are amd64-only and pin HBase 2.1.x; on Apple Silicon they run under slow
 * qemu emulation, and the RegionServer resets connections under load. More fundamentally the test
 * uses host networking ({@code withNetworkMode("host")}), which works on Linux only — HBase's
 * RegionServer advertises its own host/port through ZooKeeper and the client dials it directly, so
 * the advertised address must be reachable from the test JVM. On Linux with host networking that is
 * localhost; Docker Desktop's port mapping on macOS does not make the advertised RegionServer
 * reachable.
 *
 * <p>The two HBase-specific knobs to confirm on the Linux box are the image tag and the wait log
 * line; the sink-driving logic mirrors the existing {@code HbaseGenericRecordSinkTest}.
 */
@Slf4j
public class HbaseGenericRecordSinkIntegrationTest {

    // --- HBase-specific knobs to confirm on the Linux host ---
    private static final DockerImageName HBASE_IMAGE = DockerImageName.parse("harisekhon/hbase:2.1");
    private static final String TABLE_NAME = "pulsar_hbase";
    private static final String FAMILY_NAME = "info";

    private GenericContainer<?> hbase;
    private Connection connection;

    @Data
    public static class Foo {
        private String rowKey;
        private String name;
        private String address;
        private int age;
        private boolean flag;
    }

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        // Host networking (Linux only): binds HBase's ports on localhost so the RegionServer address
        // advertised through ZooKeeper is reachable from this JVM.
        hbase = new GenericContainer<>(HBASE_IMAGE)
                .withNetworkMode("host")
                .waitingFor(Wait.forLogMessage(".*Master has completed initialization.*", 1))
                .withStartupTimeout(Duration.ofMinutes(4));
        hbase.start();

        org.apache.hadoop.conf.Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", "2181");
        connection = ConnectionFactory.createConnection(conf);

        try (Admin admin = connection.getAdmin()) {
            TableName table = TableName.valueOf(TABLE_NAME);
            if (!admin.tableExists(table)) {
                admin.createTable(TableDescriptorBuilder.newBuilder(table)
                        .setColumnFamily(ColumnFamilyDescriptorBuilder.of(FAMILY_NAME))
                        .build());
            }
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (hbase != null) {
            hbase.stop();
        }
    }

    @Test(timeOut = 300_000)
    public void testWriteRecordIsPersistedToHbase() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("zookeeperQuorum", "localhost");
        config.put("zookeeperClientPort", "2181");
        config.put("zookeeperZnodeParent", "/hbase");
        config.put("tableName", TABLE_NAME);
        config.put("rowKeyName", "rowKey");
        config.put("familyName", FAMILY_NAME);
        List<String> qualifierNames = new ArrayList<>();
        qualifierNames.add("name");
        qualifierNames.add("address");
        qualifierNames.add("age");
        qualifierNames.add("flag");
        config.put("qualifierNames", qualifierNames);
        // Flush on each write.
        config.put("batchTimeMs", 1);
        config.put("batchSize", 1);

        Foo obj = new Foo();
        obj.setRowKey("rowKey_value");
        obj.setName("name_value");
        obj.setAddress("address_value");
        obj.setAge(30);
        obj.setFlag(true);

        SinkContext sinkContext = mock(SinkContext.class);
        HbaseGenericRecordSink sink = new HbaseGenericRecordSink();
        sink.open(config, sinkContext);
        try {
            sink.write(fooRecord(obj));

            // The sink flushes on a background executor; poll for the row rather than racing it.
            Table table = connection.getTable(TableName.valueOf(TABLE_NAME));
            Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> !table.get(new Get(Bytes.toBytes(obj.getRowKey()))).isEmpty());

            Result result = table.get(new Get(Bytes.toBytes(obj.getRowKey())));
            assertEquals(Bytes.toString(result.getValue(Bytes.toBytes(FAMILY_NAME), Bytes.toBytes("name"))),
                    "name_value");
            assertEquals(Bytes.toString(result.getValue(Bytes.toBytes(FAMILY_NAME), Bytes.toBytes("address"))),
                    "address_value");

            // Non-vacuous guard: a row that was never written must not be present.
            assertTrue(table.get(new Get(Bytes.toBytes("never_written_row"))).isEmpty(),
                    "a row that was never written should not be found");
        } finally {
            sink.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Record<GenericRecord> fooRecord(Foo obj) {
        AvroSchema<Foo> schema = AvroSchema.of(SchemaDefinition.<Foo>builder().withPojo(Foo.class).build());
        byte[] bytes = schema.encode(obj);
        Message<GenericRecord> message = mock(MessageImpl.class);
        AutoConsumeSchema autoConsumeSchema = new AutoConsumeSchema();
        autoConsumeSchema.setSchema(GenericSchemaImpl.of(schema.getSchemaInfo()));
        GenericAvroSchema genericAvroSchema = new GenericAvroSchema(schema.getSchemaInfo());
        when(message.getValue()).thenReturn(genericAvroSchema.decode(bytes));
        return PulsarRecord.<GenericRecord>builder()
                .message(message)
                .topicName("fake_topic_name")
                .schema(autoConsumeSchema)
                .build();
    }
}
