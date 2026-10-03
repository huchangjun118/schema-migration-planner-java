package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.util.Comparator;
import java.util.List;

/**
 * 结构定义：一个命名库下的一组表。表顺序不影响计划生成（规划时按表名排序）。
 */
public record SchemaDefinition(String name, List<TableDefinition> tables) {

    public SchemaDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("结构名不能为空");
        }
        name = name.trim();
        // 归一化：表按名排序，保证相同输入生成相同计划，不受输入顺序影响
        tables = tables == null ? List.of()
                : tables.stream()
                        .sorted(Comparator.comparing(t -> t.name().toLowerCase()))
                        .toList();
    }

    /** 按表名查找 */
    public TableDefinition table(String tableName) {
        return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 空结构（初始状态） */
    public static SchemaDefinition empty(String name) {
        return new SchemaDefinition(name, List.of());
    }
}
