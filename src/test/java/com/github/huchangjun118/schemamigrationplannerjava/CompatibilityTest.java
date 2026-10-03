package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
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

/**
 * 兼容性中间态测试：验证扩容期间旧代码与新代码都能正常读写。
 */
@SpringBootTest
class CompatibilityTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String meta = "jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:tgt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        r.add("migration.metadata.jdbc-url", () -> meta);
        r.add("migration.target.jdbc-url", () -> target);
    }

    @Autowired VersionService versionService;
    @Autowired PlanService planService;
    @Autowired PlanExecutor planExecutor;
    @Autowired @Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void expandPhase_oldAndNewCode_bothCanReadWrite() {
        String schema = "db9";
        // 1. 初始结构：只有 id, name
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_product", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        versionService.createVersion(schema, v1, "admin", "初始");

        // 2. 建表 + 存量数据
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_product\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(255))");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_product\" (\"id\",\"name\") VALUES (1,'Widget')");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_product\" (\"id\",\"name\") VALUES (2,'Gadget')");

        // 3. 目标：新增 code 非空列，默认 'UNKNOWN'
        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_product", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("code", ColumnType.VARCHAR, false, "'UNKNOWN'", false)
                ))
        ));
        MigrationPlan plan = planService.createPlan(schema, v2, "admin");

        // 4. 只执行到 EXPAND 阶段（加列 + 双写），不执行 BACKFILL/CONTRACT
        // 我们通过只执行前几步来模拟中间态
        // 先执行整个计划（它会完成所有阶段），然后验证中间态语义
        // 为了测试中间态，我们手动执行 EXPAND 步骤
        executeExpandOnly(plan, schema);

        // 5. 验证中间态：旧代码写入（不指定 code）→ 双写触发器自动填默认值
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_product\" (\"id\",\"name\") VALUES (3,'OldCodeItem')");
        String codeFromOldWrite = targetJdbc.queryForObject(
                "SELECT \"code\" FROM \"" + schema + "\".\"t_product\" WHERE \"id\"=3", String.class);
        assertEquals("UNKNOWN", codeFromOldWrite, "旧代码写入应通过双写触发器自动填充新列");

        // 6. 验证中间态：新代码写入（指定 code）→ 正常
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_product\" (\"id\",\"name\",\"code\") VALUES (4,'NewCodeItem','NEW-001')");
        String codeFromNewWrite = targetJdbc.queryForObject(
                "SELECT \"code\" FROM \"" + schema + "\".\"t_product\" WHERE \"id\"=4", String.class);
        assertEquals("NEW-001", codeFromNewWrite, "新代码写入应正常");

        // 7. 验证中间态：旧代码读取（不查 code）→ 正常
        String nameFromOldRead = targetJdbc.queryForObject(
                "SELECT \"name\" FROM \"" + schema + "\".\"t_product\" WHERE \"id\"=1", String.class);
        assertEquals("Widget", nameFromOldRead, "旧代码读取应正常");

        // 8. 验证中间态：新代码读取（查 code）→ 存量行 code 为 NULL（未回填）
        String codeFromExistingRow = targetJdbc.queryForObject(
                "SELECT \"code\" FROM \"" + schema + "\".\"t_product\" WHERE \"id\"=1", String.class);
        assertNull(codeFromExistingRow, "回填前存量行的新列应为 NULL（确定的中间态语义）");

        // 9. 继续执行 BACKFILL + CONTRACT
        MigrationPlan fullResult = planExecutor.applyPlan(plan.planId(), "admin");
        // 由于 EXPAND 步骤已手动执行，重新执行会幂等跳过，然后执行 BACKFILL/CONTRACT
        // 但我们手动执行的步骤没有记录到 step_execution，所以会重新执行
        // 这里直接验证最终状态
        assertEquals(PlanStatus.COMPLETED, fullResult.status());

        // 10. 验证最终状态：所有行 code 非空
        Integer nullCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".\"t_product\" WHERE \"code\" IS NULL",
                Integer.class);
        assertEquals(0, nullCount, "回填后所有行应非空");
    }

    /**
     * 只执行计划的 EXPAND 阶段步骤（加列 + 双写），模拟中间态。
     */
    private void executeExpandOnly(MigrationPlan plan, String schema) {
        // 手动执行 EXPAND 步骤：加可空列 + 安装双写触发器
        for (var step : plan.steps()) {
            if (step.phase() != com.github.huchangjun118.schemamigrationplannerjava.plan.PlanPhase.EXPAND) {
                continue;
            }
            switch (step.type()) {
                case ADD_COLUMN -> {
                    targetJdbc.execute("ALTER TABLE \"" + schema + "\".\"" + step.table()
                            + "\" ADD COLUMN IF NOT EXISTS \"" + step.column() + "\" " + step.columnType());
                }
                case INSTALL_DUAL_WRITE -> {
                    // 确保 META.dual_write_map 存在
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
                default -> {
                    // 其他 EXPAND 步骤（MODIFY_DEFAULT 等）暂不处理
                }
            }
        }
    }
}
