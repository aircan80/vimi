package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.Builds;
import com.vibeminer.formats.tables.BuildTables;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in World measurement wiring: helper JVM options and the probe's GC log parsing. */
public final class WorldMeasurementTest {
    public static void main(String[] args) {
        helperGetsNoOptionsWhileTimingsAreOff();
        helperGetsTheSwitchAndItsOwnTable();
        blankTableFileIsNotForwarded();
        gcLogPausesAreGroupedByKind();
        measurementWorldNeedsPreparedFiles();
        measurementWorldClosesTheWorldBeforeItsTables();
        System.out.println(
                "World measurement: helper options, GC log pauses and the measurement World passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void helperGetsNoOptionsWhileTimingsAreOff() {
        check(GpuPreviewService.measurementArguments(false, "x").isEmpty(), "off: no options");
    }

    private static void helperGetsTheSwitchAndItsOwnTable() {
        var options = GpuPreviewService.measurementArguments(true, "out/child.txt");
        check(
                options.equals(
                        List.of(
                                "-Dvimi.world.stageTimings=true",
                                "-Dvimi.world.stageTimings.file=out/child.txt")),
                "on: " + options);
    }

    private static void blankTableFileIsNotForwarded() {
        check(
                GpuPreviewService.measurementArguments(true, " ")
                        .equals(List.of("-Dvimi.world.stageTimings=true")),
                "blank file");
        check(
                GpuPreviewService.measurementArguments(true, null)
                        .equals(List.of("-Dvimi.world.stageTimings=true")),
                "no file");
    }

    private static void gcLogPausesAreGroupedByKind() {
        var pauses =
                WorldMeasurementProbe.gcPauses(
                        List.of(
                                "[1.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause)"
                                        + " 24M->6M(256M) 3.456ms",
                                "[2.000s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause)"
                                        + " 30M->7M(256M) 1.500ms",
                                "[2.100s][info][gc,phases] GC(1)   Pre Evacuate Collection Set: 0.1ms",
                                "[3.000s][info][gc] GC(2) Pause Remark 40M->40M(256M) 0.800ms",
                                "[3.500s][info][gc] GC(2) Concurrent Mark Cycle 50.000ms"));
        check(pauses.size() == 2, "two pause kinds: " + pauses);
        check(
                pauses.get("Pause Young (Normal) (G1 Evacuation Pause)")
                        .equals(List.of(3.456, 1.5)),
                "young pauses: " + pauses);
        check(pauses.get("Pause Remark").equals(List.of(0.8)), "remark: " + pauses);
    }

    private static <T> T refusing(Class<T> type) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, values) -> {
                            throw new AssertionError("Unexpected " + method.getName());
                        }));
    }

    private static void measurementWorldNeedsPreparedFiles() {
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3.45678",
                        "us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        var evidence = new BuildInfo.RegionEvidence("us", "b".repeat(32), true, null, null);
        Builds builds =
                (build, token) -> CompletableFuture.failedFuture(new AssertionError("No opening"));
        var work =
                new NativePreparationWork(
                        info,
                        evidence,
                        "enUS",
                        builds,
                        refusing(ObjectSource.class),
                        null,
                        null,
                        refusing(ObjectSource.class));
        try {
            work.openWorldForMeasurement(Cancellation.NONE);
            throw new AssertionError("Opened a measurement World without prepared files");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Files must be prepared"), expected.getMessage());
        }
    }

    private static void measurementWorldClosesTheWorldBeforeItsTables() {
        var tablesClosed = new AtomicBoolean();
        var worldRef = new com.vibeminer.concepts.world.WorldService[1];
        var tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, values) -> {
                                    if (!method.getName().equals("close"))
                                        throw new AssertionError("Unexpected " + method.getName());
                                    try {
                                        worldRef[0].waterPreview();
                                        throw new AssertionError("World still open at table close");
                                    } catch (IllegalStateException closed) {
                                        tablesClosed.set(true);
                                    }
                                    return null;
                                });
        worldRef[0] = new com.vibeminer.concepts.world.WorldService(tables, null, null);
        new NativePreparationWork.MeasurementWorld(tables, worldRef[0]).close();
        check(tablesClosed.get(), "tables closed after the World");
    }
}
