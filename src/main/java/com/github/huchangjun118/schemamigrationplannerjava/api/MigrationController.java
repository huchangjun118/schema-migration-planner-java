package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.audit.AuditService;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.service.MigrationService;
import com.github.huchangjun118.schemamigrationplannerjava.store.SchemaCodec;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for planning, applying, monitoring and rolling back migrations.
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationController {
    private final MigrationService migrationService;
    private final AuditService auditService;
    private final SchemaCodec codec;

    public MigrationController(MigrationService migrationService, AuditService auditService, SchemaCodec codec) {
        this.migrationService = migrationService;
        this.auditService = auditService;
        this.codec = codec;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody Map<String, Object> request) {
        String actor = (String) request.get("actor");
        TableSchema current = codec.schemaFromJson(toJson(request.get("current")));
        TableSchema target = codec.schemaFromJson(toJson(request.get("target")));
        return planView(migrationService.plan(actor, current, target));
    }

    @PostMapping("/{migrationId}/apply")
    public Map<String, Object> apply(@PathVariable String migrationId,
                                     @RequestParam String actor) {
        return migrationService.apply(actor, migrationId);
    }

    @GetMapping("/{migrationId}")
    public Map<String, Object> status(@PathVariable String migrationId) {
        return migrationService.status(migrationId);
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestBody Map<String, Object> request) {
        String actor = (String) request.get("actor");
        String table = (String) request.get("table");
        long version = ((Number) request.get("version")).longValue();
        return planView(migrationService.rollback(actor, table, version));
    }

    @GetMapping("/tables/{table}/history")
    public Map<String, Object> history(@PathVariable String table) {
        return Map.of("table", table, "versions", migrationService.history(table));
    }

    @GetMapping("/audit")
    public Map<String, Object> audit() {
        return Map.of("records", auditService.list());
    }

    private String toJson(Object node) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid schema payload", e);
        }
    }

    private Map<String, Object> planView(MigrationPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("migrationId", plan.getPlanId());
        out.put("table", plan.getTableName());
        out.put("rollback", plan.isRollback());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep s : plan.getSteps()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("ordinal", s.getOrdinal());
            v.put("type", s.getType().name());
            v.put("description", s.getDescription());
            v.put("args", s.getArgs());
            steps.add(v);
        }
        out.put("steps", steps);
        return out;
    }
}
