package com.vibeminer.eglink;

import java.time.Instant;
import java.util.List;

/** EG Link facts retain exact GitHub revision, product, build, dataset and locale provenance. */
public final class EgLinkTypes {
    public static final String REPOSITORY = "https://github.com/JIVESCORP/eg-link-output-wowf";
    public static final String ATTRIBUTION =
            "Data from Efficient Games (https://efficient.games), licensed under CC BY 4.0.";
    public static final String LICENSE = "CC BY 4.0";

    private EgLinkTypes() {}

    public record Dataset(String name, long declaredRecords, long sources, String lastCollectedOn) {
        public Dataset {
            if (name == null || !name.matches("[a-z][a-z0-9_]{0,63}(\\.[a-z]{2}[A-Z]{2})?")) {
                throw new IllegalArgumentException("Invalid dataset name");
            }
            if (declaredRecords < 0 || declaredRecords > 1_000_000 || sources < 0) {
                throw new IllegalArgumentException("Invalid dataset counts");
            }
            java.time.LocalDate.parse(lastCollectedOn);
        }

        public String locale() {
            int at = name.indexOf('.');
            return at < 0 ? "" : name.substring(at + 1);
        }

        public String kind() {
            int at = name.indexOf('.');
            return at < 0 ? name : name.substring(0, at);
        }
    }

    public record Build(String revision, String product, String version, List<Dataset> datasets) {
        public Build {
            if (revision == null
                    || !revision.matches("[0-9a-f]{40}")
                    || product == null
                    || !product.matches("[a-z0-9_]{1,40}")
                    || version == null
                    || !version.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,10}")) {
                throw new IllegalArgumentException("Invalid EG Link build identity");
            }
            datasets = List.copyOf(datasets);
            if (datasets.isEmpty()
                    || datasets.size() > 128
                    || datasets.stream().map(Dataset::name).distinct().count() != datasets.size()) {
                throw new IllegalArgumentException("Invalid dataset inventory");
            }
        }

        public long declaredRecords() {
            return datasets.stream().mapToLong(Dataset::declaredRecords).sum();
        }
    }

    public record Catalog(String revision, Instant checkedAt, List<Build> builds) {
        public Catalog {
            builds = List.copyOf(builds);
        }
    }

    public record Snapshot(
            String id,
            Build build,
            Instant downloadedAt,
            long originalBytes,
            Long indexedRecords,
            Long databaseBytes,
            String problem) {}

    /**
     * A sync's outcome. {@code issues} are failures: a build that could not be downloaded or
     * prepared. {@code warnings} are notes on a sync that otherwise completed: a build no longer
     * advertised (its data retained), an older snapshot left for the next sync to remove, pruning
     * skipped because what is in use was unknown.
     */
    public record Sync(
            List<Snapshot> snapshots,
            int downloaded,
            int prepared,
            int reused,
            List<String> issues,
            List<String> warnings) {
        public Sync {
            snapshots = List.copyOf(snapshots);
            issues = List.copyOf(issues);
            warnings = List.copyOf(warnings);
        }
    }

    public record Progress(String message, long done, long total) {}

    /** JSON is source-preserving content; a record ID is only meaningful within its collection. */
    public record Record(
            String snapshotId,
            String dataset,
            String collection,
            String id,
            String name,
            String json) {}

    /**
     * One display a creature was observed with: its {@code CreatureDisplayInfo} ID, its relative
     * weight (EG Link's {@code probability}, recorded as 1, 25, 100 and so on, not as a share) and
     * the observed scale.
     */
    public record CreatureDisplay(long displayId, double probability, double scale) {
        public CreatureDisplay {
            if (displayId < 1
                    || displayId > 0xffffffffL
                    || !Double.isFinite(probability)
                    || probability < 0
                    || probability > 1_000_000
                    || !Double.isFinite(scale)
                    || scale <= 0
                    || scale > 64) {
                throw new IllegalArgumentException("Invalid EG Link creature display");
            }
        }
    }

    /**
     * The displays one creature was observed with in one snapshot, with that snapshot's product,
     * client build and the dataset's last collection date (its provenance).
     */
    public record CreatureDisplays(
            String snapshotId,
            String product,
            String build,
            String collectedOn,
            long creatureId,
            List<CreatureDisplay> displays,
            String problem) {
        public CreatureDisplays {
            displays = List.copyOf(displays);
            if (problem != null && !displays.isEmpty()) {
                throw new IllegalArgumentException("A malformed record lists no display");
            }
        }

        public CreatureDisplays(
                String snapshotId,
                String product,
                String build,
                String collectedOn,
                long creatureId,
                List<CreatureDisplay> displays) {
            this(snapshotId, product, build, collectedOn, creatureId, displays, null);
        }

        /**
         * Whether the creature is recorded but its displays could not be read ({@link #problem}).
         */
        public boolean malformed() {
            return problem != null;
        }

        /** EG Link, its license and the collected build, for the facts shown with a display. */
        public String provenance() {
            return "EG Link creature_details ("
                    + LICENSE
                    + "), "
                    + product
                    + " build "
                    + build
                    + ", collected "
                    + collectedOn;
        }
    }

    public record Page(List<Record> records, boolean more) {
        public Page {
            records = List.copyOf(records);
        }
    }
}
