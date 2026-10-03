package com.github.huchangjun118.schemamigrationplannerjava.planner;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.DualWriteMapping;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanPhase;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;

import java.util.List;

/**
 * PlanStep 构建器，避免长参数列表的计数错误。phase 由 type 决定。
 */
class StepBuilder {
    final StepType type;
    final PlanPhase phase;
    String table;
    String column;
    String sourceColumn;
    String newColumn;
    String columnType;
    String defaultValue;
    String transform;
    String castType;
    boolean revertable = true;
    boolean dataLoss = false;
    String description = "";
    List<DualWriteMapping> mappings = List.of();
    List<ColumnDefinition> columns = List.of();

    StepBuilder(StepType type) {
        this.type = type;
        this.phase = type.phase();
    }

    StepBuilder table(String table) { this.table = table; return this; }
    StepBuilder column(String column) { this.column = column; return this; }
    StepBuilder sourceColumn(String sourceColumn) { this.sourceColumn = sourceColumn; return this; }
    StepBuilder newColumn(String newColumn) { this.newColumn = newColumn; return this; }
    StepBuilder columnType(String columnType) { this.columnType = columnType; return this; }
    StepBuilder defaultValue(String defaultValue) { this.defaultValue = defaultValue; return this; }
    StepBuilder transform(String transform) { this.transform = transform; return this; }
    StepBuilder castType(String castType) { this.castType = castType; return this; }
    StepBuilder revertable(boolean revertable) { this.revertable = revertable; return this; }
    StepBuilder dataLoss(boolean dataLoss) { this.dataLoss = dataLoss; return this; }
    StepBuilder description(String description) { this.description = description; return this; }
    StepBuilder mappings(List<DualWriteMapping> mappings) { this.mappings = mappings; return this; }
    StepBuilder columns(List<ColumnDefinition> columns) { this.columns = columns; return this; }

    PlanStep build() {
        return new PlanStep(-1, phase, type, table, column, sourceColumn, newColumn,
                columnType, defaultValue, transform, castType, revertable, dataLoss,
                description, mappings, columns);
    }
}
