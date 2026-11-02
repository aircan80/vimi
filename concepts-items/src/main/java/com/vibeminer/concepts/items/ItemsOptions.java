package com.vibeminer.concepts.items;

/** Per-call list/label/relation/appearance/resource/model bounds; no silent truncation. */
public record ItemsOptions(
        int maxScanRows,
        int maxLabelScanRows,
        int maxRelationIndexEntries,
        int maxAppearanceRows,
        int maxResourceFiles,
        int maxModels,
        int maxConcurrentOperations) {
    public ItemsOptions() {
        this(20000, 200000, 4000000, 16, 32, 8, 64);
    }

    public ItemsOptions(
            int maxScanRows,
            int maxLabelScanRows,
            int maxRelationIndexEntries,
            int maxAppearanceRows,
            int maxResourceFiles,
            int maxModels) {
        this(
                maxScanRows,
                maxLabelScanRows,
                maxRelationIndexEntries,
                maxAppearanceRows,
                maxResourceFiles,
                maxModels,
                64);
    }

    public ItemsOptions {
        if (maxScanRows < 1
                || maxLabelScanRows < 1
                || maxRelationIndexEntries < 1
                || maxAppearanceRows < 1
                || maxResourceFiles < 1
                || maxModels < 1
                || maxConcurrentOperations < 1) {
            throw new IllegalArgumentException("Positive item bounds required");
        }
    }
}
