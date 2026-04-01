<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Apache Pulsar Connectors

This repository contains the IO connectors for [Apache Pulsar](https://pulsar.apache.org/).

Connectors are packaged as [NAR](https://pulsar.apache.org/docs/next/io-develop/#nar) files and
can be deployed into any Pulsar installation by placing them in the connectors directory or
mounting them into the `apachepulsar/pulsar` Docker image.

## Available Connectors

### Sources
| Connector | Description |
|-----------|-------------|
| Canal | MySQL binlog via Alibaba Canal |
| Debezium (MySQL, PostgreSQL, MongoDB, MSSQL, Oracle) | CDC via Debezium |
| DynamoDB | Amazon DynamoDB Streams |
| File | Local filesystem |
| Kafka | Apache Kafka |
| Kinesis | Amazon Kinesis Data Streams |
| MongoDB | MongoDB change streams |
| NSQ | NSQ messaging |
| RabbitMQ | RabbitMQ / AMQP |

### Sinks
| Connector | Description |
|-----------|-------------|
| Aerospike | Aerospike database |
| Alluxio | Alluxio distributed storage |
| Azure Data Explorer | Azure Data Explorer (Kusto) |
| Cassandra | Apache Cassandra |
| Elasticsearch / OpenSearch | Elasticsearch and OpenSearch |
| HBase | Apache HBase |
| HDFS3 | Hadoop HDFS |
| HTTP | HTTP endpoint |
| InfluxDB | InfluxDB time-series database |
| JDBC (PostgreSQL, MariaDB, ClickHouse, SQLite, OpenMLDB) | JDBC databases |
| Kafka | Apache Kafka |
| Kinesis | Amazon Kinesis Data Streams |
| MongoDB | MongoDB |
| Redis | Redis |
| Solr | Apache Solr |

### Adaptor
| Connector | Description |
|-----------|-------------|
| Kafka Connect Adaptor | Run Kafka Connect connectors on Pulsar |

## Building

```bash
./gradlew build -x test
```

To build all connector NARs:

```bash
./gradlew build -x test
```

NAR files are produced under each connector's `build/libs/` directory.

To build the distribution tarball containing all connector NARs:

```bash
./gradlew :distribution:pulsar-io-distribution:assemble
```

## Running Tests

```bash
# All unit tests
./gradlew test

# Specific connector
./gradlew :elastic-search:test
```

## Using Connectors

### With Docker

Mount connector NARs into the Pulsar container:

```bash
docker run -v /path/to/connectors:/pulsar/connectors apachepulsar/pulsar
```

### Manual Installation

Copy NAR files to the `connectors/` directory of your Pulsar installation:

```bash
cp elastic-search/build/libs/pulsar-io-elastic-search-*.nar $PULSAR_HOME/connectors/
```

## Versioning

This repository follows its own release cadence, independent from Apache Pulsar releases.
All connectors are released together as a single release. The initial release version
matches the Pulsar version at the time of the split.

Each release specifies which Pulsar versions it is compatible with. The `pulsar-io-core`
API has been stable for years, so connectors are generally compatible across Pulsar 4.x
releases.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](https://github.com/apache/pulsar/blob/master/CONTRIBUTING.md)
in the main Pulsar repository for guidelines.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
