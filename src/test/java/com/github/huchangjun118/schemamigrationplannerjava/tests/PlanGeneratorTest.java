package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaConflictException;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanGenerator;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PlanGeneratorTest {
    private final PlanGenerator generator = new PlanGenerator();

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    private static TableSchema schema(String table, List<ColumnDef> cols) {
        return new TableSchema(table, cols);
    }

    private static List<String> describe(MigrationPlan plan) {
        return plan.getSteps().stream()
                .map(s -> s.getType() + ":" + s.getArgs().get("column"))
                .collect(Collectors.toList());
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        List<ColumnDef> currentCols = new ArrayList<>(List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.VARCHAR, true, null, false),
                col("age", ColumnType.INT, true, null, false)));
        List<ColumnDef> targetCols = new ArrayList<>(List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("name", ColumnType.TEXT, true, null, false),
                col("age", ColumnType.BIGINT, true, null, false),
                col("email", ColumnType.VARCHAR, false, "", false),
                col("legacy", ColumnType.VARCHAR, true, null, false)));
        TableSchema current = schema("users", currentCols);
        TableSchema target = schema("users", targetCols);

        MigrationPlan reference = generator.generate(current, target);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(currentCols);
            Collections.shuffle(targetCols);
            MigrationPlan shuffled = generator.generate(schema("users", currentCols), schema("users", targetCols));
            assertEquals(describe(reference), describe(shuffled), "plan must not depend on input order");
            assertEquals(reference.getPlanId(), shuffled.getPlanId());
        }
    }

    @Test
    void expandPhasesComeBeforeBackfillAndContract() {
        TableSchema current = schema("users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("age", ColumnType.INT, true, null, false),
                col("dead", ColumnType.VARCHAR, true, null, false)));
        TableSchema target = schema("users", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("age", ColumnType.VARCHAR, true, null, false),
                col("email", ColumnType.VARCHAR, false, "none", false)));
        MigrationPlan plan = generator.generate(current, target);

        List<StepType> types = plan.getSteps().stream().map(PlanStep::getType).toList();
        int lastExpand = -1, firstContract = Integer.MAX_VALUE, firstBackfill = Integer.MAX_VALUE;
        for (int i = 0; i < types.size(); i++) {
            StepType t = types.get(i);
            if (t.name().startsWith("EXPAND")) lastExpand = i;
            if (t.name().startsWith("CONTRACT")) firstContract = Math.min(firstContract, i);
            if (t == StepType.BACKFILL) firstBackfill = Math.min(firstBackfill, i);
        }
        assertTrue(lastExpand < firstBackfill, "all expand steps before backfills: " + types);
        assertTrue(firstBackfill < firstContract, "all backfills before contract: " + types);
        // type change INT -> VARCHAR must go through shadow column, never in-place
        assertTrue(types.contains(StepType.EXPAND_ADD_SHADOW_COLUMN));
        assertTrue(types.contains(StepType.CONTRACT_SWAP_COLUMN));
        // new NOT NULL column with default: added nullable, backfilled, enforced at contract
        assertTrue(types.contains(StepType.EXPAND_ADD_COLUMN));
        assertTrue(types.contains(StepType.CONTRACT_SET_NOT_NULL));
        // dropped column contracted with archive
        assertTrue(types.contains(StepType.CONTRACT_DROP_COLUMN));
    }

    @Test
    void wideningTypeChangeIsInPlace() {
        TableSchema current = schema("t", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("n", ColumnType.INT, true, null, false)));
        TableSchema target = schema("t", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("n", ColumnType.BIGINT, true, null, false)));
        MigrationPlan plan = generator.generate(current, target);
        assertEquals(List.of(StepType.EXPAND_ALTER_COLUMN_WIDEN),
                plan.getSteps().stream().map(PlanStep::getType).toList());
    }

    @Test
    void selfContradictoryTargetIsRejectedWithLocatedConflicts() {
        TableSchema current = schema("t", List.of(col("id", ColumnType.BIGINT, false, null, true)));
        TableSchema bad = schema("t", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("a", ColumnType.VARCHAR, true, null, false),
                col("a", ColumnType.INT, true, null, false),          // duplicate
                col("b", ColumnType.INT, false, "not-a-number", false), // bad default
                col("c", ColumnType.VARCHAR, false, null, false)));     // NOT NULL no default
        SchemaConflictException e = assertThrows(SchemaConflictException.class,
                () -> generator.generate(current, bad));
        assertTrue(e.getConflicts().stream().anyMatch(c -> c.contains("duplicate column 'a'")), e.getConflicts().toString());
        assertTrue(e.getConflicts().stream().anyMatch(c -> c.contains("'b'") && c.contains("default")), e.getConflicts().toString());
        assertTrue(e.getConflicts().stream().anyMatch(c -> c.contains("'c'") && c.contains("NOT NULL")), e.getConflicts().toString());
    }

    @Test
    void primaryKeyChangeIsRejected() {
        TableSchema current = schema("t", List.of(col("id", ColumnType.BIGINT, false, null, true)));
        TableSchema target = schema("t", List.of(col("id", ColumnType.BIGINT, false, null, false)));
        assertThrows(SchemaConflictException.class, () -> generator.generate(current, target));
    }

    @Test
    void rollbackPlanRestoresFromArchiveOrMarksUnrestorable() {
        TableSchema current = schema("t", List.of(col("id", ColumnType.BIGINT, false, null, true)));
        TableSchema historical = schema("t", List.of(
                col("id", ColumnType.BIGINT, false, null, true),
                col("lost", ColumnType.VARCHAR, true, null, false)));

        MigrationPlan withArchive = generator.generateRollback(current, historical,
                (t, c) -> Optional.of("sm_arch_t_lost"));
        assertTrue(withArchive.getSteps().stream()
                .anyMatch(s -> s.getType() == StepType.BACKFILL && "RESTORE".equals(s.getArgs().get("mode"))));

        MigrationPlan noArchive = generator.generateRollback(current, historical,
                (t, c) -> Optional.empty());
        assertTrue(noArchive.getSteps().stream()
                .anyMatch(s -> s.getType() == StepType.BACKFILL && "UNRESTORABLE".equals(s.getArgs().get("mode"))));
    }
}
