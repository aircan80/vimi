package com.vimi.gui;

import com.vibeminer.formats.tables.TableTypes;

import java.util.List;

/** Pure presentation contracts; no JavaFX toolkit, mock snapshot data or native libraries. */
public final class OverviewPresentationTest {
    private OverviewPresentationTest() {}

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static BuildPreparation.Snapshot snapshot(
            BuildPreparation.Ready ready,
            BuildPreparation.Activity activity,
            BuildPreparation.Phase phase,
            BuildPreparation.FilesSummary files,
            List<TableTypes.Entry> tables,
            long done,
            Long total) {
        return new BuildPreparation.Snapshot(
                2,
                ready,
                phase,
                activity,
                done,
                total,
                "tables",
                null,
                files,
                tables,
                List.of(),
                activity == BuildPreparation.Activity.FAILED ? "Actual failure" : null);
    }

    private static TableTypes.Entry table(
            long id, String name, TableTypes.State state, long declared, long readable) {
        return new TableTypes.Entry(
                id,
                name,
                state,
                true,
                null,
                new TableTypes.Counts(declared, readable, 0, 0, "WDC5", "00000000"),
                null);
    }

    public static void main(String[] args) {
        accountingScopesRemainExplicit();
        unknownIsNotZero();
        filesFactsDoNotBecomeByteCoverage();
        committedLedgerCountsQualifyPartialSources();
        protectedRowsAreNotReadableCounts();
        phaseCountersAreHonest();
        terminalAndNamesActivitiesStayDistinct();
        enrichmentPreservesGatesAndCommittedFacts();
        emptyLedgerIsMeasuredZero();
        System.out.println("OverviewPresentation: 9 pure live-data contracts passed");
    }

    private static void accountingScopesRemainExplicit() {
        var usage =
                new com.vibeminer.accounting.UsageIndex.Figures(
                        new com.vibeminer.accounting.UsageIndex.Split(10, 20, 0, 0),
                        new com.vibeminer.accounting.UsageIndex.Split(5, 7, 0, 100),
                        90,
                        2,
                        3,
                        1,
                        3,
                        1,
                        false,
                        java.time.Instant.EPOCH.plusSeconds(1));
        var accounting =
                new BuildAccounting(
                        usage,
                        new com.vibeminer.files.names.NameCoverage.Result(8, 3),
                        "listfile-v1",
                        null,
                        false,
                        new com.vibeminer.accounting.LibraryUsage.Totals(
                                java.util.Map.of(
                                        com.vibeminer.accounting.LibraryUsage.Kind.ORIGINAL_PACKS,
                                        500L),
                                1,
                                true));
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.FILES_READY,
                                BuildPreparation.Activity.IDLE,
                                BuildPreparation.Phase.IDLE,
                                new BuildPreparation.FilesSummary(8, 0, 0, false),
                                List.of(),
                                0,
                                null),
                        false,
                        null,
                        accounting);
        require(
                model.names().value().equals("3 / 8")
                        && model.names().scope().contains("listfile-v1"),
                "Names retain provider snapshot and locale scope");
        require(
                model.stored().value().equals("30 B / 90 B"),
                "Stored bytes are actual payloads, denominator is published bytes");
        require(
                model.stored().scope().contains("split provisional")
                        && !model.stored().scope().contains("Build-only"),
                "Unindexed builds must not create a unique claim");
        require(
                model.derived().scope().contains("Attribution incomplete")
                        && model.derived().scope().contains("SQLite files 100 B"),
                "Derived payloads and database file lengths remain separate");
        require(
                model.library().value().equals("500 B") && !model.tablesEnabled(),
                "Disk measurements never grant preparation readiness");
    }

    private static void unknownIsNotZero() {
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.METADATA_READY,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.FILES,
                                null,
                                List.of(),
                                0,
                                null),
                        false,
                        null);
        require(
                model.files().value().equals("Preparing…")
                        && model.tables().value().equals("Waiting for files"),
                "Unprepared metrics must not become zero");
        require(
                !model.filesEnabled() && !model.tablesEnabled(),
                "Metadata alone does not enable data areas");
        require(
                model.phaseProgress() == -1 && model.filesReadiness().contains("Preparing"),
                "Unknown work remains indeterminate");
    }

    private static void filesFactsDoNotBecomeByteCoverage() {
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.FILES_READY,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.TABLES,
                                new BuildPreparation.FilesSummary(1200, 300, 2),
                                List.of(),
                                0,
                                null),
                        false,
                        null);
        require(
                model.files().value().equals("1,200") && model.files().scope().contains("300"),
                "Actual catalog and presence counts");
        require(
                model.filesEnabled() && !model.tablesEnabled(),
                "Files independent from common table gate");
        require(
                model.stored().value().equals("Not measured")
                        && model.names().value().equals("Not measured"),
                "Presence is neither encoded bytes nor filename coverage");
        require(
                model.items().value().equals("Waiting for tables"),
                "Item count waits for tables without using mock totals");
    }

    private static void committedLedgerCountsQualifyPartialSources() {
        var ledger =
                List.of(
                        table(1, "Item", TableTypes.State.READY, 42, 42),
                        table(2, "Creature", TableTypes.State.PARTIAL, 100, 5),
                        table(3, "Protected", TableTypes.State.ENCRYPTED, 700, 0),
                        table(4, "Absent", TableTypes.State.NOT_IN_BUILD, 900, 0),
                        table(5, "OtherLocale", TableTypes.State.NOT_IN_LOCALE, 10, 0),
                        table(6, "UnknownMembership", TableTypes.State.NOT_CHECKED, 10, 0));
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.TABLES_READY,
                                BuildPreparation.Activity.IDLE,
                                BuildPreparation.Phase.IDLE,
                                new BuildPreparation.FilesSummary(12, 3, 2),
                                ledger,
                                6,
                                6L),
                        false,
                        null);
        require(
                model.tables().value().equals("2")
                        && model.tables().scope().contains("of 3 present")
                        && model.tables().scope().contains("1 partial"),
                "Denominator excludes absent/unassessed membership; partial is qualified");
        require(
                model.items().value().equals("42"),
                "Item count comes from committed readable source");
        require(
                model.creatures().value().equals("5")
                        && model.creatures().scope().contains("partial"),
                "Partial Creature count uses readable rows, never declared protected rows");
        require(model.tablesEnabled(), "Common table-ready gate enables table and concept areas");
    }

    private static void protectedRowsAreNotReadableCounts() {
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.TABLES_READY,
                                BuildPreparation.Activity.IDLE,
                                BuildPreparation.Phase.IDLE,
                                null,
                                List.of(
                                        table(1, "Item", TableTypes.State.ENCRYPTED, 987654, 0),
                                        table(2, null, TableTypes.State.READY, 12, 12)),
                                0,
                                0L),
                        false,
                        null);
        require(
                model.items().value().equals("Not measured"),
                "Protected source is not a measured zero or declared row count");
        require(
                model.tables().value().equals("1")
                        && model.tables().scope().contains("of 2 present"),
                "Present protected table belongs in ledger denominator");
    }

    private static void phaseCountersAreHonest() {
        var measured =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.FILES_READY,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.TABLES,
                                null,
                                List.of(),
                                5,
                                10L),
                        false,
                        null);
        require(
                measured.phaseProgress() == 0.5 && measured.counter().equals("5 / 10 tables"),
                "Determinate bar is measured current phase only");
        var zero =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.FILES_READY,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.TABLES,
                                null,
                                List.of(),
                                0,
                                0L),
                        false,
                        null);
        require(zero.phaseProgress() == -1, "Zero denominator never invents 100 percent");
        var unknown =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.NOT_PREPARED,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.METADATA,
                                null,
                                List.of(),
                                0,
                                null),
                        false,
                        null);
        require(
                unknown.phaseProgress() == -1 && unknown.counter().isEmpty(),
                "No weighted or stage-ordinal percentages");
    }

    private static void terminalAndNamesActivitiesStayDistinct() {
        for (var state :
                List.of(BuildPreparation.Activity.FAILED, BuildPreparation.Activity.CANCELED)) {
            var model =
                    OverviewPresentation.from(
                            snapshot(
                                    BuildPreparation.Ready.FILES_READY,
                                    state,
                                    BuildPreparation.Phase.TABLES,
                                    null,
                                    List.of(),
                                    5,
                                    10L),
                            true,
                            "Names unavailable");
            require(
                    model.activity().contains("stopped")
                            && !model.tablesReadiness().contains("Preparing")
                            && model.tables().value().equals("Not ready"),
                    "Terminal initial pipeline is not labeled loading");
            require(
                    model.names().value().equals("Not measured")
                            && model.names().scope().contains("updating"),
                    "Names activity is independent from initial failure");
            require(
                    model.phaseProgress() == -1
                            && model.issues().stream()
                                    .anyMatch(issue -> issue.contains("Names unavailable")),
                    "Independent failure surfaced without invented coverage");
        }
    }

    private static void enrichmentPreservesGatesAndCommittedFacts() {
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.TABLES_READY,
                                BuildPreparation.Activity.RUNNING,
                                BuildPreparation.Phase.LOOKUPS,
                                new BuildPreparation.FilesSummary(20, 7, 1),
                                List.of(table(1, "Item", TableTypes.State.READY, 12, 12)),
                                0,
                                null),
                        false,
                        null);
        require(
                model.filesEnabled() && model.tablesEnabled() && model.items().value().equals("12"),
                "Enrichment does not erase committed readiness/data");
        require(
                model.activity().contains("lookups") && model.phaseProgress() == -1,
                "Current enrichment remains visible");
    }

    private static void emptyLedgerIsMeasuredZero() {
        var model =
                OverviewPresentation.from(
                        snapshot(
                                BuildPreparation.Ready.TABLES_READY,
                                BuildPreparation.Activity.IDLE,
                                BuildPreparation.Phase.IDLE,
                                new BuildPreparation.FilesSummary(0, 0, 0),
                                List.of(),
                                0,
                                0L),
                        false,
                        null);
        require(
                model.files().value().equals("0") && model.tables().value().equals("0"),
                "Actual committed empty scopes are measured zero");
        require(
                model.items().value().equals("Not measured")
                        && model.stored().value().equals("Not measured"),
                "Absent record/byte measurements remain unknown");
    }
}
