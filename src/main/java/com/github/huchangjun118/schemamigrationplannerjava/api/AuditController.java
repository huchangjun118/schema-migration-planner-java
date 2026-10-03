package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计日志 API。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 查询审计日志（只追加，不可变） */
    @GetMapping
    public List<MetadataStore.AuditRow> list() {
        return auditService.list();
    }
}
