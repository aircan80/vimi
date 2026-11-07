package com.vimi.gui;

import java.util.List;

public final class PreparationStatusTest {
    private PreparationStatusTest() {}

    public static void main(String[] args) {
        require(
                PreparationStatus.activityFocus(List.of(), null) == null,
                "Empty immutable activity list must allow no selected build");
        for (var ready : BuildPreparation.Ready.values())
            for (var activity : BuildPreparation.Activity.values()) {
                var snapshot =
                        new BuildPreparation.Snapshot(
                                1,
                                ready,
                                BuildPreparation.Phase.TABLES,
                                activity,
                                12,
                                100L,
                                "tables",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null);
                var closed = PreparationStatus.closed(ready, "enUS");
                require(closed.startsWith("Closed ·"), "Closed is distinct from unprepared");
                require(closed.contains(PreparationStatus.readiness(ready)), "Keep last milestone");
                require(closed.endsWith("enUS"), "Keep assessed locale");
                require(!closed.contains("Loading") && !closed.contains("Preparing"),
                        "Closed must not report active preparation");
                boolean files = ready.ordinal() >= BuildPreparation.Ready.FILES_READY.ordinal();
                require(PreparationStatus.available("Files", ready) == files, "Files milestone");
                for (String area : List.of("Tables", "Items", "Creatures", "Talents", "Races", "Dungeons", "Mounts", "Professions", "World", "Maps")) {
                    require(
                            PreparationStatus.available(area, ready)
                                    == (ready == BuildPreparation.Ready.TABLES_READY),
                            "One shared initial gate");
                    if (!snapshot.busy())
                        require(
                                !PreparationStatus.tab(area, snapshot, false).contains("Loading"),
                                "Terminal state must not animate loading");
                }
                if (ready == BuildPreparation.Ready.TABLES_READY) {
                    require(PreparationStatus.tab("Maps", snapshot, false).equals("Maps"),
                            "Ready Maps tab must stop loading even during later background enrichment");
                    require(PreparationStatus.tab("Maps", snapshot, true).equals("Maps"),
                            "Name enrichment must not keep ready Maps disabled or loading");
                }
                if (ready == BuildPreparation.Ready.TABLES_READY && snapshot.busy())
                    require(
                            PreparationStatus.tab("Tables", snapshot, false).contains("Enriching"),
                            "Later enrichment keeps data available");
                if (activity == BuildPreparation.Activity.CANCELED)
                    require(
                            PreparationStatus.describe(snapshot).contains("canceled"),
                            "Visible cancel outcome");
            }
        lookupProgress();
        System.out.println(
                "Passed readiness/activity gate matrix and enrichment presentation checks");
    }

    /** #338: lookup steps show "n/5", open-ended counts show "n unit", and one timing line. */
    private static void lookupProgress() {
        require(
                PreparationStatus.describe(lookups(1, 5L, "lookup steps · item catalog"))
                        .contains("Preparing lookups… 1/5 lookup steps · item catalog"),
                "Lookup step count");
        require(
                PreparationStatus.describe(lookups(4000, null, "catalog rows stored"))
                        .contains("Preparing lookups… 4000 catalog rows stored"),
                "Open-ended count");
        require(
                !PreparationStatus.describe(lookups(0, null, "catalog rows stored"))
                        .contains("catalog rows stored"),
                "No count before the first row");
        require(
                NativePreparationWork.stepLabel(1).equals("lookup step 2 of 5 (item catalog)"),
                "Step label");
        var line =
                NativePreparationWork.lookupTimings(
                        new long[] {5_376_000_000L, 101_000_000L, 78_000_000L, 21_000_000L, 337_000_000L},
                        5_920_000_000L);
        require(
                line.equals(
                        "Research lookups prepared in 5920 ms (items 5376 ms, item catalog 101 ms,"
                                + " item attributes 78 ms, creatures 21 ms, effects 337 ms)"),
                "Timing line: " + line);
    }

    private static BuildPreparation.Snapshot lookups(long done, Long total, String unit) {
        return new BuildPreparation.Snapshot(
                1,
                BuildPreparation.Ready.TABLES_READY,
                BuildPreparation.Phase.LOOKUPS,
                BuildPreparation.Activity.RUNNING,
                done,
                total,
                unit,
                null,
                null,
                List.of(),
                List.of(),
                null);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
