package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.api.dto.RollbackRequest;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.RollbackService;
import org.springframework.web.bind.annotation.*;

/**
 * 回滚 API。
 */
@RestController
@RequestMapping("/api/schemas/{schemaName}/rollback")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    /** 生成回滚计划（含数据丢失步骤需确认后执行） */
    @PostMapping
    public MigrationPlan rollback(@PathVariable String schemaName,
                                  @RequestBody RollbackRequest request,
                                  @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return rollbackService.rollbackTo(schemaName, request.targetVersionId(), operator);
    }
}
