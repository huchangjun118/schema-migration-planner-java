package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.api.dto.PlanRequest;
import com.github.huchangjun118.schemamigrationplannerjava.executor.PlanExecutor;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 变更计划 API：生成、查询、执行、确认。
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;
    private final PlanExecutor planExecutor;

    public PlanController(PlanService planService, PlanExecutor planExecutor) {
        this.planService = planService;
        this.planExecutor = planExecutor;
    }

    /** 生成变更计划 */
    @PostMapping("/schemas/{schemaName}/plans")
    public MigrationPlan createPlan(@PathVariable String schemaName,
                                    @RequestBody PlanRequest request,
                                    @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return planService.createPlan(schemaName, request.target(), operator);
    }

    /** 查询计划详情 */
    @GetMapping("/plans/{planId}")
    public MigrationPlan getPlan(@PathVariable String planId) {
        return planService.getPlan(planId);
    }

    /** 查询结构的所有计划 */
    @GetMapping("/schemas/{schemaName}/plans")
    public List<MigrationPlan> listPlans(@PathVariable String schemaName) {
        return planService.listPlans(schemaName);
    }

    /** 执行计划（可断点续跑） */
    @PostMapping("/plans/{planId}/apply")
    public MigrationPlan applyPlan(@PathVariable String planId,
                                   @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return planExecutor.applyPlan(planId, operator);
    }

    /** 确认含数据丢失的计划 */
    @PostMapping("/plans/{planId}/confirm")
    public MigrationPlan confirmPlan(@PathVariable String planId,
                                     @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return planExecutor.confirmPlan(planId, operator);
    }
}
