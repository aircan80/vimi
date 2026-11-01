package com.vibeminer.store;

/** Internal observation points for deterministic compaction/read race checks. */
enum ReadPoint { ROW_FOUND, PACK_OPENED }
