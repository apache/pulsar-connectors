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
dependencies {
    implementation(project(":pulsar-io:pulsar-io-common"))
    implementation(project(":pulsar-io:pulsar-io-core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation("com.alibaba:fastjson:1.2.83")
    implementation(libs.spring.core)
    implementation(libs.spring.aop)
    implementation(libs.spring.context)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.orm)
    implementation("com.alibaba.otter:canal.protocol:1.1.7")
    implementation("com.alibaba.otter:canal.client:1.1.7")
    implementation(libs.log4j.core)
}
