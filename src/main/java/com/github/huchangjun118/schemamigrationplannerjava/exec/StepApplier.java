package com.github.huchangjun118.schemamigrationplannerjava.exec;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies a single plan step against the business database.
 * Every apply is idempotent: it inspects the live schema first and no-ops
 * when the step's effect is already present, so a step can be retried after
 * a crash without being applied twice.
 */
@Component
public class StepApplier {
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final MetaStore metaStore;

    public StepApplier(JdbcTemplate jdbc, MetaStore metaStore) {
        this.jdbc = jdbc;
        this.metaStore = metaStore;
    }

    public static String q(String ident) {
        if (!IDENT.matcher(ident).matches()) {
            throw new IllegalArgumentException("unsafe identifier: " + ident);
        }
        return '"' + ident + '"';
    }

    /** Apply one non-backfill step idempotently. */
    public void apply(PlanStep step) {
        String table = step.getTableName();
        Map<String, String> a = step.getArgs();
        String column = a.get("column");
        switch (step.getType()) {
            case EXPAND_ADD_COLUMN -> {
                if (columnExists(table, column)) {
                    return;
                }
                StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(q(table))
                        .append(" ADD COLUMN ").append(q(column)).append(' ')
                        .append(ColumnType.valueOf(a.get("type")).sql());
                if (a.containsKey("default")) {
                    ddl.append(" DEFAULT ").append(defaultLiteral(ColumnType.valueOf(a.get("type")), a.get("default")));
                }
                jdbc.execute(ddl.toString());
            }
            case EXPAND_ADD_SHADOW_COLUMN -> {
                String shadow = a.get("shadowColumn");
                if (columnExists(table, shadow)) {
                    return;
                }
                jdbc.execute("ALTER TABLE " + q(table) + " ADD COLUMN " + q(shadow) + ' '
                        + ColumnType.valueOf(a.get("type")).sql());
            }
            case EXPAND_ALTER_COLUMN_WIDEN -> {
                String currentType = liveType(table, column);
                ColumnType target = ColumnType.valueOf(a.get("type"));
                if (currentType != null && ColumnType.parse(currentType) == target) {
                    return;
                }
                jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(column)
                        + " SET DATA TYPE " + target.sql());
            }
            case EXPAND_DROP_NOT_NULL -> {
                if (isNullable(table, column)) {
                    return;
                }
                jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(column) + " SET NULL");
            }
            case EXPAND_ALTER_DEFAULT -> {
                if (a.containsKey("default")) {
                    jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(column)
                            + " SET DEFAULT " + defaultLiteral(ColumnType.valueOf(a.get("type")), a.get("default")));
                } else {
                    jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(column) + " DROP DEFAULT");
                }
            }
            case CONTRACT_SET_NOT_NULL -> {
                if (!isNullable(table, column)) {
                    return;
                }
                Long nulls = jdbc.queryForObject("SELECT COUNT(*) FROM " + q(table)
                        + " WHERE " + q(column) + " IS NULL", Long.class);
                if (nulls != null && nulls > 0) {
                    throw new IllegalStateException("cannot enforce NOT NULL on '" + column + "': "
                            + nulls + " rows still null (backfill incomplete or no default provided)");
                }
                jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(column) + " SET NOT NULL");
            }
            case CONTRACT_DROP_COLUMN -> {
                if (!columnExists(table, column)) {
                    return;
                }
                archiveColumn(table, column);
                jdbc.execute("ALTER TABLE " + q(table) + " DROP COLUMN " + q(column));
            }
            case CONTRACT_SWAP_COLUMN -> {
                String shadow = a.get("shadowColumn");
                if (!columnExists(table, shadow)) {
                    return; // already swapped
                }
                if (columnExists(table, column)) {
                    archiveColumn(table, column);
                    jdbc.execute("ALTER TABLE " + q(table) + " DROP COLUMN " + q(column));
                }
                jdbc.execute("ALTER TABLE " + q(table) + " ALTER COLUMN " + q(shadow)
                        + " RENAME TO " + q(column));
            }
            case BACKFILL -> throw new IllegalArgumentException("BACKFILL steps are handled by BackfillService");
        }
    }

    /** Deterministic archive table name for a dropped column. */
    public static String archiveTableName(String table, String column) {
        return "sm_arch_" + table + "_" + column;
    }

    /** Snapshot pk + column values into an archive table (idempotent). */
    private void archiveColumn(String table, String column) {
        String archive = archiveTableName(table, column);
        String pk = primaryKeyColumn(table);
        Boolean exists = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=? AND TABLE_SCHEMA='PUBLIC'",
                Boolean.class, archive);
        if (Boolean.FALSE.equals(exists)) {
            jdbc.execute("CREATE TABLE " + q(archive) + " AS SELECT " + q(pk) + ", " + q(column)
                    + " FROM " + q(table));
        }
        metaStore.recordArchive(table, column, archive);
    }

    public String primaryKeyColumn(String table) {
        List<String> pks = jdbc.queryForList("""
                SELECT ic.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.INDEX_COLUMNS ic
                  ON tc.INDEX_NAME = ic.INDEX_NAME AND tc.TABLE_SCHEMA = ic.TABLE_SCHEMA
                WHERE tc.CONSTRAINT_TYPE='PRIMARY KEY' AND tc.TABLE_NAME=? AND tc.TABLE_SCHEMA='PUBLIC'
                """, String.class, table);
        if (pks.size() != 1) {
            throw new IllegalStateException("table '" + table + "' must have exactly one primary key column, found " + pks);
        }
        return pks.get(0);
    }

    boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=? AND TABLE_SCHEMA='PUBLIC'",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    boolean isNullable(String table, String column) {
        String v = jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=? AND TABLE_SCHEMA='PUBLIC'",
                String.class, table, column);
        return "YES".equals(v);
    }

    String liveType(String table, String column) {
        List<String> rows = jdbc.queryForList(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=? AND TABLE_SCHEMA='PUBLIC'",
                String.class, table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static String defaultLiteral(ColumnType type, String literal) {
        return switch (type) {
            case VARCHAR, TEXT, TIMESTAMP -> "'" + literal.replace("'", "''") + "'";
            default -> literal;
        };
    }
}
