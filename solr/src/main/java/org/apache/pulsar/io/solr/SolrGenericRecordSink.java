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

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.apache.solr.common.SolrInputDocument;

/**
 * A simple Solr sink, which interprets input Record in generic record.
 */
@Connector(
    name = "solr",
    type = IOType.SINK,
    help = "The SolrGenericRecordSink is used for moving messages from Pulsar to Solr.",
    configClass = SolrSinkConfig.class
)
@Slf4j
public class SolrGenericRecordSink extends SolrAbstractSink<GenericRecord> {

    /**
     * Entry point for conversion. Determines if the record requires CDC unwrapping
     * based on user configuration.
     */
    @Override
    public SolrInputDocument convert(Record<GenericRecord> pulsarRecord) {
        SolrInputDocument solrDocument = new SolrInputDocument();
        GenericRecord messageValue = pulsarRecord.getValue();

        if (solrSinkConfig != null && solrSinkConfig.isUnwrapDebeziumRecord()) {
            return mapDebeziumPayload(messageValue, solrDocument);
        }

        // Default mapping for non-CDC messages now uses the same safe population logic
        populateSolrFields(messageValue, solrDocument);
        return solrDocument;
    }

    /**
     * Orchestrates the Debezium extraction process and ensures processing errors
     * are thrown to trigger Pulsar's failure handling.
     */
    private SolrInputDocument mapDebeziumPayload(GenericRecord rootRecord, SolrInputDocument solrDocument) {
        try {
            GenericRecord payloadRecord = extractValueRecord(rootRecord);

            if (isDebeziumEnvelope(payloadRecord)) {
                payloadRecord = extractAfterRecord(payloadRecord, solrDocument);
                // Return null if extractAfterRecord handled a DELETE or skipped the record
                if (payloadRecord == null) {
                    return null;
                }
            }

            populateSolrFields(payloadRecord, solrDocument);
            return solrDocument;
        } catch (Exception ex) {
            log.error("Debezium record processing failed", ex);
            throw new RuntimeException("Failed to extract Debezium payload", ex);
        }
    }

    /**
     * Handles Pulsar's KeyValue schema by checking the native object and
     * extracting the 'Value' part of the pair.
     */
    private GenericRecord extractValueRecord(GenericRecord rootRecord) {
        Object nativePayload = rootRecord.getNativeObject();

        if (!(nativePayload instanceof KeyValue)) {
            return rootRecord;
        }

        Object valuePart = ((KeyValue<?, ?>) nativePayload).getValue();
        if (valuePart instanceof GenericRecord) {
            log.debug("Detected KeyValue wrapper, extracting value section");
            return (GenericRecord) valuePart;
        }

        return rootRecord;
    }

    /**
     * Identifies if the record is a Debezium CDC envelope.
     * Debezium envelopes signify state changes and contain specific metadata fields.
     * The "after" field signifies the newest state of the database row after an insert/update.
     * We check for the presence of standard CDC fields to differentiate a true envelope
     * from a normal database table that simply happens to have a column named "after".
     */
    private boolean isDebeziumEnvelope(GenericRecord record) {
        boolean hasAfter = false;
        boolean hasBefore = false;
        boolean hasOp = false;

        for (Field field : record.getFields()) {
            String fieldName = field.getName();
            if ("after".equals(fieldName)) hasAfter = true;
            else if ("before".equals(fieldName)) hasBefore = true;
            else if ("op".equals(fieldName)) hasOp = true;
        }

        // A CDC envelope must strictly contain the operation code and a data state
        return (hasAfter || hasBefore) && hasOp;
    }

    /**
     * Separates the INSERT/UPDATE path from the DELETE path.
     * Issues immediate deletions to Solr for DELETE events.
     */
    private GenericRecord extractAfterRecord(GenericRecord envelopeRecord, SolrInputDocument solrDocument) {
        Object afterField = envelopeRecord.getField("after");

        if (afterField != null) {
            return (afterField instanceof GenericRecord) ? (GenericRecord) afterField : envelopeRecord;
        }

        // Processing Debezium DELETE path (where 'after' is null)
        log.debug("Debezium DELETE event detected, Processing deletion");
        Object beforeField = envelopeRecord.getField("before");

        if (!(beforeField instanceof GenericRecord)) {
            log.warn("DELETE event received, but 'before' field is missing or invalid.");
            return null;
        }

        GenericRecord beforeRecord = (GenericRecord) beforeField;
        List<Field> fields = beforeRecord.getFields();

        if (fields.isEmpty()) {
            log.warn("DELETE event received, but 'before' record has no fields to extract ID.");
            return null;
        }

        // Safe identifier lookup strategy: Look for a field explicitly named "id" first
        Field targetIdField = fields.stream()
                .filter(f -> "id".equals(f.getName()))
                .findFirst()
                .orElse(fields.get(0));

        Object id = beforeRecord.getField(targetIdField);
        if (id == null) {
            log.warn("DELETE event received, but primary key field value was null.");
            return null;
        }

        // We now let the exception bubble up to mapDebeziumPayload so the parent class fails the record
        try {
            executeSolrDelete(id);
        } catch (Exception e) {
            log.error("Failed to propagate document deletion to Solr for id={}", id, e);
            throw new RuntimeException("Solr deletion failed", e);
        }

        return null;
    }

    /**
     * Helper to issue delete commands via an UpdateRequest against the targeted collection.
     * Throws an exception on failure to ensure proper Pulsar record lifecycle fail/retry.
     */
    private void executeSolrDelete(Object id) throws Exception {
        int commitWithinMs = (solrSinkConfig != null && solrSinkConfig.getSolrCommitWithinMs() > 0)
                ? solrSinkConfig.getSolrCommitWithinMs() : 1000;

        org.apache.solr.client.solrj.request.UpdateRequest deleteRequest =
                new org.apache.solr.client.solrj.request.UpdateRequest();

        deleteRequest.deleteById(String.valueOf(id));
        deleteRequest.setCommitWithin(commitWithinMs);

        // Processes the request against the explicit collection, honoring client auth settings
        deleteRequest.process(getSolrClient(), solrSinkConfig.getSolrCollection());
        log.debug("Successfully issued delete to Solr for id={} with commitWithinMs={}", id, commitWithinMs);
    }

    /**
     * Iterates through all fields in a GenericRecord to build the Solr document,
     * applying type normalization to each value.
     */
    private void populateSolrFields(GenericRecord dataRecord, SolrInputDocument solrDocument) {
        for (Field field : dataRecord.getFields()) {
            Object rawValue = dataRecord.getField(field);
            // Skip nulls and nested records as Solr requires flat field values
            if (rawValue == null || rawValue instanceof GenericRecord) {
                continue;
            }
            solrDocument.setField(field.getName(), normalizeValue(rawValue));
        }
    }

    /**
     * Ensures numeric types are safely coerced to Strings to maintain
     * compatibility with Solr's field requirements.
     */
    private Object normalizeValue(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        return value;
    }
}
