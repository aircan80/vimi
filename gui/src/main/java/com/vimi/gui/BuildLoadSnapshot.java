package com.vimi.gui;

import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.store.LibraryCatalog;

/** Cached presentation facts from persisted load state and the shared object catalog. */
public record BuildLoadSnapshot(
        BuildRegistry.Entry entry,
        LibraryCatalog.Figures usage,
        boolean measuring,
        String problem) {
    public String status() {
        if (entry == null) {
            return "Not loaded";
        }
        return switch (entry.status()) {
            case DISCOVERED -> "Not loaded";
            case LOADING -> "Loading…";
            case LOADED -> "Loaded";
            case FAILED -> "Load failed";
            case INTERRUPTED -> "Load interrupted";
        };
    }

    public String stored() {
        return usage == null
                ? (measuring ? "Measuring…" : "Not measured")
                : size(usage.original().storedBytes() + usage.derived().storedBytes());
    }

    public String expected() {
        return entry == null
                        || entry.status() != BuildRegistry.Status.LOADED
                        || measuring
                        || usage == null
                        || !usage.original().inventoryComplete()
                ? (measuring ? "Measuring…" : "Unknown")
                : size(usage.original().declaredBytes());
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
        }
        return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }
}
