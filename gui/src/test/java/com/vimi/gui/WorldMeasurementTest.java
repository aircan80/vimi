package com.vimi.gui;

import java.util.List;

/** Opt-in World measurement wiring: helper JVM options and the probe's GC log parsing. */
public final class WorldMeasurementTest {
    public static void main(String[] args) {
        helperGetsNoOptionsWhileTimingsAreOff();
        helperGetsTheSwitchAndItsOwnTable();
        blankTableFileIsNotForwarded();
        gcLogPausesAreGroupedByKind();
        System.out.println("World measurement: helper options and GC log pauses passed");
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
}
