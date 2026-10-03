package com.github.huchangjun118.schemamigrationplannerjava.executor;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.DualWriteMapping;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤应用器。每个步骤都是幂等的：重复执行不会产生重复结构，
 * 已完成的步骤会被跳过，失败后可从断点恢复。
 */
@Component
public class StepApplier {

    private static final String TRIGGER_CLASS =
            "com.github.huchangjun118.schemamigrationplannerjava.executor.DualWriteTrigger";

    private final JdbcTemplate targetJdbc;
    private final BackfillExecutor backfillExecutor;

    public StepApplier(@Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc,
                       BackfillExecutor backfillExecutor) {
        this.targetJdbc = targetJdbc;
        this.backfillExecutor = backfillExecutor;
    }

    /** 应用一个步骤（幂等） */
    public boolean apply(String planId, PlanStep step, String schemaName, String leaseKey, String token) {
        switch (step.type()) {
            case CREATE_TABLE -> applyCreateTable(step, schemaName);
            case DROP_TABLE -> applyDropTable(step, schemaName);
            case ADD_COLUMN -> applyAddColumn(step, schemaName);
            case DROP_COLUMN -> applyDropColumn(step, schemaName);
            case MODIFY_DEFAULT -> applyModifyDefault(step, schemaName);
            case MODIFY_NULLABLE -> applyModifyNullable(step, schemaName);
            case INSTALL_DUAL_WRITE -> applyInstallDualWrite(step, schemaName);
            case BACKFILL -> {
                boolean done = backfillExecutor.execute(planId, step, schemaName, leaseKey, token);
                return done;
            }
            case MAKE_NOT_NULL -> applyMakeNotNull(step, schemaName);
            case RENAME_COLUMN -> applyRenameColumn(step, schemaName);
        }
        return true;
    }

    private String schemaTable(PlanStep step, String schemaName) {
        return quote(schemaName) + "." + quote(step.table());
    }

    private void applyCreateTable(PlanStep step, String schemaName) {
        if (tableExists(step.table())) {
            return;
        }
        StringBuilder ddl = new StringBuilder("CREATE TABLE ");
        ddl.append(schemaTable(step, schemaName)).append(" (");
        List<ColumnDefinition> cols = step.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(cols.get(i).toSqlDefinition());
        }
        ddl.append(")");
        targetJdbc.execute(ddl.toString());
    }

    private void applyDropTable(PlanStep step, String schemaName) {
        targetJdbc.execute("DROP TABLE IF EXISTS " + schemaTable(step, schemaName));
    }

    private void applyAddColumn(PlanStep step, String schemaName) {
        if (columnExists(step.table(), step.column())) {
            return;
        }
        StringBuilder ddl = new StringBuilder("ALTER TABLE ");
        ddl.append(schemaTable(step, schemaName)).append(" ADD COLUMN IF NOT EXISTS ")
                .append(quote(step.column())).append(" ").append(step.columnType());
        // 扩容阶段：一律以可空列加入，不带 DEFAULT。
        // 存量行由 BACKFILL 阶段填充，新行由双写触发器按默认值维护。
        targetJdbc.execute(ddl.toString());
    }

    private void applyDropColumn(PlanStep step, String schemaName) {
        if (!columnExists(step.table(), step.column())) {
            return;
        }
        targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                + " DROP COLUMN IF EXISTS " + quote(step.column()));
    }

    private void applyModifyDefault(PlanStep step, String schemaName) {
        if (step.defaultValue() != null && !step.defaultValue().isBlank()) {
            targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                    + " ALTER COLUMN " + quote(step.column()) + " SET DEFAULT "
                    + step.defaultValue().trim());
        } else {
            targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                    + " ALTER COLUMN " + quote(step.column()) + " DROP DEFAULT");
        }
    }

    private void applyModifyNullable(PlanStep step, String schemaName) {
        targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                + " ALTER COLUMN " + quote(step.column()) + " DROP NOT NULL");
    }

    private void applyMakeNotNull(PlanStep step, String schemaName) {
        if (!columnExists(step.table(), step.column())) {
            return;
        }
        // 幂等：已是 NOT NULL 则跳过
        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?) AND IS_NULLABLE='NO'",
                Integer.class, step.table(), step.column());
        if (count != null && count > 0) {
            return;
        }
        targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                + " ALTER COLUMN " + quote(step.column()) + " SET NOT NULL");
    }

    private void applyRenameColumn(PlanStep step, String schemaName) {
        String oldName = step.column();
        String newName = step.newColumn();
        if (columnExists(step.table(), newName)) {
            return;
        }
        if (!columnExists(step.table(), oldName)) {
            return;
        }
        targetJdbc.execute("ALTER TABLE " + schemaTable(step, schemaName)
                + " ALTER COLUMN " + quote(oldName) + " RENAME TO " + quote(newName));
    }

    private void applyInstallDualWrite(PlanStep step, String schemaName) {
        // 确保目标库 META.dual_write_map 存在
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"META\"");
        targetJdbc.execute("""
                CREATE TABLE IF NOT EXISTS "META"."dual_write_map" (
                    "table_schema"   VARCHAR(255) NOT NULL,
                    "table_name"     VARCHAR(255) NOT NULL,
                    "new_column"     VARCHAR(255) NOT NULL,
                    "old_column"     VARCHAR(255),
                    "mode"           VARCHAR(16) NOT NULL,
                    "cast_type"      VARCHAR(255),
                    "default_value"  VARCHAR(1000),
                    "enabled"        BOOLEAN NOT NULL DEFAULT true,
                    PRIMARY KEY ("table_schema","table_name","new_column")
                )
                """);
        // upsert 映射
        for (DualWriteMapping m : step.mappings()) {
            targetJdbc.update("MERGE INTO \"META\".\"dual_write_map\" " +
                            "(\"table_schema\",\"table_name\",\"new_column\",\"old_column\"," +
                            "\"mode\",\"cast_type\",\"default_value\",\"enabled\") " +
                            "KEY(\"table_schema\",\"table_name\",\"new_column\") " +
                            "VALUES (?,?,?,?,?,?,?,true)",
                    schemaName, step.table(), m.newColumn(), m.oldColumn(),
                    m.mode(), m.castType(), m.defaultValue());
        }
        // 创建触发器（幂等）
        String triggerName = "\"TRG_DW_" + step.table() + "\"";
        targetJdbc.execute("CREATE TRIGGER IF NOT EXISTS " + triggerName
                + " BEFORE INSERT,UPDATE ON " + schemaTable(step, schemaName)
                + " FOR EACH ROW CALL \"" + TRIGGER_CLASS + "\"");
    }

    private boolean tableExists(String tableName) {
        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_NAME)=UPPER(?)",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)",
                Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private String quote(String identifier) {
        return "\"" + identifier + "\"";
    }
}
