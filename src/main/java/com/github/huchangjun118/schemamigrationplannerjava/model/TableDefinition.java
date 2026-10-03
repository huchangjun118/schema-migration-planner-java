package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.util.List;

/**
 * 表定义。列顺序不影响计划生成（规划时按列名排序）。
 */
public record TableDefinition(String name, List<ColumnDefinition> columns) {

    public TableDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        name = name.trim();
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** 按列名查找列 */
    public ColumnDefinition column(String columnName) {
        return columns.stream()
                .filter(c -> c.name().equalsIgnoreCase(columnName.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 主键列（约定单主键，回填按主键范围分批） */
    public ColumnDefinition primaryKeyColumn() {
        return columns.stream().filter(ColumnDefinition::primaryKey).findFirst().orElse(null);
    }
}
