package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseLostException;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.service.MigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migrations on different tables execute concurrently; two executors applying
 * migrations on the same table are serialized by the lease.
 */
@SpringBootTest
class MultiTableConcurrencyTest {
    @Autowired MigrationService migrationService;
    @Autowired JdbcTemplate jdbc;

    private static ColumnDef col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDef(name, type, nullable, def, pk);
    }

    private TableSchema schema(String table, boolean withExtra) {
        var cols = new java.util.ArrayList<>(List.of(col("id", ColumnType.BIGINT, false, null, true)));
        if (withExtra) {
            cols.add(col("x" + table.replaceAll("[^0-9]", ""), ColumnType.INT, true, "1", false));
        }
        return new TableSchema(table, cols);
    }

    @BeforeEach
    void setUp() {
        for (String t : List.of("mt_a", "mt_b")) {
            jdbc.execute("DROP TABLE IF EXISTS \"" + t + "\"");
            jdbc.execute("CREATE TABLE \"" + t + "\" (\"id\" BIGINT PRIMARY KEY)");
            jdbc.update("INSERT INTO \"" + t + "\"(\"id\") VALUES (1)");
            jdbc.update("DELETE FROM sm_schema_version WHERE table_name=?", t);
        }
    }

    @Test
    void differentTablesMigrateConcurrently() throws Exception {
        MigrationPlan pa = migrationService.plan("alice", schema("mt_a", false), schema("mt_a", true));
        MigrationPlan pb = migrationService.plan("alice", schema("mt_b", false), schema("mt_b", true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String, Object>> runA = () -> migrationService.apply("alice", pa.getPlanId());
            Callable<Map<String, Object>> runB = () -> migrationService.apply("alice", pb.getPlanId());
            Future<Map<String, Object>> fa = pool.submit(runA);
            Future<Map<String, Object>> fb = pool.submit(runB);
            assertEquals("DONE", fa.get().get("status"));
            assertEquals("DONE", fb.get().get("status"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameTableSecondExecutorIsRejected() throws Exception {
        MigrationPlan p1 = migrationService.plan("alice", schema("mt_a", false), schema("mt_a", true));
        // hold the table lease directly, then a concurrent apply must be refused
        try (var lease = migrationServiceLease("mt_a")) {
            MigrationPlan p2 = migrationService.plan("bob", schema("mt_a", false), schema("mt_a", true));
            ExecutionException ex = assertThrows(ExecutionException.class, () -> {
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    pool.submit(() -> migrationService.apply("bob", p2.getPlanId())).get();
                } finally {
                    pool.shutdownNow();
                }
            });
            assertInstanceOf(LeaseLostException.class, ex.getCause());
        }
        // after the lease is released the migration applies cleanly
        assertEquals("DONE", migrationService.apply("alice", p1.getPlanId()).get("status"));
    }

    private com.github.huchangjun118.schemamigrationplannerjava.lease.Lease migrationServiceLease(String table) {
        return leaseManager().acquire(table, "test-holder");
    }

    @Autowired
    private com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager leaseManager;

    private com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager leaseManager() {
        return leaseManager;
    }
}
