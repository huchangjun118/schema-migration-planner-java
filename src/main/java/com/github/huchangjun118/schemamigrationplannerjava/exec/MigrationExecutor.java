package com.github.huchangjun118.schemamigrationplannerjava.exec;

import com.github.huchangjun118.schemamigrationplannerjava.backfill.BackfillService;
import com.github.huchangjun118.schemamigrationplannerjava.lease.Lease;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import com.github.huchangjun118.schemamigrationplannerjava.store.SchemaCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Executes a persisted migration step by step under the table lease.
 * Resumes after restart from the first non-DONE step; committed steps are never
 * re-executed, steps are never skipped, and the fencing check before every step
 * prevents a stale executor from committing after a lease takeover.
 */
@Component
public class MigrationExecutor {
    private final MetaStore metaStore;
    private final StepApplier stepApplier;
    private final BackfillService backfillService;
    private final LeaseManager leaseManager;
    private final SchemaCodec codec;

    public MigrationExecutor(MetaStore metaStore, StepApplier stepApplier,
                             BackfillService backfillService, LeaseManager leaseManager, SchemaCodec codec) {
        this.metaStore = metaStore;
        this.stepApplier = stepApplier;
        this.backfillService = backfillService;
        this.leaseManager = leaseManager;
        this.codec = codec;
    }

    /** Run (or resume) a migration to completion under the table lease. */
    public void execute(String migrationId, String owner) {
        Map<String, Object> record = metaStore.findMigration(migrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown migration: " + migrationId));
        MigrationPlan plan = codec.planFromJson((String) record.get("PLAN_JSON"));
        List<Map<String, Object>> stepRows = metaStore.listSteps(migrationId);

        metaStore.updateMigrationStatus(migrationId, "RUNNING");
        try (Lease lease = leaseManager.acquire(plan.getTableName(), owner)) {
            for (int i = 0; i < plan.getSteps().size(); i++) {
                Map<String, Object> row = stepRows.get(i);
                if ("DONE".equals(row.get("STATUS"))) {
                    continue; // already committed in a previous run: never re-execute
                }
                leaseManager.checkFencing(lease);
                int ordinal = ((Number) row.get("ORDINAL")).intValue();
                PlanStep step = plan.getSteps().get(i);
                metaStore.markStepRunning(migrationId, ordinal);
                try {
                    if (step.getType() == StepType.BACKFILL) {
                        backfillService.runBackfill(migrationId, step);
                    } else {
                        stepApplier.apply(step);
                    }
                    metaStore.markStepDone(migrationId, ordinal);
                } catch (RuntimeException e) {
                    metaStore.markStepFailed(migrationId, ordinal, String.valueOf(e.getMessage()));
                    metaStore.updateMigrationStatus(migrationId, "FAILED");
                    throw e;
                }
            }
            metaStore.updateMigrationStatus(migrationId, "DONE");
        } catch (RuntimeException e) {
            if (!"FAILED".equals(metaStore.findMigration(migrationId).map(m -> m.get("STATUS")).orElse(""))) {
                metaStore.updateMigrationStatus(migrationId, "FAILED");
            }
            throw e;
        }
    }
}
