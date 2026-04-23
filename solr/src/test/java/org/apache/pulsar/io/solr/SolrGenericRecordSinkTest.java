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
package org.apache.pulsar.io.solr;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.schema.AutoConsumeSchema;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericAvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.source.PulsarRecord;
import org.apache.solr.common.SolrInputDocument;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * solr Sink test.
 */
@Slf4j
public class SolrGenericRecordSinkTest {

    private SolrServerUtil solrServerUtil;
    private Message<GenericRecord> message;

    /**
     * A Simple class to test solr class.
     */
    @Data
    public static class Foo {
        private String field1;
        private String field2;
    }

    @BeforeMethod
    public void setUp() throws Exception {
        solrServerUtil = new SolrServerUtil(8983);
        solrServerUtil.startStandaloneSolr();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        solrServerUtil.stopStandaloneSolr();
    }

    @Test
    public void testOpenAndWriteSink() throws Exception {
        message = mock(MessageImpl.class);
        Map<String, Object> configs = new HashMap<>();
        configs.put("solrUrl", "http://localhost:8983/solr");
        configs.put("solrMode", "Standalone");
        configs.put("solrCollection", "techproducts");
        configs.put("solrCommitWithinMs", "100");
        configs.put("username", "");
        configs.put("password", "");
        GenericSchema<GenericRecord> genericAvroSchema;

        SolrGenericRecordSink sink = new SolrGenericRecordSink();

        // prepare a foo Record
        Foo obj = new Foo();
        obj.setField1("FakeFiled1");
        obj.setField2("FakeFiled1");
        AvroSchema<Foo> schema = AvroSchema.of(Foo.class);

        byte[] bytes = schema.encode(obj);
        AutoConsumeSchema autoConsumeSchema = new AutoConsumeSchema();
        autoConsumeSchema.setSchema(GenericSchemaImpl.of(schema.getSchemaInfo()));

        Record<GenericRecord> record = PulsarRecord.<GenericRecord>builder()
            .message(message)
            .topicName("fake_topic_name")
            .build();

        genericAvroSchema = new GenericAvroSchema(schema.getSchemaInfo());

        when(message.getValue())
                .thenReturn(genericAvroSchema.decode(bytes));

        log.info("foo:{}, Message.getValue: {}, record.getValue: {}",
            obj.toString(),
            message.getValue().toString(),
            record.getValue().toString());

        // open should success
        sink.open(configs, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDebeziumUnwrapEnvelope() throws Exception {
        SolrGenericRecordSink sink = new SolrGenericRecordSink();
        Map<String, Object> configs = new HashMap<>();
        configs.put("solrUrl", "http://localhost:8983/solr");
        configs.put("solrMode", "Standalone");
        configs.put("solrCollection", "techproducts");
        configs.put("solrCommitWithinMs", "100");
        configs.put("username", "");
        configs.put("password", "");
        configs.put("unwrapDebeziumRecord", true);
        sink.open(configs, null);

        // Build root record wrapping a KeyValue payload
        GenericRecord mockRootRecord = mock(GenericRecord.class);
        Record<GenericRecord> mockMessage = mock(Record.class);
        when(mockMessage.getValue()).thenReturn(mockRootRecord);

        GenericRecord mockValueRecord = mock(GenericRecord.class);
        when(mockRootRecord.getNativeObject()).thenReturn(new KeyValue<>("key", mockValueRecord));
        // containsAfterField() iterates getFields() — must include "after" field here
        Field afterSchemaField = mock(Field.class);
        when(afterSchemaField.getName()).thenReturn("after");
        when(mockValueRecord.getFields()).thenReturn(Arrays.asList(afterSchemaField));

        // extractAfterRecord() calls getField("after") by string — return nested record
        GenericRecord mockAfterRecord = mock(GenericRecord.class);
        when(mockValueRecord.getField("after")).thenReturn(mockAfterRecord);

        // Fields inside the "after" payload to be mapped into Solr document
        Field idField = mock(Field.class);
        when(idField.getName()).thenReturn("id");
        Field userIdField = mock(Field.class);
        when(userIdField.getName()).thenReturn("user_id");
        when(mockAfterRecord.getFields()).thenReturn(Arrays.asList(idField, userIdField));
        when(mockAfterRecord.getField(idField)).thenReturn(100);
        when(mockAfterRecord.getField(userIdField)).thenReturn(999);

        SolrInputDocument doc = sink.convert(mockMessage);

        Assert.assertNotNull(doc, "Document should not be null for envelope with 'after' field");
        Assert.assertEquals(doc.getFieldValue("id"), "100");
        Assert.assertEquals(doc.getFieldValue("user_id"), "999");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDebeziumUnwrapFlatValue() throws Exception {
        SolrGenericRecordSink sink = new SolrGenericRecordSink();
        Map<String, Object> configs = new HashMap<>();
        configs.put("solrUrl", "http://localhost:8983/solr");
        configs.put("solrMode", "Standalone");
        configs.put("solrCollection", "techproducts");
        configs.put("solrCommitWithinMs", "100");
        configs.put("username", "");
        configs.put("password", "");
        configs.put("unwrapDebeziumRecord", true);
        sink.open(configs, null);

        // Build root record wrapping a KeyValue payload
        GenericRecord mockRootRecord = mock(GenericRecord.class);
        Record<GenericRecord> mockMessage = mock(Record.class);
        when(mockMessage.getValue()).thenReturn(mockRootRecord);

        GenericRecord mockValueRecord = mock(GenericRecord.class);
        when(mockRootRecord.getNativeObject()).thenReturn(new KeyValue<>("key", mockValueRecord));

        // containsAfterField() will iterate these — no "after" field, so flat path is taken
        Field idField = mock(Field.class);
        when(idField.getName()).thenReturn("id");
        Field profileIdField = mock(Field.class);
        when(profileIdField.getName()).thenReturn("profile_id");
        when(mockValueRecord.getFields()).thenReturn(Arrays.asList(idField, profileIdField));

        // populateSolrFields() reads values by Field object reference
        when(mockValueRecord.getField(idField)).thenReturn(200);
        when(mockValueRecord.getField(profileIdField)).thenReturn(801);
        SolrInputDocument doc = sink.convert(mockMessage);

        Assert.assertNotNull(doc, "Document should not be null for flat value record");
        Assert.assertEquals(doc.getFieldValue("id"), "200");
        Assert.assertEquals(doc.getFieldValue("profile_id"), "801");
        Assert.assertNull(doc.getFieldValue("user_id"), "user_id was not provided, should be null");
    }
}
