package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.backfill.BackfillService;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.service.MigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that at every intermediate state of an expand/backfill/contract
 * migration, both old-version and new-version code can keep reading and writing.
 */
@SpringBootTest
class ExpandContractCompatibilityTest {
    @Autowired MigrationService migrationService;
    @Autowired BackfillService backfillService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS \"compat_users\"");
        jdbc.execute("DROP TABLE IF EXISTS \"sm_arch_compat_users_age\"");
        jdbc.execute("CREATE TABLE \"compat_users\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100), \"age\" INT)");
        jdbc.update("INSERT INTO \"compat_users\"(\"id\",\"name\",\"age\") VALUES (1,'ann',30),(2,'bob',40),(3,'cat',50)");
        backfillService.setBatchHook(null);
    }

    private TableSchema current() {
        return new TableSchema("compat_users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("age", ColumnType.INT, true, null, false)));
    }

    private TableSchema target() {
        return new TableSchema("compat_users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("age", ColumnType.VARCHAR, true, null, false),           // type change via shadow
                col("email", ColumnType.VARCHAR, false, "n/a", false)));   // new NOT NULL w/ default
    }

    @Test
    void oldAndNewCodeWorkAtEveryIntermediateState() {
        MigrationPlan plan = migrationService.plan("alice", current(), target());
        String migrationId = plan.getPlanId();

        // stop the migration right after the first backfill batch -> mid-flight state
        AtomicInteger batches = new AtomicInteger();
        backfillService.setBatchHook(n -> {
            if (batches.incrementAndGet() >= 1) {
                throw new RuntimeException("simulated crash mid-backfill");
            }
        });
        assertThrows(RuntimeException.class, () -> migrationService.apply("alice", migrationId));
        backfillService.setBatchHook(null);

        Map<String, Object> mid = migrationService.status(migrationId);
        assertEquals("FAILED", mid.get("status"));

        // --- old code: knows nothing about email / age__new ---
        jdbc.update("INSERT INTO \"compat_users\"(\"id\",\"name\",\"age\") VALUES (4,'dan',60)"); // must not fail
        Integer age = jdbc.queryForObject("SELECT \"age\" FROM \"compat_users\" WHERE \"id\"=2", Integer.class);
        assertEquals(40, age);

        // --- new code: dual-writes old and shadow column, tolerates null email ---
        jdbc.update("INSERT INTO \"compat_users\"(\"id\",\"name\",\"age\",\"age__new\",\"email\") VALUES (5,'eve',70,'70','e@x')");
        // read semantics during backfill: coalesce(new, old)
        String readBack = jdbc.queryForObject(
                "SELECT COALESCE(\"age__new\", CAST(\"age\" AS VARCHAR)) FROM \"compat_users\" WHERE \"id\"=1", String.class);
        assertEquals("30", readBack);

        // resume: must complete without re-doing committed steps
        Map<String, Object> done = migrationService.apply("alice", migrationId);
        assertEquals("DONE", done.get("status"));

        // --- final state matches target ---
        String email = jdbc.queryForObject("SELECT \"email\" FROM \"compat_users\" WHERE \"id\"=1", String.class);
        assertEquals("n/a", email); // backfilled default
        String ageNow = jdbc.queryForObject("SELECT \"age\" FROM \"compat_users\" WHERE \"id\"=3", String.class);
        assertEquals("50", ageNow); // copied through shadow column
        // email is now NOT NULL
        assertThrows(Exception.class,
                () -> jdbc.update("INSERT INTO \"compat_users\"(\"id\",\"name\",\"age\",\"email\") VALUES (9,'x','1',NULL)"));
        // old shadow column is gone after contract
        Integer shadow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='compat_users' AND COLUMN_NAME='age__new'",
                Integer.class);
        assertEquals(0, shadow);
        // dropped old column data was archived for potential rollback
        Integer archived = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='sm_arch_compat_users_age'",
                Integer.class);
        assertEquals(1, archived);
    }
}
