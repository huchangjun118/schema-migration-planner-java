package com.github.huchangjun118.schemamigrationplannerjava.plan;

/** 计划执行状态 */
public enum PlanStatus {
    /** 已生成，待执行 */
    PLANNED,
    /** 含数据丢失步骤，等待人工确认后才能执行 */
    AWAITING_CONFIRMATION,
    /** 执行中 */
    EXECUTING,
    /** 全部步骤完成 */
    COMPLETED,
    /** 执行失败（停在文档化的中间状态，可恢复） */
    FAILED,
    /** 已被回滚 */
    ROLLED_BACK
}
