package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.util.Objects;

/**
 * Immutable column definition.
 */
public final class ColumnDef {
    private final String name;
    private final ColumnType type;
    private final boolean nullable;
    private final String defaultValue;
    private final boolean primaryKey;

    public ColumnDef(String name, ColumnType type, boolean nullable, String defaultValue, boolean primaryKey) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.primaryKey = primaryKey;
    }

    public String getName() { return name; }
    public ColumnType getType() { return type; }
    public boolean isNullable() { return nullable; }
    public String getDefaultValue() { return defaultValue; }
    public boolean isPrimaryKey() { return primaryKey; }

    public ColumnDef withNullable(boolean n) { return new ColumnDef(name, type, n, defaultValue, primaryKey); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDef other)) return false;
        return nullable == other.nullable && primaryKey == other.primaryKey
                && Objects.equals(name, other.name) && type == other.type
                && Objects.equals(defaultValue, other.defaultValue);
    }

    @Override
    public int hashCode() { return Objects.hash(name, type, nullable, defaultValue, primaryKey); }
}
