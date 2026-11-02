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
                for (String area : List.of("Tables", "Items", "Creatures")) {
                    require(
                            PreparationStatus.available(area, ready)
                                    == (ready == BuildPreparation.Ready.TABLES_READY),
                            "One shared initial gate");
                    if (!snapshot.busy())
                        require(
                                !PreparationStatus.tab(area, snapshot, false).contains("Loading"),
                                "Terminal state must not animate loading");
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
        System.out.println(
                "Passed readiness/activity gate matrix and enrichment presentation checks");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
