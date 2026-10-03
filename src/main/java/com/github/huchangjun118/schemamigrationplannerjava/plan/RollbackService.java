package com.github.huchangjun118.schemamigrationplannerjava.plan;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaVersion;
import com.github.huchangjun118.schemamigrationplannerjava.planner.MigrationPlanner;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 回滚服务：生成回滚计划（同样以计划形式给出，受同一套安全约束）。
 *
 * <p>回滚到任意历史版本。回滚计划中含数据丢失步骤时状态为
 * AWAITING_CONFIRMATION，需 ADMIN 操作者显式确认后才能执行。
 * 版本记录只追加，回滚后生成新的版本快照（历史版本不可改写）。
 */
@Service
public class RollbackService {

    private final MetadataStore store;
    private final MigrationPlanner planner;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public RollbackService(MetadataStore store, MigrationPlanner planner,
                           PermissionService permissionService, AuditService auditService) {
        this.store = store;
        this.planner = planner;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /**
     * 生成回滚到指定历史版本的计划。
     */
    public MigrationPlan rollbackTo(String schemaName, long targetVersionId, String operator) {
        permissionService.require(operator, "ROLLBACK");

        SchemaVersion current = store.getLatestVersion(schemaName)
                .orElseThrow(() -> new IllegalStateException("结构 " + schemaName + " 暂无版本记录"));
        SchemaVersion target = store.getVersion(targetVersionId)
                .orElseThrow(() -> new IllegalArgumentException("目标版本不存在: " + targetVersionId));

        if (target.snapshot().name() != null && !target.snapshot().name().equals(schemaName)) {
            throw new IllegalArgumentException("版本 " + targetVersionId + " 不属于结构 " + schemaName);
        }

        String planId = UUID.randomUUID().toString();
        MigrationPlan plan = planner.plan(planId, current.snapshot(), target.snapshot(),
                current.versionId(), targetVersionId, PlanKind.ROLLBACK, operator);
        store.insertPlan(plan);

        String detail = "生成回滚计划 " + planId + "：v" + current.versionId()
                + " -> v" + targetVersionId + "，" + plan.steps().size() + " 步";
        if (plan.status() == PlanStatus.AWAITING_CONFIRMATION) {
            detail += "（含数据丢失步骤，需确认后执行）";
        }
        auditService.record(planId, "ROLLBACK", operator, detail, null, null);
        return plan;
    }
}
