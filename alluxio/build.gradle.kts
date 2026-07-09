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

val alluxioVersion = "2.9.4"

// Alluxio requires older versions of netty, grpc, and jetty than the shared platform provides.
// Exclude these BOMs from the enforced platform so the alluxio-specific versions below can apply.
pulsarConnectorsDependencies {
    exclude(libs.jetty.bom)
    exclude(libs.netty.bom)
    exclude(libs.grpc.bom)
}

dependencies {
    // Alluxio-compatible BOMs — these override the shared platform versions.
    implementation(enforcedPlatform(libs.jetty9.bom.override))
    implementation(enforcedPlatform("io.netty:netty-bom:4.1.100.Final"))
    implementation(enforcedPlatform("io.grpc:grpc-bom:1.37.0"))

    implementation(libs.pulsar.io.core)
    implementation("org.alluxio:alluxio-core-client-fs:$alluxioVersion")
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.guava)

    testImplementation(libs.pulsar.client)
    testImplementation("org.alluxio:alluxio-minicluster:$alluxioVersion") {
        exclude(group = "org.glassfish", module = "javax.el")
    }
}