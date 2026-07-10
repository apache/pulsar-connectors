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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import com.amazonaws.AmazonWebServiceClient;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.io.aws.AwsCredentialProviderPlugin;
import org.apache.pulsar.io.core.SourceContext;
import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * Regression tests for client construction when a custom endpoint is configured.
 *
 * <p>All three builders used to set an {@code EndpointConfiguration} and then, in a separate
 * branch, a region. AWS SDK v1 rejects that pairing with
 * {@code IllegalStateException: Only one of Region or EndpointConfiguration may be set}, and
 * {@code DynamoDBSource.open()} requires {@code awsRegion}, so every configuration supplying an
 * endpoint failed at construction — the endpoint path could never have worked.
 *
 * <p>Two builders also gated on {@code awsEndpoint} while reading {@code dynamoEndpoint} /
 * {@code cloudwatchEndpoint}, so setting {@code awsEndpoint} alone built an endpoint
 * configuration from an empty string. These tests pin both behaviors.
 */
public class DynamoDBSourceConfigClientBuilderTest {

    private static final String REGION = "us-east-1";
    private static final String STREAMS_ENDPOINT = "http://localhost:4566/streams";
    private static final String DYNAMO_ENDPOINT = "http://localhost:4566/dynamo";
    private static final String CLOUDWATCH_ENDPOINT = "http://localhost:4566/cloudwatch";

    /** Minimal plugin: the builders only need a credential provider. */
    private static final AwsCredentialProviderPlugin CREDENTIALS = new AwsCredentialProviderPlugin() {
        @Override
        public void init(String param) {
        }

        @Override
        public AWSCredentialsProvider getCredentialProvider() {
            return new AWSStaticCredentialsProvider(new BasicAWSCredentials("access", "secret"));
        }

        @Override
        public void close() {
        }
    };

    private DynamoDBSourceConfig config(Map<String, Object> overrides) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("awsDynamodbStreamArn", "arn:aws:dynamodb:us-east-1:1:table/t/stream/1");
        map.put("awsRegion", REGION);
        map.put("awsCredentialPluginParam", "{\"accessKey\":\"a\",\"secretKey\":\"s\"}");
        map.putAll(overrides);
        return DynamoDBSourceConfig.load(map, Mockito.mock(SourceContext.class));
    }

    /** Reads the endpoint the SDK actually resolved for a built client. */
    private static URI endpointOf(Object client) throws Exception {
        Field field = AmazonWebServiceClient.class.getDeclaredField("endpoint");
        field.setAccessible(true);
        return (URI) field.get(client);
    }

    /**
     * The bug: an endpoint plus the (mandatory) region made the SDK throw
     * {@code Only one of Region or EndpointConfiguration may be set}.
     */
    @Test
    public void testBuildersDoNotThrowWhenEndpointAndRegionAreBothSet() throws Exception {
        DynamoDBSourceConfig config = config(Map.of(
                "awsEndpoint", STREAMS_ENDPOINT,
                "dynamoEndpoint", DYNAMO_ENDPOINT,
                "cloudwatchEndpoint", CLOUDWATCH_ENDPOINT));

        // Construction is the assertion: each of these threw IllegalStateException before the fix.
        config.buildDynamoDBStreamsClient(CREDENTIALS);
        config.buildDynamoDBClient(CREDENTIALS);
        config.buildCloudwatchClient(CREDENTIALS);
    }

    /** Each builder must use its own endpoint field, not another client's. */
    @Test
    public void testEachBuilderSelectsItsOwnEndpoint() throws Exception {
        DynamoDBSourceConfig config = config(Map.of(
                "awsEndpoint", STREAMS_ENDPOINT,
                "dynamoEndpoint", DYNAMO_ENDPOINT,
                "cloudwatchEndpoint", CLOUDWATCH_ENDPOINT));

        assertEquals(endpointOf(config.buildDynamoDBStreamsClient(CREDENTIALS)).toString(), STREAMS_ENDPOINT);
        assertEquals(endpointOf(config.buildDynamoDBClient(CREDENTIALS)).toString(), DYNAMO_ENDPOINT);
        assertEquals(endpointOf(config.buildCloudwatchClient(CREDENTIALS)).toString(), CLOUDWATCH_ENDPOINT);
    }

    /**
     * Setting only {@code awsEndpoint} used to make the DynamoDB and CloudWatch builders construct an
     * endpoint configuration from an empty {@code dynamoEndpoint} / {@code cloudwatchEndpoint}.
     * They must fall back to the region's default endpoint instead.
     */
    @Test
    public void testAwsEndpointAloneDoesNotLeakIntoTheOtherClients() throws Exception {
        DynamoDBSourceConfig config = config(Map.of("awsEndpoint", STREAMS_ENDPOINT));

        assertEquals(endpointOf(config.buildDynamoDBStreamsClient(CREDENTIALS)).toString(), STREAMS_ENDPOINT);

        URI dynamoEndpoint = endpointOf(config.buildDynamoDBClient(CREDENTIALS));
        assertTrue(dynamoEndpoint.toString().contains("dynamodb." + REGION),
                "DynamoDB client should use the regional default endpoint, got " + dynamoEndpoint);

        URI cloudwatchEndpoint = endpointOf(config.buildCloudwatchClient(CREDENTIALS));
        assertTrue(cloudwatchEndpoint.toString().contains("monitoring." + REGION),
                "CloudWatch client should use the regional default endpoint, got " + cloudwatchEndpoint);
    }

    /** The common case against real AWS: no endpoint override at all. */
    @Test
    public void testRegionOnlyUsesRegionalEndpoints() throws Exception {
        DynamoDBSourceConfig config = config(Map.of());

        assertTrue(endpointOf(config.buildDynamoDBStreamsClient(CREDENTIALS)).toString()
                .contains("streams.dynamodb." + REGION));
        assertTrue(endpointOf(config.buildDynamoDBClient(CREDENTIALS)).toString()
                .contains("dynamodb." + REGION));
        assertTrue(endpointOf(config.buildCloudwatchClient(CREDENTIALS)).toString()
                .contains("monitoring." + REGION));
    }
}
