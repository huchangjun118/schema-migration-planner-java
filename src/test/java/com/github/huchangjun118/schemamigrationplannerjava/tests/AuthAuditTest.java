package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.auth.AccessDeniedException;
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
 * Authorization denies carry distinguishable reasons; every apply/rollback is
 * audited with actor, time and before/after schema; audit is append-only.
 */
@SpringBootTest
class AuthAuditTest {
    @Autowired MigrationService migrationService;
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    private TableSchema current() {
        return new TableSchema("au_t", List.of(col("id", ColumnType.BIGINT, false, null, true)));
    }

    private TableSchema target() {
        return new TableSchema("au_t", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("flag", ColumnType.INT, true, "0", false)));
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS \"au_t\"");
        jdbc.execute("CREATE TABLE \"au_t\" (\"id\" BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO \"au_t\"(\"id\") VALUES (1)");
        jdbc.update("DELETE FROM sm_schema_version WHERE table_name='au_t'");
    }

    @Test
    void unauthorizedRequestsAreRejectedWithDistinguishableReasons() {
        // unknown user
        AccessDeniedException e1 = assertThrows(AccessDeniedException.class,
                () -> migrationService.plan("nobody", current(), target()));
        assertEquals(AccessDeniedException.Reason.UNKNOWN_USER, e1.getReason());

        // carol may plan but not apply
        MigrationPlan plan = migrationService.plan("carol", current(), target());
        AccessDeniedException e2 = assertThrows(AccessDeniedException.class,
                () -> migrationService.apply("carol", plan.getPlanId()));
        assertEquals(AccessDeniedException.Reason.MISSING_ROLE, e2.getReason());
        assertEquals("carol", e2.getActor());

        // bob may apply but not roll back
        migrationService.apply("bob", plan.getPlanId());
        AccessDeniedException e3 = assertThrows(AccessDeniedException.class,
                () -> migrationService.rollback("bob", "au_t", 1));
        assertEquals(AccessDeniedException.Reason.MISSING_ROLE, e3.getReason());

        // admin may roll back
        MigrationPlan rb = migrationService.rollback("alice", "au_t", 1);
        migrationService.apply("alice", rb.getPlanId());
    }

    @Test
    void auditTrailIsAppendOnlyAndComplete() {
        int before = auditService.list().size();

        MigrationPlan plan = migrationService.plan("carol", current(), target());
        migrationService.apply("bob", plan.getPlanId());
        MigrationPlan rb = migrationService.rollback("alice", "au_t", 1);
        migrationService.apply("alice", rb.getPlanId());

        List<Map<String, Object>> audit = auditService.list();
        assertEquals(before + 4, audit.size(), "every operation appends exactly one record");

        List<Map<String, Object>> ours = audit.subList(before, audit.size());
        // ids are strictly increasing: nothing is ever overwritten
        for (int i = 1; i < ours.size(); i++) {
            assertTrue(((Number) ours.get(i).get("ID")).longValue()
                    > ((Number) ours.get(i - 1).get("ID")).longValue());
        }
        Map<String, Object> applyRec = ours.get(1);
        assertEquals("bob", applyRec.get("ACTOR"));
        assertEquals("APPLY", applyRec.get("ACTION"));
        assertEquals("au_t", applyRec.get("TABLE_NAME"));
        assertNotNull(applyRec.get("CREATED_AT"));
        assertNotNull(applyRec.get("BEFORE_SCHEMA"));
        assertNotNull(applyRec.get("AFTER_SCHEMA"));

        Map<String, Object> rollbackRec = ours.get(3);
        assertEquals("alice", rollbackRec.get("ACTOR"));
        assertEquals("ROLLBACK", rollbackRec.get("ACTION"));
    }
}
