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
}
dependencies {
    // Deliberately not a NAR module. nar-conventions disables the jar task and replaces this
    // project's outgoing artifacts with the NAR itself, so a NAR module cannot be depended on — the
    // consumer would bundle a .nar inside META-INF/bundled-dependencies where the classloader cannot
    // reach the classes. The shared sink machinery therefore lives here, in a plain jar that the
    // `cassandra`, `cassandra-generic-record` and `cassandra-json` NARs each depend on. This mirrors
    // jdbc/core and its per-database NAR modules.
    api(libs.pulsar.io.core)
    api(libs.pulsar.io.common)
    api(libs.pulsar.client.api)
    api(libs.cassandra.driver)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.beanutils)

    testImplementation(libs.testcontainers.cassandra)
    // Test only: builds the Avro-backed GenericRecord that CassandraGenericRecordSink consumes, the
    // same way the hbase and jdbc sink tests do.
    testImplementation(libs.pulsar.client)
}
