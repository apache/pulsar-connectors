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
    alias(libs.plugins.nar)
}

dependencies {
    // The shaded KPL project bundles amazon-kinesis-producer with relocated protobuf.
    // Use shadowElements to get the shadow JAR (which has relocated protobuf).
    implementation(project(path = ":pulsar-io:pulsar-io-kinesis-kpl-shaded", configuration = "shadowElements"))
    // CompileOnly: needed for compilation against KPL classes but NOT bundled in NAR.
    // At runtime, KPL classes come from the shaded project's shadow JAR.
    compileOnly(libs.amazon.kinesis.producer)
    testImplementation(libs.amazon.kinesis.producer)
    implementation(project(":pulsar-io:pulsar-io-common"))
    implementation(project(":pulsar-io:pulsar-io-core"))
    implementation(project(":pulsar-io:pulsar-io-aws"))
    implementation(libs.commons.lang3)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.dataformat.cbor)
    implementation(libs.avro)
    implementation(libs.json.flattener)
    implementation(libs.gson)
    implementation(libs.aws.java.sdk.core)
    implementation(libs.aws.sdk2.utils)
    implementation(libs.amazon.kinesis.client)
    implementation(libs.flatbuffers.java)

    testImplementation(project(":pulsar-functions:pulsar-functions-instance"))
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.awaitility)
    testImplementation(libs.jsonassert)
}

