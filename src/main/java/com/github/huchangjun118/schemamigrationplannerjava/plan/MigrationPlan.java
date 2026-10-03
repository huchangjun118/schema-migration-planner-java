package com.github.huchangjun118.schemamigrationplannerjava.plan;

import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;

import java.util.List;

/**
 * An ordered, deterministic migration plan from one schema version to another.
 */
public final class MigrationPlan {
    private final String planId;
    private final String tableName;
    private final TableSchema fromSchema;
    private final TableSchema toSchema;
    private final List<PlanStep> steps;
    private final boolean rollback;

    public MigrationPlan(String planId, String tableName, TableSchema fromSchema, TableSchema toSchema,
                         List<PlanStep> steps, boolean rollback) {
        this.planId = planId;
        this.tableName = tableName;
        this.fromSchema = fromSchema;
        this.toSchema = toSchema;
        this.steps = steps;
        this.rollback = rollback;
    }

    public String getPlanId() { return planId; }
    public String getTableName() { return tableName; }
    public TableSchema getFromSchema() { return fromSchema; }
    public TableSchema getToSchema() { return toSchema; }
    public List<PlanStep> getSteps() { return steps; }
    public boolean isRollback() { return rollback; }
}
