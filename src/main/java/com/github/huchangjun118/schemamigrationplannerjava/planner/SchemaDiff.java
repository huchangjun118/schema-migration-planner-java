package com.github.huchangjun118.schemamigrationplannerjava.planner;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构差异计算。表与列都按名排序后做集合差，结果确定、可复现。
 */
@Component
public class SchemaDiff {

    /** 计算 current -> target 的差异操作（按表名、列名排序） */
    public List<DiffOp> diff(SchemaDefinition current, SchemaDefinition target) {
        List<DiffOp> ops = new ArrayList<>();
        Map<String, TableDefinition> currentTables = new HashMap<>();
        for (TableDefinition t : current.tables()) {
            currentTables.put(t.name().toLowerCase(), t);
        }
        Map<String, TableDefinition> targetTables = new HashMap<>();
        for (TableDefinition t : target.tables()) {
            targetTables.put(t.name().toLowerCase(), t);
        }

        // 按表名排序并去重，保证确定性且同一张表只处理一次
        java.util.Set<String> allNames = new java.util.TreeSet<>();
        allNames.addAll(currentTables.keySet());
        allNames.addAll(targetTables.keySet());

        for (String name : allNames) {
            TableDefinition ct = currentTables.get(name);
            TableDefinition tt = targetTables.get(name);
            if (ct == null) {
                ops.add(new DiffOp.CreateTable(tt));
            } else if (tt == null) {
                ops.add(new DiffOp.DropTable(ct.name()));
            } else {
                diffTable(ct, tt, ops);
            }
        }
        return ops;
    }

    private void diffTable(TableDefinition current, TableDefinition target, List<DiffOp> ops) {
        Map<String, ColumnDefinition> curCols = new HashMap<>();
        for (ColumnDefinition c : current.columns()) {
            curCols.put(c.name().toLowerCase(), c);
        }
        Map<String, ColumnDefinition> tgtCols = new HashMap<>();
        for (ColumnDefinition c : target.columns()) {
            tgtCols.put(c.name().toLowerCase(), c);
        }

        List<ColumnDefinition> added = new ArrayList<>();
        List<ColumnDefinition> removed = new ArrayList<>();
        for (ColumnDefinition c : target.columns()) {
            if (!curCols.containsKey(c.name().toLowerCase())) {
                added.add(c);
            }
        }
        for (ColumnDefinition c : current.columns()) {
            if (!tgtCols.containsKey(c.name().toLowerCase())) {
                removed.add(c);
            }
        }
        added.sort(Comparator.comparing(c -> c.name().toLowerCase()));
        removed.sort(Comparator.comparing(c -> c.name().toLowerCase()));

        // 改名识别：恰好一删一增且类型相同，视为改名（确定性配对）；否则按删列处理
        Map<String, String> renameSources = new HashMap<>();
        if (added.size() == 1 && removed.size() == 1
                && added.get(0).type() == removed.get(0).type()) {
            renameSources.put(added.get(0).name().toLowerCase(), removed.get(0).name());
        }

        for (ColumnDefinition c : added) {
            ops.add(new DiffOp.AddColumn(current.name(), c, renameSources.get(c.name().toLowerCase())));
        }
        for (ColumnDefinition c : removed) {
            if (!renameSources.containsValue(c.name())) {
                ops.add(new DiffOp.DropColumn(current.name(), c.name()));
            }
        }

        // 同名列的类型/可空/默认值变化
        for (ColumnDefinition tc : target.columns()) {
            ColumnDefinition cc = curCols.get(tc.name().toLowerCase());
            if (cc == null) {
                continue;
            }
            if (cc.type() != tc.type() || !java.util.Objects.equals(cc.length(), tc.length())) {
                ops.add(new DiffOp.ChangeType(current.name(), tc.name(), tc));
            }
            if (cc.nullable() != tc.nullable()) {
                ops.add(new DiffOp.ChangeNullable(current.name(), tc.name(), tc));
            }
            if (!java.util.Objects.equals(normalizeDefault(cc.defaultValue()),
                    normalizeDefault(tc.defaultValue()))) {
                ops.add(new DiffOp.ChangeDefault(current.name(), tc.name(), tc));
            }
        }
    }

    private String normalizeDefault(String d) {
        return d == null ? null : d.trim();
    }
}
