package com.vibeminer.concepts.items;

/** Completeness of the verified ItemDisplayInfoMaterialRes foreign-key join. */
public enum ItemArmorMaterialsState {
    COMPLETE,
    INCOMPLETE,
    ROW_LIMIT,
    INDEX_LIMIT,
    STALE,
    TABLE_NOT_READABLE,
    UNSUPPORTED
}
