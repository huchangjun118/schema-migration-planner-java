package com.github.huchangjun118.schemamigrationplannerjava.version;

import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import com.github.huchangjun118.schemamigrationplannerjava.store.SchemaCodec;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only schema version history. Versions are never rewritten; every
 * migration and rollback appends a new version so the full trail is traceable.
 */
@Service
public class SchemaVersionService {
    private final MetaStore metaStore;
    private final SchemaCodec codec;

    public SchemaVersionService(MetaStore metaStore, SchemaCodec codec) {
        this.metaStore = metaStore;
        this.codec = codec;
    }

    public synchronized void recordVersion(String tableName, TableSchema schema, String migrationId, String actor) {
        long next = metaStore.latestSchemaVersion(tableName)
                .map(m -> ((Number) m.get("VERSION")).longValue() + 1)
                .orElse(1L);
        metaStore.appendSchemaVersion(tableName, next, codec.schemaToJson(schema), migrationId, actor);
    }

    public List<Map<String, Object>> history(String tableName) {
        return metaStore.listSchemaVersions(tableName);
    }

    public Optional<TableSchema> versionAt(String tableName, long version) {
        return metaStore.listSchemaVersions(tableName).stream()
                .filter(m -> ((Number) m.get("VERSION")).longValue() == version)
                .findFirst()
                .map(m -> codec.schemaFromJson((String) m.get("SCHEMA_JSON")));
    }

    public Optional<TableSchema> latest(String tableName) {
        return metaStore.latestSchemaVersion(tableName)
                .map(m -> codec.schemaFromJson((String) m.get("SCHEMA_JSON")));
    }
}
