package com.github.huchangjun118.schemamigrationplannerjava.plan;

/**
 * 双写映射：旧代码写入来源列时，触发器自动维护新列，保证灰度期间新旧代码都能读写。
 *
 * @param table        表
 * @param newColumn    新列（被维护的列）
 * @param oldColumn    来源列（可空，纯默认值新增列为 null）
 * @param mode         COPY=同类型复制；CAST=类型转换；DEFAULT=取默认值
 * @param castType     CAST 目标 SQL 类型（可空）
 * @param defaultValue DEFAULT 模式的 SQL 字面量（可空）
 */
public record DualWriteMapping(
        String table,
        String newColumn,
        String oldColumn,
        String mode,
        String castType,
        String defaultValue
) {
}
