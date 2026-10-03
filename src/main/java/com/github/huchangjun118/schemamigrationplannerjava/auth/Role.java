package com.github.huchangjun118.schemamigrationplannerjava.auth;

/**
 * Permission roles for migration operations.
 */
public enum Role {
    /** May generate plans (read-only). */
    PLAN,
    /** May apply/execute migrations. */
    APPLY,
    /** May roll back to historical versions. */
    ROLLBACK,
    /** Full access. */
    ADMIN
}
