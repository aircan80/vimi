package com.vibeminer.store;

/** Internal per-instance observation points; no public fault-injection controls. */
enum MaintenancePoint {
    REBUILD_CREATED, REPLAY_ENTRY, REBUILD_REPLAYED, REBUILD_CHECKPOINTED,
    REBUILD_BEFORE_REPLACE, REBUILD_REPLACED,
    SCAN_SEALED, SCAN_CLEARED, SCAN_REPLAYED, SCAN_COMMITTED, SCAN_CHECKPOINTED
}
