package com.vibeminer.builds;

import com.vibeminer.builds.CatalogTypes.CatalogProblem;
import com.vibeminer.store.History;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure versions projection: malformed rows are reported without discarding valid peers. */
final class VersionsReply {
    record Sighting(BuildIdentity identity, String region, String cdnConfig, int row) {}

    record Read(List<Sighting> sightings, List<CatalogProblem> problems, String sequence) {
        Read {
            sightings = List.copyOf(sightings);
            problems = List.copyOf(problems);
        }

        Long sequenceNumber() {
            try {
                return sequence != null && sequence.matches("[0-9]+")
                        ? Long.valueOf(sequence)
                        : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        boolean readable() {
            return problems.stream().allMatch(p -> p.row() != null);
        }
    }

    private VersionsReply() {}

    static Read read(History.Entry entry, byte[] reply) {
        if (entry.kind() != 1) {
            return fail(
                    new CatalogProblem(
                            "VERSIONS_WRONG_KIND",
                            "The entry is not a versions reply",
                            entry.record()));
        }
        if (!Products.isWow(entry.product())) {
            return fail(
                    new CatalogProblem(
                            "VERSIONS_UNKNOWN_PRODUCT",
                            "The product is not a WoW product",
                            entry.record()));
        }
        VersionsTable table;
        try {
            table = VersionsTable.parse(reply);
        } catch (IllegalArgumentException e) {
            return fail(
                    new CatalogProblem(
                            "VERSIONS_UNREADABLE",
                            "The versions reply cannot be read: " + e.getMessage(),
                            entry.record()));
        }
        List<String> missing =
                List.of("Region", "BuildConfig", "CDNConfig", "VersionsName").stream()
                        .filter(n -> !table.columns().contains(n))
                        .toList();
        if (!missing.isEmpty()) {
            return new Read(
                    List.of(),
                    List.of(
                            new CatalogProblem(
                                    "VERSIONS_COLUMNS_MISSING",
                                    "The reply has no " + String.join(", ", missing) + " column",
                                    entry.record())),
                    table.sequence());
        }
        List<CatalogProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>(), ambiguous = new HashSet<>();
        for (var row : table.rows()) {
            String region = row.fields().get("Region");
            if (!seen.add(region) && ambiguous.add(region)) {
                problems.add(
                        new CatalogProblem(
                                "VERSIONS_REGION_AMBIGUOUS",
                                "More than one row lists region " + region,
                                entry.record(),
                                row.ordinal()));
            }
        }
        List<Sighting> sightings = new ArrayList<>();
        for (var row : table.rows()) {
            try {
                BuildIdentity identity = BuildIdentity.create(entry.product(), row.fields());
                String region = row.fields().get("Region");
                if (!ambiguous.contains(region)) {
                    sightings.add(
                            new Sighting(
                                    identity,
                                    region,
                                    BuildIdentity.observedCdnConfig(row.fields()),
                                    row.ordinal()));
                }
            } catch (BuildIdentityException e) {
                problems.add(
                        new CatalogProblem(
                                "VERSIONS_ROW_INVALID",
                                "A versions row cannot be read (" + e.code() + ")",
                                entry.record(),
                                row.ordinal()));
            }
        }
        return new Read(sightings, problems, table.sequence());
    }

    private static Read fail(CatalogProblem problem) {
        return new Read(List.of(), List.of(problem), null);
    }
}
