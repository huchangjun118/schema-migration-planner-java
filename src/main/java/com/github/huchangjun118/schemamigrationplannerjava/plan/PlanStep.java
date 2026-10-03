package com.github.huchangjun118.schemamigrationplannerjava.plan;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;

import java.util.List;

/**
 * 计划中的一个原子步骤。
 *
 * @param index         步骤序号（从 0 开始，计划内唯一且有序）
 * @param phase         阶段（EXPAND/BACKFILL/CONTRACT）
 * @param type          步骤类型
 * @param table         目标表
 * @param column        目标列（加列/回填/删列/非空/改名时使用）
 * @param sourceColumn  回填/双写的来源列（可空）
 * @param newColumn     改名后的列名（RENAME_COLUMN 使用）
 * @param columnType    加列时的 SQL 类型（可空）
 * @param defaultValue  默认值 SQL 字面量（可空）
 * @param transform     回填 SQL 表达式，如 "\"name\""、"CAST(\"id\" AS VARCHAR(255))"、"0"
 * @param castType      CAST 目标类型（双写触发器 Java 侧转换用，可空）
 * @param revertable    该步骤是否可安全回退
 * @param dataLoss      该步骤是否会造成数据丢失（删列/删表），回滚时需显式标记
 * @param description   人类可读解释
 * @param mappings      双写映射（仅 INSTALL_DUAL_WRITE 步骤）
 * @param columns       建表列定义（仅 CREATE_TABLE 步骤）
 */
public record PlanStep(
        int index,
        PlanPhase phase,
        StepType type,
        String table,
        String column,
        String sourceColumn,
        String newColumn,
        String columnType,
        String defaultValue,
        String transform,
        String castType,
        boolean revertable,
        boolean dataLoss,
        String description,
        List<DualWriteMapping> mappings,
        List<ColumnDefinition> columns
) {
    public PlanStep {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** 步骤的确定性排序键：阶段固定，阶段内按类型/表/列排序，保证相同输入生成相同计划 */
    public String orderKey() {
        return phase.name() + "|" + type.name() + "|" + nullToEmpty(table) + "|"
                + nullToEmpty(column) + "|" + nullToEmpty(newColumn) + "|" + index;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
