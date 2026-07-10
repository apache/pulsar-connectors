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
package org.apache.pulsar.io.dynamodb;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.StreamSpecification;
import com.amazonaws.services.dynamodbv2.model.StreamViewType;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.io.core.SourceContext;
import org.awaitility.Awaitility;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration test for {@link DynamoDBSource} that exercises the full path from a real DynamoDB
 * Streams-enabled table through the KCL DynamoDB Streams adapter and into the source's record
 * queue. A LocalStack container provides DynamoDB, DynamoDB Streams and CloudWatch (the KCL uses
 * DynamoDB for its lease table and CloudWatch for metrics).
 */
@Slf4j
public class DynamoDBSourceIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "access";
    private static final String SECRET_KEY = "secret";
    private static final String TABLE_NAME = "pulsar-dynamodb-it";
    private static final String KEY_ATTR = "id";

    // KCL against LocalStack is slow to bootstrap (lease table creation + shard discovery), so the
    // first record can take well over a minute to surface. Give each read a generous deadline while
    // keeping the overall test bounded so an under-delivering source fails promptly rather than hangs.
    private static final int READ_TIMEOUT_SECONDS = 240;
    private static final int EXPECTED_RECORDS = 3;

    public static final LocalStackContainer LOCAL_STACK_CONTAINER =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.0.3"))
                    .withServices(
                            LocalStackContainer.Service.DYNAMODB,
                            LocalStackContainer.Service.CLOUDWATCH)
                    .withStartupTimeout(Duration.ofMinutes(5));

    private AmazonDynamoDB dynamoDB;
    private String streamArn;
    private DynamoDBSource source;
    private ExecutorService readerExecutor;
    private Thread writerThread;
    private final AtomicBoolean keepWriting = new AtomicBoolean(false);

    @BeforeClass(alwaysRun = true)
    public void beforeClass() {
        LOCAL_STACK_CONTAINER.start();
    }

    @AfterClass(alwaysRun = true)
    public void afterClass() {
        LOCAL_STACK_CONTAINER.stop();
    }

    @BeforeMethod
    public void setup() throws Exception {
        readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dynamodb-it-reader");
            t.setDaemon(true);
            return t;
        });

        dynamoDB = AmazonDynamoDBClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint(), REGION))
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY)))
                .build();

        dynamoDB.createTable(new CreateTableRequest()
                .withTableName(TABLE_NAME)
                .withAttributeDefinitions(new AttributeDefinition(KEY_ATTR, ScalarAttributeType.S))
                .withKeySchema(new KeySchemaElement(KEY_ATTR, KeyType.HASH))
                .withProvisionedThroughput(new ProvisionedThroughput(10L, 10L))
                .withStreamSpecification(new StreamSpecification()
                        .withStreamEnabled(true)
                        .withStreamViewType(StreamViewType.NEW_AND_OLD_IMAGES)));

        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() ->
                "ACTIVE".equals(dynamoDB.describeTable(TABLE_NAME).getTable().getTableStatus()));

        streamArn = dynamoDB.describeTable(TABLE_NAME).getTable().getLatestStreamArn();
        assertNotNull(streamArn, "Table did not expose a stream ARN");
        log.info("Created table {} with stream {}", TABLE_NAME, streamArn);

        source = new DynamoDBSource();
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        keepWriting.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (readerExecutor != null) {
            // A reader may still be blocked in read(), which never returns null.
            readerExecutor.shutdownNow();
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                log.warn("Failed to close source", e);
            }
        }
        if (dynamoDB != null) {
            try {
                dynamoDB.deleteTable(TABLE_NAME);
            } catch (Exception e) {
                log.warn("Failed to delete table", e);
            }
            dynamoDB.shutdown();
        }
    }

    @Test(timeOut = 600_000)
    public void testReadFromDynamoDBStream() throws Exception {
        // Seed some items before the source starts; TRIM_HORIZON makes the worker replay them.
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            putItem("seed-" + i);
        }

        // Keep writing after open as well so the worker has a steady supply regardless of exactly
        // when its lease/shard bootstrap completes.
        startBackgroundWriter();

        SourceContext sourceContext = mock(SourceContext.class);
        source.open(buildConfig(streamArn), sourceContext);

        int received = 0;
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            StreamsRecord record = readOne();
            assertNotNull(record, "read() returned null");
            assertTrue(record.getKey().isPresent(), "record had no key (eventID)");
            assertTrue(record.getProperties().containsKey(StreamsRecord.EVENT_NAME),
                    "record missing EVENT_NAME property; got " + record.getProperties());
            log.info("Received DynamoDB stream record: key={} event={}",
                    record.getKey().orElse(null), record.getProperties().get(StreamsRecord.EVENT_NAME));
            received++;
        }
        assertTrue(received >= EXPECTED_RECORDS,
                "Expected at least " + EXPECTED_RECORDS + " records, got " + received);
    }

    /**
     * Reads a single record on a bounded worker thread, failing (rather than hanging) if none
     * arrives in time. {@link DynamoDBSource#read()} blocks forever on an empty queue, so it must
     * never be called on the test thread nor inside an Awaitility assertion.
     */
    private StreamsRecord readOne() throws Exception {
        Future<StreamsRecord> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a record from the DynamoDB stream. The source produced no "
                    + "record; see the KCL/worker logs above.", e);
        }
    }

    private void startBackgroundWriter() {
        keepWriting.set(true);
        final AtomicInteger counter = new AtomicInteger();
        writerThread = new Thread(() -> {
            while (keepWriting.get()) {
                try {
                    putItem("live-" + counter.incrementAndGet());
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Background write failed", e);
                }
            }
        }, "dynamodb-it-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void putItem(String id) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY_ATTR, new AttributeValue(id));
        item.put("payload", new AttributeValue("value-" + id));
        dynamoDB.putItem(new PutItemRequest(TABLE_NAME, item));
    }

    private Map<String, Object> buildConfig(String arn) {
        Map<String, Object> config = new HashMap<>();
        // buildDynamoDBStreamsClient uses awsEndpoint; buildDynamoDBClient and buildCloudwatchClient
        // gate on awsEndpoint being non-empty but then apply dynamoEndpoint / cloudwatchEndpoint,
        // so all three must be provided to reach LocalStack.
        config.put("awsEndpoint", endpoint());
        config.put("dynamoEndpoint", endpoint());
        config.put("cloudwatchEndpoint", endpoint());
        config.put("awsRegion", REGION);
        config.put("awsDynamodbStreamArn", arn);
        config.put("awsCredentialPluginParam",
                "{\"accessKey\":\"" + ACCESS_KEY + "\",\"secretKey\":\"" + SECRET_KEY + "\"}");
        // Replay from the start of the stream so the seeded items are delivered.
        config.put("initialPositionInStream", "TRIM_HORIZON");
        // Unique application name -> unique KCL lease table per run, avoiding stale leases.
        config.put("applicationName", "pulsar-dynamodb-it-" + System.currentTimeMillis());
        return config;
    }

    private static String endpoint() {
        final URI uri = LOCAL_STACK_CONTAINER.getEndpointOverride(LocalStackContainer.Service.DYNAMODB);
        return uri.toString();
    }
}
