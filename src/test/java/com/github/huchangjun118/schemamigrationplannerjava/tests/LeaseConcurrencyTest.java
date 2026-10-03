package com.github.huchangjun118.schemamigrationplannerjava.tests;

import com.github.huchangjun118.schemamigrationplannerjava.lease.Lease;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseLostException;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same-table migrations are serialized by leases; different tables proceed
 * concurrently. Expired leases are taken over with a higher fencing token and
 * the stale owner is rejected before committing anything further.
 */
@SpringBootTest
class LeaseConcurrencyTest {
    @Autowired LeaseManager leaseManager;
    @Autowired MetaStore metaStore;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameTableSerializesAndExpiredLeaseIsSafelyTakenOver() {
        Lease a = leaseManager.acquire("lease_t1", "executor-A");
        // a second executor on the SAME table is refused while the lease is held
        assertThrows(LeaseLostException.class, () -> leaseManager.acquire("lease_t1", "executor-B"));
        // a different table is unaffected: migrations on different tables run concurrently
        Lease other = leaseManager.acquire("lease_t2", "executor-B");
        other.close();

        // same owner re-acquiring (e.g. after restart) keeps its fencing token
        Lease a2 = leaseManager.acquire("lease_t1", "executor-A");
        assertEquals(a.getFencingToken(), a2.getFencingToken());

        // simulate executor-A dying without releasing: force the lease to expire
        jdbc.update("UPDATE sm_lease SET expires_at=? WHERE table_name=?", 
                System.currentTimeMillis() - 1000, "lease_t1");
        Lease b = leaseManager.acquire("lease_t1", "executor-B");
        assertTrue(b.getFencingToken() > a.getFencingToken(), "takeover must bump the fencing token");

        // the stale owner is now rejected by the fencing check: no double apply
        assertThrows(LeaseLostException.class, () -> leaseManager.checkFencing(a));
        // the new owner passes
        assertDoesNotThrow(() -> leaseManager.checkFencing(b));
        b.close();
    }

    @Test
    void releasedLeaseCanBeAcquiredByAnotherOwner() {
        Lease a = leaseManager.acquire("lease_t3", "executor-A");
        a.close();
        Lease b = leaseManager.acquire("lease_t3", "executor-B");
        assertDoesNotThrow(() -> leaseManager.checkFencing(b));
        b.close();
    }
}
