# schema-migration-planner-java

在线库表结构变更的规划与执行引擎。规划与执行全部在本地进程内完成，存储使用嵌入式
H2（业务表与 `sm_*` 元数据表同库），不依赖任何外部服务，所有结论可在本地复现。

## 能力总览

| 能力 | 实现 |
| --- | --- |
| 有序变更计划 | `PlanGenerator`：当前结构 + 目标结构 → 确定性原子步骤序列 |
| 在线演进 | expand → backfill → contract 三阶段，任意中间态新旧代码均可读写 |
| 大表回填 | `BackfillService`：分批、可断点续跑、进度可查、节奏可配 |
| 失败恢复 | 每步幂等，重启后从最近已提交步骤继续 |
| 多表并发 | 表级租约 + fencing token，同表串行、异表并行、过期安全接管 |
| 回滚 | 回滚同样生成计划并受同一套安全约束；版本历史只增不改 |
| 权限与审计 | 本地角色校验（原因可区分），审计只追加 |

## 计划语义

计划由三类阶段按固定顺序组成，相同输入生成的计划逐条一致（列按名称规范排序，
与输入顺序、并发度无关）：

1. **EXPAND（扩容）** —— 只做对旧代码无害的变更：
   - `EXPAND_ADD_COLUMN`：新列一律先以 **nullable**（可带默认值）加入，旧代码
     插入时无需感知该列；
   - `EXPAND_ADD_SHADOW_COLUMN`：非 widening 的类型变更先加影子列 `<列名>__new`；
   - `EXPAND_ALTER_COLUMN_WIDEN`：widening 类型变更（INT→BIGINT 等）原地修改，无损；
   - `EXPAND_DROP_NOT_NULL` / `EXPAND_ALTER_DEFAULT`：放松约束、改默认值（仅元数据）。
2. **BACKFILL（回填）** —— 分批数据搬迁，模式：
   - `DEFAULT`：把仍为 NULL 的新列填为默认值；
   - `COPY`：把旧列 CAST 到影子列（只写影子列仍为 NULL 的行，不覆盖新代码的双写）；
   - `RESTORE`：回滚时从归档表恢复被删列的数据；
   - `UNRESTORABLE`：归档不存在时明确标记数据无法还原（结构仍回滚，数据留空）。
3. **CONTRACT（收缩）** —— 所有回填完成后才执行：
   - `CONTRACT_SWAP_COLUMN`：归档并删除旧列，影子列改名就位；
   - `CONTRACT_DROP_COLUMN`：归档后删列；
   - `CONTRACT_SET_NOT_NULL`：数据回填完整后强加非空（仍有 NULL 则失败并停在可恢复状态）。

目标结构自相矛盾时整体拒绝并定位全部冲突（`SchemaConflictException.conflicts`）：
重复列、非法默认值、新增 NOT NULL 列无默认值、主键变更等。

## 中间态兼容规则

- **读**：回填完成前，读取新列必须 `COALESCE(新列, 旧列)` 或容忍 NULL；
- **写**：过渡期内新版本代码对旧列与影子列 **双写**；回填只填充仍为 NULL 的行，
  绝不覆盖更新的写入；
- **旧代码**：expand 阶段及之前完全无感（新列 nullable、旧列仍在、约束未收紧）；
- **contract 阶段**只有在全部回填完成、且确认所有旧版本代码下线后才执行。

## 回填语义

- 按主键范围分批（`migration.backfill.batch-size`），每批一个短事务，不持有表级锁；
- 每批提交后写检查点（`sm_checkpoint`），崩溃/重启后从最近已提交批次继续，
  已填行不会被重复填充；
- 批间可配置停顿（`migration.backfill.batch-interval-millis`），避免在线读写停顿；
- 进度通过 `GET /api/migrations/{migrationId}` 查询（doneRows/totalRows/percent）。

## 失败与恢复

- 每个步骤执行前先检查线上结构，效果已存在则跳过（幂等）；
- 步骤状态机：PENDING → RUNNING → DONE / FAILED，持久化于 `sm_step`；
- 失败后迁移停在 FAILED，已完成步骤保持 DONE，重新 apply 从第一个非 DONE 步骤继续，
  不重复、不跳过；中间结构始终是可解释的（影子列、nullable 新列均有文档语义）；
- 删列/换列前先把 `主键+列值` 归档到 `sm_arch_<表>_<列>`，供回滚恢复。

## 租约与并发

- 同一时刻一张表只有一个执行者（`sm_lease`），不同表互不影响；
- 租约带 fencing token 与过期时间（`migration.lease-ttl-millis`）；
- 执行者崩溃后租约过期可被接管，接管获得更大的 fencing token；
- 每个步骤提交前做 fencing 检查，过期执行者会被拒绝，接管不会造成重复应用。

## 回滚

- `POST /api/migrations/rollback` 以历史版本为目标生成回滚计划，走同一套
  expand/backfill/contract 安全约束；
- 已删列数据：归档存在则 RESTORE 安全还原，否则步骤明确标记 UNRESTORABLE；
- 版本历史（`sm_schema_version`）只追加不改写，回滚本身也产生新版本，全程可追溯。

## 权限与审计

- 角色：`PLAN` / `APPLY` / `ROLLBACK` / `ADMIN`（ADMIN 含全部），配置于
  `migration.users.<actor>`；
- 越权拒绝且原因可区分：`UNKNOWN_USER`（未知用户）与 `MISSING_ROLE`（角色不足）；
- 每次 PLAN/APPLY/ROLLBACK 都向 `sm_audit` 追加一条记录（操作者、时间、前后结构、
  明细），审计表无任何更新/删除路径。

## 配置项（application.properties）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `migration.lease-ttl-millis` | 30000 | 表级租约时长 |
| `migration.lease-renew-millis` | 10000 | 租约续期间隔 |
| `migration.backfill.batch-size` | 500 | 回填单批行数 |
| `migration.backfill.batch-interval-millis` | 50 | 批间停顿（毫秒） |
| `migration.users.<actor>` | — | 操作者角色，逗号分隔 |
| `spring.datasource.url` | 本地文件 H2 | 嵌入式存储位置 |

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/migrations/plan` | `{actor, current, target}` → 生成并持久化计划 |
| POST | `/api/migrations/{id}/apply?actor=` | 执行/恢复迁移（幂等） |
| GET | `/api/migrations/{id}` | 状态与回填进度 |
| POST | `/api/migrations/rollback` | `{actor, table, version}` → 回滚计划 |
| GET | `/api/migrations/tables/{table}/history` | 版本历史 |
| GET | `/api/migrations/audit` | 审计记录 |

错误响应：`422 SCHEMA_CONFLICT`（含 conflicts 列表）、`403 FORBIDDEN`
（含 reason=UNKNOWN_USER/MISSING_ROLE）、`409 LEASE_CONFLICT`、`400 BAD_REQUEST`。

## 构建与测试

```bash
mvn test
```

测试覆盖：计划生成与确定性（`PlanGeneratorTest`）、中间态兼容
（`ExpandContractCompatibilityTest`）、回填续跑（`BackfillResumeTest`）、
失败恢复（`FailureRecoveryTest`）、并发租约（`LeaseConcurrencyTest`、
`MultiTableConcurrencyTest`）、回滚（`RollbackTest`）、权限与审计（`AuthAuditTest`）。
