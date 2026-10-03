package com.github.huchangjun118.schemamigrationplannerjava.executor;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 双写触发器。旧代码（不知道新列）INSERT/UPDATE 时，自动用来源列维护新列，
 * 保证灰度期间新旧代码都能正常读写。
 *
 * <p>映射存储在目标库 META.dual_write_map，支持三种模式：
 * <ul>
 *   <li>COPY：同类型直接复制来源列值</li>
 *   <li>CAST：把来源列值转换为新类型（Java 侧转换）</li>
 *   <li>DEFAULT：新列未显式赋值时填入默认值</li>
 * </ul>
 */
public class DualWriteTrigger implements Trigger {

    private String schemaName;
    private String tableName;

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) {
        this.schemaName = schemaName;
        this.tableName = tableName;
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow == null) {
            return;
        }
        Map<String, Integer> colIndex = columnIndices(conn);
        List<Mapping> mappings = loadMappings(conn);
        for (Mapping m : mappings) {
            Integer newIdx = colIndex.get(m.newColumn.toLowerCase());
            if (newIdx == null) {
                continue;
            }
            // 新列已被显式赋值（新代码写入）则不覆盖
            // H2 BEFORE 触发器在未提供时 newRow[newIdx-1] 可能为 null 或预填值，
            // 但显式提供时一定是用户给的值。这里用 oldRow 是否存在来区分 INSERT/UPDATE：
            // - INSERT（oldRow==null）：新列值为 null 或预填值，需要填默认值
            // - UPDATE（oldRow!=null）：新列值为更新后的值，如果非 null 则不覆盖
            Object currentValue = newRow[newIdx - 1];
            if (currentValue != null) {
                // 已有值（新代码写入或 UPDATE 保留），不覆盖
                continue;
            }
            Object value = computeValue(m, colIndex, newRow);
            if (value != null) {
                newRow[newIdx - 1] = value;
            }
        }
    }

    private Object computeValue(Mapping m, Map<String, Integer> colIndex, Object[] newRow) {
        return switch (m.mode()) {
            case "COPY" -> {
                Integer oldIdx = colIndex.get(m.oldColumn.toLowerCase());
                yield oldIdx == null ? null : newRow[oldIdx - 1];
            }
            case "CAST" -> {
                Integer oldIdx = colIndex.get(m.oldColumn.toLowerCase());
                if (oldIdx == null || newRow[oldIdx - 1] == null) {
                    yield null;
                }
                yield cast(newRow[oldIdx - 1], m.castType());
            }
            case "DEFAULT" -> parseDefault(m.defaultValue());
            default -> null;
        };
    }

    private Object cast(Object value, String castType) {
        if (value == null) {
            return null;
        }
        String type = castType.toUpperCase();
        String base = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        try {
            return switch (base) {
                case "VARCHAR", "CHAR", "TEXT", "CLOB" -> value.toString();
                case "INT", "INTEGER" -> value instanceof Number n
                        ? n.intValue() : Integer.parseInt(value.toString().trim());
                case "BIGINT" -> value instanceof Number n
                        ? n.longValue() : Long.parseLong(value.toString().trim());
                case "SMALLINT" -> value instanceof Number n
                        ? n.shortValue() : Short.parseShort(value.toString().trim());
                case "TINYINT" -> value instanceof Number n
                        ? n.byteValue() : Byte.parseByte(value.toString().trim());
                case "DOUBLE", "FLOAT" -> value instanceof Number n
                        ? n.doubleValue() : Double.parseDouble(value.toString().trim());
                case "DECIMAL", "NUMERIC" -> new java.math.BigDecimal(value.toString().trim());
                case "BOOLEAN", "BOOL", "BIT" -> value instanceof Boolean b
                        ? b : Boolean.parseBoolean(value.toString().trim());
                default -> value.toString();
            };
        } catch (Exception e) {
            // 转换失败：保持 NULL，由回填阶段处理；不阻断业务写入
            return null;
        }
    }

    private Object parseDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return null;
        }
        String d = defaultValue.trim();
        if (d.startsWith("'") && d.endsWith("'")) {
            return d.substring(1, d.length() - 1);
        }
        if (d.equalsIgnoreCase("true") || d.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(d);
        }
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException ignore) {
            // 非数字默认值按字符串处理
            return d;
        }
    }

    private Map<String, Integer> columnIndices(Connection conn) throws SQLException {
        Map<String, Integer> map = new HashMap<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, schemaName, tableName, null)) {
            while (rs.next()) {
                map.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getInt("ORDINAL_POSITION"));
            }
        }
        return map;
    }

    private List<Mapping> loadMappings(Connection conn) throws SQLException {
        List<Mapping> list = new ArrayList<>();
        String sql = "SELECT \"new_column\",\"old_column\",\"mode\",\"cast_type\",\"default_value\" "
                + "FROM \"META\".\"dual_write_map\" "
                + "WHERE \"table_schema\"=? AND \"table_name\"=? AND \"enabled\"=true";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Mapping(
                            rs.getString("new_column"),
                            rs.getString("old_column"),
                            rs.getString("mode"),
                            rs.getString("cast_type"),
                            rs.getString("default_value")));
                }
            }
        }
        return list;
    }

    private record Mapping(String newColumn, String oldColumn, String mode,
                           String castType, String defaultValue) {
    }

    @Override
    public void close() {
    }

    @Override
    public void remove() {
    }
}
