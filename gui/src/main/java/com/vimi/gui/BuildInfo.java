package com.vimi.gui;

/** Presentation facts, not backend domain types. Unknown sizes remain null. */
public record BuildInfo(
        String product, String edition, String version, String regions, String config,
        BuildSource source, boolean prepared, Double totalGiB, double storedGiB) {
    public enum BuildSource {
        CURRENT("Current", "Advertised by Blizzard at the mock snapshot’s last check."),
        SAVED("Saved", "Previously observed by VibeMiner; not advertised at the last check."),
        COMMUNITY("Community", "Reported by third-party build history; never directly observed by VibeMiner.");

        private final String label;
        private final String description;

        BuildSource(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    public boolean current() {
        return source == BuildSource.CURRENT;
    }

    public String downloaded() {
        if (!prepared) {
            return "Not opened";
        }
        if (totalGiB == null) {
            return "Size unknown";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", storedGiB / totalGiB * 100);
    }
}
