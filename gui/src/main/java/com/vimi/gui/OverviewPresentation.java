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
        Metric derived,
        Metric library,
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
        return from(snapshot, namesRunning, namesProblem, null);
    }

    public static OverviewPresentation from(
            BuildPreparation.Snapshot snapshot,
            boolean namesRunning,
            String namesProblem,
            BuildAccounting accounting) {
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
                                "Distinct FileDataIDs · Selected variants stored at last file"
                                        + " assessment: "
                                        + (fileSummary.presenceAssessed()
                                                ? count(fileSummary.stored())
                                                : "Not assessed; checked while browsing"));
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
        Metric derived = new Metric("Not measured", "Derived attribution has not been measured");
        Metric library = new Metric("Not measured", "Library file lengths have not been measured");
        if (accounting != null) {
            if (accounting.names() != null) {
                var coverage = accounting.names();
                names =
                        new Metric(
                                count(coverage.named()) + " / " + count(coverage.files()),
                                "Community labels · selected locale · "
                                        + accounting.nameRevision());
            } else if (accounting.measuring())
                names = new Metric("Measuring…", "Selected-locale community-name coverage");
            var measured = accounting.usage();
            if (measured != null && !measured.measuredAt().equals(java.time.Instant.EPOCH)) {
                var original = measured.original();
                long storedBytes = original.onlyThisBuild() + original.shared() + original.global();
                String scope =
                        (measured.incompleteBuilds() == 0
                                        ? "Build-only "
                                        : "Only this indexed build ")
                                + bytes(original.onlyThisBuild())
                                + " · Shared "
                                + bytes(original.shared())
                                + " · "
                                + count(measured.retainedBuilds())
                                + " retained builds"
                                + (measured.incompleteBuilds() > 0
                                        ? " · "
                                                + count(measured.incompleteBuilds())
                                                + " not indexed; split provisional"
                                        : "")
                                + " · All published encoded variants"
                                + (measured.sizeMismatches() > 0
                                        ? " · "
                                                + count(measured.sizeMismatches())
                                                + " declared/stored size discrepancies"
                                        : "")
                                + " · Measured "
                                + measured.measuredAt();
                stored =
                        new Metric(
                                bytes(storedBytes) + " / " + bytes(measured.publishedBytes()),
                                scope);
                var generated = measured.derived();
                derived =
                        new Metric(
                                bytes(generated.onlyThisBuild() + generated.shared()),
                                (measured.derivedComplete()
                                                ? "Build-only "
                                                : "Measured portion · Only this indexed build ")
                                        + bytes(generated.onlyThisBuild())
                                        + " · Shared "
                                        + bytes(generated.shared())
                                        + (measured.derivedComplete()
                                                ? ""
                                                : " · Attribution incomplete")
                                        + (generated.databaseBytes() > 0
                                                ? " · SQLite files "
                                                        + bytes(generated.databaseBytes())
                                                : ""));
            } else if (accounting.measuring())
                stored = new Metric("Measuring…", "Local encoded payloads; no downloads");
            if (accounting.library() != null) {
                var disk = accounting.library();
                library =
                        new Metric(
                                bytes(disk.total()),
                                "Original packs "
                                        + bytes(
                                                disk.bytes()
                                                        .getOrDefault(
                                                                com.vibeminer.accounting
                                                                        .LibraryUsage.Kind
                                                                        .ORIGINAL_PACKS,
                                                                0L))
                                        + " · Generated packs "
                                        + bytes(
                                                disk.bytes()
                                                        .getOrDefault(
                                                                com.vibeminer.accounting
                                                                        .LibraryUsage.Kind
                                                                        .GENERATED_PACKS,
                                                                0L))
                                        + " · Databases "
                                        + bytes(
                                                disk.bytes()
                                                        .getOrDefault(
                                                                com.vibeminer.accounting
                                                                        .LibraryUsage.Kind
                                                                        .DATABASES,
                                                                0L))
                                        + " · WAL "
                                        + bytes(
                                                disk.bytes()
                                                        .getOrDefault(
                                                                com.vibeminer.accounting
                                                                        .LibraryUsage.Kind.WAL,
                                                                0L))
                                        + " · Includes global/unattributed data, staging and"
                                        + " overhead"
                                        + (disk.complete()
                                                ? ""
                                                : " · Scan incomplete; paths excluded or changed")
                                        + " · Point-in-time; not allocated/reclaimable space");
            }
        }
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
                                ? PreparationStatus.restoringTables(snapshot)
                                        ? "Restoring cached tables"
                                        : phase(snapshot.phase())
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
            else
                counter =
                        snapshot.unit() != null && snapshot.unit().contains(" ")
                                ? snapshot.unit()
                                : "";
        } else if (namesRunning) counter = "Filename coverage is not measured";
        var issues = new ArrayList<>(snapshot.issues());
        if (snapshot.problem() != null) issues.add(snapshot.problem());
        if (accounting != null && accounting.problem() != null) issues.add(accounting.problem());
        if (namesProblem != null) issues.add("Community names: " + namesProblem);
        return new OverviewPresentation(
                files,
                tables,
                names,
                stored,
                derived,
                library,
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

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024d);
        if (value < 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f MiB", value / (1024d * 1024));
        return String.format(Locale.ROOT, "%.2f GiB", value / (1024d * 1024 * 1024));
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
