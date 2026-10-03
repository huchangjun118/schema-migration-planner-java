package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Supported column types and compatibility rules.
 */
public enum ColumnType {
    INT,
    BIGINT,
    DOUBLE,
    DECIMAL,
    BOOLEAN,
    VARCHAR,
    TEXT,
    TIMESTAMP;

    public static ColumnType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        // tolerate common aliases so hand-written schemas still parse
        return switch (normalized) {
            case "INT", "INTEGER" -> INT;
            case "BIGINT", "LONG" -> BIGINT;
            case "DOUBLE", "FLOAT", "REAL" -> DOUBLE;
            case "DECIMAL", "NUMERIC" -> DECIMAL;
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "VARCHAR", "STRING", "CHAR" -> VARCHAR;
            case "TEXT", "CLOB" -> TEXT;
            case "TIMESTAMP", "DATETIME" -> TIMESTAMP;
            default -> null;
        };
    }

    /**
     * @return true when a column of this type can be read as {@code target} without
     *         data loss (widening only). Anything else requires the shadow-column
     *         expand/backfill/contract path.
     */
    public boolean isWideningTo(ColumnType target) {
        if (target == this) {
            return true;
        }
        return switch (this) {
            case INT -> target == BIGINT || target == DOUBLE || target == DECIMAL;
            case BIGINT -> target == DOUBLE || target == DECIMAL;
            case DOUBLE -> target == DECIMAL;
            case VARCHAR -> target == TEXT;
            default -> false;
        };
    }

    /** @return true when the literal is a valid default value for this type. */
    public boolean isValidDefault(String literal) {
        if (literal == null) {
            return true; // no default
        }
        String v = literal.trim();
        try {
            return switch (this) {
                case INT, BIGINT -> {
                    Long.parseLong(v);
                    yield true;
                }
                case DOUBLE, DECIMAL -> {
                    new BigDecimal(v);
                    yield true;
                }
                case BOOLEAN -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")
                        || v.equals("0") || v.equals("1");
                case VARCHAR, TEXT -> true; // any literal is a valid string
                case TIMESTAMP -> v.matches("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?");
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** SQL type name used in DDL. */
    public String sql() {
        return switch (this) {
            case VARCHAR -> "VARCHAR(1024)";
            case DECIMAL -> "DECIMAL(38,10)";
            default -> name();
        };
    }
}
