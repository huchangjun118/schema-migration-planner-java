package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
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
class BackfillResumeTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String meta = "jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:tgt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        r.add("migration.metadata.jdbc-url", () -> meta);
        r.add("migration.target.jdbc-url", () -> target);
        r.add("migration.backfill.batch-size", () -> "10");
        r.add("migration.backfill.batch-delay-ms", () -> "0");
        r.add("migration.backfill.max-batches", () -> "1"); // 每批执行一次就暂停
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
    void backfill_pausesAndResumes_fromCheckpoint() {
        String schema = "db2";
        // 1. 初始版本
        SchemaDefinition v1 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_item", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false)
                ))
        ));
        versionService.createVersion(schema, v1, "admin", "初始");

        // 2. 建表并写入 25 行存量数据
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        targetJdbc.execute("CREATE TABLE \"" + schema + "\".\"t_item\" (" +
                "\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(255))");
        for (long i = 1; i <= 25; i++) {
            targetJdbc.update("INSERT INTO \"" + schema + "\".\"t_item\" (\"id\",\"name\") VALUES (?,?)",
                    i, "item" + i);
        }

        // 3. 目标结构：新增 count 非空列，默认 0
        SchemaDefinition v2 = new SchemaDefinition(schema, List.of(
                new TableDefinition("t_item", List.of(
                        col("id", ColumnType.BIGINT, false, null, true),
                        col("name", ColumnType.VARCHAR, true, null, false),
                        col("count", ColumnType.INT, false, "0", false)
                ))
        ));

        // 4. 生成计划并执行（每批暂停）
        MigrationPlan plan = planService.createPlan(schema, v2, "admin");
        MigrationPlan afterFirst = planExecutor.applyPlan(plan.planId(), "admin");
        System.out.println("=== 计划状态: " + afterFirst.status() + " ===");
        metadataStore.getStepStates(plan.planId()).forEach(st ->
                System.out.println("  步骤 " + st.stepIndex() + ": " + st.stepType() + " -> " + st.status()));

        // 5. 验证计划暂停在 EXECUTING，回填进度 PAUSED，lastKey=10
        assertEquals(PlanStatus.EXECUTING, afterFirst.status(), "回填暂停时计划应保持 EXECUTING");
        int backfillIdx = findBackfillIndex(afterFirst);
        var progress = metadataStore.getProgress(plan.planId(), backfillIdx);
        assertTrue(progress.isPresent());
        assertEquals("PAUSED", progress.get().status());
        assertEquals(10L, progress.get().lastKey(), "应暂停在 lastKey=10");
        assertEquals(10L, progress.get().processedRows());

        // 6. 续跑：反复执行直到计划完成（每批最多 1 批）
        MigrationPlan current = afterFirst;
        int applies = 1;
        while (current.status() != PlanStatus.COMPLETED && applies < 10) {
            current = planExecutor.applyPlan(plan.planId(), "admin");
            applies++;
        }
        assertEquals(PlanStatus.COMPLETED, current.status(), "续跑后计划应完成");
        System.out.println("=== 共执行 " + applies + " 次 ===");
        progress = metadataStore.getProgress(plan.planId(), backfillIdx);
        assertEquals("COMPLETED", progress.get().status());
        assertEquals(25L, progress.get().lastKey());
        assertEquals(25L, progress.get().processedRows());

        // 8. 验证所有行都已回填
        Integer nullCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".\"t_item\" WHERE \"count\" IS NULL",
                Integer.class);
        assertEquals(0, nullCount, "所有行应已回填");
        Integer total = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"" + schema + "\".\"t_item\"", Integer.class);
        assertEquals(25, total);
    }

    private int findBackfillIndex(MigrationPlan plan) {
        return plan.steps().stream()
                .filter(st -> st.type() == StepType.BACKFILL)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("计划中没有 BACKFILL 步骤"))
                .index();
    }
}
