package com.github.huchangjun118.schemamigrationplannerjava.audit;

import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Append-only audit log. Records actor, time, action and before/after schema
 * for every apply and rollback. No update or delete path exists.
 */
@Service
public class AuditService {
    private final MetaStore metaStore;

    public AuditService(MetaStore metaStore) {
        this.metaStore = metaStore;
    }

    public void record(String actor, String action, String tableName, String migrationId,
                       String beforeSchema, String afterSchema, String detail) {
        metaStore.appendAudit(actor, action, tableName, migrationId, beforeSchema, afterSchema, detail);
    }

    public List<Map<String, Object>> list() {
        return metaStore.listAudit();
    }
}
