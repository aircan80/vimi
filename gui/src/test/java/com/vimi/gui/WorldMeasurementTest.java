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
        displayCensusCountsStandOutcomesPerCreatureDisplay();
        displayCensusClassifiesGeosetCasesAndDefaults();
        displayTableCensusCountsCasesAndSampleOrderIsFixed();
        displayCensusSeparatesUnresolvedGeosetsAndGroupZeroVariants();
        displaySampleCountsFailuresAndGoesOn();
        System.out.println(
                "World measurement: helper options, GC log pauses, the measurement World, NPC"
                        + " outcomes, choice reasons, water issue kinds and the creature display census passed");
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

    private static com.vibeminer.rendering.models.MeshAppearance parts(int... ids) {
        var list = new java.util.ArrayList<com.vibeminer.rendering.models.PreviewMesh.Part>();
        var surfaces = new java.util.ArrayList<com.vibeminer.rendering.models.MeshAppearance.Surface>();
        int[] indices = new int[ids.length * 3];
        for (int i = 0; i < ids.length; i++) {
            list.add(new com.vibeminer.rendering.models.PreviewMesh.Part(ids[i], i * 3, 3));
            surfaces.add(new com.vibeminer.rendering.models.MeshAppearance.Surface(i, 0, true, true, false));
            indices[i * 3 + 1] = 1;
            indices[i * 3 + 2] = 2;
        }
        return new com.vibeminer.rendering.models.MeshAppearance(
                new com.vibeminer.rendering.models.PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, indices, list),
                new float[6],
                surfaces,
                List.of(new com.vibeminer.rendering.models.MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    private static com.vibeminer.concepts.models.DisplayModelTypes.Preview preview(
            long display, Long extended, Long geosetDataId, java.util.Map<Integer, Integer> rows,
            com.vibeminer.rendering.models.MeshAppearance appearance) {
        var reference =
                new com.vibeminer.concepts.models.DisplayModelTypes.Reference(
                        display, 1, 2, List.of(), extended, geosetDataId, rows);
        return new com.vibeminer.concepts.models.DisplayModelTypes.Preview(
                com.vibeminer.concepts.models.DisplayModelTypes.State.READY,
                reference, appearance, java.util.Set.of(), List.of(), false, null);
    }

    /** The probe's Stand census (#377) counts each creature display's latest idle outcome. */
    private static void displayCensusCountsStandOutcomesPerCreatureDisplay() {
        var standing = WorldTileCreaturesTest.standing();
        // The same triangle one unit higher: a different rest surface.
        var other =
                new com.vibeminer.rendering.models.MeshAppearance(
                        new com.vibeminer.rendering.models.PreviewMesh(
                                new float[] {0, 0, 1, 1, 0, 1, 0, 1, 1},
                                new int[] {0, 1, 2},
                                List.of(new com.vibeminer.rendering.models.PreviewMesh.Part(0, 0, 3))),
                        new float[6],
                        List.of(new com.vibeminer.rendering.models.MeshAppearance.Surface(0, 0, true, true, false)),
                        List.of(new com.vibeminer.rendering.models.MeshAppearance.Texture(1, 1, new int[] {-1})));
        var previews = new java.util.HashMap<Long, com.vibeminer.concepts.models.DisplayModelTypes.Preview>();
        var idles = new java.util.HashMap<Long, com.vibeminer.concepts.models.DisplayModelTypes.Idle>();
        previews.put(1L, preview(1, 0L, 0L, java.util.Map.of(), standing));
        idles.put(1L, new com.vibeminer.concepts.models.DisplayModelTypes.Idle(
                com.vibeminer.concepts.models.DisplayModelTypes.IdleState.AVAILABLE, null,
                WorldTileCreaturesTest.lift(standing), "ok"));
        previews.put(2L, preview(2, 0L, 0L, java.util.Map.of(), standing));
        idles.put(2L, new com.vibeminer.concepts.models.DisplayModelTypes.Idle(
                com.vibeminer.concepts.models.DisplayModelTypes.IdleState.UNSUPPORTED, null, null,
                "Stand uses 3 global sequences"));
        previews.put(3L, preview(3, 0L, 0L, java.util.Map.of(), other));
        idles.put(3L, new com.vibeminer.concepts.models.DisplayModelTypes.Idle(
                com.vibeminer.concepts.models.DisplayModelTypes.IdleState.AVAILABLE, null,
                WorldTileCreaturesTest.lift(standing), "ok"));
        // A character display: not a creature, not counted.
        previews.put(4L, preview(4, 9L, 0L, java.util.Map.of(), standing));
        idles.put(4L, idles.get(1L));
        var census =
                new WorldMeasurementProbe.DisplayCensus(
                        new WorldTileCreatures.Previews() {
                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Preview preview(
                                    long display, Cancellation token) {
                                return previews.get(display);
                            }

                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Idle idle(
                                    long display, Cancellation token) {
                                return idles.get(display);
                            }
                        });
        try {
            for (long display = 1; display <= 4; display++) {
                census.preview(display, Cancellation.NONE);
                census.idle(display, Cancellation.NONE);
            }
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        var tally = census.standTally(false);
        check(
                tally.equals(
                        java.util.Map.of(
                                "Stand pose", 1,
                                "rest pose: UNSUPPORTED Stand uses # global sequences", 1,
                                "rest pose: the Stand sequence belongs to another rest surface", 1)),
                "Stand outcomes per creature display: " + tally);
        check(census.standTally(true).equals(tally), "All three resolve their geosets: " + census.standTally(true));
    }

    /** The probe's geoset census (#455) names each display's case, rule outcome and defaults. */
    private static void displayCensusClassifiesGeosetCasesAndDefaults() {
        var census = new WorldMeasurementProbe.DisplayCensus((display, token) -> null);
        // ID 0, no rows: group 1 has x00 only (hidden), group 2 both (draws 201): drawn.
        census.facts.put(1L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(1, 0L, 0L, java.util.Map.of(), parts(0, 100, 200, 201)).reference(),
                parts(0, 100, 200, 201).mesh()));
        // ID 0: group 1 has x01 only and draws it (rows ignored); group 3 neither: hidden, drawn.
        census.facts.put(2L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(2, 0L, 0L, java.util.Map.of(2, 1), parts(0, 101)).reference(),
                parts(0, 101).mesh()));
        census.facts.put(3L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(3, 0L, 0L, java.util.Map.of(), parts(0, 302)).reference(),
                parts(0, 302).mesh()));
        // ID > 0 with a row choosing 101: drawn.
        census.facts.put(4L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(4, 0L, 5L, java.util.Map.of(1, 1), parts(0, 100, 101)).reference(),
                parts(0, 100, 101).mesh()));
        // ID > 0 without section 0 in group 0: refused.
        census.facts.put(6L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(6, 0L, 5L, java.util.Map.of(), parts(1, 101)).reference(),
                parts(1, 101).mesh()));
        // ID unreadable: refused.
        census.facts.put(5L, WorldMeasurementProbe.DisplayModelFacts.of(
                preview(5, 0L, null, null, parts(0)).reference(), parts(0).mesh()));
        var tally = census.geosetTally();
        check(
                tally.equals(
                        java.util.Map.of(
                                "ID 0, no rows -> drawn", 2,
                                "ID 0, rows -> drawn", 1,
                                "ID > 0, no rows -> refused: geoset group # has no section #", 1,
                                "ID > 0, rows -> drawn", 1,
                                "ID unreadable -> refused: creature geoset data could not be read", 1)),
                "Geoset cases: " + tally);
        var defaults = census.defaultCensus();
        check(
                defaults.equals(
                        "3 displays; groups 1+: x00 only 1, x01 only 1, both 1, neither 1; with one"
                                + " section 3, two 1, three or more 0; displays whose every group 1+ has"
                                + " x00 1, x00 or x01 2, x01 1, x01 or a single section 3; displays with"
                                + " group 0 sections other than 0: 0"),
                "Default census: " + defaults);
        check(
                census.groupPatterns().equals(java.util.Map.of("0", 1, "0,1", 1, "1", 1, "2", 1)),
                "Group patterns: " + census.groupPatterns());
    }

    /** The displays mode's table census and its fixed sample order (#377, #455). */
    private static void displayTableCensusCountsCasesAndSampleOrderIsFixed() {
        var census = new WorldMeasurementProbe.DisplayTableCensus();
        census.add(false, 0L, false);
        census.add(false, 0L, true);
        census.add(false, 3L, true);
        census.add(false, 3L, false);
        census.add(false, 3L, false);
        census.add(false, null, false);
        census.add(true, 0L, false);
        check(
                census.counts.equals(
                        java.util.Map.of(
                                "ID 0, no rows", 1,
                                "ID 0, rows", 1,
                                "ID > 0, rows", 1,
                                "ID > 0, no rows", 2,
                                "model not in CreatureModelData", 1,
                                "character display (extended display info), not counted by case", 1)),
                "Table census: " + census.counts);
        var ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        var order = WorldMeasurementProbe.sampleOrder(ids);
        check(new java.util.HashSet<>(order).equals(new java.util.HashSet<>(ids)), "Every ID once: " + order);
        check(order.equals(List.of(1L, 6L, 3L, 8L, 5L, 2L, 7L, 4L)), "The golden-ratio order: " + order);
        check(order.equals(WorldMeasurementProbe.sampleOrder(List.of(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L))),
                "The same order whatever the input order");
    }

    /**
     * The census's "geosets resolved" Stand tally leaves out displays whose geosets refuse; group
     * 0 sections other than 0 are counted; unknown rows and an unknown extended display ID are
     * classified (#377, #455).
     */
    private static void displayCensusSeparatesUnresolvedGeosetsAndGroupZeroVariants() {
        var resolved = parts(0, 1, 100); // resolves under the x00 and the x01 default
        var refused = parts(1, 101); // group 0 without section 0
        var previews = new java.util.HashMap<Long, com.vibeminer.concepts.models.DisplayModelTypes.Preview>();
        previews.put(1L, preview(1, 0L, 0L, java.util.Map.of(), resolved));
        previews.put(2L, preview(2, 0L, 0L, java.util.Map.of(), refused));
        previews.put(3L, preview(3, 0L, 0L, null, parts(0)));
        previews.put(4L, preview(4, null, 0L, java.util.Map.of(), parts(0)));
        var census =
                new WorldMeasurementProbe.DisplayCensus(
                        new WorldTileCreatures.Previews() {
                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Preview preview(
                                    long display, Cancellation token) {
                                return previews.get(display);
                            }

                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Idle idle(
                                    long display, Cancellation token) {
                                return new com.vibeminer.concepts.models.DisplayModelTypes.Idle(
                                        com.vibeminer.concepts.models.DisplayModelTypes.IdleState.AVAILABLE,
                                        null,
                                        WorldTileCreaturesTest.lift(previews.get(display).appearance()),
                                        "ok");
                            }
                        });
        try {
            for (long display = 1; display <= 4; display++) {
                census.preview(display, Cancellation.NONE);
                census.idle(display, Cancellation.NONE);
            }
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        check(census.standTally(false).equals(java.util.Map.of("Stand pose", 3)),
                "All creature displays: " + census.standTally(false));
        check(census.standTally(true).equals(java.util.Map.of("Stand pose", 2)),
                "Only displays whose geosets resolve: " + census.standTally(true));
        var cases = census.geosetTally();
        check(cases.containsKey("ID 0, rows unknown -> drawn") && cases.values().stream().mapToInt(i -> i).sum() == 3,
                "Unknown rows are their own case and an unknown extended display ID is not a creature: " + cases);
        check(census.defaultCensus().endsWith("displays with group 0 sections other than 0: 2"),
                "Group 0 variants: " + census.defaultCensus());
    }

    /** One failing preview or idle call is counted and the census goes on (#377). */
    private static void displaySampleCountsFailuresAndGoesOn() {
        var good = WorldTileCreaturesTest.standing();
        var census =
                new WorldMeasurementProbe.DisplayCensus(
                        new WorldTileCreatures.Previews() {
                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Preview preview(
                                    long display, Cancellation token) {
                                if (display == 1) throw new IllegalStateException("broken");
                                return display == 2 ? null : WorldMeasurementTest.preview(display, 0L, 0L, java.util.Map.of(), good);
                            }

                            @Override
                            public com.vibeminer.concepts.models.DisplayModelTypes.Idle idle(
                                    long display, Cancellation token) {
                                if (display == 3) throw new IllegalArgumentException("broken");
                                if (display == 5)
                                    throw new java.util.concurrent.CancellationException("closed");
                                return new com.vibeminer.concepts.models.DisplayModelTypes.Idle(
                                        com.vibeminer.concepts.models.DisplayModelTypes.IdleState.AVAILABLE,
                                        null, WorldTileCreaturesTest.lift(good), "ok");
                            }
                        });
        var unavailable = new java.util.TreeMap<String, Integer>();
        try {
            for (long display = 1; display <= 4; display++)
                WorldMeasurementProbe.sample(census, display, unavailable);
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        check(unavailable.equals(java.util.Map.of("preview failed: IllegalStateException", 1, "no static preview", 1)),
                "Preview failures and missing previews: " + unavailable);
        check(census.standTally(false).equals(
                        java.util.Map.of("Stand pose", 1, "rest pose: Stand pose failed: IllegalArgumentException", 1)),
                "Idle failure is a rest pose: " + census.standTally(false));
        boolean canceled = false;
        try {
            WorldMeasurementProbe.sample(census, 5, unavailable);
        } catch (java.util.concurrent.CancellationException expected) {
            canceled = true;
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        check(canceled, "Cancellation propagates");
    }
}
