package com.github.huchangjun118.schemamigrationplannerjava.executor;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.audit.PermissionService;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseManager;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStatus;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划执行器。
 *
 * <p>每一步都幂等，恢复时从最近已提交位置继续：已完成步骤跳过，
 * 失败步骤重跑（幂等），不重复执行、不跳过步骤、不留下无法解释的中间结构。
 * 执行全程持有租约，fencing 校验失败立即停止。
 */
@Service
public class PlanExecutor {

    private final MetadataStore store;
    private final StepApplier stepApplier;
    private final LeaseManager leaseManager;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public PlanExecutor(MetadataStore store, StepApplier stepApplier, LeaseManager leaseManager,
                        PermissionService permissionService, AuditService auditService) {
        this.store = store;
        this.stepApplier = stepApplier;
        this.leaseManager = leaseManager;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    /**
     * 执行计划（可断点续跑）。
     */
    public MigrationPlan applyPlan(String planId, String operator) {
        permissionService.require(operator, "PLAN_APPLY");

        MigrationPlan plan = store.getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + planId));

        if (plan.status() == PlanStatus.COMPLETED) {
            return plan;
        }
        if (plan.status() == PlanStatus.AWAITING_CONFIRMATION) {
            throw new NeedsConfirmationException(
                    "计划含数据丢失步骤，需操作者确认后才能执行: " + planId);
        }

        String leaseKey = LeaseManager.schemaLeaseKey(plan.schemaName());
        LeaseManager.LeaseResult lease = leaseManager.acquire(leaseKey, operator);
        if (!lease.acquired()) {
            throw new LeaseBusyException("库 " + plan.schemaName() + " 上的变更正在由 "
                    + lease.owner() + " 执行中，租约到期时间 " + lease.expiresAt());
        }

        store.updatePlanStatus(planId, PlanStatus.EXECUTING, null);
        auditService.record(planId, "PLAN_APPLY", operator,
                "开始执行计划 " + planId + "（" + plan.steps().size() + " 步）", null, null);

        try {
            for (PlanStep step : plan.steps()) {
                // fencing：租约失效立即停止
                if (!leaseManager.isHeld(leaseKey, lease.token())) {
                    throw new BackfillExecutor.LeaseLostException(
                            "租约已失效，计划停止在步骤 " + step.index());
                }

                // 已完成步骤跳过（幂等恢复）
                List<MetadataStore.StepState> states = store.getStepStates(planId);
                MetadataStore.StepState state = states.stream()
                        .filter(s -> s.stepIndex() == step.index())
                        .findFirst().orElse(null);
                if (state != null && "COMPLETED".equals(state.status())) {
                    continue;
                }

                // 标记执行中
                store.upsertStepState(planId, step.index(), step.type().name(),
                        "RUNNING", now(), null, null);

                // 应用步骤（幂等）；返回 false 表示回填暂停（可续跑）
                boolean completed = stepApplier.apply(planId, step, plan.schemaName(),
                        leaseKey, lease.token());

                if (!completed) {
                    // 回填暂停：步骤标记 PAUSED，计划保持 EXECUTING，等待后续续跑
                    store.upsertStepState(planId, step.index(), step.type().name(),
                            "PAUSED", null, null, null);
                    auditService.record(planId, "PLAN_APPLY", operator,
                            "计划 " + planId + " 回填暂停在步骤 " + step.index()
                                    + "（" + step.column() + "），可断点续跑", null, null);
                    return store.getPlan(planId).orElse(plan);
                }

                // 标记完成
                store.upsertStepState(planId, step.index(), step.type().name(),
                        "COMPLETED", null, now(), null);
            }

            store.updatePlanStatus(planId, PlanStatus.COMPLETED, null);
            auditService.record(planId, "PLAN_APPLY", operator,
                    "计划 " + planId + " 全部执行完成", null, null);
            return store.getPlan(planId).orElse(plan);

        } catch (Exception e) {
            store.updatePlanStatus(planId, PlanStatus.FAILED, e.getMessage());
            store.upsertStepState(planId, -1, "PLAN", "FAILED", null, null, e.getMessage());
            auditService.record(planId, "PLAN_APPLY", operator,
                    "计划 " + planId + " 执行失败: " + e.getMessage(), null, null);
            throw e;
        } finally {
            leaseManager.release(leaseKey, lease.token());
        }
    }

    /**
     * 确认含数据丢失的计划（回滚计划需 ADMIN 权限）。
     */
    public MigrationPlan confirmPlan(String planId, String operator) {
        permissionService.require(operator, "ROLLBACK");
        MigrationPlan plan = store.getPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在: " + planId));
        if (plan.status() != PlanStatus.AWAITING_CONFIRMATION) {
            return plan;
        }
        // 更新已有计划的状态为 PLANNED（不重新插入）
        store.updatePlanStatus(planId, PlanStatus.PLANNED, null);
        auditService.record(planId, "PLAN_CONFIRM", operator,
                "确认执行含数据丢失的计划 " + planId, null, null);
        return store.getPlan(planId).orElse(plan);
    }

    private String now() {
        return java.time.Instant.now().toString();
    }

    /** 计划需确认异常 */
    public static class NeedsConfirmationException extends RuntimeException {
        public NeedsConfirmationException(String message) {
            super(message);
        }
    }

    /** 租约占用异常 */
    public static class LeaseBusyException extends RuntimeException {
        public LeaseBusyException(String message) {
            super(message);
        }
    }
}
