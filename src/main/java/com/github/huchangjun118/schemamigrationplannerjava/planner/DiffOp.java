package com.github.huchangjun118.schemamigrationplannerjava.planner;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;

/**
 * 结构差异操作（内部表示）。由当前结构与目标结构做集合差得到，
 * 与输入顺序无关；MigrationPlanner 再把它翻译为有序的原子计划步骤。
 */
public sealed interface DiffOp {

    /** 建新表 */
    record CreateTable(TableDefinition table) implements DiffOp {}

    /** 删表 */
    record DropTable(String tableName) implements DiffOp {}

    /**
     * 加列。
     * @param tableName    所在表
     * @param sourceColumn 非空时表示该列由旧列改名而来（同类型），需要双写 + 回填
     */
    record AddColumn(String tableName, ColumnDefinition column, String sourceColumn) implements DiffOp {}

    /** 删列（改名场景下的旧列不在此列） */
    record DropColumn(String tableName, String columnName) implements DiffOp {}

    /** 改列类型（同名列类型变化） */
    record ChangeType(String tableName, String column, ColumnDefinition newColumn) implements DiffOp {}

    /** 改可空性（同名列 nullable 变化） */
    record ChangeNullable(String tableName, String column, ColumnDefinition newColumn) implements DiffOp {}

    /** 改默认值（同名列 default 变化） */
    record ChangeDefault(String tableName, String column, ColumnDefinition newColumn) implements DiffOp {}
}
