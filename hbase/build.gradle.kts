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

// hbase-client references io.opentelemetry.semconv.SemanticAttributes when building a trace span
// on every table operation. That class was removed after the ~1.30 semconv line, but the shared
// platform force-upgrades semconv to 1.37.0, so the sink throws NoClassDefFoundError on its first
// HBase call. Drop the platform-managed version and pin the version hbase-client actually needs.
pulsarConnectorsDependencies {
    exclude(libs.opentelemetry.semconv)
}

dependencies {
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.functions.instance)
    implementation(libs.pulsar.client)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.guava)
    implementation(libs.hbase.client)
    implementation(libs.hbase.common)
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.29.0-alpha")
}
