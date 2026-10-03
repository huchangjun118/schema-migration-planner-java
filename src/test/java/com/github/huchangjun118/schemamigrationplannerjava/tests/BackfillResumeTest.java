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
 * Backfill runs in configurable batches, checkpoints progress, and resumes
 * from the last committed batch after a crash without re-filling rows.
 */
@SpringBootTest
class BackfillResumeTest {
    @Autowired MigrationService migrationService;
    @Autowired BackfillService backfillService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS \"bf_users\"");
        jdbc.execute("CREATE TABLE \"bf_users\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100), \"age\" INT)");
        for (int i = 1; i <= 10; i++) {
            jdbc.update("INSERT INTO \"bf_users\"(\"id\",\"name\",\"age\") VALUES (?,?,?)", i, "u" + i, 20 + i);
        }
        backfillService.setBatchHook(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void backfillResumesFromLastCommittedBatch() {
        TableSchema current = new TableSchema("bf_users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("age", ColumnType.INT, true, null, false)));
        // INT -> VARCHAR goes through a shadow column with a COPY backfill of all rows
        TableSchema target = new TableSchema("bf_users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("age", ColumnType.VARCHAR, true, null, false)));
        MigrationPlan plan = migrationService.plan("alice", current, target);
        String migrationId = plan.getPlanId();

        // crash after the second committed batch (batch size is 3 in test config)
        AtomicInteger batches = new AtomicInteger();
        backfillService.setBatchHook(n -> {
            if (batches.incrementAndGet() >= 2) {
                throw new RuntimeException("simulated crash");
            }
        });
        assertThrows(RuntimeException.class, () -> migrationService.apply("alice", migrationId));
        backfillService.setBatchHook(null);

        // progress is queryable mid-flight and reflects exactly the committed batches
        Map<String, Object> mid = migrationService.status(migrationId);
        Map<String, Object> progress = (Map<String, Object>) ((List<Map<String, Object>>) mid.get("steps"))
                .stream().filter(s -> "BACKFILL".equals(s.get("type"))).findFirst().get().get("progress");
        long doneRows = ((Number) progress.get("doneRows")).longValue();
        assertTrue(doneRows > 0 && doneRows < 10, "partial progress expected, got " + doneRows);

        // resume: completes, and previously backfilled rows are not touched again
        Map<String, Object> done = migrationService.apply("alice", migrationId);
        assertEquals("DONE", done.get("status"));
        Integer filled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"bf_users\" WHERE \"age\" = CAST(\"id\" AS VARCHAR)", Integer.class);
        assertEquals(0, filled); // age holds 20+i, not i
        Integer copied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"bf_users\" WHERE \"age\" = CAST(20 + \"id\" AS VARCHAR)", Integer.class);
        assertEquals(10, copied);

        Map<String, Object> end = migrationService.status(migrationId);
        Map<String, Object> endProgress = (Map<String, Object>) ((List<Map<String, Object>>) end.get("steps"))
                .stream().filter(s -> "BACKFILL".equals(s.get("type"))).findFirst().get().get("progress");
        // total rows updated across both runs equals the row count: no double-fill
        assertEquals(10L, ((Number) endProgress.get("doneRows")).longValue());
    }
}
