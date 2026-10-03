package com.github.huchangjun118.schemamigrationplannerjava.planner;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 结构一致性校验。目标结构自相矛盾时整体拒绝，并把每一处冲突定位到 表.列。
 */
@Component
public class SchemaValidator {

    /** 系统保留前缀：类型切换临时列使用，用户列不得占用 */
    public static final String MIGR_PREFIX = "__migr_";

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<ColumnType> NUMERIC = Set.of(
            ColumnType.TINYINT, ColumnType.SMALLINT, ColumnType.INT,
            ColumnType.BIGINT, ColumnType.DECIMAL, ColumnType.FLOAT, ColumnType.DOUBLE);

    /** 校验整个结构，返回冲突列表（空列表表示通过） */
    public List<String> validate(SchemaDefinition schema) {
        List<String> conflicts = new ArrayList<>();
        if (schema == null) {
            conflicts.add("结构定义不能为空");
            return conflicts;
        }
        Set<String> seenTables = new HashSet<>();
        for (TableDefinition table : schema.tables()) {
            String tLoc = "表[" + table.name() + "]";
            if (!seenTables.add(table.name().toLowerCase(Locale.ROOT))) {
                conflicts.add(tLoc + " 表名重复（大小写不敏感）");
            }
            if (!IDENT.matcher(table.name()).matches()) {
                conflicts.add(tLoc + " 表名非法（需字母/下划线开头，仅含字母数字下划线）");
            }
            validateTable(table, tLoc, conflicts);
        }
        return conflicts;
    }

    private void validateTable(TableDefinition table, String tLoc, List<String> conflicts) {
        Set<String> seenCols = new HashSet<>();
        ColumnDefinition pk = null;
        for (ColumnDefinition col : table.columns()) {
            String cLoc = tLoc + ".列[" + col.name() + "]";
            if (!IDENT.matcher(col.name()).matches()) {
                conflicts.add(cLoc + " 列名非法");
            }
            if (col.name().toLowerCase(Locale.ROOT).startsWith(MIGR_PREFIX)) {
                conflicts.add(cLoc + " 列名占用系统保留前缀 " + MIGR_PREFIX);
            }
            if (!seenCols.add(col.name().toLowerCase(Locale.ROOT))) {
                conflicts.add(cLoc + " 列名重复（大小写不敏感）");
            }
            if (col.primaryKey()) {
                if (pk != null) {
                    conflicts.add(cLoc + " 一个表只能有一个主键列（已有 " + pk.name() + "）");
                } else {
                    pk = col;
                }
            }
            validateDefault(col, cLoc, conflicts);
        }
        if (table.columns().isEmpty()) {
            conflicts.add(tLoc + " 表没有任何列");
            return;
        }
        if (pk == null) {
            conflicts.add(tLoc + " 缺少主键列（分批回填依赖单主键范围扫描）");
        } else {
            if (pk.nullable()) {
                conflicts.add(tLoc + ".列[" + pk.name() + "] 主键列不允许为 NULL");
            }
            if (!NUMERIC.contains(pk.type())) {
                conflicts.add(tLoc + ".列[" + pk.name() + "] 主键列必须是数值类型（BIGINT/INT 等），当前为 " + pk.type());
            }
        }
    }

    private void validateDefault(ColumnDefinition col, String cLoc, List<String> conflicts) {
        String d = col.defaultValue();
        if (d == null || d.isBlank()) {
            return;
        }
        String expr = d.trim();
        // 表达式形式（CURRENT_TIMESTAMP 等）直接放行
        if (expr.contains("(") || expr.contains(" ")) {
            return;
        }
        switch (col.type()) {
            case TINYINT, SMALLINT, INT, BIGINT, DECIMAL, FLOAT, DOUBLE -> {
                if (!isNumber(expr)) {
                    conflicts.add(cLoc + " 数值列的默认值不是合法数字: " + expr);
                }
            }
            case BOOLEAN -> {
                if (!expr.equalsIgnoreCase("true") && !expr.equalsIgnoreCase("false")
                        && !expr.equals("0") && !expr.equals("1")) {
                    conflicts.add(cLoc + " 布尔列默认值必须是 true/false/0/1，当前为: " + expr);
                }
            }
            default -> {
                // 字符串/日期等：要求引号包裹或为合法字面量，这里不做严格限制
            }
        }
    }

    private boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 校验通过，否则抛出 ConflictException（整体拒绝） */
    public void requireValid(SchemaDefinition schema) {
        List<String> conflicts = validate(schema);
        if (!conflicts.isEmpty()) {
            throw new ConflictException(conflicts);
        }
    }
}
