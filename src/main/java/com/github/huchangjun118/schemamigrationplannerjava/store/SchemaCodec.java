package com.github.huchangjun118.schemamigrationplannerjava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnDef;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.model.TableSchema;
import com.github.huchangjun118.schemamigrationplannerjava.plan.MigrationPlan;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.plan.StepType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for schemas and plans. Serialization is canonical (columns sorted,
 * keys fixed) so identical inputs always produce identical text.
 */
@Component
public class SchemaCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String schemaToJson(TableSchema schema) {
        ObjectNode root = mapper.createObjectNode();
        root.put("table", schema.getTableName());
        ArrayNode cols = root.putArray("columns");
        for (ColumnDef c : schema.getColumns()) {
            ObjectNode n = cols.addObject();
            n.put("name", c.getName());
            n.put("type", c.getType().name());
            n.put("nullable", c.isNullable());
            if (c.getDefaultValue() != null) {
                n.put("default", c.getDefaultValue());
            }
            n.put("pk", c.isPrimaryKey());
        }
        return root.toString();
    }

    public TableSchema schemaFromJson(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<ColumnDef> cols = new ArrayList<>();
            for (JsonNode n : root.withArray("columns")) {
                cols.add(new ColumnDef(
                        n.get("name").asText(),
                        ColumnType.valueOf(n.get("type").asText()),
                        n.get("nullable").asBoolean(),
                        n.hasNonNull("default") ? n.get("default").asText() : null,
                        n.path("pk").asBoolean(false)));
            }
            return new TableSchema(root.get("table").asText(), cols);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid schema json", e);
        }
    }

    public String planToJson(MigrationPlan plan) {
        ObjectNode root = mapper.createObjectNode();
        root.put("planId", plan.getPlanId());
        root.put("table", plan.getTableName());
        root.put("rollback", plan.isRollback());
        root.set("from", parse(schemaToJson(plan.getFromSchema())));
        root.set("to", parse(schemaToJson(plan.getToSchema())));
        ArrayNode steps = root.putArray("steps");
        for (PlanStep s : plan.getSteps()) {
            ObjectNode n = steps.addObject();
            n.put("ordinal", s.getOrdinal());
            n.put("type", s.getType().name());
            n.put("description", s.getDescription());
            ObjectNode args = n.putObject("args");
            s.getArgs().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> args.put(e.getKey(), e.getValue()));
        }
        return root.toString();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public MigrationPlan planFromJson(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            TableSchema from = schemaFromJson(root.get("from").toString());
            TableSchema to = schemaFromJson(root.get("to").toString());
            List<PlanStep> steps = new ArrayList<>();
            for (JsonNode n : root.withArray("steps")) {
                Map<String, String> args = new LinkedHashMap<>();
                n.get("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
                steps.add(new PlanStep(n.get("ordinal").asInt(), StepType.valueOf(n.get("type").asText()),
                        root.get("table").asText(), args, n.get("description").asText()));
            }
            return new MigrationPlan(root.get("planId").asText(), root.get("table").asText(), from, to,
                    steps, root.get("rollback").asBoolean());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid plan json", e);
        }
    }
}
