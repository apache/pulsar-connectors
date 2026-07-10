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

// The hadoop-minicluster used by the sink integration test embeds an HDFS namenode whose HTTP
// server requires Jetty 9.x, while the shared platform enforces Jetty 12.x (which removed classes
// such as HandlerWrapper). Drop the Jetty 12 BOM from the enforced platform so the Jetty 9.x
// override below can apply. This affects the test classpath only; the connector itself is an HDFS
// client and never loads Jetty at runtime.
pulsarConnectorsDependencies {
    exclude(libs.jetty.bom)
}

dependencies {
    implementation(libs.pulsar.io.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.collections4)
    implementation(libs.hadoop.client) {
        exclude(group = "org.slf4j")
        exclude(group = "log4j")
    }
    implementation(libs.bcprov.jdk18on)
    implementation(libs.jakarta.activation.api)

    // In-JVM HDFS cluster for sink integration testing (no external services / Docker required).
    testImplementation(enforcedPlatform(libs.jetty9.bom.override))
    testImplementation(libs.hadoop.minicluster)
}
