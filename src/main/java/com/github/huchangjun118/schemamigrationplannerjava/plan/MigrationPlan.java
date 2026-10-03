package com.github.huchangjun118.schemamigrationplannerjava.plan;

import java.util.List;

/**
 * 一次结构变更的有序计划。相同输入（当前结构 + 目标结构）生成的计划逐条一致。
 */
public record MigrationPlan(
        String planId,
        String schemaName,
        long fromVersion,
        long toVersion,
        PlanKind kind,
        List<PlanStep> steps,
        PlanStatus status,
        String createdAt,
        String operator,
        String failureReason
) {
    public MigrationPlan {
        steps = List.copyOf(steps);
    }

    /** 是否包含数据丢失步骤（回滚时需要显式确认） */
    public boolean hasDataLoss() {
        return steps.stream().anyMatch(s -> s.dataLoss());
    }

    /** 按阶段统计步数 */
    public long countPhase(PlanPhase phase) {
        return steps.stream().filter(s -> s.phase() == phase).count();
    }
}
