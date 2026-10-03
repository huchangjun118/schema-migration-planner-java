package com.github.huchangjun118.schemamigrationplannerjava.backfill;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import com.github.huchangjun118.schemamigrationplannerjava.exec.StepApplier;
import com.github.huchangjun118.schemamigrationplannerjava.model.ColumnType;
import com.github.huchangjun118.schemamigrationplannerjava.plan.PlanStep;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Batched, resumable backfill. Progress is checkpointed after every committed
 * batch so a crash or restart resumes from the last committed batch; batches are
 * short pk-range updates that never hold a table lock, and a configurable pause
 * between batches keeps online reads/writes smooth.
 *
 * Semantics while a backfill is in flight (documented contract):
 *  - reads must COALESCE(new_column, old_column) / tolerate nulls in the new column;
 *  - writes performed by new code dual-write old and new columns; the backfill only
 *    touches rows whose new column is still null, so it never overwrites newer writes.
 */
@Component
public class BackfillService {
    private final JdbcTemplate jdbc;
    private final MetaStore metaStore;
    private final StepApplier stepApplier;
    private final MigrationProperties properties;
    /** Test/fault-injection hook invoked with the batch number after each committed batch. */
    private volatile IntConsumer batchHook = n -> { };

    public BackfillService(JdbcTemplate jdbc, MetaStore metaStore, StepApplier stepApplier,
                           MigrationProperties properties) {
        this.jdbc = jdbc;
        this.metaStore = metaStore;
        this.stepApplier = stepApplier;
        this.properties = properties;
    }

    /** Run (or resume) the backfill described by the step to completion. */
    public void runBackfill(String migrationId, PlanStep step) {
        Map<String, String> a = step.getArgs();
        String table = step.getTableName();
        String column = a.get("column");
        String mode = a.get("mode");
        String stepKey = step.idempotencyKey(migrationId);

        if ("UNRESTORABLE".equals(mode)) {
            // documented terminal state: column exists but its data cannot be restored
            metaStore.saveCheckpoint(migrationId, stepKey, -1, 0, 0);
            return;
        }

        String pk = stepApplier.primaryKeyColumn(table);
        long total = countRows(table);
        Optional<Map<String, Object>> cp = metaStore.loadCheckpoint(migrationId, stepKey);
        long lastPk = cp.map(m -> ((Number) m.get("LAST_PK")).longValue()).orElse(Long.MIN_VALUE);
        long doneRows = cp.map(m -> ((Number) m.get("DONE_ROWS")).longValue()).orElse(0L);

        int batchSize = properties.getBackfill().getBatchSize();
        while (true) {
            Long batchMax = jdbc.queryForObject(
                    "SELECT MAX(t." + StepApplier.q(pk) + ") FROM (SELECT " + StepApplier.q(pk)
                            + " FROM " + StepApplier.q(table) + " WHERE " + StepApplier.q(pk) + " > ?"
                            + " ORDER BY " + StepApplier.q(pk) + " LIMIT " + batchSize + ") t",
                    Long.class, lastPk);
            if (batchMax == null) {
                break; // no more rows
            }
            int updated = switch (mode) {
                case "DEFAULT" -> jdbc.update("UPDATE " + StepApplier.q(table)
                                + " SET " + StepApplier.q(column) + " = "
                                + StepApplier.defaultLiteral(ColumnType.valueOf(a.get("type")), a.get("default"))
                                + " WHERE " + StepApplier.q(column) + " IS NULL"
                                + " AND " + StepApplier.q(pk) + " > ? AND " + StepApplier.q(pk) + " <= ?",
                        lastPk, batchMax);
                case "COPY" -> jdbc.update("UPDATE " + StepApplier.q(table)
                                + " SET " + StepApplier.q(a.get("shadowColumn")) + " = CAST("
                                + StepApplier.q(a.get("sourceColumn")) + " AS "
                                + ColumnType.valueOf(a.get("type")).sql() + ")"
                                + " WHERE " + StepApplier.q(a.get("shadowColumn")) + " IS NULL"
                                + " AND " + StepApplier.q(a.get("sourceColumn")) + " IS NOT NULL"
                                + " AND " + StepApplier.q(pk) + " > ? AND " + StepApplier.q(pk) + " <= ?",
                        lastPk, batchMax);
                case "RESTORE" -> jdbc.update("UPDATE " + StepApplier.q(table) + " t"
                                + " SET " + StepApplier.q(column) + " = (SELECT a." + StepApplier.q(column)
                                + " FROM " + StepApplier.q(a.get("archiveTable")) + " a"
                                + " WHERE a." + StepApplier.q(pk) + " = t." + StepApplier.q(pk) + ")"
                                + " WHERE " + StepApplier.q(column) + " IS NULL"
                                + " AND EXISTS (SELECT 1 FROM " + StepApplier.q(a.get("archiveTable")) + " a"
                                + " WHERE a." + StepApplier.q(pk) + " = t." + StepApplier.q(pk) + ")"
                                + " AND " + StepApplier.q(pk) + " > ? AND " + StepApplier.q(pk) + " <= ?",
                        lastPk, batchMax);
                default -> throw new IllegalArgumentException("unknown backfill mode: " + mode);
            };
            doneRows += updated;
            lastPk = batchMax;
            metaStore.saveCheckpoint(migrationId, stepKey, lastPk, doneRows, total);
            batchHook.accept((int) (doneRows));
            pauseBetweenBatches();
        }
    }

    private long countRows(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + StepApplier.q(table), Long.class);
        return n == null ? 0 : n;
    }

    private void pauseBetweenBatches() {
        long pause = properties.getBackfill().getBatchIntervalMillis();
        if (pause > 0) {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("backfill interrupted; safe to resume", e);
            }
        }
    }

    /** Install a hook invoked after each committed batch (tests / fault injection). */
    public void setBatchHook(IntConsumer hook) {
        this.batchHook = hook == null ? n -> { } : hook;
    }

    /** Progress of one backfill step: done/total rows and last checkpoint. */
    public Map<String, Object> progress(String migrationId, String stepKey) {
        Optional<Map<String, Object>> cp = metaStore.loadCheckpoint(migrationId, stepKey);
        Map<String, Object> out = new HashMap<>();
        if (cp.isPresent()) {
            Map<String, Object> m = cp.get();
            long done = ((Number) m.get("DONE_ROWS")).longValue();
            long total = ((Number) m.get("TOTAL_ROWS")).longValue();
            out.put("doneRows", done);
            out.put("totalRows", total);
            out.put("lastPk", m.get("LAST_PK"));
            out.put("percent", total == 0 ? 100.0 : Math.min(100.0, done * 100.0 / total));
        } else {
            out.put("doneRows", 0);
            out.put("totalRows", -1);
            out.put("percent", 0.0);
        }
        return out;
    }
}
