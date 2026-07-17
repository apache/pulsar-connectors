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
package org.apache.pulsar.io.kinesis;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.StreamStatus;

/**
 * Integration test for {@link KinesisSource} that exercises the full path from a real Kinesis
 * stream through the KCL (Kinesis Client Library) worker and into the source's record queue. A
 * LocalStack container provides Kinesis, DynamoDB and CloudWatch (the KCL uses DynamoDB for its
 * lease table and CloudWatch for metrics).
 */
@Slf4j
public class KinesisSourceIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "access";
    private static final String SECRET_KEY = "secret";
    private static final String STREAM_NAME = "pulsar-kinesis-source-it";

    // KCL against LocalStack is slow to bootstrap (lease table creation + shard discovery), so the
    // first record can take well over a minute to surface. Give each read a generous deadline while
    // keeping the overall test bounded so an under-delivering source fails promptly rather than hangs.
    private static final int READ_TIMEOUT_SECONDS = 240;
    private static final int EXPECTED_RECORDS = 3;

    public static final LocalStackContainer LOCAL_STACK_CONTAINER =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.0.3"))
                    .withServices(
                            LocalStackContainer.Service.KINESIS,
                            LocalStackContainer.Service.DYNAMODB,
                            LocalStackContainer.Service.CLOUDWATCH)
                    .withEnv("KINESIS_PROVIDER", "kinesalite")
                    .withStartupTimeout(Duration.ofMinutes(5));

    private KinesisAsyncClient client;
    private KinesisSource source;
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
            Thread t = new Thread(r, "kinesis-it-reader");
            t.setDaemon(true);
            return t;
        });

        client = createClient();
        // Bound every blocking AWS call: @BeforeMethod is not covered by @Test(timeOut=...), so an
        // unbounded future.get() here could hang the suite if LocalStack stalls.
        client.createStream(CreateStreamRequest.builder()
                .streamName(STREAM_NAME)
                .shardCount(1)
                .build()).get(30, TimeUnit.SECONDS);

        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() ->
                client.describeStream(DescribeStreamRequest.builder().streamName(STREAM_NAME).build())
                        .get(10, TimeUnit.SECONDS).streamDescription().streamStatus() == StreamStatus.ACTIVE);
        log.info("Created Kinesis stream {}", STREAM_NAME);

        source = new KinesisSource();
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
        if (client != null) {
            try {
                client.deleteStream(builder -> builder.streamName(STREAM_NAME)).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to delete stream", e);
            }
            client.close();
        }
    }

    @Test(timeOut = 600_000)
    public void testReadFromKinesisStream() throws Exception {
        // Seed some records before the source starts; TRIM_HORIZON makes the worker replay them.
        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            putRecord("seed-" + i);
        }

        // Keep writing after open as well so the worker has a steady supply regardless of exactly
        // when its lease/shard bootstrap completes.
        startBackgroundWriter();

        SourceContext sourceContext = mock(SourceContext.class);
        source.open(buildConfig(), sourceContext);

        for (int i = 0; i < EXPECTED_RECORDS; i++) {
            KinesisRecord record = readOne();
            assertNotNull(record, "read() returned null");

            // The emitted value and key must carry what we put on the stream: putRecord writes a
            // "value-<id>" payload under a "pk-<id>" partition key (id = seed-* or live-*).
            assertNotNull(record.getValue(), "record had no value");
            String value = new String(record.getValue(), StandardCharsets.UTF_8);
            assertTrue(value.startsWith("value-"), "unexpected record value: " + value);
            assertTrue(record.getKey().isPresent(), "record had no key (partition key)");
            assertTrue(record.getKey().get().startsWith("pk-"),
                    "unexpected partition key: " + record.getKey().get());
            assertTrue(record.getProperties().containsKey(KinesisRecord.SEQUENCE_NUMBER),
                    "record missing SEQUENCE_NUMBER property; got " + record.getProperties());
            log.info("Received Kinesis record: key={} value={} seq={}",
                    record.getKey().get(), value,
                    record.getProperties().get(KinesisRecord.SEQUENCE_NUMBER));
        }
    }

    /**
     * Reads a single record on a bounded worker thread, failing (rather than hanging) if none
     * arrives in time. {@link KinesisSource#read()} blocks forever on an empty queue, so it must
     * never be called on the test thread nor inside an Awaitility assertion.
     */
    private KinesisRecord readOne() throws Exception {
        Future<KinesisRecord> future = readerExecutor.submit(() -> source.read());
        try {
            return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssertionError("Timed out after " + READ_TIMEOUT_SECONDS
                    + "s waiting for a record from the Kinesis stream. The source produced no "
                    + "record; see the KCL/worker logs above.", e);
        }
    }

    private void startBackgroundWriter() {
        keepWriting.set(true);
        final AtomicInteger counter = new AtomicInteger();
        writerThread = new Thread(() -> {
            while (keepWriting.get()) {
                try {
                    putRecord("live-" + counter.incrementAndGet());
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Background write failed", e);
                }
            }
        }, "kinesis-it-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void putRecord(String id) throws Exception {
        client.putRecord(PutRecordRequest.builder()
                .streamName(STREAM_NAME)
                .partitionKey("pk-" + id)
                .data(SdkBytes.fromByteArray(("value-" + id).getBytes(StandardCharsets.UTF_8)))
                .build()).get();
    }

    private Map<String, Object> buildConfig() {
        Map<String, Object> config = new HashMap<>();
        // The source config applies each endpoint via URI.create; LocalStack serves every service on
        // one edge port, so the Kinesis endpoint URI works for the DynamoDB (lease table) and
        // CloudWatch (metrics) clients too. All three must be set to reach LocalStack.
        config.put("awsEndpoint", endpoint());
        config.put("dynamoEndpoint", endpoint());
        config.put("cloudwatchEndpoint", endpoint());
        config.put("awsRegion", REGION);
        config.put("awsKinesisStreamName", STREAM_NAME);
        config.put("awsCredentialPluginParam",
                "{\"accessKey\":\"" + ACCESS_KEY + "\",\"secretKey\":\"" + SECRET_KEY + "\"}");
        // Replay from the start of the stream so the seeded records are delivered.
        config.put("initialPositionInStream", "TRIM_HORIZON");
        // Unique application name -> unique KCL lease table per run, avoiding stale leases.
        config.put("applicationName", "pulsar-kinesis-it-" + System.currentTimeMillis());
        // Enhanced fan-out (SubscribeToShard) is unreliable on LocalStack; use polling instead.
        config.put("useEnhancedFanOut", false);
        return config;
    }

    private KinesisAsyncClient createClient() {
        return KinesisAsyncClient.builder()
                .credentialsProvider(new AwsCredentialsProvider() {
                    @Override
                    public AwsCredentials resolveCredentials() {
                        return AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);
                    }
                })
                .region(Region.of(REGION))
                .endpointOverride(LOCAL_STACK_CONTAINER.getEndpointOverride(LocalStackContainer.Service.KINESIS))
                .build();
    }

    private static String endpoint() {
        final URI uri = LOCAL_STACK_CONTAINER.getEndpointOverride(LocalStackContainer.Service.KINESIS);
        return uri.toString();
    }
}
