package com.github.huchangjun118.schemamigrationplannerjava.plan;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaVersion;
import com.github.huchangjun118.schemamigrationplannerjava.planner.MigrationPlanner;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 计划服务：生成、查询变更计划。
 */
@Service
public class PlanService {

    private final MetadataStore store;
    private final MigrationPlanner planner;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public PlanService(MetadataStore store, MigrationPlanner planner,
                       PermissionService permissionService, AuditService auditService) {
        this.store = store;
        this.planner = planner;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /**
     * 生成从当前最新版本到目标结构的变更计划。
     */
    public MigrationPlan createPlan(String schemaName, SchemaDefinition target, String operator) {
        permissionService.require(operator, "PLAN_CREATE");
        SchemaVersion current = store.getLatestVersion(schemaName).orElse(null);
        SchemaDefinition currentSnapshot = current == null
                ? SchemaDefinition.empty(schemaName) : current.snapshot();
        long fromVersion = current == null ? 0L : current.versionId();

        String planId = UUID.randomUUID().toString();
        MigrationPlan plan = planner.plan(planId, currentSnapshot, target,
                fromVersion, 0L, PlanKind.FORWARD, operator);
        store.insertPlan(plan);
        auditService.record(planId, "PLAN_CREATE", operator,
                "生成变更计划 " + planId + "：v" + fromVersion + " -> 目标结构，"
                        + plan.steps().size() + " 步", null, null);
        return plan;
    }

    public MigrationPlan getPlan(String planId) {
        return store.getPlan(planId).orElse(null);
    }

    public List<MigrationPlan> listPlans(String schemaName) {
        return store.listPlans(schemaName);
    }
}
