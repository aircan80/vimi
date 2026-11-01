package com.vibeminer.builds;

import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.store.ObjectKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Values exposed by layer 3's offline history-derived catalog. */
public final class CatalogTypes {
    private CatalogTypes() {}

    public record BuildRegion(
            String region,
            String cdnConfig,
            Instant firstSeen,
            Instant lastSeen,
            boolean current,
            ObjectKey lastRecord) {}

    public record CatalogBuild(
            String product,
            String id,
            String versionsName,
            String buildConfig,
            String productConfig,
            Map<String, String> fields,
            Instant firstSeen,
            Instant lastSeen,
            boolean current,
            List<BuildRegion> regions) {
        public CatalogBuild {
            fields = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(fields));
            regions = List.copyOf(regions);
        }
    }

    public record CatalogProblem(String code, String message, ObjectKey record, Integer row) {
        public CatalogProblem(String code, String message) {
            this(code, message, null, null);
        }

        public CatalogProblem(String code, String message, ObjectKey record) {
            this(code, message, record, null);
        }
    }

    public record BuildList(
            String product,
            List<CatalogBuild> builds,
            List<CatalogProblem> problems,
            Instant lastObservedAt) {
        public BuildList {
            builds = List.copyOf(builds);
            problems = List.copyOf(problems);
        }
    }

    public record CatalogRefresh(
            String product, ObservationResult versions, ObservationResult cdns, BuildList list) {}

    public record SummaryRefresh(
            ObservationResult summary,
            List<String> newProducts,
            List<String> listed,
            List<CatalogProblem> problems,
            String sequence,
            List<String> withoutVersions) {
        public SummaryRefresh {
            newProducts = List.copyOf(newProducts);
            listed = List.copyOf(listed);
            problems = List.copyOf(problems);
            withoutVersions = List.copyOf(withoutVersions);
        }
    }
}
