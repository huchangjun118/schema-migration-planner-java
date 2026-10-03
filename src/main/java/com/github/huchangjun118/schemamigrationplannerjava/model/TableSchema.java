package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable table schema. Columns are always kept in a canonical (name-sorted) order
 * so that generated plans are deterministic regardless of input ordering.
 */
public final class TableSchema {
    private final String tableName;
    private final List<ColumnDef> columns;

    public TableSchema(String tableName, List<ColumnDef> columns) {
        this.tableName = tableName;
        List<ColumnDef> copy = columns == null ? List.of() : new ArrayList<>(columns);
        copy.sort(Comparator.comparing(ColumnDef::getName));
        this.columns = List.copyOf(copy);
    }

    public String getTableName() { return tableName; }
    public List<ColumnDef> getColumns() { return columns; }

    public ColumnDef findColumn(String name) {
        for (ColumnDef c : columns) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Validate the schema itself; returns every self-conflict found so the caller
     * can reject the target as a whole and point at the concrete problems.
     */
    public List<String> validate() {
        List<String> conflicts = new ArrayList<>();
        if (tableName == null || tableName.isBlank()) {
            conflicts.add("table name is blank");
        }
        if (columns.isEmpty()) {
            conflicts.add("table '" + tableName + "' has no columns");
        }
        Map<String, ColumnDef> seen = new LinkedHashMap<>();
        int pkCount = 0;
        for (ColumnDef c : columns) {
            if (c.getName() == null || c.getName().isBlank()) {
                conflicts.add("a column of table '" + tableName + "' has a blank name");
                continue;
            }
            ColumnDef prev = seen.putIfAbsent(c.getName(), c);
            if (prev != null) {
                conflicts.add("duplicate column '" + c.getName() + "' in table '" + tableName + "'");
            }
            if (c.getType() == null) {
                conflicts.add("column '" + c.getName() + "' of table '" + tableName + "' has unknown type");
            } else if (!c.getType().isValidDefault(c.getDefaultValue())) {
                conflicts.add("column '" + c.getName() + "' of table '" + tableName
                        + "' has default '" + c.getDefaultValue() + "' incompatible with type " + c.getType());
            }
            if (c.isPrimaryKey()) {
                pkCount++;
                if (c.isNullable()) {
                    conflicts.add("primary key column '" + c.getName() + "' of table '" + tableName
                            + "' must not be nullable");
                }
            }
        }
        if (pkCount > 1) {
            conflicts.add("table '" + tableName + "' declares " + pkCount
                    + " primary key columns; composite keys are not supported");
        }
        return conflicts;
    }

    /** Canonical textual form; identical schemas always produce identical text. */
    public String canonical() {
        StringBuilder sb = new StringBuilder("table:").append(tableName).append('\n');
        for (ColumnDef c : columns) {
            sb.append("  column:").append(c.getName())
              .append(" type:").append(c.getType())
              .append(" nullable:").append(c.isNullable())
              .append(" default:").append(c.getDefaultValue() == null ? "<none>" : c.getDefaultValue())
              .append(" pk:").append(c.isPrimaryKey())
              .append('\n');
        }
        return sb.toString();
    }

    /** Deterministic fingerprint used for plan ids and audit. */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
