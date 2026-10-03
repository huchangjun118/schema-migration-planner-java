package com.github.huchangjun118.schemamigrationplannerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Migration engine configuration.
 */
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {
    /** Lease time-to-live in milliseconds. */
    private long leaseTtlMillis = 30_000;
    /** Lease renew interval in milliseconds. */
    private long leaseRenewMillis = 10_000;
    /** actor -> comma separated roles, e.g. migration.auth.users.alice=ADMIN */
    private Map<String, String> users = new HashMap<>();
    /** Backfill pacing. */
    private Backfill backfill = new Backfill();

    public static class Backfill {
        private int batchSize = 500;
        private long batchIntervalMillis = 50;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getBatchIntervalMillis() { return batchIntervalMillis; }
        public void setBatchIntervalMillis(long batchIntervalMillis) { this.batchIntervalMillis = batchIntervalMillis; }
    }

    public long getLeaseTtlMillis() { return leaseTtlMillis; }
    public void setLeaseTtlMillis(long leaseTtlMillis) { this.leaseTtlMillis = leaseTtlMillis; }
    public long getLeaseRenewMillis() { return leaseRenewMillis; }
    public void setLeaseRenewMillis(long leaseRenewMillis) { this.leaseRenewMillis = leaseRenewMillis; }
    public Map<String, String> getUsers() { return users; }
    public void setUsers(Map<String, String> users) { this.users = users; }
    public Backfill getBackfill() { return backfill; }
    public void setBackfill(Backfill backfill) { this.backfill = backfill; }
}
