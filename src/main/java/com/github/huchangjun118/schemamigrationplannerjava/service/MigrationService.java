package com.github.huchangjun118.schemamigrationplannerjava.service;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.auth.AuthService;
import com.github.huchangjun118.schemamigrationplannerjava.auth.Role;
import com.github.huchangjun118.schemamigrationplannerjava.backfill.BackfillService;
import com.github.huchangjun118.schemamigrationplannerjava.exec.MigrationExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanGenerator;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import com.github.huchangjun118.schemamigrationplannerjava.store.SchemaCodec;
import com.github.huchangjun118.schemamigrationplannerjava.version.SchemaVersionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Facade orchestrating plan -> authorize -> execute -> version -> audit,
 * plus rollback to any historical version under the same safety rules.
 */
@Service
public class MigrationService {
    private final PlanGenerator planGenerator;
    private final MigrationExecutor executor;
    private final MetaStore metaStore;
    private final AuthService authService;
    private final AuditService auditService;
    private final SchemaVersionService versionService;
    private final SchemaCodec codec;
    private final BackfillService backfillService;

    public MigrationService(PlanGenerator planGenerator, MigrationExecutor executor, MetaStore metaStore,
                            AuthService authService, AuditService auditService,
                            SchemaVersionService versionService, SchemaCodec codec,
                            BackfillService backfillService) {
        this.planGenerator = planGenerator;
        this.executor = executor;
        this.metaStore = metaStore;
        this.authService = authService;
        this.auditService = auditService;
        this.versionService = versionService;
        this.codec = codec;
        this.backfillService = backfillService;
    }

    /** Generate (and persist) a migration plan from current to target schema. */
    public MigrationPlan plan(String actor, TableSchema current, TableSchema target) {
        authService.require(actor, Role.PLAN);
        MigrationPlan plan = planGenerator.generate(current, target);
        // record the baseline so the very first version is rollback-able later
        if (versionService.latest(current.getTableName()).isEmpty()) {
            versionService.recordVersion(current.getTableName(), current, "baseline", actor);
        }
        String migrationId = persistPlan(plan, actor);
        auditService.record(actor, "PLAN", plan.getTableName(), migrationId,
                codec.schemaToJson(current), codec.schemaToJson(target),
                "planned " + plan.getSteps().size() + " steps");
        return withMigrationId(plan, migrationId);
    }

    /** Apply a planned migration; resumable and idempotent. */
    public Map<String, Object> apply(String actor, String migrationId) {
        authService.require(actor, Role.APPLY);
        Map<String, Object> record = metaStore.findMigration(migrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown migration: " + migrationId));
        MigrationPlan plan = codec.planFromJson((String) record.get("PLAN_JSON"));
        boolean rollback = Boolean.TRUE.equals(record.get("ROLLBACK"));
        String action = rollback ? "ROLLBACK" : "APPLY";
        try {
            executor.execute(migrationId, actor);
        } catch (RuntimeException e) {
            auditService.record(actor, action + "_FAILED", plan.getTableName(), migrationId,
                    codec.schemaToJson(plan.getFromSchema()), null, String.valueOf(e.getMessage()));
            throw e;
        }
        versionService.recordVersion(plan.getTableName(), plan.getToSchema(), migrationId, actor);
        auditService.record(actor, action, plan.getTableName(), migrationId,
                codec.schemaToJson(plan.getFromSchema()), codec.schemaToJson(plan.getToSchema()),
                "applied " + plan.getSteps().size() + " steps");
        return status(migrationId);
    }

    /** Roll back a table to a historical schema version via a generated plan. */
    public MigrationPlan rollback(String actor, String tableName, long targetVersion) {
        authService.require(actor, Role.ROLLBACK);
        TableSchema current = versionService.latest(tableName)
                .orElseThrow(() -> new IllegalArgumentException("no version history for table " + tableName));
        TableSchema historical = versionService.versionAt(tableName, targetVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no version " + targetVersion + " for table " + tableName));
        MigrationPlan plan = planGenerator.generateRollback(current, historical,
                (table, column) -> metaStore.findArchive(table, column)
                        .map(m -> (String) m.get("ARCHIVE_TABLE")));
        String migrationId = persistPlan(plan, actor);
        auditService.record(actor, "ROLLBACK_PLAN", tableName, migrationId,
                codec.schemaToJson(current), codec.schemaToJson(historical),
                "rollback to version " + targetVersion);
        return withMigrationId(plan, migrationId);
    }

    public Map<String, Object> status(String migrationId) {
        Map<String, Object> record = metaStore.findMigration(migrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown migration: " + migrationId));
        Map<String, Object> out = new HashMap<>();
        out.put("migrationId", migrationId);
        out.put("table", record.get("TABLE_NAME"));
        out.put("status", record.get("STATUS"));
        out.put("rollback", record.get("ROLLBACK"));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> s : metaStore.listSteps(migrationId)) {
            Map<String, Object> view = new HashMap<>();
            view.put("ordinal", s.get("ORDINAL"));
            view.put("type", s.get("TYPE"));
            view.put("description", s.get("DESCRIPTION"));
            view.put("status", s.get("STATUS"));
            view.put("error", s.get("ERROR"));
            if (StepType.BACKFILL.name().equals(s.get("TYPE"))) {
                view.put("progress", backfillService.progress(migrationId, (String) s.get("STEP_KEY")));
            }
            steps.add(view);
        }
        out.put("steps", steps);
        return out;
    }

    public List<Map<String, Object>> history(String tableName) {
        return versionService.history(tableName);
    }

    // ---- helpers ----

    private String persistPlan(MigrationPlan plan, String actor) {
        String base = plan.getPlanId();
        String migrationId = base;
        int n = 1;
        while (metaStore.findMigration(migrationId).isPresent()) {
            migrationId = base + "#" + (++n);
        }
        String planJson = codec.planToJson(plan);
        metaStore.insertMigration(migrationId, plan.getPlanId(), plan.getTableName(), planJson,
                plan.isRollback(), actor);
        for (PlanStep step : plan.getSteps()) {
            metaStore.insertStep(migrationId, step.getOrdinal(), step.idempotencyKey(migrationId),
                    step.getType().name(), step.getDescription(),
                    codec.planToJson(new MigrationPlan(plan.getPlanId(), plan.getTableName(),
                            plan.getFromSchema(), plan.getToSchema(), List.of(step), plan.isRollback())));
        }
        return migrationId;
    }

    private static MigrationPlan withMigrationId(MigrationPlan plan, String migrationId) {
        return new MigrationPlan(migrationId, plan.getTableName(), plan.getFromSchema(), plan.getToSchema(),
                plan.getSteps(), plan.isRollback());
    }

    public Optional<Map<String, Object>> findMigration(String migrationId) {
        return metaStore.findMigration(migrationId);
    }
}
