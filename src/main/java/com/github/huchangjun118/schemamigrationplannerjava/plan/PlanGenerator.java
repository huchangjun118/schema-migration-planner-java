package com.github.huchangjun118.schemamigrationplannerjava.plan;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaConflictException;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Generates deterministic expand -> backfill -> contract plans from current to
 * target schema. The same (current, target) pair always yields the same plan,
 * step for step, regardless of input column order or concurrency.
 *
 * Safety rules encoded here:
 *  - new columns are always added nullable (or with default) first, so old code
 *    keeps inserting without knowing them;
 *  - non-widening type changes go through a shadow column plus backfill plus an
 *    atomic swap, so old code keeps reading/writing the original column until
 *    the contract phase;
 *  - drops and NOT NULL enforcement only happen in the contract phase, after
 *    all backfills completed.
 */
@Component
public class PlanGenerator {

    /** Suffix of the shadow column used while a type change is in flight. */
    public static final String SHADOW_SUFFIX = "__new";

    /** Generate a forward plan. Throws SchemaConflictException when the target is contradictory. */
    public MigrationPlan generate(TableSchema current, TableSchema target) {
        return generate(current, target, (table, column) -> Optional.empty(), false);
    }

    /**
     * Generate a plan; {@code archiveLookup} resolves the archive table holding a
     * previously dropped column's data so rollbacks can restore it. When the
     * archive is gone the restore step is explicitly marked unrestorable.
     */
    public MigrationPlan generate(TableSchema current, TableSchema target,
                                  BiFunction<String, String, Optional<String>> archiveLookup,
                                  boolean rollback) {
        if (!current.getTableName().equals(target.getTableName())) {
            throw new SchemaConflictException(List.of(
                    "table name mismatch: current='" + current.getTableName()
                            + "' target='" + target.getTableName() + "'"));
        }
        List<String> conflicts = new ArrayList<>(target.validate());
        conflicts.addAll(current.validate());

        String table = target.getTableName();
        List<PlanStep> steps = new ArrayList<>();
        int[] ord = {0};

        // ---- diff ----
        List<ColumnDef> added = new ArrayList<>();
        List<ColumnDef> dropped = new ArrayList<>();
        List<ColumnDef[]> changed = new ArrayList<>(); // [current, target]
        for (ColumnDef t : target.getColumns()) {
            ColumnDef c = current.findColumn(t.getName());
            if (c == null) {
                added.add(t);
            } else if (!c.equals(t)) {
                changed.add(new ColumnDef[]{c, t});
            }
        }
        for (ColumnDef c : current.getColumns()) {
            if (target.findColumn(c.getName()) == null) {
                dropped.add(c);
            }
        }

        // primary key changes are rejected up front: they cannot be done online safely
        for (ColumnDef[] pair : changed) {
            if (pair[0].isPrimaryKey() != pair[1].isPrimaryKey()) {
                conflicts.add("column '" + pair[1].getName() + "' changes primary-key membership; "
                        + "primary key changes are not supported online");
            }
        }
        // a NOT NULL column added without default can be enforced only when the
        // table is empty; we cannot know that at plan time, so require a default
        // to backfill with, otherwise the contract step would be unsafe.
        for (ColumnDef t : added) {
            if (!t.isNullable() && t.getDefaultValue() == null && !t.isPrimaryKey()) {
                conflicts.add("new column '" + t.getName() + "' is NOT NULL without a default; "
                        + "provide a default so existing rows can be backfilled before enforcement");
            }
        }
        if (!conflicts.isEmpty()) {
            throw new SchemaConflictException(conflicts);
        }

        // ---- phase 1: expand (never breaks old code) ----
        for (ColumnDef t : added) {
            Map<String, String> args = colArgs(t);
            args.put("nullable", "true"); // always added nullable first
            steps.add(step(ord, StepType.EXPAND_ADD_COLUMN, table, args,
                    "expand: add column '" + t.getName() + "' as nullable "
                            + (t.getDefaultValue() != null ? "with default " + t.getDefaultValue() : "without default")));
        }
        for (ColumnDef[] pair : changed) {
            ColumnDef c = pair[0], t = pair[1];
            if (c.getType() != t.getType()) {
                if (c.getType().isWideningTo(t.getType())) {
                    Map<String, String> args = colArgs(t);
                    steps.add(step(ord, StepType.EXPAND_ALTER_COLUMN_WIDEN, table, args,
                            "expand: widen column '" + t.getName() + "' from " + c.getType() + " to " + t.getType()));
                } else {
                    Map<String, String> args = colArgs(t);
                    args.put("shadowColumn", t.getName() + SHADOW_SUFFIX);
                    args.put("sourceColumn", c.getName());
                    steps.add(step(ord, StepType.EXPAND_ADD_SHADOW_COLUMN, table, args,
                            "expand: add shadow column '" + t.getName() + SHADOW_SUFFIX + "' (" + t.getType()
                                    + ") for type change of '" + c.getName() + "'"));
                }
            }
            if (c.isNullable() && !t.isNullable()) {
                // enforced in contract phase; nothing to expand
            } else if (!c.isNullable() && t.isNullable()) {
                steps.add(step(ord, StepType.EXPAND_DROP_NOT_NULL, table, colArgs(t),
                        "expand: relax NOT NULL on '" + t.getName() + "'"));
            }
            if (!java.util.Objects.equals(c.getDefaultValue(), t.getDefaultValue())
                    && c.getType() == t.getType()) {
                steps.add(step(ord, StepType.EXPAND_ALTER_DEFAULT, table, colArgs(t),
                        "expand: change default of '" + t.getName() + "' to " + t.getDefaultValue()));
            }
        }

        // ---- phase 2: backfill (batched, resumable) ----
        for (ColumnDef t : added) {
            if (t.getDefaultValue() != null) {
                Map<String, String> args = colArgs(t);
                args.put("mode", "DEFAULT");
                steps.add(step(ord, StepType.BACKFILL, table, args,
                        "backfill: fill '" + t.getName() + "' with default " + t.getDefaultValue()
                                + " where still null"));
            } else {
                Optional<String> archive = archiveLookup.apply(table, t.getName());
                Map<String, String> args = colArgs(t);
                if (archive.isPresent()) {
                    args.put("mode", "RESTORE");
                    args.put("archiveTable", archive.get());
                    steps.add(step(ord, StepType.BACKFILL, table, args,
                            "backfill: restore '" + t.getName() + "' from archive " + archive.get()));
                } else if (rollback) {
                    args.put("mode", "UNRESTORABLE");
                    steps.add(step(ord, StepType.BACKFILL, table, args,
                            "backfill: DATA UNRESTORABLE for '" + t.getName()
                                    + "' (no archive); column stays null-filled"));
                }
            }
        }
        for (ColumnDef[] pair : changed) {
            ColumnDef c = pair[0], t = pair[1];
            if (c.getType() != t.getType() && !c.getType().isWideningTo(t.getType())) {
                Map<String, String> args = colArgs(t);
                args.put("mode", "COPY");
                args.put("sourceColumn", c.getName());
                args.put("shadowColumn", t.getName() + SHADOW_SUFFIX);
                steps.add(step(ord, StepType.BACKFILL, table, args,
                        "backfill: copy '" + c.getName() + "' into shadow '" + t.getName() + SHADOW_SUFFIX
                                + "' casting to " + t.getType()));
            }
            if (c.isNullable() && !t.isNullable() && t.getDefaultValue() != null) {
                Map<String, String> args = colArgs(t);
                args.put("mode", "DEFAULT");
                steps.add(step(ord, StepType.BACKFILL, table, args,
                        "backfill: fill nulls of '" + t.getName() + "' with default "
                                + t.getDefaultValue() + " before NOT NULL enforcement"));
            }
        }

        // ---- phase 3: contract (only after backfills) ----
        for (ColumnDef[] pair : changed) {
            ColumnDef c = pair[0], t = pair[1];
            if (c.getType() != t.getType() && !c.getType().isWideningTo(t.getType())) {
                Map<String, String> args = colArgs(t);
                args.put("shadowColumn", t.getName() + SHADOW_SUFFIX);
                args.put("oldType", c.getType().name());
                steps.add(step(ord, StepType.CONTRACT_SWAP_COLUMN, table, args,
                        "contract: archive and drop old '" + c.getName() + "', rename shadow '"
                                + t.getName() + SHADOW_SUFFIX + "' to '" + t.getName() + "'"));
            }
            if (c.isNullable() && !t.isNullable()) {
                steps.add(step(ord, StepType.CONTRACT_SET_NOT_NULL, table, colArgs(t),
                        "contract: enforce NOT NULL on '" + t.getName() + "'"));
            }
        }
        for (ColumnDef t : added) {
            if (!t.isNullable()) {
                steps.add(step(ord, StepType.CONTRACT_SET_NOT_NULL, table, colArgs(t),
                        "contract: enforce NOT NULL on new column '" + t.getName() + "'"));
            }
        }
        for (ColumnDef c : dropped) {
            Map<String, String> args = colArgs(c);
            steps.add(step(ord, StepType.CONTRACT_DROP_COLUMN, table, args,
                    "contract: archive and drop column '" + c.getName() + "'"));
        }

        String planId = (rollback ? "rb-" : "mg-") + current.fingerprint().substring(0, 8)
                + "-" + target.fingerprint().substring(0, 8);
        return new MigrationPlan(planId, table, current, target, List.copyOf(steps), rollback);
    }

    /** Generate a rollback plan towards a historical schema version, same safety rules. */
    public MigrationPlan generateRollback(TableSchema current, TableSchema historical,
                                          BiFunction<String, String, Optional<String>> archiveLookup) {
        return generate(current, historical, archiveLookup, true);
    }

    private static PlanStep step(int[] ord, StepType type, String table, Map<String, String> args, String desc) {
        return new PlanStep(ord[0]++, type, table, args, desc);
    }

    private static Map<String, String> colArgs(ColumnDef c) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("column", c.getName());
        args.put("type", c.getType().name());
        args.put("nullable", String.valueOf(c.isNullable()));
        if (c.getDefaultValue() != null) {
            args.put("default", c.getDefaultValue());
        }
        if (c.isPrimaryKey()) {
            args.put("pk", "true");
        }
        return args;
    }
}
