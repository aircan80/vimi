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

    public record Sync(
            List<Snapshot> snapshots,
            int downloaded,
            int prepared,
            int reused,
            List<String> issues) {
        public Sync {
            snapshots = List.copyOf(snapshots);
            issues = List.copyOf(issues);
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

    public record Page(List<Record> records, boolean more) {
        public Page {
            records = List.copyOf(records);
        }
    }
}
