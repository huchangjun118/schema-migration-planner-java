# 在线库表结构变更规划与执行系统

一套完整的在线库表结构变更（Online Schema Migration）规划与执行能力。规划与执行均在本地进程内完成，存储使用本地可嵌入的 H2 数据库承载，不依赖任何外部服务，结论可在本地复现。

## 核心能力

1. **计划生成**：由当前结构与目标结构生成有序的变更计划，每一步都是可独立执行且可解释的原子操作。相同输入下生成的计划逐条一致，不受输入顺序或并发度影响。目标结构自相矛盾时整体拒绝并定位到具体冲突。
2. **扩容-收缩兼容**：按先扩容（EXPAND）、再回填（BACKFILL）、后收缩（CONTRACT）的顺序分阶段推进。任一中间状态下旧版本代码与新版本代码都能正常读写。
3. **大表回填**：分批推进，可断点续跑，进度可查询，单批规模与节奏可配置。回填过程不长时间独占锁，批间休眠让出在线读写窗口。
4. **失败恢复**：每一步幂等，恢复时从最近一次已提交位置继续，不重复执行、不跳过步骤、不留下无法解释的中间结构。
5. **并发租约**：同一张表上的变更串行执行，执行方通过租约避免同一份变更被两个执行者同时应用。租约过期后可被安全接管，接管不造成重复应用。
6. **回滚**：回滚以计划形式给出，受同一套安全约束。含数据丢失步骤的回滚计划需显式确认。版本记录不可改写且全程可追溯。
7. **权限校验与审计**：越权请求被拒绝并给出可区分的原因码。每一次应用与回滚都记录操作者、时间与结构前后状态。审计记录只追加、不覆盖。

## 计划语义

### 阶段划分

所有变更计划严格按以下三个阶段顺序推进：

| 阶段 | 说明 | 典型步骤 |
|------|------|----------|
| EXPAND（扩容） | 只做新增操作，不破坏旧代码 | 建新表、加可空列、安装双写触发器、改默认值、放松非空约束 |
| BACKFILL（回填） | 分批回填存量数据 | 按主键范围分批 UPDATE，批间休眠 |
| CONTRACT（收缩） | 新代码全量后执行 | 加非空约束、删列、删表、类型切换收尾 |

### 原子步骤类型

| 步骤类型 | 阶段 | 幂等性 | 数据丢失 |
|----------|------|--------|----------|
| CREATE_TABLE | EXPAND | 是 | 否 |
| ADD_COLUMN | EXPAND | 是 | 否 |
| INSTALL_DUAL_WRITE | EXPAND | 是 | 否 |
| MODIFY_DEFAULT | EXPAND | 是 | 否 |
| MODIFY_NULLABLE | EXPAND | 是 | 否 |
| BACKFILL | BACKFILL | 是（IS NULL 条件） | 否 |
| MAKE_NOT_NULL | CONTRACT | 是 | 否 |
| DROP_COLUMN | CONTRACT | 是 | 是 |
| RENAME_COLUMN | CONTRACT | 是 | 否 |
| DROP_TABLE | CONTRACT | 是 | 是 |

### 确定性保证

- 表与列在规划时按名排序，与输入顺序无关。
- 步骤在各阶段内按类型、表名、列名排序。
- 相同输入（当前结构 + 目标结构）生成的计划逐条一致。

### 冲突检测

目标结构存在以下问题时整体拒绝，并定位到具体的 `表.列`：

- 表名/列名重复
- 列类型非法
- 缺少主键或主键非数值类型
- 新增非空列无默认值或来源列
- 可空列改非空无默认值
- 列名占用系统保留前缀 `__migr_`

## 中间态兼容规则

### 双写触发器

扩容阶段在表上安装双写触发器（`DualWriteTrigger`），支持三种模式：

- **COPY**：同类型直接复制来源列值（用于改名场景）
- **CAST**：把来源列值转换为新类型（用于类型切换）
- **DEFAULT**：新列未显式赋值时填入默认值（用于新增列）

旧代码 INSERT/UPDATE 时不知道新列，触发器自动维护新列，保证旧代码写入不报错。

### 各阶段兼容性

| 阶段 | 旧代码读 | 旧代码写 | 新代码读 | 新代码写 |
|------|----------|----------|----------|----------|
| EXPAND 后 | 正常（读旧列） | 正常（触发器维护新列） | 正常（新列可空） | 正常 |
| BACKFILL 中 | 正常 | 正常 | 正常（未回填行为 NULL） | 正常 |
| CONTRACT 后 | 需已下线 | 需已下线 | 正常 | 正常 |

### 回填语义

- 回填按主键范围分批，每批提交后更新进度。
- 回填语句带 `IS NULL` 条件，幂等可重复执行。
- 批间休眠（`batch-delay-ms`）让出在线读写窗口。
- 回填完成前，新列读取确定语义：已回填行返回新值，未回填行返回 NULL。

## 配置项

在 `application.properties` 中配置：

```properties
# ===== 回填节奏控制 =====
migration.backfill.batch-size=1000        # 单批回填行数
migration.backfill.batch-delay-ms=50      # 批间休眠毫秒（让出在线读写窗口）
migration.backfill.max-batches=0          # 单次执行最大批数，0 表示不限

# ===== 租约 =====
migration.lease.ttl-ms=30000              # 租约有效期（毫秒）
migration.lease.renew-interval-ms=5000    # 续期间隔（毫秒）
migration.lease.max-wait-ms=0             # 获取租约最长等待（毫秒）

# ===== 审计 =====
migration.audit.enabled=true              # 是否开启审计

# ===== 数据源（本地嵌入 H2） =====
migration.metadata.jdbc-url=jdbc:h2:file:./data/metadata;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE
migration.target.jdbc-url=jdbc:h2:file:./data/target;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE
```

## 角色与权限

| 角色 | 权限 |
|------|------|
| ADMIN | 规划、应用、回滚、版本管理、角色管理 |
| APPLIER | 规划、应用变更、创建版本 |
| VIEWER | 只读 |

### 权限矩阵

| 动作 | ADMIN | APPLIER | VIEWER |
|------|-------|---------|--------|
| PLAN_CREATE | ✓ | ✓ | ✗ |
| PLAN_APPLY | ✓ | ✓ | ✗ |
| ROLLBACK | ✓ | ✗ | ✗ |
| VERSION_CREATE | ✓ | ✓ | ✗ |
| VERSION_VIEW | ✓ | ✓ | ✓ |
| AUDIT_VIEW | ✓ | ✓ | ✓ |
| ROLE_MANAGE | ✓ | ✗ | ✗ |

### 拒绝原因码

| 原因码 | 含义 |
|--------|------|
| ROLLBACK_REQUIRES_ADMIN | 回滚需要 ADMIN 角色 |
| APPLY_REQUIRES_APPLIER | 应用变更需要 APPLIER 角色 |
| PLAN_REQUIRES_APPLIER | 生成计划需要 APPLIER 角色 |
| UNKNOWN_OPERATOR | 操作者未注册 |
| UNKNOWN_ROLE | 操作者角色非法 |

## 版本与审计

- **版本记录**：只追加（INSERT），不更新、不删除。每次创建版本生成唯一版本号，历史版本永久可查。
- **审计记录**：只追加（INSERT），不覆盖。记录操作者、时间、动作、前后状态。越权拒绝也记录审计。
- **计划状态**：PLANNED → EXECUTING → COMPLETED/FAILED；含数据丢失的回滚计划为 AWAITING_CONFIRMATION，需确认后执行。

## 测试

```bash
mvn test
```

测试覆盖：

- `PlannerTest`：计划生成、确定性、冲突检测
- `IntegrationTest`：端到端流程（建版本→生成计划→执行→回填→双写）
- `CompatibilityTest`：扩容阶段新旧代码共存
- `BackfillResumeTest`：回填分批、断点续跑、进度查询
- `FailureRecoveryTest`：失败恢复、幂等续跑
- `ConcurrencyLeaseTest`：并发租约、过期接管
- `RollbackTest`：回滚计划、数据丢失标记、版本链
- `PermissionAuditTest`：权限校验、拒绝原因码、审计只追加
