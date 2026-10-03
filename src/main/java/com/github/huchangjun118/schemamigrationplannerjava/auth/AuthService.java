package com.github.huchangjun118.schemamigrationplannerjava.auth;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local, config-backed authorization. Denials carry a machine-readable reason
 * so callers can distinguish "unknown user" from "missing role".
 */
@Service
public class AuthService {
    private final MigrationProperties properties;

    public AuthService(MigrationProperties properties) {
        this.properties = properties;
    }

    /** Require the actor to hold the role (ADMIN implies all). */
    public void require(String actor, Role role) {
        Map<String, String> users = properties.getUsers();
        if (actor == null || !users.containsKey(actor)) {
            throw new AccessDeniedException(AccessDeniedException.Reason.UNKNOWN_USER, actor, role);
        }
        Set<String> roles = new HashSet<>(Arrays.asList(users.get(actor).split(",")));
        boolean ok = roles.stream().map(String::trim).anyMatch(r ->
                r.equalsIgnoreCase(Role.ADMIN.name()) || r.equalsIgnoreCase(role.name()));
        if (!ok) {
            throw new AccessDeniedException(AccessDeniedException.Reason.MISSING_ROLE, actor, role);
        }
    }
}
