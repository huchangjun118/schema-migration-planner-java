package com.github.huchangjun118.schemamigrationplannerjava.auth;

/**
 * Authorization failure with a distinguishable reason.
 */
public class AccessDeniedException extends RuntimeException {
    public enum Reason { UNKNOWN_USER, MISSING_ROLE }

    private final Reason reason;
    private final String actor;
    private final Role requiredRole;

    public AccessDeniedException(Reason reason, String actor, Role requiredRole) {
        super(reason + ": actor=" + actor + ", required=" + requiredRole);
        this.reason = reason;
        this.actor = actor;
        this.requiredRole = requiredRole;
    }

    public Reason getReason() { return reason; }
    public String getActor() { return actor; }
    public Role getRequiredRole() { return requiredRole; }
}
