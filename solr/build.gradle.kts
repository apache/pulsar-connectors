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
// Solr 9.x embeds Jetty 10.x, which is incompatible with Pulsar's Jetty 12.
// The pulsar-dependencies platform enforces Jetty 12 strict versions, which override
// enforcedPlatform("jetty-bom:10.0.24") because Gradle picks the highest strict version.
// Use resolutionStrategy.force to downgrade Jetty to 10.0.24 for test configurations.
val jetty10Version = "10.0.24"
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.eclipse.jetty")
                && !requested.group.contains("toolchain")) {
            useVersion(jetty10Version)
        }
    }
}

dependencies {
    implementation(libs.pulsar.io.common)
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.functions.instance)
    implementation(libs.pulsar.client)
    implementation(libs.solr.solrj)
    implementation(libs.guava)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.lang3)
    implementation(libs.commons.collections4)

    testImplementation(libs.solr.test.framework)
    testImplementation(libs.solr.core)
}
