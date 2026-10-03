package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaVersion;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanPhase;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import com.github.huchangjun118.schemamigrationplannerjava.version.VersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String meta = "jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:tgt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        r.add("migration.metadata.jdbc-url", () -> meta);
        r.add("migration.target.jdbc-url", () -> target);
        r.add("migration.backfill.batch-size", () -> "10");
        r.add("migration.backfill.batch-delay-ms", () -> "10");
    }

    @Autowired VersionService versionService;
    @Autowired PlanService planService;
    @Autowired PlanExecutor planExecutor;
    @Autowired @Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void fullFlow_addColumnWithDefault_backfillsAndDualWrites() {
        String schema = "db1";
        // 1. 创建初始版本（含 t_user 表）
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        long v1Id = versionService.createVersion(schema, v1, "admin", "初始版本");
        assertTrue(v1Id > 0);

        // 2. 在目标库建表并写入存量数据
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_user\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(255))");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_user\" (\"id\",\"name\") VALUES (1,'Alice')");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_user\" (\"id\",\"name\") VALUES (2,'Bob')");

        // 3. 创建目标结构（新增 status 非空列，带默认值）——先不保存为版本
        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("status", ColumnType.INT, false, "0", false)
                ))
        ));

        // 4. 生成计划（当前 v1 -> 目标 v2）
        MigrationPlan plan = planService.createPlan(schema, v2, "admin");
        assertNotNull(plan);
        assertFalse(plan.steps().isEmpty());

        // 5. 先执行 EXPAND 阶段（加可空列 + 安装双写触发器），验证中间态双写
        executeExpandPhaseOnly(plan, schema);

        // 6. 验证双写：旧代码写入（不指定新列）→ 新列自动填默认值
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_user\" (\"id\",\"name\") VALUES (3,'Carol')");
        Integer carolStatus = targetJdbc.queryForObject(
                "SELECT \"status\" FROM \"" + schema + "\".\"t_user\" WHERE \"id\"=3", Integer.class);
        assertEquals(0, carolStatus, "扩容阶段：旧代码写入应通过双写触发器自动填充新列默认值");

        // 7. 执行完整计划（BACKFILL + CONTRACT）
        MigrationPlan result = planExecutor.applyPlan(plan.planId(), "admin");
        assertEquals("COMPLETED", result.status().name());

        // 8. 执行成功后保存 v2 版本（版本记录只追加）
        versionService.createVersion(schema, v2, "admin", "新增 status 列");

        // 9. 验证列已添加且回填完成
        Integer colCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='T_USER' AND UPPER(COLUMN_NAME)='STATUS'",
                Integer.class);
        assertEquals(1, colCount);

        // 10. 验证存量数据已回填
        Integer aliceStatus = targetJdbc.queryForObject(
                "SELECT \"status\" FROM \"" + schema + "\".\"t_user\" WHERE \"id\"=1", Integer.class);
        assertEquals(0, aliceStatus);

        // 11. 版本记录不可变：历史版本仍可查
        SchemaVersion v1Again = versionService.getVersion(v1Id);
        assertNotNull(v1Again);
        assertEquals(2, versionService.listVersions(schema).size());
    }

    /**
     * 只执行计划的 EXPAND 阶段（加可空列 + 安装双写触发器），模拟中间态。
     */
    private void executeExpandPhaseOnly(MigrationPlan plan, String schema) {
        for (var step : plan.steps()) {
            if (step.phase() != PlanPhase.EXPAND) {
                continue;
            }
            if (step.type() == StepType.ADD_COLUMN) {
                targetJdbc.execute("ALTER TABLE \"" + schema + "\".\"" + step.table()
                        + "\" ADD COLUMN IF NOT EXISTS \"" + step.column() + "\" " + step.columnType());
            } else if (step.type() == StepType.INSTALL_DUAL_WRITE) {
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
                for (var m : step.mappings()) {
                    targetJdbc.update("MERGE INTO \"META\".\"dual_write_map\" " +
                                    "(\"table_schema\",\"table_name\",\"new_column\",\"old_column\"," +
                                    "\"mode\",\"cast_type\",\"default_value\",\"enabled\") " +
                                    "KEY(\"table_schema\",\"table_name\",\"new_column\") " +
                                    "VALUES (?,?,?,?,?,?,?,true)",
                            schema, step.table(), m.newColumn(), m.oldColumn(),
                            m.mode(), m.castType(), m.defaultValue());
                }
                targetJdbc.execute("CREATE TRIGGER IF NOT EXISTS \"TRG_DW_" + step.table()
                        + "\" BEFORE INSERT,UPDATE ON \"" + schema + "\".\"" + step.table()
                        + "\" FOR EACH ROW CALL \""
                        + "com.github.huchangjun118.schemamigrationplannerjava.executor.DualWriteTrigger\"");
            }
        }
    }
}
