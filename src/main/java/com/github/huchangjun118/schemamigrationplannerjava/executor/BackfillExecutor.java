package com.github.huchangjun118.schemamigrationplannerjava.executor;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 大表数据回填执行器。
 *
 * <p>按主键范围分批推进，单批规模与节奏可配置；每批提交后更新进度，
 * 失败后从最近已提交位置继续（断点续跑）。批间休眠让出在线读写窗口，
 * 不长时间独占锁。回填语句带 IS NULL 条件，幂等可重复执行。
 */
@Component
public class BackfillExecutor {

    private final JdbcTemplate targetJdbc;
    private final MetadataStore store;
    private final MigrationProperties props;
    private final LeaseManager leaseManager;

    public BackfillExecutor(@Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc,
                            MetadataStore store,
                            MigrationProperties props,
                            LeaseManager leaseManager) {
        this.targetJdbc = targetJdbc;
        this.store = store;
        this.props = props;
        this.leaseManager = leaseManager;
    }

    /**
     * 执行回填（可断点续跑）。
     *
     * @param planId   计划 ID
     * @param step     回填步骤
     * @param leaseKey 租约键（用于续期/fencing）
     * @param token    租约 token
     */
    public boolean execute(String planId, PlanStep step, String schemaName, String leaseKey, String token) {
        String schemaTable = quote(schemaName) + "." + quote(step.table());
        String targetCol = quote(step.column());
        String transform = step.transform();

        // 读取断点
        Optional<MetadataStore.BackfillProgress> existing = store.getProgress(planId, step.index());
        long lastKey = existing.map(MetadataStore.BackfillProgress::lastKey).orElse(0L);
        long processed = existing.map(MetadataStore.BackfillProgress::processedRows).orElse(0L);

        store.upsertProgress(planId, step.index(), step.table(), step.column(),
                lastKey, processed, "RUNNING");

        int batchSize = props.getBackfill().getBatchSize();
        long delayMs = props.getBackfill().getBatchDelayMs();
        int maxBatches = props.getBackfill().getMaxBatches();
        int batches = 0;

        while (true) {
            // fencing：租约失效则立即停止
            if (!leaseManager.isHeld(leaseKey, token)) {
                throw new LeaseLostException("租约已失效，回填停止在断点 lastKey=" + lastKey);
            }

            // 取下一批主键范围
            List<Long> ids = targetJdbc.queryForList(
                    "SELECT " + quote("id") + " FROM " + schemaTable + " WHERE " + quote("id")
                            + " > ? AND " + targetCol + " IS NULL ORDER BY " + quote("id") + " LIMIT ?",
                    Long.class, lastKey, batchSize);
            if (ids.isEmpty()) {
                break;
            }
            long batchMax = ids.get(ids.size() - 1);

            // 分批更新：只更新本批范围内尚未填充的行（幂等）
            int updated = targetJdbc.update(
                    "UPDATE " + schemaTable + " SET " + targetCol + " = " + transform
                            + " WHERE " + quote("id") + " > ? AND " + quote("id") + " <= ?"
                            + " AND " + targetCol + " IS NULL",
                    lastKey, batchMax);

            lastKey = batchMax;
            processed += updated;
            store.upsertProgress(planId, step.index(), step.table(), step.column(),
                    lastKey, processed, "RUNNING");

            // 租约续期
            leaseManager.renew(leaseKey, token);

            batches++;
            if (maxBatches > 0 && batches >= maxBatches) {
                // 达到单次执行批数上限，停在断点（可续跑）
                store.upsertProgress(planId, step.index(), step.table(), step.column(),
                        lastKey, processed, "PAUSED");
                return false;
            }

            // 批间休眠，让出在线读写窗口
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LeaseLostException("回填被中断", e);
                }
            }
        }

        store.upsertProgress(planId, step.index(), step.table(), step.column(),
                lastKey, processed, "COMPLETED");
        return true;
    }

    private String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** 租约丢失异常（fencing 失败时抛出，计划停在可恢复中间状态） */
    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException(String message) {
            super(message);
        }
        public LeaseLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
