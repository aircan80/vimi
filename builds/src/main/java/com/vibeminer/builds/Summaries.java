package com.vibeminer.builds;

import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.builds.CatalogTypes.CatalogProblem;
import com.vibeminer.builds.CatalogTypes.SummaryRefresh;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/** Summary discovery, including products that have no unflagged versions document. */
public final class Summaries {
    private Summaries() {}

    public static SummaryRefresh read(ObservationResult observed) {
        if (observed.observation() == null) {
            return new SummaryRefresh(observed, List.of(), List.of(), List.of(), null, List.of());
        }
        VersionsTable table;
        try {
            table = VersionsTable.parse(observed.observation().bytes());
        } catch (IllegalArgumentException e) {
            return new SummaryRefresh(
                    observed,
                    List.of(),
                    List.of(),
                    List.of(
                            new CatalogProblem(
                                    "SUMMARY_UNREADABLE",
                                    "The summary cannot be read: " + e.getMessage())),
                    null,
                    List.of());
        }
        if (!table.columns().contains("Product")) {
            return new SummaryRefresh(
                    observed,
                    List.of(),
                    List.of(),
                    List.of(
                            new CatalogProblem(
                                    "SUMMARY_COLUMNS_MISSING",
                                    "The summary has no Product column")),
                    table.sequence(),
                    List.of());
        }
        var products = new TreeSet<String>();
        var versioned = new HashSet<String>();
        boolean flags = table.columns().contains("Flags");
        int bad = 0;
        List<CatalogProblem> problems = new ArrayList<>();
        for (var row : table.rows()) {
            String product = row.fields().get("Product");
            if (product.matches("[a-z0-9_-]{1,64}")) {
                products.add(product);
                if (flags && row.fields().get("Flags").isEmpty()) {
                    versioned.add(product);
                }
            } else if (++bad <= 50) {
                problems.add(
                        new CatalogProblem(
                                "SUMMARY_ROW_INVALID",
                                "A summary product is invalid",
                                null,
                                row.ordinal()));
            }
        }
        if (bad > 50) {
            problems.add(
                    new CatalogProblem(
                            "CATALOG_PROBLEMS_TRUNCATED",
                            (bad - 50) + " more summary rows cannot be read"));
        }
        List<String> listed = List.copyOf(products);
        List<String> fresh =
                listed.stream().filter(p -> Products.isWow(p) && !Products.isKnown(p)).toList();
        List<String> without =
                flags ? listed.stream().filter(p -> !versioned.contains(p)).toList() : List.of();
        return new SummaryRefresh(observed, fresh, listed, problems, table.sequence(), without);
    }
}
