package com.github.huchangjun118.schemamigrationplannerjava.audit;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计服务。所有应用与回滚都记录操作者、时间与前后状态，只追加、不覆盖。
 */
@Service
public class AuditService {

    private final MetadataStore store;
    private final MigrationProperties props;

    public AuditService(MetadataStore store, MigrationProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 记录一次动作（审计关闭时跳过） */
    public void record(String planId, String action, String operator, String detail,
                       String beforeSnapshot, String afterSnapshot) {
        if (!props.getAudit().isEnabled()) {
            return;
        }
        store.appendAudit(planId, action, operator, detail, beforeSnapshot, afterSnapshot);
    }

    /** 记录越权拒绝 */
    public void recordDenied(String operator, String action, String reasonCode, String message) {
        if (!props.getAudit().isEnabled()) {
            return;
        }
        store.appendAudit(null, "PERMISSION_DENIED", operator,
                action + " | " + reasonCode + " | " + message, null, null);
    }

    /** 查询审计日志（不可变） */
    public List<MetadataStore.AuditRow> list() {
        return store.listAudit();
    }
}
