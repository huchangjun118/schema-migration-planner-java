package com.github.huchangjun118.schemamigrationplannerjava.plan;

/**
 * Atomic, independently executable and explainable plan step kinds.
 * Expand phase runs before contract phase so old and new code coexist.
 */
public enum StepType {
    /** Expand: add a nullable column (optionally with default). Safe for old code. */
    EXPAND_ADD_COLUMN,
    /** Expand: add a shadow column holding the new type of an existing column. */
    EXPAND_ADD_SHADOW_COLUMN,
    /** Expand: widen a column type in place (lossless, safe for old code). */
    EXPAND_ALTER_COLUMN_WIDEN,
    /** Expand: relax a NOT NULL constraint. */
    EXPAND_DROP_NOT_NULL,
    /** Expand: change a column default (metadata only). */
    EXPAND_ALTER_DEFAULT,
    /** Backfill: copy/default/restore data in resumable batches. */
    BACKFILL,
    /** Contract: archive old column, drop it, rename the shadow column into place. */
    CONTRACT_SWAP_COLUMN,
    /** Contract: archive and drop a column no longer read by any deployed code. */
    CONTRACT_DROP_COLUMN,
    /** Contract: enforce NOT NULL once data is fully backfilled. */
    CONTRACT_SET_NOT_NULL
}
