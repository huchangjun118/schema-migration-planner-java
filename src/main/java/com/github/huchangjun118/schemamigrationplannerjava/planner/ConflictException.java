package com.github.huchangjun118.schemamigrationplannerjava.planner;

import java.util.List;

/**
 * 目标结构自相矛盾或变更无法安全规划时抛出。
 * 携带全部冲突定位信息（表.列 + 原因），调用方应当整体拒绝而不是部分执行。
 */
public class ConflictException extends RuntimeException {

    private final List<String> conflicts;

    public ConflictException(List<String> conflicts) {
        super("结构存在 " + conflicts.size() + " 处冲突:\n  - " + String.join("\n  - ", conflicts));
        this.conflicts = List.copyOf(conflicts);
    }

    public ConflictException(String conflict) {
        this(List.of(conflict));
    }

    public List<String> getConflicts() {
        return conflicts;
    }
}
