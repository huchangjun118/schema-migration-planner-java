package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
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
class FailureRecoveryTest {

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
    @Autowired MetadataStore metadataStore;
    @Autowired @Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void failureAtStep_resumesFromLastCommitted() {
        String schema = "db3";
        // 1. 初始版本
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_order", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("amount", ColumnType.INT, true, null, false)
                ))
        ));
        versionService.createVersion(schema, v1, "admin", "初始");

        // 2. 建表
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_order\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"amount\" INT)");

        // 3. 目标：新增 status 列
        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_order", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("amount", ColumnType.INT, true, null, false),
                        col("status", ColumnType.INT, false, "0", false)
                ))
        ));
        MigrationPlan plan = planService.createPlan(schema, v2, "admin");

        // 4. 人为制造失败：删除目标表，使 ADD_COLUMN 步骤失败
        targetJdbc.execute("DROP TABLE \"" + schema + "\".\"t_order\"");

        // 5. 执行计划 -> 失败
        assertThrows(Exception.class, () -> planExecutor.applyPlan(plan.planId(), "admin"));
        MigrationPlan failed = planService.getPlan(plan.planId());
        assertEquals(PlanStatus.FAILED, failed.status());

        // 6. 验证步骤状态：ADD_COLUMN 失败，后续步骤未执行
        var states = metadataStore.getStepStates(plan.planId());
        System.out.println("=== 失败后步骤状态 ===");
        states.forEach(st -> System.out.println("  步骤 " + st.stepIndex() + ": " + st.stepType() + " -> " + st.status()));
        boolean hasFailed = states.stream().anyMatch(s -> "FAILED".equals(s.status()));
        assertTrue(hasFailed, "应有步骤标记为 FAILED");

        // 7. 恢复：重建表（模拟修复），重新执行
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_order\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"amount\" INT)");
        targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_order\" (\"id\",\"amount\") VALUES (1,100)");

        MigrationPlan recovered = planExecutor.applyPlan(plan.planId(), "admin");
        assertEquals(PlanStatus.COMPLETED, recovered.status(), "恢复后计划应完成");

        // 8. 验证：步骤幂等，ADD_COLUMN 只执行一次（列不重复）
        Integer colCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='T_ORDER' AND UPPER(COLUMN_NAME)='STATUS'",
                Integer.class);
        assertEquals(1, colCount, "列不应重复添加");

        // 9. 验证数据已回填
        Integer status = targetJdbc.queryForObject(
                "SELECT \"status\" FROM \"" + schema + "\".\"t_order\" WHERE \"id\"=1", Integer.class);
        assertEquals(0, status);
    }
}
