package com.github.huchangjun118.schemamigrationplannerjava.plan;

/**
 * 原子步骤类型。每一步都可独立执行、可解释、幂等。
 */
public enum StepType {
    /** 建新表（EXPAND） */
    CREATE_TABLE(PlanPhase.EXPAND),
    /** 删表（CONTRACT，数据丢失） */
    DROP_TABLE(PlanPhase.CONTRACT),
    /** 加列（EXPAND，一律先加可空列） */
    ADD_COLUMN(PlanPhase.EXPAND),
    /** 删列（CONTRACT，数据丢失） */
    DROP_COLUMN(PlanPhase.CONTRACT),
    /** 修改默认值（EXPAND，在线 DDL） */
    MODIFY_DEFAULT(PlanPhase.EXPAND),
    /** 修改可空性（EXPAND，仅用于 NOT NULL -> NULL 的放松） */
    MODIFY_NULLABLE(PlanPhase.EXPAND),
    /** 安装双写触发器（EXPAND） */
    INSTALL_DUAL_WRITE(PlanPhase.EXPAND),
    /** 分批回填存量数据（BACKFILL） */
    BACKFILL(PlanPhase.BACKFILL),
    /** 加非空约束（CONTRACT，回填完成后执行） */
    MAKE_NOT_NULL(PlanPhase.CONTRACT),
    /** 列改名（CONTRACT，类型切换收尾：临时列改回正式名） */
    RENAME_COLUMN(PlanPhase.CONTRACT);

    private final PlanPhase phase;

    StepType(PlanPhase phase) {
        this.phase = phase;
    }

    public PlanPhase phase() {
        return phase;
    }
}
