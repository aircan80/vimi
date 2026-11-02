package com.vibeminer.concepts.items;

final class ItemsSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    static final String NAME_UNSUPPORTED = "ITEMS_NAME_UNSUPPORTED",
            SCHEMA_AMBIGUOUS = "ITEMS_SCHEMA_AMBIGUOUS",
            SOURCE_INVALID = "ITEMS_SOURCE_INVALID";
    private final String code;

    ItemsSourceException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
