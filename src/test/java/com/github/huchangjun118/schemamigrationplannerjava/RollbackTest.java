package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanKind;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.RollbackService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
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
class RollbackTest {

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
    @Autowired RollbackService rollbackService;
    @Autowired @Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void rollbackToPreviousVersion_generatesPlanAndRestores() {
        String schema = "db7";
        // 1. v1：初始结构
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        versionService.createVersion(schema, v1, "admin", "初始版本");

        // 2. 建表 + 存量数据
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_user\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(255))");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_user\" (\"id\",\"name\") VALUES (1,'Alice')");

        // 3. v2：新增 status 列
        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_user", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("status", ColumnType.INT, false, "0", false)
                ))
        ));
        MigrationPlan forward = planService.createPlan(schema, v2, "admin");
        planExecutor.applyPlan(forward.planId(), "admin");
        versionService.createVersion(schema, v2, "admin", "新增 status");

        // 验证 v2 结构
        Integer statusCol = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='T_USER' AND UPPER(COLUMN_NAME)='STATUS'",
                Integer.class);
        assertEquals(1, statusCol);

        // 4. 回滚到 v1（生成 ROLLBACK 类型计划）
        long v1Id = versionService.listVersions(schema).get(0).versionId();
        MigrationPlan rollback = rollbackService.rollbackTo(schema, v1Id, "admin");
        assertEquals(PlanKind.ROLLBACK, rollback.kind());
        System.out.println("=== 回滚计划步骤 ===");
        rollback.steps().forEach(st -> System.out.println("  " + st.index() + ": " + st.type()
                + " " + st.table() + "." + st.column() + " dataLoss=" + st.dataLoss()));

        // 回滚计划应包含 DROP_COLUMN 步骤（数据丢失）
        boolean hasDropColumn = rollback.steps().stream()
                .anyMatch(st -> st.type() == StepType.DROP_COLUMN);
        assertTrue(hasDropColumn, "回滚计划应包含删列步骤");
        // 含数据丢失的回滚计划需要确认
        assertEquals(PlanStatus.AWAITING_CONFIRMATION, rollback.status(),
                "含数据丢失的回滚计划应等待确认");

        // 5. 确认后执行回滚
        MigrationPlan confirmed = planExecutor.confirmPlan(rollback.planId(), "admin");
        assertEquals(PlanStatus.PLANNED, confirmed.status());

        MigrationPlan rollbackResult = planExecutor.applyPlan(rollback.planId(), "admin");
        assertEquals(PlanStatus.COMPLETED, rollbackResult.status());

        // 6. 验证结构恢复到 v1（status 列已删除）
        Integer statusAfterRollback = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='T_USER' AND UPPER(COLUMN_NAME)='STATUS'",
                Integer.class);
        assertEquals(0, statusAfterRollback, "回滚后 status 列应删除");

        // 7. 验证版本链：v1, v2 都可查（不可改写）
        assertEquals(2, versionService.listVersions(schema).size());
    }
}
