package com.github.huchangjun118.schemamigrationplannerjava.plan;

/**
 * 变更阶段。所有步骤严格按 EXPAND -> BACKFILL -> CONTRACT 顺序推进，
 * 保证灰度期间旧代码与新代码在任一中间状态下都能正常读写。
 */
public enum PlanPhase {
    /** 扩容阶段：只做新增（建表、加可空列、装双写触发器），不破坏旧代码 */
    EXPAND,
    /** 回填阶段：分批回填存量数据，短锁、可续跑、进度可查 */
    BACKFILL,
    /** 收缩阶段：新代码全量后执行（加非空约束、删列、删表、类型切换收尾） */
    CONTRACT
}
