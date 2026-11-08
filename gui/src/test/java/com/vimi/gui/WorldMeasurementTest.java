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
        npcAnswersAreClassifiedByOutcome();
        npcAnswersNameAppearanceResults();
        choiceReasonsCountOnlyUnqualifiedNpcs();
        waterIssueKindsKeepTheirTextAndGroupByNumber();
        System.out.println(
                "World measurement: helper options, GC log pauses, the measurement World, NPC"
                        + " outcomes, choice reasons and water issue kinds passed");
    }

    private static void waterIssueKindsKeepTheirTextAndGroupByNumber() {
        check(
                WorldMeasurementProbe.issueKind("3 malformed liquid instance(s) were left out")
                        .equals("# malformed liquid instance(s) were left out"),
                "malformed kind");
        check(
                WorldMeasurementProbe.issueKind("12 layer(s) without vertex heights at 0.5")
                        .equals(WorldMeasurementProbe.issueKind("7 layer(s) without vertex heights at 2.25")),
                "counts and levels group into one kind");
        check(
                WorldMeasurementProbe.issueKind("LiquidType 2 resolves to depth-only")
                        .equals("LiquidType # resolves to depth-only"),
                "text after a number is kept");
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
        try {
            work.openCreaturesForMeasurement(null, Cancellation.NONE);
            throw new AssertionError("Opened measurement creatures without prepared files");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Files must be prepared"), expected.getMessage());
        }
    }

    /** An answer with a result: built, or refused by the result's message (or without one). */
    private static void npcAnswersNameAppearanceResults() {
        var built =
                new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY,
                        WorldTileCreaturesTest.appearance(true),
                        java.util.Set.of(0),
                        java.util.List.of(),
                        java.util.List.of(),
                        false,
                        null);
        check(
                WorldMeasurementProbe.NpcAnswers.outcome(
                                new com.vibeminer.concepts.races.RaceNpcs.Appearance(
                                        built, 0, 0, null, false))
                        .equals("appearance built"),
                "A built appearance");
        for (String message : new String[] {"no race model", null}) {
            var empty =
                    new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                            com.vibeminer.concepts.races.RaceTypes.State.READY,
                            null,
                            java.util.Set.of(),
                            java.util.List.of(),
                            java.util.List.of(),
                            false,
                            message);
            String outcome =
                    WorldMeasurementProbe.NpcAnswers.outcome(
                            new com.vibeminer.concepts.races.RaceNpcs.Appearance(
                                    empty, 0, 0, null, false));
            check(
                    outcome.equals(
                            "refused: " + (message == null ? "no NPC appearance" : message)),
                    "A result without an appearance: " + outcome);
        }
    }

    /**
     * The probe's choice reasons (#553): qualified NPCs are not counted, each NPC keeps its latest
     * reason, and each reason keeps its first example detail.
     */
    private static void choiceReasonsCountOnlyUnqualifiedNpcs() {
        var choices = new java.util.HashMap<Long, com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice>();
        var absent =
                new com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice(
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.State.UNKNOWN,
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason.CREATURE_ABSENT,
                        0, null, null, null, "no record 1");
        choices.put(1L, absent);
        choices.put(
                2L,
                new com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice(
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.State.UNKNOWN,
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason.CREATURE_ABSENT,
                        0, null, null, null, "no record 2"));
        choices.put(
                3L,
                new com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice(
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.State.AMBIGUOUS,
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason.SEVERAL_DISPLAYS,
                        2, null, null, null, "two displays"));
        choices.put(
                4L,
                new com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice(
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.State.QUALIFIED,
                        com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason.SINGLE_DISPLAY,
                        1, 7, 8L, 9L, "one display"));
        var probe =
                new WorldMeasurementProbe.ChoiceReasons(
                        (npc, token) -> new WorldTileCreatures.Chosen(choices.get(npc), null));
        try {
            for (long npc = 1; npc <= 4; npc++) probe.choose(npc, Cancellation.NONE);
            check(
                    probe.tally()
                            .equals(
                                    java.util.Map.of(
                                            "UNKNOWN CREATURE_ABSENT", 2,
                                            "AMBIGUOUS SEVERAL_DISPLAYS", 1)),
                    "Only unqualified NPCs are counted: " + probe.tally());
            check(
                    probe.examples.get("UNKNOWN CREATURE_ABSENT").equals("no record 1"),
                    "The first example detail is kept");
            // NPC 3 asked again with another reason: only its latest counts.
            choices.put(3L, absent);
            probe.choose(3, Cancellation.NONE);
            check(
                    probe.tally().equals(java.util.Map.of("UNKNOWN CREATURE_ABSENT", 3)),
                    "Latest reason per NPC: " + probe.tally());
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    /** The probe's NPC counts (#553) name each display's latest answer, refusals by reason. */
    private static void npcAnswersAreClassifiedByOutcome() throws RuntimeException {
        var answers =
                new java.util.LinkedHashMap<Long, com.vibeminer.concepts.races.RaceNpcs.Appearance>();
        answers.put(1L, com.vibeminer.concepts.races.RaceNpcs.Appearance.refused("no bake"));
        answers.put(2L, com.vibeminer.concepts.races.RaceNpcs.Appearance.refused("no bake"));
        answers.put(3L, new com.vibeminer.concepts.races.RaceNpcs.Appearance(null, 0, 0, "busy", true));
        var probe =
                new WorldMeasurementProbe.NpcAnswers((id, token) -> answers.get(id));
        try {
            for (long id = 1; id <= 3; id++) probe.appearance(id, Cancellation.NONE);
            probe.appearance(4, Cancellation.NONE);
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        check(
                probe.tally()
                        .equals(
                                java.util.Map.of(
                                        "refused: no bake", 2,
                                        "not readable yet: busy", 1,
                                        "refused: no NPC appearance", 1)),
                "NPC outcomes: " + probe.tally());
        // A display asked again keeps only its latest answer.
        answers.put(3L, com.vibeminer.concepts.races.RaceNpcs.Appearance.refused("no bake"));
        try {
            probe.appearance(3, Cancellation.NONE);
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        check(probe.tally().get("refused: no bake") == 3, "Latest answer: " + probe.tally());
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
