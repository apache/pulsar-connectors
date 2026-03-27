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
    alias(libs.plugins.rat)
}

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

subprojects {
    // Platform modules use java-platform which is mutually exclusive with java-library.
    if (project.name == "pulsar-dependencies") {
        return@subprojects
    }

    // Parent modules (jdbc, debezium) that have no source code
    if (project.name == "jdbc" || project.name == "debezium" || project.name == "distribution") {
        return@subprojects
    }

    apply(plugin = "java-library")

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    configurations.all {
        // Exclude the old SLF4J 1.x bridge
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")

        // Force Jackson version to match the version catalog
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("com.fasterxml.jackson")) {
                useVersion(rootProject.libs.versions.jackson.get())
            }
        }
    }

    // Exclude bc-fips from modules that don't need it.
    val modulesUsingBcFips = setOf("kafka-connect-adaptor")
    if (project.name !in modulesUsingBcFips) {
        configurations.all {
            exclude(group = "org.bouncycastle", module = "bc-fips")
        }
    }

    dependencies {
        // Enforced platform pins all dependency versions from the version catalog.
        "implementation"(enforcedPlatform(project(":pulsar-dependencies")))

        // Resolve lz4-java capability conflict
        configurations.all {
            resolutionStrategy.capabilitiesResolution.withCapability("org.lz4:lz4-java") {
                select("at.yawk.lz4:lz4-java:0")
            }
        }

        // Annotation processing for Lombok
        "compileOnly"(rootProject.libs.lombok)
        "annotationProcessor"(rootProject.libs.lombok)
        "testCompileOnly"(rootProject.libs.lombok)
        "testAnnotationProcessor"(rootProject.libs.lombok)

        // Common test dependencies
        "testImplementation"(rootProject.libs.testng)
        "testImplementation"(rootProject.libs.mockito.core)
        "testImplementation"(rootProject.libs.assertj.core)
        "testImplementation"(rootProject.libs.awaitility)
        "testImplementation"(rootProject.libs.system.lambda)
        "testImplementation"(rootProject.libs.slf4j.api)
    }

    tasks.withType<Test> {
        useTestNG {
            // TestNG group filtering
            providers.gradleProperty("testGroups").orNull?.let { groups ->
                includeGroups(*groups.split(",").map { it.trim() }.toTypedArray())
            }
            val excludedTestGroups = providers.gradleProperty("excludedTestGroups").getOrElse("quarantine,flaky")
            excludeGroups(*(excludedTestGroups.split(",").map { it.trim() }.toTypedArray()))
        }
        maxHeapSize = "1300m"
        maxParallelForks = 4
        systemProperty("testRetryCount", System.getProperty("testRetryCount", "1"))
        systemProperty("testFailFast", System.getProperty("testFailFast", "true"))
        jvmArgs(
            "--add-opens", "java.base/jdk.internal.loader=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/sun.net=ALL-UNNAMED",
            "--add-opens", "java.management/sun.management=ALL-UNNAMED",
            "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED",
            "--add-opens", "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "-XX:+EnableDynamicAgentLoading",
            "-Xshare:off",
            "-Dio.netty.tryReflectionSetAccessible=true",
            "-Dpulsar.allocator.pooled=true",
            "-Dpulsar.allocator.exit_on_oom=false",
            "-Dpulsar.allocator.out_of_memory_policy=FallbackToHeap",
            "-Dpulsar.test.preventExit=true",
        )
    }

    // Shadow JAR modules: expose the shadow JAR as a consumable configuration so other
    // projects can depend on it via project(path = "...", configuration = "shadowElements")
    pluginManager.withPlugin("com.gradleup.shadow") {
        val shadowElements by configurations.creating {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
            }
        }
        artifacts.add("shadowElements", tasks.named("shadowJar"))
    }

    // NAR modules should not bundle Pulsar platform dependencies — they are provided
    // at runtime by Pulsar's classloader hierarchy.
    pluginManager.withPlugin("io.github.merlimat.nar") {
        val pulsarPlatformModules = setOf(
            "pulsar-client-api",
            "pulsar-client-admin-api",
            "pulsar-client-original",
            "pulsar-client",
            "pulsar-common",
            "pulsar-config-validation",
            "bouncy-castle-bc",
            "pulsar-functions-api",
            "pulsar-functions-instance",
            "pulsar-functions-proto",
            "pulsar-functions-secrets",
            "pulsar-functions-utils",
            "pulsar-io-core",
            "pulsar-metadata",
            "pulsar-opentelemetry",
            "managed-ledger",
            "pulsar-package-core",
        )
        configurations.named("runtimeClasspath") {
            exclude(group = "org.apache.bookkeeper")
            exclude(group = "com.google.protobuf")
            pulsarPlatformModules.forEach { module ->
                exclude(group = "org.apache.pulsar", module = module)
            }
        }

        // The NAR plugin copies from runtimeClasspath which resolves project dependencies
        // as class directories, not JARs. The NarClassLoader expects JARs in
        // META-INF/bundled-dependencies/. Force the NAR task to use JAR artifacts.
        val jarView = configurations.named("runtimeClasspath").get()
            .incoming.artifactView {
                attributes {
                    attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objects.named(LibraryElements::class.java, LibraryElements.JAR)
                    )
                }
            }.files
        tasks.named("nar", Jar::class.java) {
            into("META-INF/bundled-dependencies") {
                from(jarView)
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }
    }

    // Set archive names to match Maven artifactId for nested modules.
    val parentProject = project.parent
    if (parentProject != null && parentProject != rootProject && parentProject.parent != rootProject
            && !project.name.startsWith(parentProject.name)) {
        val qualifiedName = "${parentProject.name}-${project.name}"
        the<BasePluginExtension>().archivesName.set(qualifiedName)
        pluginManager.withPlugin("io.github.merlimat.nar") {
            @Suppress("UNCHECKED_CAST")
            val narExt = extensions.getByName("nar")
            val narIdProp = narExt.javaClass.getMethod("getNarId").invoke(narExt) as Property<String>
            narIdProp.set(qualifiedName)
        }
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }
}

// Access version catalog from subprojects
val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = rootProject.extensions.getByType()
