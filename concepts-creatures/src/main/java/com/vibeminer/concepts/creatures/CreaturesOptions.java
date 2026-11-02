package com.vibeminer.concepts.creatures;

public record CreaturesOptions(int maxScanRows) {
    public static final int DEFAULT_MAX_SCAN_ROWS = 20000;

    public CreaturesOptions {
        if (maxScanRows < 1) {
            throw new IllegalArgumentException("Positive scan bound required");
        }
    }

    public CreaturesOptions() {
        this(DEFAULT_MAX_SCAN_ROWS);
    }
}
