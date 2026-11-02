package com.vimi.gui;

import com.vibeminer.builds.BuildTypes;
import com.vibeminer.generated.MemoryGeneratedStore;

/** Historical UI assessments survive restarts without becoming live readiness. */
public final class PreparationHistoryTest {
    private PreparationHistoryTest() {}

    public static void main(String[] args) {
        var store = new MemoryGeneratedStore();
        var build =
                new BuildInfo(
                        "wowf",
                        "Forever",
                        "1.60.1.70707",
                        "us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.SAVED,
                        false,
                        null,
                        0);
        var ref = new BuildTypes.BuildRef("wowf", build.config(), "b".repeat(32), "us");
        var first = new PreparationHistory(store);
        first.load();
        require(first.summary(build) == null, "Unassessed is not never opened");
        first.record(build, ref, "enUS", BuildPreparation.Ready.TABLES_READY);
        var reopened = new PreparationHistory(store);
        reopened.load();
        require(
                "Previously: Tables ready · enUS".equals(reopened.summary(build)),
                "Lost saved readiness across restart");
        reopened.record(build, ref, "enUS", BuildPreparation.Ready.METADATA_READY);
        require(
                reopened.summary(build).contains("Tables ready"),
                "Rechecking a prior milestone must not erase its history");
        reopened.record(build, ref, "deDE", BuildPreparation.Ready.FILES_READY);
        var another = new PreparationHistory(store);
        another.load();
        require(
                "Previously: Files ready · deDE".equals(another.summary(build)),
                "A different locale has a separate assessment");
        var other =
                new BuildInfo(
                        "wowf",
                        "Forever",
                        build.version(),
                        "us",
                        "c".repeat(32),
                        build.source(),
                        false,
                        null,
                        0,
                        build.identity(),
                        null,
                        null,
                        java.util.List.of());
        require(
                another.summary(other) == null,
                "Must not transfer history to another build configuration");
        require(
                !PreparationStatus.available("Tables", BuildPreparation.Ready.NOT_PREPARED),
                "History must not grant current workspace readiness");
        var snapshot =
                new BuildPreparation.Snapshot(
                        1,
                        BuildPreparation.Ready.FILES_READY,
                        BuildPreparation.Phase.TABLES,
                        BuildPreparation.Activity.RUNNING,
                        1,
                        4L,
                        "cached tables restored",
                        null,
                        null,
                        java.util.List.of(),
                        java.util.List.of(),
                        null);
        require(
                PreparationStatus.describe(snapshot).contains("Restoring cached tables"),
                "Restoration is not fresh preparation");
        require(
                OverviewPresentation.from(snapshot, false, null)
                        .activity()
                        .equals("Restoring cached tables"),
                "Overview must distinguish cache restoration");
        System.out.println(
                "Passed durable historical readiness, build/locale isolation and cached table status presentation");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
