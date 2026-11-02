package com.vimi.gui;

import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure live Overview projection. Unknown measurements never become zero or mock estimates. */
public record OverviewPresentation(
        Metric files,
        Metric tables,
        Metric names,
        Metric stored,
        Metric items,
        Metric creatures,
        boolean filesEnabled,
        boolean tablesEnabled,
        String configurationReadiness,
        String filesReadiness,
        String tablesReadiness,
        String activity,
        String counter,
        double phaseProgress,
        boolean active,
        List<String> issues) {
    public record Metric(String value, String scope) {}

    public OverviewPresentation {
        issues = List.copyOf(issues);
    }

    public static OverviewPresentation from(
            BuildPreparation.Snapshot snapshot, boolean namesRunning, String namesProblem) {
        boolean filesReady =
                snapshot.ready().ordinal() >= BuildPreparation.Ready.FILES_READY.ordinal();
        boolean tablesReady = snapshot.ready() == BuildPreparation.Ready.TABLES_READY;
        var fileSummary = filesReady ? snapshot.files() : null;
        String unavailable =
                snapshot.busy()
                        ? "Preparing…"
                        : snapshot.activity() == BuildPreparation.Activity.FAILED
                                        || snapshot.activity() == BuildPreparation.Activity.CANCELED
                                ? "Not ready"
                                : "Not prepared";
        String filePending =
                snapshot.busy() && snapshot.phase() == BuildPreparation.Phase.METADATA
                        ? "Waiting for metadata"
                        : unavailable;
        String tablePending = snapshot.busy() && !filesReady ? "Waiting for files" : unavailable;
        Metric files =
                fileSummary == null
                        ? new Metric(filePending, "File catalog has not been prepared")
                        : new Metric(
                                count(fileSummary.files()),
                                "Distinct FileDataIDs · Selected variants stored at last file assessment: "
                                        + count(fileSummary.stored()));
        long present = snapshot.tables().stream().filter(OverviewPresentation::present).count();
        long readable = snapshot.tables().stream().filter(OverviewPresentation::readable).count();
        long partial =
                snapshot.tables().stream()
                        .filter(table -> table.state() == TableTypes.State.PARTIAL)
                        .count();
        Metric tables =
                tablesReady
                        ? new Metric(
                                count(readable),
                                "of "
                                        + count(present)
                                        + " present known tables"
                                        + (partial > 0 ? " · " + count(partial) + " partial" : ""))
                        : new Metric(tablePending, "Common table preparation has not completed");
        var names =
                new Metric(
                        "Not measured",
                        namesRunning
                                ? "Community names updating · build coverage not measured"
                                : "Build-scoped filename coverage has not been measured");
        var stored =
                new Metric("Not measured", "Stored encoded bytes and total byte scope are unknown");
        String stopped =
                snapshot.busy()
                        ? "Preparing…"
                        : snapshot.activity() == BuildPreparation.Activity.CANCELED
                                ? "Not ready · canceled"
                                : snapshot.activity() == BuildPreparation.Activity.FAILED
                                        ? "Not ready · failed"
                                        : "Not prepared";
        String activity =
                snapshot.activity() == BuildPreparation.Activity.STOPPING
                        ? "Stopping…"
                        : snapshot.busy()
                                ? phase(snapshot.phase())
                                : snapshot.activity() == BuildPreparation.Activity.FAILED
                                        ? "Preparation stopped · failed"
                                        : snapshot.activity() == BuildPreparation.Activity.CANCELED
                                                ? "Preparation stopped · canceled"
                                                : namesRunning
                                                        ? "Updating community names"
                                                        : tablesReady
                                                                ? "Preparation complete"
                                                                : "Preparation incomplete";
        double progress = -1;
        String counter = "";
        if (snapshot.busy()) {
            if (snapshot.total() != null) {
                counter =
                        count(snapshot.done())
                                + " / "
                                + count(snapshot.total())
                                + " "
                                + snapshot.unit();
                if (snapshot.activity() == BuildPreparation.Activity.RUNNING)
                    progress =
                            snapshot.total() == 0
                                    ? -1
                                    : Math.min(1, (double) snapshot.done() / snapshot.total());
            } else if (snapshot.done() > 0)
                counter = count(snapshot.done()) + " " + snapshot.unit();
        } else if (namesRunning) counter = "Filename coverage is not measured";
        var issues = new ArrayList<>(snapshot.issues());
        if (snapshot.problem() != null) issues.add(snapshot.problem());
        if (namesProblem != null) issues.add("Community names: " + namesProblem);
        return new OverviewPresentation(
                files,
                tables,
                names,
                stored,
                records(snapshot, "Item", tablesReady),
                records(snapshot, "Creature", tablesReady),
                filesReady,
                tablesReady,
                snapshot.metadata() != null ? "Verified" : stopped,
                filesReady ? "Catalogued" : stopped,
                tablesReady ? (partial > 0 ? "Ready · partial tables included" : "Ready") : stopped,
                activity,
                counter,
                progress,
                snapshot.busy() || namesRunning,
                issues);
    }

    private static boolean present(TableTypes.Entry entry) {
        return entry.state() != TableTypes.State.NOT_IN_BUILD
                && entry.state() != TableTypes.State.NOT_IN_LOCALE
                && entry.state() != TableTypes.State.NOT_CHECKED;
    }

    private static boolean readable(TableTypes.Entry entry) {
        return entry.state() == TableTypes.State.READY || entry.state() == TableTypes.State.PARTIAL;
    }

    private static Metric records(BuildPreparation.Snapshot snapshot, String name, boolean ready) {
        if (ready)
            for (var entry : snapshot.tables())
                if (entry.name() != null
                        && entry.name().equalsIgnoreCase(name)
                        && readable(entry)
                        && entry.counts() != null) {
                    return new Metric(
                            count(entry.counts().readableRows()),
                            "Readable "
                                    + name.toLowerCase(Locale.ROOT)
                                    + " records"
                                    + (entry.state() == TableTypes.State.PARTIAL
                                            ? " · partial source"
                                            : ""));
                }
        String value =
                ready
                        ? "Not measured"
                        : snapshot.busy()
                                ? "Waiting for tables"
                                : snapshot.activity() == BuildPreparation.Activity.FAILED
                                                || snapshot.activity()
                                                        == BuildPreparation.Activity.CANCELED
                                        ? "Not ready"
                                        : "Not prepared";
        return new Metric(value, name + " records have not been measured from a readable table");
    }

    private static String phase(BuildPreparation.Phase phase) {
        return switch (phase) {
            case METADATA -> "Verifying build metadata";
            case FILES -> "Preparing file catalog";
            case KEYS -> "Bootstrapping table keys";
            case TABLES -> "Preparing common tables";
            case LOOKUPS -> "Preparing research lookups";
            case IDLE -> "Preparing build";
        };
    }

    private static String count(long number) {
        return String.format(Locale.US, "%,d", number);
    }
}
