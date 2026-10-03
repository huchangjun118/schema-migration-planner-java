package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import com.github.huchangjun118.schemamigrationplannerjava.service.MigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rollback to any historical version is itself a plan under the same safety
 * rules. Dropped-column data is restored from the archive when available and
 * explicitly marked unrestorable otherwise. Version history is append-only.
 */
@SpringBootTest
class RollbackTest {
    @Autowired MigrationService migrationService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    private TableSchema v1(String table) {
        return new TableSchema(table, List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("legacy", ColumnType.VARCHAR, true, null, false)));
    }

    private TableSchema v2(String table) {
        return new TableSchema(table, List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false)));
    }

    private void createTable(String table) {
        jdbc.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        jdbc.execute("CREATE TABLE \"" + table + "\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100), \"legacy\" VARCHAR(100))");
        jdbc.update("INSERT INTO \"" + table + "\"(\"id\",\"name\",\"legacy\") VALUES (1,'a','L1'),(2,'b','L2')");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS \"rb_users\"");
        jdbc.execute("DROP TABLE IF EXISTS \"sm_arch_rb_users_legacy\"");
        jdbc.execute("DROP TABLE IF EXISTS \"rb_gone\"");
        jdbc.execute("DROP TABLE IF EXISTS \"sm_arch_rb_gone_legacy\"");
        jdbc.update("DELETE FROM sm_archive WHERE table_name IN ('rb_users','rb_gone')");
        jdbc.update("DELETE FROM sm_schema_version WHERE table_name IN ('rb_users','rb_gone')");
    }

    @Test
    void rollbackRestoresDroppedColumnDataFromArchive() {
        createTable("rb_users");
        MigrationPlan forward = migrationService.plan("alice", v1("rb_users"), v2("rb_users"));
        migrationService.apply("alice", forward.getPlanId());
        // column is gone after the forward migration
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='rb_users' AND COLUMN_NAME='legacy'",
                Integer.class);
        assertEquals(0, cols);

        MigrationPlan rollback = migrationService.rollback("alice", "rb_users", 1);
        assertTrue(rollback.isRollback());
        assertTrue(rollback.getSteps().stream().anyMatch(s ->
                s.getType() == StepType.BACKFILL && "RESTORE".equals(s.getArgs().get("mode"))));
        migrationService.apply("alice", rollback.getPlanId());

        // structure and data are back
        assertEquals("L1", jdbc.queryForObject(
                "SELECT \"legacy\" FROM \"rb_users\" WHERE \"id\"=1", String.class));
        assertEquals("L2", jdbc.queryForObject(
                "SELECT \"legacy\" FROM \"rb_users\" WHERE \"id\"=2", String.class));

        // append-only history: baseline + forward + rollback, nothing rewritten
        List<Map<String, Object>> history = migrationService.history("rb_users");
        assertEquals(3, history.size());
        assertEquals(1L, ((Number) history.get(0).get("VERSION")).longValue());
        assertEquals(2L, ((Number) history.get(1).get("VERSION")).longValue());
        assertEquals(3L, ((Number) history.get(2).get("VERSION")).longValue());
    }

    @Test
    void rollbackWithoutArchiveIsExplicitlyMarkedUnrestorable() {
        createTable("rb_gone");
        MigrationPlan forward = migrationService.plan("alice", v1("rb_gone"), v2("rb_gone"));
        migrationService.apply("alice", forward.getPlanId());

        // destroy the archive: data can no longer be restored
        jdbc.execute("DROP TABLE IF EXISTS \"sm_arch_rb_gone_legacy\"");
        jdbc.update("DELETE FROM sm_archive WHERE table_name='rb_gone'");

        MigrationPlan rollback = migrationService.rollback("alice", "rb_gone", 1);
        assertTrue(rollback.getSteps().stream().anyMatch(s ->
                s.getType() == StepType.BACKFILL && "UNRESTORABLE".equals(s.getArgs().get("mode"))),
                "rollback plan must explicitly mark the data as unrestorable");
        migrationService.apply("alice", rollback.getPlanId());

        // column exists again (structure rolled back) but data stays null and documented
        String legacy = jdbc.queryForObject("SELECT \"legacy\" FROM \"rb_gone\" WHERE \"id\"=1", String.class);
        assertNull(legacy);
    }
}
