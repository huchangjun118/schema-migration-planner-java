package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanKind;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanPhase;
import com.github.huchangjun118.schemamigrationplannerjava.planner.ConflictException;
import com.github.huchangjun118.schemamigrationplannerjava.planner.MigrationPlanner;
import com.github.huchangjun118.schemamigrationplannerjava.planner.SchemaDiff;
import com.github.huchangjun118.schemamigrationplannerjava.planner.SchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    private final MigrationPlanner planner = new MigrationPlanner(new SchemaDiff(), new SchemaValidator());

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void emptyToNewTable_generatesExpandOnly() {
        SchemaDefinition current = SchemaDefinition.empty("db");
        SchemaDefinition target = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("age", ColumnType.INT, true, null, false)
                ))
        ));
        MigrationPlan plan = planner.plan("p1", current, target, 0, 1, PlanKind.FORWARD, "admin");
        assertFalse(plan.steps().isEmpty());
        // 新表只有 EXPAND 步骤
        assertEquals(plan.steps().size(), plan.countPhase(PlanPhase.EXPAND));
        // 步骤序号连续
        for (int i = 0; i < plan.steps().size(); i++) {
            assertEquals(i, plan.steps().get(i).index());
        }
    }

    @Test
    void addNotNullColumnWithDefault_generatesExpandBackfillContract() {
        SchemaDefinition current = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        SchemaDefinition target = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("status", ColumnType.INT, false, "0", false)
                ))
        ));
        MigrationPlan plan = planner.plan("p2", current, target, 1, 2, PlanKind.FORWARD, "admin");
        assertTrue(plan.countPhase(PlanPhase.EXPAND) >= 2); // add + dual-write
        assertTrue(plan.countPhase(PlanPhase.BACKFILL) >= 1);
        assertTrue(plan.countPhase(PlanPhase.CONTRACT) >= 1);
    }

    @Test
    void sameInputDifferentOrder_producesIdenticalPlan() {
        SchemaDefinition current = new SchemaDefinition("db", List.of(
                new TableDefinition("t_order", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("amount", ColumnType.INT, true, null, false)
                ))
        ));
        // 目标：表顺序、列顺序都打乱
        SchemaDefinition target1 = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("age", ColumnType.INT, true, null, false),
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                )),
                new TableDefinition("t_order", List.of(
                        col("amount", ColumnType.INT, true, null, false),
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("status", ColumnType.VARCHAR, true, "'pending'", false)
                ))
        ));
        SchemaDefinition target2 = new SchemaDefinition("db", List.of(
                new TableDefinition("t_order", List.of(
                        col("status", ColumnType.VARCHAR, true, "'pending'", false),
                        col("amount", ColumnType.INT, true, null, false),
                        col("id", ColumnType.BIGINT, false, null, true)
                )),
                new TableDefinition("t_user", List.of(
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("age", ColumnType.INT, true, null, false),
                        col("id", ColumnType.BIGINT, false, null, true)
                ))
        ));
        MigrationPlan p1 = planner.plan("p", current, target1, 1, 2, PlanKind.FORWARD, "admin");
        MigrationPlan p2 = planner.plan("p", current, target2, 1, 2, PlanKind.FORWARD, "admin");
        assertEquals(p1.steps().size(), p2.steps().size());
        for (int i = 0; i < p1.steps().size(); i++) {
            var a = p1.steps().get(i);
            var b = p2.steps().get(i);
            assertEquals(a.type(), b.type(), "步骤 " + i + " 类型不一致");
            assertEquals(a.table(), b.table(), "步骤 " + i + " 表不一致");
            assertEquals(a.column(), b.column(), "步骤 " + i + " 列不一致");
            assertEquals(a.phase(), b.phase(), "步骤 " + i + " 阶段不一致");
        }
    }

    @Test
    void conflictingTarget_isRejectedWithLocations() {
        SchemaDefinition current = SchemaDefinition.empty("db");
        SchemaDefinition target = new SchemaDefinition("db", List.of(
                new TableDefinition("t_bad", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("id", ColumnType.VARCHAR, true, null, false), // 重复列名
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> planner.plan("p", current, target, 0, 1, PlanKind.FORWARD, "admin"));
        assertFalse(ex.getConflicts().isEmpty());
        assertTrue(ex.getConflicts().stream().anyMatch(c -> c.contains("t_bad") && c.contains("id")),
                "冲突应定位到 t_bad.id: " + ex.getConflicts());
    }

    @Test
    void addNotNullColumnWithoutDefault_isRejected() {
        SchemaDefinition current = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true)
                ))
        ));
        SchemaDefinition target = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, false, null, false) // 非空无默认
                ))
        ));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> planner.plan("p", current, target, 1, 2, PlanKind.FORWARD, "admin"));
        assertTrue(ex.getConflicts().stream().anyMatch(c -> c.contains("t_user") && c.contains("name")));
    }

    @Test
    void typeChange_generatesExpandBackfillContract() {
        SchemaDefinition current = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("age", ColumnType.INT, true, null, false)
                ))
        ));
        SchemaDefinition target = new SchemaDefinition("db", List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("age", ColumnType.VARCHAR, true, null, false) // INT -> VARCHAR
                ))
        ));
        MigrationPlan plan = planner.plan("p", current, target, 1, 2, PlanKind.FORWARD, "admin");
        assertTrue(plan.countPhase(PlanPhase.EXPAND) >= 2);
        assertTrue(plan.countPhase(PlanPhase.BACKFILL) >= 1);
        assertTrue(plan.countPhase(PlanPhase.CONTRACT) >= 2);
    }
}
