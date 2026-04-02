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

plugins {
    id("pulsar-connectors.java-conventions")
    id("pulsar-connectors.nar-conventions")
}

// KafkaBytesSource uses SchemaInfoImpl from pulsar-common, which is excluded from
// NAR runtimeClasspath by default. Include it in the NAR bundle.
pulsarConnectorsNar {
    includePulsarModule("pulsar-common")
}

dependencies {
    implementation(libs.pulsar.io.common)
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.common)
    implementation(libs.pulsar.client)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.guava)
    implementation(libs.kafka.clients) {
        exclude(group = "org.bitbucket.b_c", module = "jose4j")
        exclude(group = "org.lz4", module = "lz4-java")
    }
    implementation(libs.kafka.schema.registry.client)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.jjwt.impl)
    implementation(libs.jjwt.jackson)

    testImplementation(libs.hamcrest)
    testImplementation(libs.awaitility)
    testImplementation(libs.bcpkix.jdk18on)
}
