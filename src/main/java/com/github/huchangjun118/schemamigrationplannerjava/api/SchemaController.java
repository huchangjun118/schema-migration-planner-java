package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.api.dto.VersionRequest;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaVersion;
import com.github.huchangjun118.schemamigrationplannerjava.version.VersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 结构版本管理 API。
 */
@RestController
@RequestMapping("/api/schemas")
public class SchemaController {

    private final VersionService versionService;

    public SchemaController(VersionService versionService) {
        this.versionService = versionService;
    }

    /** 创建新版本（追加，不覆盖历史） */
    @PostMapping("/{schemaName}/versions")
    public long createVersion(@PathVariable String schemaName,
                              @RequestBody VersionRequest request,
                              @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return versionService.createVersion(schemaName, request.snapshot(), operator, request.comment());
    }

    /** 查询所有版本 */
    @GetMapping("/{schemaName}/versions")
    public List<SchemaVersion> listVersions(@PathVariable String schemaName) {
        return versionService.listVersions(schemaName);
    }

    /** 查询指定版本 */
    @GetMapping("/versions/{versionId}")
    public SchemaVersion getVersion(@PathVariable long versionId) {
        return versionService.getVersion(versionId);
    }
}
