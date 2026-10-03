package com.github.huchangjun118.schemamigrationplannerjava.api.dto;

import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;

public record VersionRequest(SchemaDefinition snapshot, String comment) {
}
