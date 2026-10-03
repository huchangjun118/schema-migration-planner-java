package com.github.huchangjun118.schemamigrationplannerjava.model;

/**
 * 结构版本快照。版本记录只追加、不可改写。
 */
public record SchemaVersion(
        long versionId,
        String schemaName,
        SchemaDefinition snapshot,
        String createdAt,
        String operator,
        String comment
) {
}
