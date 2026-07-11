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

// The OpenMLDB SDK is compiled against APIs that later dependency versions removed, so the
// platform-enforced versions break it at runtime; force the versions the SDK can use. Nothing
// else in this connector uses Curator or (unshaded) protobuf. resolutionStrategy.force is needed
// (rather than a platform exclusion) because the enforced platform also reaches this module
// transitively through the jdbc-core project dependency.
// - Curator: the SDK calls NodeCache.getListenable() expecting the 4.x signature (returning
//   ListenerContainer); Curator 5.x changed it to return Listenable -> NoSuchMethodError on
//   connect under the platform's 5.7.1.
// - Protobuf: the SDK's generated classes (protoc 3.16) call makeExtensionsImmutable(), removed
//   in protobuf-java 3.22 -> NoSuchMethodError on insert under the platform's 3.25.5. 3.21.12 is
//   the newest runtime that still has it. (NARs never bundle protobuf — the Pulsar runtime
//   provides it — so this only affects compile/test classpaths.)
configurations.all {
    resolutionStrategy.force(
        "org.apache.curator:curator-client:4.2.0",
        "org.apache.curator:curator-framework:4.2.0",
        "org.apache.curator:curator-recipes:4.2.0",
        "com.google.protobuf:protobuf-java:3.21.12",
    )
}

dependencies {
    implementation(project(":jdbc:pulsar-io-jdbc-core"))
    runtimeOnly(libs.openmldb.jdbc)
    runtimeOnly(libs.openmldb.native)

    // Integration test: the OpenMLDB JDBC driver's native SDK is Linux x86-64 only, so this test
    // only runs on Linux/CI — see OpenMLDBJdbcSinkIntegrationTest.
    testImplementation(libs.testcontainers)
    testImplementation(libs.pulsar.client)
    testImplementation(libs.pulsar.functions.instance)
    testImplementation(libs.avro)
    // The JDBC driver + native SDK must be on the TEST classpath (they are runtimeOnly for the
    // NAR, which is not built for unit tests).
    testRuntimeOnly(libs.openmldb.jdbc)
    testRuntimeOnly(libs.openmldb.native)
}
