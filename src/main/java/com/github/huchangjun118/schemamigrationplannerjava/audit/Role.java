package com.github.huchangjun118.schemamigrationplannerjava.audit;

/**
 * 操作者角色。
 * <ul>
 *   <li>ADMIN：可规划、应用、回滚、管理角色</li>
 *   <li>APPLIER：可规划、应用变更、创建版本</li>
 *   <li>VIEWER：只读</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    APPLIER,
    VIEWER;

    public static Role parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
