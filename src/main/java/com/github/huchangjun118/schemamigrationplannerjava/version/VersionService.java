package com.github.huchangjun118.schemamigrationplannerjava.version;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaVersion;
import com.github.huchangjun118.schemamigrationplannerjava.planner.SchemaValidator;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构版本服务。版本记录只追加、不可改写，全程可追溯。
 */
@Service
public class VersionService {

    private final MetadataStore store;
    private final JdbcTemplate targetJdbc;
    private final SchemaValidator validator;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public VersionService(MetadataStore store,
                          @Qualifier("targetJdbcTemplate") JdbcTemplate targetJdbc,
                          SchemaValidator validator,
                          PermissionService permissionService,
                          AuditService auditService) {
        this.store = store;
        this.targetJdbc = targetJdbc;
        this.validator = validator;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /**
     * 创建一个新结构版本（追加，不覆盖历史）。
     *
     * @return 新版本号
     */
    public long createVersion(String schemaName, SchemaDefinition snapshot, String operator, String comment) {
        permissionService.require(operator, "VERSION_CREATE");
        validator.requireValid(snapshot);
        // 确保目标库中存在对应 schema
        targetJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        long versionId = store.insertVersion(schemaName, snapshot, operator, comment);
        auditService.record(null, "VERSION_CREATE", operator,
                "创建结构版本 v" + versionId + "（" + comment + "）", null, null);
        return versionId;
    }

    public SchemaVersion getLatest(String schemaName) {
        return store.getLatestVersion(schemaName).orElse(null);
    }

    public SchemaVersion getVersion(long versionId) {
        return store.getVersion(versionId).orElse(null);
    }

    public List<SchemaVersion> listVersions(String schemaName) {
        return store.listVersions(schemaName);
    }
}
