package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.util.Locale;

/**
 * 支持的列类型。每种类型都可以映射为目标库（H2）的 SQL 类型，
 * 并用于回填/双写时的类型转换语义判断。
 */
public enum ColumnType {
    VARCHAR, CHAR, TEXT,
    TINYINT, SMALLINT, INT, BIGINT,
    DECIMAL, FLOAT, DOUBLE,
    BOOLEAN,
    DATE, TIME, TIMESTAMP;

    /** 映射为目标库 SQL 类型字面量 */
    public String toSql(Integer length) {
        return switch (this) {
            case VARCHAR -> "VARCHAR(" + (length == null ? 255 : length) + ")";
            case CHAR -> "CHAR(" + (length == null ? 1 : length) + ")";
            case TEXT -> "CLOB";
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INT -> "INT";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL(20,4)";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
        };
    }

    /** 解析类型名（大小写不敏感，支持 VARCHAR(255) 这种带长度的写法） */
    public static ColumnType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("列类型不能为空");
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        int paren = upper.indexOf('(');
        String name = paren > 0 ? upper.substring(0, paren).trim() : upper.trim();
        return switch (name) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR" -> VARCHAR;
            case "CHAR", "NCHAR" -> CHAR;
            case "TEXT", "CLOB" -> TEXT;
            case "TINYINT" -> TINYINT;
            case "SMALLINT" -> SMALLINT;
            case "INT", "INTEGER" -> INT;
            case "BIGINT" -> BIGINT;
            case "DECIMAL", "NUMERIC" -> DECIMAL;
            case "FLOAT", "REAL" -> FLOAT;
            case "DOUBLE" -> DOUBLE;
            case "BOOLEAN", "BOOL", "BIT" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIME" -> TIME;
            case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
            default -> throw new IllegalArgumentException("不支持的列类型: " + raw);
        };
    }

    /** 从类型字面量中解析长度，如 VARCHAR(64) -> 64 */
    public static Integer parseLength(String raw) {
        if (raw == null) return null;
        int l = raw.indexOf('(');
        int r = raw.indexOf(')');
        if (l > 0 && r > l) {
            String inner = raw.substring(l + 1, r).trim();
            int comma = inner.indexOf(',');
            String first = comma > 0 ? inner.substring(0, comma).trim() : inner;
            if (!first.isEmpty()) {
                try { return Integer.valueOf(first); } catch (NumberFormatException ignore) { return null; }
            }
        }
        return null;
    }
}
