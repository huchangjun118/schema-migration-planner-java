package com.github.huchangjun118.schemamigrationplannerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迁移系统配置项。
 *
 * <ul>
 *   <li>backfill.batch-size：单批回填处理的行数上限</li>
 *   <li>backfill.batch-delay-ms：批与批之间的休眠毫秒数，让出在线读写窗口</li>
 *   <li>backfill.max-batches：单次执行最多回填多少批，0 表示不限（用于节奏控制/测试）</li>
 *   <li>lease.ttl-ms：租约有效期，超时后可被安全接管</li>
 *   <li>lease.renew-interval-ms：执行期间租约续期间隔</li>
 *   <li>lease.max-wait-ms：获取租约的最长等待时间，超时返回租约占用错误</li>
 *   <li>audit.enabled：是否开启审计记录</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

    private Backfill backfill = new Backfill();
    private Lease lease = new Lease();
    private Audit audit = new Audit();

    public Backfill getBackfill() { return backfill; }
    public void setBackfill(Backfill backfill) { this.backfill = backfill; }
    public Lease getLease() { return lease; }
    public void setLease(Lease lease) { this.lease = lease; }
    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }

    public static class Backfill {
        /** 单批回填行数 */
        private int batchSize = 1000;
        /** 批间休眠毫秒，避免长时间占用 */
        private long batchDelayMs = 50;
        /** 单次执行最大批数，0 不限 */
        private int maxBatches = 0;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getBatchDelayMs() { return batchDelayMs; }
        public void setBatchDelayMs(long batchDelayMs) { this.batchDelayMs = batchDelayMs; }
        public int getMaxBatches() { return maxBatches; }
        public void setMaxBatches(int maxBatches) { this.maxBatches = maxBatches; }
    }

    public static class Lease {
        /** 租约有效期（毫秒） */
        private long ttlMs = 30_000;
        /** 续期间隔（毫秒） */
        private long renewIntervalMs = 5_000;
        /** 获取租约最长等待（毫秒） */
        private long maxWaitMs = 0;

        public long getTtlMs() { return ttlMs; }
        public void setTtlMs(long ttlMs) { this.ttlMs = ttlMs; }
        public long getRenewIntervalMs() { return renewIntervalMs; }
        public void setRenewIntervalMs(long renewIntervalMs) { this.renewIntervalMs = renewIntervalMs; }
        public long getMaxWaitMs() { return maxWaitMs; }
        public void setMaxWaitMs(long maxWaitMs) { this.maxWaitMs = maxWaitMs; }
    }

    public static class Audit {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
