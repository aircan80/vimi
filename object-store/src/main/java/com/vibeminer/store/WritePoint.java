package com.vibeminer.store;

/** Internal observation points for deterministic child-process crash tests. */
enum WritePoint {
    ENTRY_STARTED,
    PAYLOAD_PARTIAL,
    PAYLOAD_WRITTEN,
    TABLE_WRITTEN,
    HEADER_WRITTEN,
    PACK_SYNCED,
    INDEX_UPDATED,
    INDEX_COMMITTED
}
