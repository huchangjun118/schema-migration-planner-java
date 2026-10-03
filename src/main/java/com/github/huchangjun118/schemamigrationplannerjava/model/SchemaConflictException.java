package com.github.huchangjun118.schemamigrationplannerjava.model;

import java.util.List;

/**
 * Raised when the target schema is self-contradictory; carries every located conflict.
 */
public class SchemaConflictException extends RuntimeException {
    private final List<String> conflicts;

    public SchemaConflictException(List<String> conflicts) {
        super("schema conflicts: " + String.join("; ", conflicts));
        this.conflicts = conflicts;
    }

    public List<String> getConflicts() { return conflicts; }
}
