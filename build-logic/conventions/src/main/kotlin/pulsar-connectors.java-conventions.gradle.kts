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
    `java-library`
    id("pulsar-connectors.code-quality-conventions")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String): Provider<MinimalExternalModuleDependency> =
    catalog.findLibrary(alias).orElseThrow {
        GradleException("Library alias '$alias' not found in version catalog 'libs'")
    }

// Add shared test resources (log4j2-test.xml) to the test classpath for all modules.
the<SourceSetContainer>()["test"].resources.srcDir(rootProject.file("gradle/test-resources"))

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
}

configurations.all {
    // Exclude the old SLF4J 1.x bridge pulled in by transitive dependencies.
    // Pulsar uses SLF4J 2.x with log4j-slf4j2-impl; having both causes
    // NoSuchMethodError in Log4jLoggerFactory at test startup.
    exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j-impl")
}

// Exclude bc-fips from modules that don't need it. bc-fips's CryptoServicesRegistrar
// conflicts with bcprov-jdk18on's version — having both causes NoSuchMethodError.
val modulesUsingBcFips = setOf("kafka-connect-adaptor")
if (project.name !in modulesUsingBcFips) {
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bc-fips")
    }
}

/**
 * Configures how the shared `pulsar-connectors-dependencies` platform is applied.
 *
 * By default, the platform is applied as `enforcedPlatform`, which pins (strictly enforces) all
 * dependency versions from the version catalog. Subprojects can customize this behavior:
 *
 * ```kotlin
 * pulsarConnectorsDependencies {
 *     // Exclude specific dependencies from the platform so they can be overridden locally.
 *     // Useful when a module needs an older version of a BOM or library (e.g. alluxio needs
 *     // older netty/grpc).
 *     exclude(group = "io.netty", module = "netty-bom")
 *
 *     // Set enforced = false to use platform() instead of enforcedPlatform(). This makes all
 *     // version constraints non-strict: the platform versions are used only as defaults (allowing
 *     // version omission when declaring dependencies), but can be overridden by the module's own
 *     // enforcedPlatform or strictly-versioned dependencies.
 *     enforced = false
 * }
 * ```
 */
open class PulsarConnectorsDependenciesExtension {
    var enforced: Boolean = true

    internal val excludes: MutableList<DependencyExclusion> = mutableListOf()

    fun exclude(group: String, module: String) {
        excludes.add(DependencyExclusion(group, module))
    }
}

data class DependencyExclusion(val group: String, val module: String)

val pulsarConnectorsDependencies = extensions.create<PulsarConnectorsDependenciesExtension>("pulsarConnectorsDependencies")

// withDependencies runs lazily after subproject build scripts have configured the extension.
// This is configuration-cache compatible (unlike afterEvaluate).
configurations["implementation"].withDependencies {
    val platformProject = project(":pulsar-connectors-dependencies")
    val configureAction = Action<Dependency> {
        (this as ModuleDependency).apply {
            pulsarConnectorsDependencies.excludes.forEach { exc ->
                exclude(group = exc.group, module = exc.module)
            }
        }
    }
    val dep = if (pulsarConnectorsDependencies.enforced) {
        dependencies.enforcedPlatform(platformProject, configureAction)
    } else {
        dependencies.platform(platformProject, configureAction)
    }
    add(dep)
}

dependencies {

    // Resolve lz4-java capability conflict: at.yawk.lz4:lz4-java (used by Pulsar) and
    // org.lz4:lz4-java (used by kafka-clients) both provide the org.lz4:lz4-java capability.
    // Prefer at.yawk.lz4 which is the version Pulsar standardizes on.
    configurations.all {
        resolutionStrategy.capabilitiesResolution.withCapability("org.lz4:lz4-java") {
            select("at.yawk.lz4:lz4-java:0")
        }
    }

    // Annotation processing for Lombok
    "compileOnly"(lib("lombok"))
    "annotationProcessor"(lib("lombok"))
    "testCompileOnly"(lib("lombok"))
    "testAnnotationProcessor"(lib("lombok"))

    // Common test dependencies
    "testImplementation"(lib("testng"))
    "testImplementation"(lib("mockito-core"))
    "testImplementation"(lib("assertj-core"))
    "testImplementation"(lib("awaitility"))
    "testImplementation"(lib("system-lambda"))
    "testImplementation"(lib("slf4j-api"))

    // Logging runtime for tests — provides Log4j2 as the SLF4J backend.
    // Some connectors (Alluxio minicluster, Solr embedded) require a logging
    // implementation to be present at test runtime.
    "testRuntimeOnly"(lib("log4j-api"))
    "testRuntimeOnly"(lib("log4j-core"))
    "testRuntimeOnly"(lib("log4j-slf4j2-impl"))
    "testRuntimeOnly"(lib("jcl-over-slf4j"))
}

tasks.withType<Test>().configureEach {
    useTestNG {
        // TestNG group filtering: -PtestGroups=group1,group2 -PexcludedTestGroups=flaky
        providers.gradleProperty("testGroups").orNull?.let { groups ->
            includeGroups(*groups.split(",").map { it.trim() }.toTypedArray())
        }
        val excludedTestGroups = providers.gradleProperty("excludedTestGroups").getOrElse("quarantine,flaky")
        excludeGroups(*(excludedTestGroups.split(",").map { it.trim() }.toTypedArray()))
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showExceptions = true
        showCauses = true
        showStandardStreams = providers.gradleProperty("testShowOutput")
            .map { it.isBlank() || it.toBoolean() }.getOrElse(false)
    }
    maxHeapSize = "1300m"
    maxParallelForks = 4
    val failFastValue = providers.gradleProperty("testFailFast").getOrElse("true").toBoolean()
    failFast = failFastValue
    systemProperty("testRetryCount", providers.gradleProperty("testRetryCount").getOrElse("1"))
    systemProperty("testFailFast", failFastValue.toString())
    systemProperty("java.net.preferIPv4Stack", "true")
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

// Set archive names to match Maven artifactId for nested modules.
// Skip if the project name is already qualified (starts with parent name),
// which happens for sub-modules that use qualified names in settings.gradle.kts
// to avoid Gradle name clashes.
val parentProject = project.parent
if (parentProject != null && parentProject != rootProject && parentProject.parent != rootProject
        && !project.name.startsWith(parentProject.name)) {
    the<BasePluginExtension>().archivesName.set("${parentProject.name}-${project.name}")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

// Add a task for viewing all configurations for all projects in a simple way
tasks.register<DependencyReportTask>("allDependencies"){}
