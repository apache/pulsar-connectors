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

import org.jetbrains.gradle.ext.copyright
import org.jetbrains.gradle.ext.settings

plugins {
    alias(libs.plugins.rat)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.versions)
    alias(libs.plugins.idea.ext)
    alias(libs.plugins.spotless) apply false // workaround for https://github.com/diffplug/spotless/issues/2877
}

versionCatalogUpdate {
    sortByKey = false
    keep {
        keepUnusedVersions.set(true)
    }
}

tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>("dependencyUpdates") {
    outputFormatter = "html"
    rejectVersionIf {
        val nonStable = candidate.version.contains("alpha") || candidate.version.contains("beta") || candidate.version.contains("rc")
        // OpenTelemetry publishes stable releases with -alpha suffix for some modules
        val isOpenTelemetry = candidate.group.startsWith("io.opentelemetry")
        nonStable && !(isOpenTelemetry && candidate.version.contains("alpha"))
    }
}

// ── Apache RAT (Release Audit Tool) ─────────────────────────────────────────
tasks.named("rat").configure {
    val excludesProp = this.javaClass.getMethod("getExcludes").invoke(this)
    @Suppress("UNCHECKED_CAST")
    val excludes = excludesProp as MutableCollection<String>
    excludes.addAll(listOf(
        // Build artifacts
        "**/build/**",
        "**/target/**",
        // Gradle files
        ".gradle/**",
        "gradle/wrapper/**",
        "**/.gradle/**",
        "**/gradle/wrapper/**",
        // Generated Flatbuffer files (Kinesis)
        "**/org/apache/pulsar/io/kinesis/fbs/*.java",
        // Services files
        "**/META-INF/services/*",
        // Certificates and keys
        "**/*.crt",
        "**/*.key",
        "**/*.csr",
        "**/*.pem",
        "**/*.srl",
        "**/certificate-authority/serial",
        "**/certificate-authority/index.txt",
        "**/*.json",
        "**/*.txt",
        // Project/IDE files
        "**/*.md",
        ".github/**",
        "**/*.nar",
        "**/.gitignore",
        "**/.gitattributes",
        "**/*.iml",
        "**/.classpath",
        "**/.project",
        "**/.settings",
        "**/.idea/**",
        "**/.vscode/**",
        // Avro schemas
        "**/*.avsc",
        // Patch files
        "**/*.patch",
        // Hidden directories
        ".*/**",
        // Test output
        "**/test-output/**",
        // Log files
        "**/*.log",
    ))
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val pulsarConnectorsVersion = catalog.findVersion("pulsar-connectors").get().requiredVersion

allprojects {
    group = "org.apache.pulsar"
    version = pulsarConnectorsVersion
}

idea {
    project {
        settings {
            // add ASL2 copyright profile to IntelliJ
            copyright {
                useDefault = "ASL2"
                profiles {
                    create("ASL2") {
                        notice = rootProject.file("src/license-header.txt").readText().trimEnd()
                        keyword = "Copyright"
                    }
                }
            }
        }
    }
}
