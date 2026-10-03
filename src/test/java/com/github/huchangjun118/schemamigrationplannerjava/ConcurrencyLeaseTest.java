package com.github.huchangjun118.schemamigrationplannerjava;

import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ConcurrencyLeaseTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String meta = "jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:tgt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        r.add("migration.metadata.jdbc-url", () -> meta);
        r.add("migration.target.jdbc-url", () -> target);
        r.add("migration.lease.ttl-ms", () -> "500");
        r.add("migration.lease.max-wait-ms", () -> "200");
    }

    @Autowired LeaseManager leaseManager;
    @Autowired VersionService versionService;
    @Autowired PlanService planService;
    @Autowired PlanExecutor planExecutor;

    private ColumnDefinition col(String name, ColumnType type, boolean nullable, String def, boolean pk) {
        return new ColumnDefinition(name, type, null, nullable, def, pk);
    }

    @Test
    void lease_serializesSameSchema() throws Exception {
        String key = LeaseManager.schemaLeaseKey("db4");
        // 两个线程同时尝试获取同一租约
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    LeaseManager.LeaseResult r = leaseManager.tryAcquire(key, "executor" + idx);
                    if (r.acquired()) {
                        acquired.incrementAndGet();
                        // 持有一段时间，让另一个线程尝试
                        Thread.sleep(100);
                        leaseManager.release(key, r.token());
                    } else {
                        busy.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        System.out.println("=== 并发结果: acquired=" + acquired.get() + " busy=" + busy.get() + " ===");
        assertEquals(1, acquired.get(), "恰好一个执行者获取租约");
        assertEquals(1, busy.get(), "另一个执行者因租约占用被拒绝");
    }

    @Test
    void lease_expiry_allowsSafeTakeover() throws Exception {
        String key = LeaseManager.schemaLeaseKey("db5");
        // 1. 获取租约
        LeaseManager.LeaseResult r1 = leaseManager.tryAcquire(key, "executor1");
        assertTrue(r1.acquired());
        String token1 = r1.token();
        assertTrue(leaseManager.isHeld(key, token1));

        // 2. 等待租约过期（TTL=500ms）
        Thread.sleep(600);

        // 3. 接管者获取租约
        LeaseManager.LeaseResult r2 = leaseManager.tryAcquire(key, "executor2");
        assertTrue(r2.acquired(), "租约过期后应可被接管");
        assertNotEquals(token1, r2.token());

        // 4. 旧 token 失效（fencing）
        assertFalse(leaseManager.isHeld(key, token1), "旧执行者的 fencing 应失效");
        assertTrue(leaseManager.isHeld(key, r2.token()), "新执行者应持有租约");

        // 5. 释放租约
        assertTrue(leaseManager.release(key, r2.token()));
        assertFalse(leaseManager.isHeld(key, r2.token()));
    }

    @Test
    void differentSchemas_canExecuteConcurrently() throws Exception {
        // 不同 schema 的租约互不阻塞
        String key1 = LeaseManager.schemaLeaseKey("db6a");
        String key2 = LeaseManager.schemaLeaseKey("db6b");
        LeaseManager.LeaseResult r1 = leaseManager.tryAcquire(key1, "executor1");
        LeaseManager.LeaseResult r2 = leaseManager.tryAcquire(key2, "executor2");
        assertTrue(r1.acquired());
        assertTrue(r2.acquired());
        leaseManager.release(key1, r1.token());
        leaseManager.release(key2, r2.token());
    }
}
