package com.github.huchangjun118.schemamigrationplannerjava.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One atomic step of a migration plan. Immutable and explainable.
 */
public final class PlanStep {
    private final int ordinal;
    private final StepType type;
    private final String tableName;
    private final Map<String, String> args;
    private final String description;

    public PlanStep(int ordinal, StepType type, String tableName, Map<String, String> args, String description) {
        this.ordinal = ordinal;
        this.type = type;
        this.tableName = tableName;
        this.args = args == null ? Map.of() : new LinkedHashMap<>(args);
        this.description = description;
    }

    public int getOrdinal() { return ordinal; }
    public StepType getType() { return type; }
    public String getTableName() { return tableName; }
    public Map<String, String> getArgs() { return args; }
    public String getDescription() { return description; }

    /** Stable idempotency key: identical plans yield identical keys. */
    public String idempotencyKey(String planId) {
        StringBuilder sb = new StringBuilder()
                .append(planId).append('#').append(ordinal).append(':').append(type);
        args.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append('|').append(e.getKey()).append('=').append(e.getValue()));
        return sb.toString();
    }
}
