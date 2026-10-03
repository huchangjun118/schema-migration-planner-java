package com.github.huchangjun118.schemamigrationplannerjava.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Embedded metastore (same local H2 instance, tables prefixed {@code sm_}) holding
 * migrations, steps, checkpoints, leases, schema versions, archives and audit
 * records. Version and audit tables are append-only: no update/delete is issued.
 */
@Repository
public class MetaStore {
    private final JdbcTemplate jdbc;

    public MetaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_migration (
                  migration_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  table_name VARCHAR(128) NOT NULL,
                  plan_json CLOB NOT NULL,
                  rollback BOOLEAN NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  actor VARCHAR(128) NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_step (
                  migration_id VARCHAR(128) NOT NULL,
                  ordinal INT NOT NULL,
                  step_key VARCHAR(512) NOT NULL,
                  type VARCHAR(64) NOT NULL,
                  description VARCHAR(1024) NOT NULL,
                  args_json CLOB NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  error CLOB,
                  updated_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (migration_id, ordinal)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_checkpoint (
                  migration_id VARCHAR(128) NOT NULL,
                  step_key VARCHAR(512) NOT NULL,
                  last_pk BIGINT NOT NULL,
                  done_rows BIGINT NOT NULL,
                  total_rows BIGINT NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (migration_id, step_key)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_lease (
                  table_name VARCHAR(128) PRIMARY KEY,
                  owner VARCHAR(128) NOT NULL,
                  fencing_token BIGINT NOT NULL,
                  expires_at BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_fencing (
                  id INT PRIMARY KEY,
                  next_token BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_schema_version (
                  table_name VARCHAR(128) NOT NULL,
                  version BIGINT NOT NULL,
                  schema_json CLOB NOT NULL,
                  migration_id VARCHAR(128) NOT NULL,
                  actor VARCHAR(128) NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (table_name, version)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_audit (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  actor VARCHAR(128) NOT NULL,
                  action VARCHAR(64) NOT NULL,
                  table_name VARCHAR(128),
                  migration_id VARCHAR(128),
                  before_schema CLOB,
                  after_schema CLOB,
                  detail CLOB,
                  created_at TIMESTAMP NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sm_archive (
                  table_name VARCHAR(128) NOT NULL,
                  column_name VARCHAR(128) NOT NULL,
                  archive_table VARCHAR(256) NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  PRIMARY KEY (table_name, column_name)
                )""");
        jdbc.update("MERGE INTO sm_fencing KEY(id) VALUES (1, 1)");
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    // ---- migrations ----
    public void insertMigration(String migrationId, String planId, String tableName, String planJson,
                                boolean rollback, String actor) {
        jdbc.update("INSERT INTO sm_migration VALUES (?,?,?,?,?,?,?,?,?)",
                migrationId, planId, tableName, planJson, rollback, "PLANNED", actor, now(), now());
    }

    public Optional<Map<String, Object>> findMigration(String migrationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sm_migration WHERE migration_id=?", migrationId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> listMigrations() {
        return jdbc.queryForList("SELECT * FROM sm_migration ORDER BY created_at");
    }

    public void updateMigrationStatus(String migrationId, String status) {
        jdbc.update("UPDATE sm_migration SET status=?, updated_at=? WHERE migration_id=?",
                status, now(), migrationId);
    }

    // ---- steps ----
    public void insertStep(String migrationId, int ordinal, String stepKey, String type, String description,
                           String argsJson) {
        jdbc.update("INSERT INTO sm_step VALUES (?,?,?,?,?,?,?,?,?)",
                migrationId, ordinal, stepKey, type, description, argsJson, "PENDING", null, now());
    }

    public List<Map<String, Object>> listSteps(String migrationId) {
        return jdbc.queryForList("SELECT * FROM sm_step WHERE migration_id=? ORDER BY ordinal", migrationId);
    }

    public void markStepRunning(String migrationId, int ordinal) {
        jdbc.update("UPDATE sm_step SET status='RUNNING', updated_at=? WHERE migration_id=? AND ordinal=?",
                now(), migrationId, ordinal);
    }

    public void markStepDone(String migrationId, int ordinal) {
        jdbc.update("UPDATE sm_step SET status='DONE', error=NULL, updated_at=? WHERE migration_id=? AND ordinal=?",
                now(), migrationId, ordinal);
    }

    public void markStepFailed(String migrationId, int ordinal, String error) {
        jdbc.update("UPDATE sm_step SET status='FAILED', error=?, updated_at=? WHERE migration_id=? AND ordinal=?",
                error, now(), migrationId, ordinal);
    }

    // ---- backfill checkpoints ----
    public void saveCheckpoint(String migrationId, String stepKey, long lastPk, long doneRows, long totalRows) {
        jdbc.update("""
                MERGE INTO sm_checkpoint KEY(migration_id, step_key)
                VALUES (?,?,?,?,?,?)""", migrationId, stepKey, lastPk, doneRows, totalRows, now());
    }

    public Optional<Map<String, Object>> loadCheckpoint(String migrationId, String stepKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sm_checkpoint WHERE migration_id=? AND step_key=?", migrationId, stepKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---- leases ----
    public Optional<Map<String, Object>> findLease(String tableName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sm_lease WHERE table_name=?", tableName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean tryInsertLease(String tableName, String owner, long fencingToken, long expiresAtMillis) {
        try {
            return jdbc.update("INSERT INTO sm_lease VALUES (?,?,?,?)",
                    tableName, owner, fencingToken, expiresAtMillis) == 1;
        } catch (Exception duplicate) {
            return false;
        }
    }

    public boolean renewLease(String tableName, String owner, long fencingToken, long expiresAtMillis) {
        return jdbc.update("UPDATE sm_lease SET expires_at=? WHERE table_name=? AND owner=? AND fencing_token=?",
                expiresAtMillis, tableName, owner, fencingToken) == 1;
    }

    public boolean takeoverExpiredLease(String tableName, String newOwner, long newToken, long expiresAtMillis,
                                        long nowMillis) {
        return jdbc.update("""
                UPDATE sm_lease SET owner=?, fencing_token=?, expires_at=?
                WHERE table_name=? AND expires_at < ?""",
                newOwner, newToken, expiresAtMillis, tableName, nowMillis) == 1;
    }

    public boolean releaseLease(String tableName, String owner, long fencingToken) {
        return jdbc.update("DELETE FROM sm_lease WHERE table_name=? AND owner=? AND fencing_token=?",
                tableName, owner, fencingToken) == 1;
    }

    public synchronized long nextFencingToken() {
        Long next = jdbc.queryForObject("SELECT next_token FROM sm_fencing WHERE id=1", Long.class);
        jdbc.update("UPDATE sm_fencing SET next_token=? WHERE id=1", next + 1);
        return next;
    }

    // ---- schema versions (append-only) ----
    public void appendSchemaVersion(String tableName, long version, String schemaJson, String migrationId,
                                    String actor) {
        jdbc.update("INSERT INTO sm_schema_version VALUES (?,?,?,?,?,?)",
                tableName, version, schemaJson, migrationId, actor, now());
    }

    public List<Map<String, Object>> listSchemaVersions(String tableName) {
        return jdbc.queryForList("SELECT * FROM sm_schema_version WHERE table_name=? ORDER BY version", tableName);
    }

    public Optional<Map<String, Object>> latestSchemaVersion(String tableName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sm_schema_version WHERE table_name=? ORDER BY version DESC LIMIT 1", tableName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---- audit (append-only) ----
    public void appendAudit(String actor, String action, String tableName, String migrationId,
                            String beforeSchema, String afterSchema, String detail) {
        jdbc.update("INSERT INTO sm_audit(actor,action,table_name,migration_id,before_schema,after_schema,detail,created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                actor, action, tableName, migrationId, beforeSchema, afterSchema, detail, now());
    }

    public List<Map<String, Object>> listAudit() {
        return jdbc.queryForList("SELECT * FROM sm_audit ORDER BY id");
    }

    // ---- dropped-column archives to make rollback restorable ----
    public void recordArchive(String tableName, String columnName, String archiveTable) {
        jdbc.update("MERGE INTO sm_archive KEY(table_name, column_name) VALUES (?,?,?,?)",
                tableName, columnName, archiveTable, now());
    }

    public Optional<Map<String, Object>> findArchive(String tableName, String columnName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sm_archive WHERE table_name=? AND column_name=?", tableName, columnName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
