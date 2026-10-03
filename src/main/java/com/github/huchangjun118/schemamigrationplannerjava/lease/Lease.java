package com.github.huchangjun118.schemamigrationplannerjava.lease;

/**
 * A held lease: proves the owner is the only executor for one table.
 */
public final class Lease implements AutoCloseable {
    private final String tableName;
    private final String owner;
    private final long fencingToken;
    private final long expiresAtMillis;
    private final LeaseManager manager;

    public Lease(String tableName, String owner, long fencingToken, long expiresAtMillis, LeaseManager manager) {
        this.tableName = tableName;
        this.owner = owner;
        this.fencingToken = fencingToken;
        this.expiresAtMillis = expiresAtMillis;
        this.manager = manager;
    }

    public String getTableName() { return tableName; }
    public String getOwner() { return owner; }
    public long getFencingToken() { return fencingToken; }
    public long getExpiresAtMillis() { return expiresAtMillis; }

    public boolean renew() { return manager.renew(this); }

    @Override
    public void close() {
        manager.release(this);
    }
}
