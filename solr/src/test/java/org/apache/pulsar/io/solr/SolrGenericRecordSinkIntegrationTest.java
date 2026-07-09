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

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericAvroSchema;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.MapSolrParams;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test for {@link SolrGenericRecordSink} that exercises the full write path against a
 * real Solr instance running in a Testcontainers-managed Docker container. Records are pushed
 * through the sink and then read back via SolrJ to assert the documents and their field values
 * actually landed in Solr.
 */
@Slf4j
public class SolrGenericRecordSinkIntegrationTest {

    // Matches the repo's solr = "9.8.0" version catalog entry.
    private static final DockerImageName SOLR_IMAGE = DockerImageName.parse("solr:9.8");
    private static final int SOLR_PORT = 8983;
    private static final String COLLECTION = "pulsar_test";

    private GenericContainer<?> solrContainer;
    private String baseSolrUrl;
    private SolrGenericRecordSink sink;

    /**
     * A simple record POJO. The {@code id} field is required by Solr's default (schemaless)
     * configset because it is the uniqueKey; the remaining fields are auto-added to the schema.
     */
    @Data
    public static class Product {
        private String id;
        private String title;
        private String category;
    }

    @BeforeMethod
    public void setUp() {
        // "solr-precreate <core>" creates the core before launching Solr in the foreground, so the
        // core is guaranteed to exist by the time the HTTP endpoint becomes reachable.
        solrContainer = new GenericContainer<>(SOLR_IMAGE)
                .withExposedPorts(SOLR_PORT)
                .withCommand("solr-precreate", COLLECTION)
                .waitingFor(Wait.forHttp("/solr/admin/cores?action=STATUS&core=" + COLLECTION)
                        .forPort(SOLR_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        solrContainer.start();

        baseSolrUrl = "http://" + solrContainer.getHost() + ":"
                + solrContainer.getMappedPort(SOLR_PORT) + "/solr";
        sink = new SolrGenericRecordSink();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (sink != null) {
            try {
                sink.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (solrContainer != null) {
            solrContainer.stop();
        }
    }

    @Test(timeOut = 300_000)
    public void testWriteAndReadBack() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("solrUrl", baseSolrUrl);
        config.put("solrMode", "Standalone");
        config.put("solrCollection", COLLECTION);
        config.put("solrCommitWithinMs", "100");

        sink.open(config, mock(SinkContext.class));

        AvroSchema<Product> schema = AvroSchema.of(Product.class);
        GenericAvroSchema genericAvroSchema = new GenericAvroSchema(schema.getSchemaInfo());

        int numRecords = 5;
        CompletableFuture<?>[] acks = new CompletableFuture[numRecords];
        for (int i = 0; i < numRecords; i++) {
            Product product = new Product();
            product.setId("doc-" + i);
            product.setTitle("title-" + i);
            product.setCategory("category-" + i);

            acks[i] = new CompletableFuture<>();
            final int idx = i;
            GenericRecord value = genericAvroSchema.decode(schema.encode(product));
            Record<GenericRecord> record = new Record<GenericRecord>() {
                @Override
                public GenericRecord getValue() {
                    return value;
                }

                @Override
                public void ack() {
                    acks[idx].complete(null);
                }

                @Override
                public void fail() {
                    acks[idx].completeExceptionally(new RuntimeException("Record " + idx + " failed"));
                }
            };
            sink.write(record);
        }

        // All records must be acknowledged by the sink (status 0 from Solr).
        CompletableFuture.allOf(acks).get();

        // The sink commits asynchronously (commitWithin=100ms), so poll until the documents become
        // searchable rather than sleeping for a fixed interval.
        try (HttpSolrClient queryClient = new HttpSolrClient.Builder(baseSolrUrl).build()) {
            await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
                Map<String, String> params = new HashMap<>();
                params.put("q", "*:*");
                QueryResponse response = queryClient.query(COLLECTION, new MapSolrParams(params));
                assertEquals(response.getResults().getNumFound(), numRecords,
                        "expected all records to be indexed and searchable");
            });

            // Verify each document's field values were persisted correctly.
            for (int i = 0; i < numRecords; i++) {
                Map<String, String> params = new HashMap<>();
                params.put("q", "id:doc-" + i);
                QueryResponse response = queryClient.query(COLLECTION, new MapSolrParams(params));
                assertEquals(response.getResults().getNumFound(), 1L, "expected exactly one doc for id doc-" + i);
                SolrDocument doc = response.getResults().get(0);
                assertEquals(doc.getFieldValue("id"), "doc-" + i);
                assertEquals(firstValue(doc, "title"), "title-" + i);
                assertEquals(firstValue(doc, "category"), "category-" + i);
            }
        }
    }

    // Schemaless string fields may be stored as single-valued or multi-valued depending on the
    // field-guessing update processor; normalize to the first value for assertions.
    private static Object firstValue(SolrDocument doc, String field) {
        Object v = doc.getFieldValue(field);
        if (v instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) v;
            return c.isEmpty() ? null : c.iterator().next();
        }
        return v;
    }
}
