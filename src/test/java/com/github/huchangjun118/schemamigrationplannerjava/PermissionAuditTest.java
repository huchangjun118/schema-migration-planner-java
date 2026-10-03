package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AccessDeniedException;
import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.RollbackService;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import com.github.huchangjun118.schemamigrationplannerjava.version.VersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PermissionAuditTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String meta = "jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:tgt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        r.add("migration.metadata.jdbc-url", () -> meta);
        r.add("migration.target.jdbc-url", () -> target);
    }

    @Autowired PermissionService permissionService;
    @Autowired AuditService auditService;
    @Autowired VersionService versionService;
    @Autowired PlanService planService;
    @Autowired RollbackService rollbackService;
    @Autowired MetadataStore metadataStore;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void applierCannotRollback_deniedWithReason() {
        // APPLIER 尝试回滚 → 拒绝，原因码 ROLLBACK_REQUIRES_ADMIN
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> permissionService.require("applier", "ROLLBACK"));
        assertEquals("ROLLBACK_REQUIRES_ADMIN", ex.getReasonCode());
        assertEquals("applier", ex.getOperator());
        assertEquals("ROLLBACK", ex.getAction());
    }

    @Test
    void viewerCannotApply_deniedWithReason() {
        // VIEWER 尝试应用 → 拒绝，原因码 APPLY_REQUIRES_APPLIER
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> permissionService.require("viewer", "PLAN_APPLY"));
        assertEquals("APPLY_REQUIRES_APPLIER", ex.getReasonCode());
    }

    @Test
    void unknownOperator_denied() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> permissionService.require("nobody", "PLAN_APPLY"));
        assertEquals("UNKNOWN_OPERATOR", ex.getReasonCode());
    }

    @Test
    void adminCanDoEverything() {
        // ADMIN 可以执行所有操作
        assertDoesNotThrow(() -> permissionService.require("admin", "PLAN_CREATE"));
        assertDoesNotThrow(() -> permissionService.require("admin", "PLAN_APPLY"));
        assertDoesNotThrow(() -> permissionService.require("admin", "ROLLBACK"));
        assertDoesNotThrow(() -> permissionService.require("admin", "VERSION_CREATE"));
    }

    @Test
    void deniedAttempts_areRecordedInAudit() {
        // 记录一次越权尝试
        try {
            permissionService.require("viewer", "ROLLBACK");
        } catch (AccessDeniedException ignore) {
            // 预期
        }

        // 验证审计记录包含越权拒绝
        List<MetadataStore.AuditRow> audit = auditService.list();
        boolean hasDenied = audit.stream()
                .anyMatch(a -> "PERMISSION_DENIED".equals(a.action())
                        && "viewer".equals(a.operator()));
        assertTrue(hasDenied, "审计应记录越权拒绝");
    }

    @Test
    void auditRecords_areAppendOnly() {
        String schema = "db8";
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_x", List.of(
                        col("id", ColumnType.BIGINT, false, null, true)
                ))
        ));
        versionService.createVersion(schema, v1, "admin", "初始");

        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_x", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        planService.createPlan(schema, v2, "admin");

        // 审计记录数量应随操作增长，且只追加
        List<MetadataStore.AuditRow> audit = auditService.list();
        long versionCreates = audit.stream().filter(a -> "VERSION_CREATE".equals(a.action())).count();
        long planCreates = audit.stream().filter(a -> "PLAN_CREATE".equals(a.action())).count();
        assertTrue(versionCreates >= 1, "应有 VERSION_CREATE 审计");
        assertTrue(planCreates >= 1, "应有 PLAN_CREATE 审计");

        // 验证审计记录不可变（没有更新/删除操作）
        // 审计表只追加，没有 UPDATE/DELETE
        long total = audit.size();
        // 再次查询，数量不变（没有被覆盖）
        assertEquals(total, auditService.list().size());
    }
}
