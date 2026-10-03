package com.github.huchangjun118.schemamigrationplannerjava.lease;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Per-table leases with fencing tokens. Expired leases can be taken over safely:
 * a stale owner is rejected by the fencing check before committing any step, so
 * a takeover never causes a step to be applied twice.
 */
@Component
public class LeaseManager {
    private final MetaStore metaStore;
    private final MigrationProperties properties;

    public LeaseManager(MetaStore metaStore, MigrationProperties properties) {
        this.metaStore = metaStore;
        this.properties = properties;
    }

    /** Acquire (or take over an expired) lease for the table. */
    public Lease acquire(String tableName, String owner) {
        long now = System.currentTimeMillis();
        long expires = now + properties.getLeaseTtlMillis();
        Optional<Map<String, Object>> existing = metaStore.findLease(tableName);
        if (existing.isEmpty()) {
            long token = metaStore.nextFencingToken();
            if (metaStore.tryInsertLease(tableName, owner, token, expires)) {
                return new Lease(tableName, owner, token, expires, this);
            }
            existing = metaStore.findLease(tableName); // lost the race; re-read
        }
        Map<String, Object> row = existing.orElseThrow();
        String currentOwner = (String) row.get("OWNER");
        long currentToken = ((Number) row.get("FENCING_TOKEN")).longValue();
        long currentExpiry = ((Number) row.get("EXPIRES_AT")).longValue();
        if (currentOwner.equals(owner)) {
            // same owner re-acquires (e.g. after restart): keep fencing token, extend
            if (metaStore.renewLease(tableName, owner, currentToken, expires)) {
                return new Lease(tableName, owner, currentToken, expires, this);
            }
        }
        if (currentExpiry < now) {
            long token = metaStore.nextFencingToken();
            if (metaStore.takeoverExpiredLease(tableName, owner, token, expires, now)) {
                return new Lease(tableName, owner, token, expires, this);
            }
        }
        throw new LeaseLostException("table '" + tableName + "' is leased by '" + currentOwner
                + "' until " + currentExpiry + " (not expired); refusing to run concurrently");
    }

    /** Throw when the lease is no longer valid (lost or taken over). */
    public void checkFencing(Lease lease) {
        Map<String, Object> row = metaStore.findLease(lease.getTableName())
                .orElseThrow(() -> new LeaseLostException("lease for '" + lease.getTableName() + "' is gone"));
        String owner = (String) row.get("OWNER");
        long token = ((Number) row.get("FENCING_TOKEN")).longValue();
        long expiry = ((Number) row.get("EXPIRES_AT")).longValue();
        if (!owner.equals(lease.getOwner()) || token != lease.getFencingToken()) {
            throw new LeaseLostException("lease for '" + lease.getTableName() + "' was taken over by '"
                    + owner + "' (token " + token + "); this executor must stop");
        }
        if (expiry < System.currentTimeMillis()) {
            throw new LeaseLostException("lease for '" + lease.getTableName() + "' expired; "
                    + "another executor may take over");
        }
    }

    boolean renew(Lease lease) {
        long expires = System.currentTimeMillis() + properties.getLeaseTtlMillis();
        return metaStore.renewLease(lease.getTableName(), lease.getOwner(), lease.getFencingToken(), expires);
    }

    void release(Lease lease) {
        metaStore.releaseLease(lease.getTableName(), lease.getOwner(), lease.getFencingToken());
    }
}
