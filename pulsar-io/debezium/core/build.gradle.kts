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


dependencies {
    compileOnly(project(":pulsar-io:pulsar-io-core"))
    api(libs.debezium.core)
    implementation(libs.guava)
    implementation(libs.commons.lang3)
    api(project(":pulsar-common"))
    api(project(":pulsar-io:pulsar-io-kafka-connect-adaptor"))
    api(libs.kafka.connect.runtime) {
        exclude(group = "org.apache.kafka", module = "kafka-log4j-appender")
        exclude(group = "org.bitbucket.b_c", module = "jose4j")
        exclude(group = "org.slf4")
        exclude(group = "org.eclipse.jetty")
        exclude(group = "org.eclipse.jetty.ee10")
        exclude(group = "com.fasterxml.jackson.jakarta.rs")
        exclude(group = "org.glassfish.jersey.containers")
        exclude(group = "org.glassfish.jersey.inject")
        exclude(group = "javax.xml.bind", module = "jaxb-api")
        exclude(group = "javax.activation", module = "activation")
        exclude(group = "org.lz4", module = "lz4-java")
    }

    testImplementation(project(":pulsar-broker"))
    testImplementation(project(path = ":pulsar-broker", configuration = "testJar"))
    testImplementation(project(":testmocks"))
    testImplementation(project(":pulsar-client-original"))
    testImplementation(libs.debezium.connector.mysql)
}
