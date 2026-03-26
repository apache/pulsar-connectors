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
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(libs.amazon.kinesis.producer)
}

// The KPL requires protobuf 4.x (has RuntimeVersion class). Override the enforced
// platform constraint (3.25.5) since this module shades protobuf into a relocated
// package — the version won't conflict with the rest of Pulsar at runtime.
configurations.all {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-java:4.29.0")
    }
}

// Disable the default jar task so the shadow JAR is the only artifact.
// This avoids Gradle's implicit dependency validation errors when consumers
// use project() to depend on this module.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    dependencies {
        include(dependency("software.amazon.kinesis:amazon-kinesis-producer"))
        include(dependency("com.google.protobuf:protobuf-java"))
    }
    relocate("com.google.protobuf", "org.apache.pulsar.io.kinesis.shaded.com.google.protobuf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
