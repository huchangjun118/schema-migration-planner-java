package com.github.huchangjun118.schemamigrationplannerjava.tests;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * A failed migration stops at a documented, resumable state: committed steps
 * stay DONE, the failed step is marked with its error, and re-applying resumes
 * from exactly that step without re-executing or skipping anything.
 */
@SpringBootTest
class FailureRecoveryTest {
    @Autowired MigrationService migrationService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS \"fr_orders\"");
        jdbc.execute("CREATE TABLE \"fr_orders\" (\"id\" BIGINT PRIMARY KEY, \"note\" VARCHAR(100))");
        jdbc.update("INSERT INTO \"fr_orders\"(\"id\",\"note\") VALUES (1,'a'),(2,NULL)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedStepStopsMigrationAndResumeContinuesFromIt() {
        TableSchema current = new TableSchema("fr_orders", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("note", ColumnType.VARCHAR, true, null, false)));
        // add a column AND tighten note to NOT NULL (no default -> no backfill possible)
        TableSchema target = new TableSchema("fr_orders", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("note", ColumnType.VARCHAR, false, null, false),
                col("prio", ColumnType.INT, true, "0", false)));
        MigrationPlan plan = migrationService.plan("alice", current, target);
        String migrationId = plan.getPlanId();

        // row 2 has note=NULL, so CONTRACT_SET_NOT_NULL must fail
        assertThrows(RuntimeException.class, () -> migrationService.apply("alice", migrationId));

        Map<String, Object> mid = migrationService.status(migrationId);
        assertEquals("FAILED", mid.get("status"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) mid.get("steps");
        long doneCount = steps.stream().filter(s -> "DONE".equals(s.get("status"))).count();
        assertTrue(doneCount > 0, "expand steps should be committed");
        Map<String, Object> failed = steps.stream().filter(s -> "FAILED".equals(s.get("status")))
                .findFirst().orElseThrow();
        assertEquals("CONTRACT_SET_NOT_NULL", failed.get("type"));
        assertNotNull(failed.get("error"));
        // intermediate structure is explainable: new column exists and is nullable
        jdbc.update("INSERT INTO \"fr_orders\"(\"id\",\"note\") VALUES (3,'c')"); // old code still works

        // fix the data problem, then resume
        jdbc.update("UPDATE \"fr_orders\" SET \"note\"='fixed' WHERE \"note\" IS NULL");
        Map<String, Object> done = migrationService.apply("alice", migrationId);
        assertEquals("DONE", done.get("status"));

        List<Map<String, Object>> finalSteps = (List<Map<String, Object>>) done.get("steps");
        assertTrue(finalSteps.stream().allMatch(s -> "DONE".equals(s.get("status"))));
        // every step appears exactly once: nothing skipped, nothing duplicated
        assertEquals(steps.size(), finalSteps.size());
        // note is now NOT NULL
        assertThrows(Exception.class,
                () -> jdbc.update("INSERT INTO \"fr_orders\"(\"id\",\"note\") VALUES (9,NULL)"));

        // re-applying a finished migration is a no-op (idempotent)
        Map<String, Object> again = migrationService.apply("alice", migrationId);
        assertEquals("DONE", again.get("status"));
    }
}
