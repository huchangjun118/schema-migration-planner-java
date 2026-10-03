package com.github.huchangjun118.schemamigrationplannerjava.model;

/**
 * 列定义。
 *
 * @param name          列名（同一表内唯一）
 * @param type          列类型
 * @param length        长度（VARCHAR/CHAR 使用，可空）
 * @param nullable      是否允许 NULL
 * @param defaultValue  默认值 SQL 字面量（如 "0"、"'x'"、"CURRENT_TIMESTAMP"），null 表示无默认值
 * @param primaryKey    是否为主键列（回填分批依赖主键范围）
 */
public record ColumnDefinition(
        String name,
        ColumnType type,
        Integer length,
        boolean nullable,
        String defaultValue,
        boolean primaryKey
) {
    /** 紧凑构造：规范列名（去空白） */
    public ColumnDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        name = name.trim();
        if (type == null) {
            throw new IllegalArgumentException("列类型不能为空: " + name);
        }
    }

    /** 生成列的 SQL 定义片段，如 "name" VARCHAR(255) NOT NULL DEFAULT 'x' */
    public String toSqlDefinition() {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(name).append("\" ");
        sb.append(type.toSql(length));
        if (primaryKey) {
            sb.append(" PRIMARY KEY");
        }
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            sb.append(" DEFAULT ").append(defaultValue.trim());
        }
        return sb.toString();
    }

    /** 默认值是否为字面量（用于回填表达式） */
    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isBlank();
    }
}
