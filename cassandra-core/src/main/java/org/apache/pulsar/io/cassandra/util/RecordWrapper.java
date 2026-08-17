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
package org.apache.pulsar.io.cassandra.util;

import com.datastax.driver.core.DataType;
import java.util.EnumSet;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.commons.beanutils.converters.IntegerConverter;
import org.apache.commons.beanutils.converters.NumberConverter;

public abstract class RecordWrapper<T> {

    T recordValue;
    NumberConverter converter = new IntegerConverter();

    public RecordWrapper(T value) {
        this.recordValue = value;
    }

    public abstract Object get(TableMetadataProvider.ColumnId column);

    public abstract boolean containsKey(String name);

    /**
     * Column types this wrapper can reliably bind a JSON or Avro value to. The set is deliberately
     * narrow. Types such as {@code timestamp}, {@code uuid}, {@code inet}, {@code decimal} and
     * {@code varint} need driver-specific Java objects ({@code Date}, {@code UUID},
     * {@code InetAddress}, {@code BigDecimal}, {@code BigInteger}) that neither Jackson nor Avro ever
     * produces, and {@code bigint}/{@code smallint}/{@code tinyint} are worse than unsupported — they
     * work or fail depending on the magnitude of the value, since Jackson decodes a small number as an
     * {@code Integer}. Rejecting the table at {@code open()} beats an {@code InvalidTypeException} on
     * every record, or on some records.
     */
    private static final EnumSet<DataType.Name> SUPPORTED_COLUMN_TYPES = EnumSet.of(
            DataType.Name.TEXT,
            DataType.Name.VARCHAR,
            DataType.Name.ASCII,
            DataType.Name.INT,
            DataType.Name.DOUBLE,
            DataType.Name.FLOAT,
            DataType.Name.BOOLEAN);

    public static boolean supports(DataType type) {
        return SUPPORTED_COLUMN_TYPES.contains(type.getName());
    }

    public static String supportedColumnTypes() {
        return SUPPORTED_COLUMN_TYPES.stream()
                .map(name -> name.toString().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }

    Object getValueAsExpectedType(Object value, TableMetadataProvider.ColumnId column) {
        // A field that is present but null binds as null. Every branch below would otherwise fail on
        // it: TEXT calls toString(), and the converters reject a null with no default configured.
        if (value == null) {
            return null;
        }
        switch (column.getType().getName()) {
            case FLOAT: return converter.convert(Float.class, value);
            case INT: return converter.convert(Integer.class, value);
            case DOUBLE: return converter.convert(Double.class, value);
            case TEXT:
            case VARCHAR:
            case ASCII: return value.toString();
            default: return value;
        }

    }

}
