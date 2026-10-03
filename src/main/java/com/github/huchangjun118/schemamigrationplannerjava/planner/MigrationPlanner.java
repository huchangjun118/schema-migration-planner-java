package com.github.huchangjun118.schemamigrationplannerjava.planner;

import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableDefinition;
import com.github.huchangjun118.schemamigrationplannerjava.plan.DualWriteMapping;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanKind;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanPhase;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.huchangjun118.schemamigrationplannerjava.planner.SchemaValidator.MIGR_PREFIX;

/**
 * 变更计划生成器。
 *
 * <p>把当前结构与目标结构的差异翻译为有序的原子步骤，严格遵循
 * 先扩容（EXPAND）、再回填（BACKFILL）、后收缩（CONTRACT）的顺序。
 * 相同输入（表/列顺序无关）生成的计划逐条一致。
 */
@Component
public class MigrationPlanner {

    private final SchemaDiff diff;
    private final SchemaValidator validator;

    public MigrationPlanner(SchemaDiff diff, SchemaValidator validator) {
        this.diff = diff;
        this.validator = validator;
    }

    /**
     * 生成 current -> target 的变更计划。
     */
    public MigrationPlan plan(String planId, SchemaDefinition current, SchemaDefinition target,
                              long fromVersion, long toVersion, PlanKind kind, String operator) {
        // 1. 整体校验目标结构，冲突全部收集后一次性拒绝
        validator.requireValid(target);

        List<DiffOp> ops = diff.diff(current, target);
        List<String> conflicts = new ArrayList<>();

        List<PlanStep> expand = new ArrayList<>();
        List<PlanStep> backfill = new ArrayList<>();
        List<PlanStep> contract = new ArrayList<>();
        Map<String, List<DualWriteMapping>> mappingsByTable = new HashMap<>();

        for (DiffOp op : ops) {
            if (op instanceof DiffOp.CreateTable ct) {
                expand.add(new StepBuilder(StepType.CREATE_TABLE)
                        .table(ct.table().name())
                        .columns(ct.table().columns())
                        .description("创建新表 " + ct.table().name() + "（" + describeColumns(ct.table()) + "）")
                        .build());

            } else if (op instanceof DiffOp.DropTable dt) {
                contract.add(new StepBuilder(StepType.DROP_TABLE)
                        .table(dt.tableName())
                        .revertable(false).dataLoss(true)
                        .description("删除表 " + dt.tableName() + "（表中数据将全部丢失，不可恢复）")
                        .build());

            } else if (op instanceof DiffOp.AddColumn ac) {
                ColumnDefinition c = ac.column();
                String table = ac.tableName();
                boolean hasSource = ac.sourceColumn() != null;
                boolean hasDefault = c.hasDefault();
                if (c.primaryKey()) {
                    conflicts.add("已有表上新增的列不能作为主键: " + table + "." + c.name());
                }
                if (!c.nullable() && !hasDefault && !hasSource) {
                    conflicts.add("已有表上新增非空列必须提供默认值或来源列，否则存量行无法填充: "
                            + table + "." + c.name());
                }
                // EXPAND：一律先以可空列加入
                expand.add(new StepBuilder(StepType.ADD_COLUMN)
                        .table(table).column(c.name())
                        .columnType(c.type().toSql(c.length()))
                        .defaultValue(c.defaultValue())
                        .description("新增列 " + table + "." + c.name() + "（先以可空列加入，避免阻塞旧代码写入）")
                        .build());
                // 双写映射
                if (hasSource) {
                    mappingsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(
                            new DualWriteMapping(table, c.name(), ac.sourceColumn(), "COPY", null, null));
                } else if (hasDefault) {
                    mappingsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(
                            new DualWriteMapping(table, c.name(), null, "DEFAULT", null, c.defaultValue()));
                }
                // BACKFILL
                if (hasSource) {
                    backfill.add(new StepBuilder(StepType.BACKFILL)
                            .table(table).column(c.name()).sourceColumn(ac.sourceColumn())
                            .transform("\"" + ac.sourceColumn() + "\"")
                            .description("分批回填 " + table + "." + c.name() + "（由改名前的 "
                                    + ac.sourceColumn() + " 复制）")
                            .build());
                } else if (hasDefault) {
                    backfill.add(new StepBuilder(StepType.BACKFILL)
                            .table(table).column(c.name())
                            .defaultValue(c.defaultValue())
                            .transform(c.defaultValue())
                            .description("分批回填 " + table + "." + c.name() + " 的存量默认值 " + c.defaultValue())
                            .build());
                }
                // CONTRACT：目标非空则在回填后加非空约束
                if (!c.nullable()) {
                    contract.add(new StepBuilder(StepType.MAKE_NOT_NULL)
                            .table(table).column(c.name())
                            .description("回填完成后将 " + table + "." + c.name() + " 改为非空约束")
                            .build());
                }

            } else if (op instanceof DiffOp.DropColumn dc) {
                contract.add(new StepBuilder(StepType.DROP_COLUMN)
                        .table(dc.tableName()).column(dc.columnName())
                        .revertable(false).dataLoss(true)
                        .description("删除列 " + dc.tableName() + "." + dc.columnName()
                                + "（列中数据将丢失，不可恢复）")
                        .build());

            } else if (op instanceof DiffOp.ChangeType ct) {
                ColumnDefinition c = ct.newColumn();
                String temp = MIGR_PREFIX + c.name() + "_new";
                String newTypeSql = c.type().toSql(c.length());
                // EXPAND：新增临时可空列
                expand.add(new StepBuilder(StepType.ADD_COLUMN)
                        .table(ct.tableName()).column(temp)
                        .columnType(newTypeSql).defaultValue(c.defaultValue())
                        .description("为类型切换新增临时列 " + ct.tableName() + "." + temp
                                + "（类型 " + newTypeSql + "，先可空）")
                        .build());
                // 双写：旧列写入自动 CAST 到临时列
                mappingsByTable.computeIfAbsent(ct.tableName(), k -> new ArrayList<>()).add(
                        new DualWriteMapping(ct.tableName(), temp, ct.column(), "CAST", newTypeSql, null));
                // BACKFILL：存量数据 CAST 到临时列
                backfill.add(new StepBuilder(StepType.BACKFILL)
                        .table(ct.tableName()).column(temp).sourceColumn(ct.column())
                        .transform("CAST(\"" + ct.column() + "\" AS " + newTypeSql + ")")
                        .castType(newTypeSql)
                        .description("分批回填 " + ct.tableName() + "." + temp + "（由 " + ct.column()
                                + " 转换为 " + newTypeSql + "）")
                        .build());
                // CONTRACT：临时列非空 -> 删旧列 -> 临时列改名
                if (!c.nullable()) {
                    contract.add(new StepBuilder(StepType.MAKE_NOT_NULL)
                            .table(ct.tableName()).column(temp)
                            .description("类型切换收尾：将临时列 " + ct.tableName() + "." + temp + " 改为非空")
                            .build());
                }
                contract.add(new StepBuilder(StepType.DROP_COLUMN)
                        .table(ct.tableName()).column(ct.column())
                        .revertable(false).dataLoss(true)
                        .description("类型切换收尾：删除旧列 " + ct.tableName() + "." + ct.column()
                                + "（数据不可恢复）")
                        .build());
                contract.add(new StepBuilder(StepType.RENAME_COLUMN)
                        .table(ct.tableName()).column(temp).newColumn(c.name())
                        .description("类型切换收尾：将临时列 " + ct.tableName() + "." + temp
                                + " 改名为 " + c.name())
                        .build());

            } else if (op instanceof DiffOp.ChangeNullable cn) {
                ColumnDefinition c = cn.newColumn();
                if (c.nullable()) {
                    // NOT NULL -> NULL：放松约束，扩容阶段完成
                    expand.add(new StepBuilder(StepType.MODIFY_NULLABLE)
                            .table(cn.tableName()).column(c.name())
                            .description("放松约束：" + cn.tableName() + "." + c.name() + " 允许 NULL（对旧代码安全）")
                            .build());
                } else {
                    // NULL -> NOT NULL：必须有默认值回填存量 NULL
                    if (!c.hasDefault()) {
                        conflicts.add("可空列改为非空必须提供默认值以回填存量 NULL: "
                                + cn.tableName() + "." + c.name());
                    }
                    backfill.add(new StepBuilder(StepType.BACKFILL)
                            .table(cn.tableName()).column(c.name())
                            .defaultValue(c.defaultValue())
                            .transform(c.defaultValue())
                            .description("分批回填 " + cn.tableName() + "." + c.name()
                                    + " 的 NULL 值为默认值 " + c.defaultValue())
                            .build());
                    contract.add(new StepBuilder(StepType.MAKE_NOT_NULL)
                            .table(cn.tableName()).column(c.name())
                            .description("回填完成后将 " + cn.tableName() + "." + c.name() + " 改为非空约束")
                            .build());
                }

            } else if (op instanceof DiffOp.ChangeDefault cd) {
                ColumnDefinition c = cd.newColumn();
                expand.add(new StepBuilder(StepType.MODIFY_DEFAULT)
                        .table(cd.tableName()).column(c.name())
                        .defaultValue(c.defaultValue())
                        .description(c.hasDefault()
                                ? "修改 " + cd.tableName() + "." + c.name() + " 的默认值为 " + c.defaultValue()
                                : "删除 " + cd.tableName() + "." + c.name() + " 的默认值")
                        .build());
            }
        }

        if (!conflicts.isEmpty()) {
            throw new ConflictException(conflicts);
        }

        // 安装双写触发器（EXPAND，排在所有加列步骤之后）
        for (Map.Entry<String, List<DualWriteMapping>> e : mappingsByTable.entrySet()) {
            expand.add(new StepBuilder(StepType.INSTALL_DUAL_WRITE)
                    .table(e.getKey())
                    .mappings(e.getValue())
                    .description("在表 " + e.getKey() + " 上安装双写触发器（" + e.getValue().size()
                            + " 个列映射），旧代码写入自动维护新列")
                    .build());
        }

        // 各阶段内排序：确定性，不受输入顺序影响
        expand.sort(EXPAND_ORDER);
        backfill.sort(Comparator.comparing((PlanStep s) -> s.table() == null ? "" : s.table())
                .thenComparing(s -> s.column() == null ? "" : s.column()));
        contract.sort(CONTRACT_ORDER);

        List<PlanStep> all = new ArrayList<>();
        all.addAll(expand);
        all.addAll(backfill);
        all.addAll(contract);

        List<PlanStep> indexed = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            PlanStep s = all.get(i);
            indexed.add(new PlanStep(i, s.phase(), s.type(), s.table(), s.column(), s.sourceColumn(),
                    s.newColumn(), s.columnType(), s.defaultValue(), s.transform(), s.castType(),
                    s.revertable(), s.dataLoss(), s.description(), s.mappings(), s.columns()));
        }

        String schemaName = target.name();
        boolean hasDataLoss = indexed.stream().anyMatch(PlanStep::dataLoss);
        // 回滚计划含数据丢失步骤时，必须显式确认后才能执行
        PlanStatus status = (kind == PlanKind.ROLLBACK && hasDataLoss)
                ? PlanStatus.AWAITING_CONFIRMATION : PlanStatus.PLANNED;

        return new MigrationPlan(planId, schemaName, fromVersion, toVersion, kind,
                indexed, status, Instant.now().toString(), operator, null);
    }

    private String describeColumns(TableDefinition t) {
        return t.columns().stream()
                .map(c -> c.name() + " " + c.type())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** EXPAND 阶段内排序：建表 -> 加列 -> 装双写 -> 改默认/可空 */
    private static final Comparator<PlanStep> EXPAND_ORDER = Comparator
            .comparingInt((PlanStep s) -> switch (s.type()) {
                case CREATE_TABLE -> 0;
                case ADD_COLUMN -> 1;
                case INSTALL_DUAL_WRITE -> 2;
                case MODIFY_DEFAULT, MODIFY_NULLABLE -> 3;
                default -> 4;
            })
            .thenComparing(s -> s.table() == null ? "" : s.table())
            .thenComparing(s -> s.column() == null ? "" : s.column());

    /** CONTRACT 阶段内排序：非空 -> 删列 -> 改名 -> 删表 */
    private static final Comparator<PlanStep> CONTRACT_ORDER = Comparator
            .comparingInt((PlanStep s) -> switch (s.type()) {
                case MAKE_NOT_NULL -> 0;
                case DROP_COLUMN -> 1;
                case RENAME_COLUMN -> 2;
                case DROP_TABLE -> 3;
                default -> 4;
            })
            .thenComparing(s -> s.table() == null ? "" : s.table())
            .thenComparing(s -> s.column() == null ? "" : s.column());
}
