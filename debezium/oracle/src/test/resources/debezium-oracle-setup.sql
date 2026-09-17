-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- Prepares an Oracle Database Free (gvenzl/oracle-free) container for
-- Debezium LogMiner-based CDC. Executed once as SYSDBA from
-- /container-entrypoint-initdb.d/ on first container startup.
-- Based on the canonical Debezium Oracle connector setup:
-- https://debezium.io/documentation/reference/stable/connectors/oracle.html

-- 1) Enable archive log mode (required by the LogMiner adapter).
ALTER SYSTEM SET db_recovery_file_dest_size = 5G;
ALTER SYSTEM SET db_recovery_file_dest = '/opt/oracle/oradata' SCOPE=spfile;
SHUTDOWN IMMEDIATE
STARTUP MOUNT
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER PLUGGABLE DATABASE ALL OPEN;

-- 2) Enable minimal supplemental logging at the CDB level.
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

-- 3) Tablespaces for the LogMiner user (CDB and PDB).
CREATE TABLESPACE LOGMINER_TBS DATAFILE '/opt/oracle/oradata/FREE/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

ALTER SESSION SET CONTAINER=FREEPDB1;

CREATE TABLESPACE LOGMINER_TBS DATAFILE '/opt/oracle/oradata/FREE/FREEPDB1/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

ALTER SESSION SET CONTAINER=CDB$ROOT;

-- 4) Common (CDB-wide) Debezium connector user with the LogMiner grant set.
CREATE USER c##dbzuser IDENTIFIED BY dbz
    DEFAULT TABLESPACE LOGMINER_TBS
    QUOTA UNLIMITED ON LOGMINER_TBS
    CONTAINER=ALL;

GRANT CREATE SESSION TO c##dbzuser CONTAINER=ALL;
GRANT SET CONTAINER TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$DATABASE TO c##dbzuser CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ANY DICTIONARY TO c##dbzuser CONTAINER=ALL;
GRANT LOGMINING TO c##dbzuser CONTAINER=ALL;
GRANT CREATE TABLE TO c##dbzuser CONTAINER=ALL;
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT ALTER ANY TABLE TO c##dbzuser CONTAINER=ALL;
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOG_HISTORY TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_LOGS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_CONTENTS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$LOGFILE TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$ARCHIVED_LOG TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$TRANSACTION TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$MYSTAT TO c##dbzuser CONTAINER=ALL;
GRANT SELECT ON V_$STATNAME TO c##dbzuser CONTAINER=ALL;

-- 5) Test schema: a table with two seeded rows in the FREEPDB1 PDB.
ALTER SESSION SET CONTAINER=FREEPDB1;

CREATE USER debezium IDENTIFIED BY dbz
    DEFAULT TABLESPACE LOGMINER_TBS
    QUOTA UNLIMITED ON LOGMINER_TBS;

GRANT CONNECT TO debezium;
GRANT CREATE SESSION TO debezium;
GRANT CREATE TABLE TO debezium;
GRANT CREATE SEQUENCE TO debezium;

CREATE TABLE debezium.products (
    id NUMBER(9,0) NOT NULL PRIMARY KEY,
    name VARCHAR2(255) NOT NULL,
    description VARCHAR2(512)
);

INSERT INTO debezium.products VALUES (1, 'widget', 'A small widget');
INSERT INTO debezium.products VALUES (2, 'gadget', 'A fancy gadget');
COMMIT;

ALTER TABLE debezium.products ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;
