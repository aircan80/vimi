package com.vimi.gui;


import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.creatures.NpcDisplays;
import com.vibeminer.concepts.models.BuildDisplayModels;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceAppearanceTypes;
import com.vibeminer.concepts.races.RaceNpcs;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** NPC page previews: display resolution, creature and humanoid outcomes; authored data only. */
public final class NpcPreviewsTest {
    private NpcPreviewsTest() {}

    public static void main(String[] args) throws Exception {
        anNpcWithoutAClientRecordShowsTheObservedCreatureDisplayAndItsProvenance();
        aRefusedHumanoidAppearanceShowsTheUntexturedModelWithItsReason();
        aHumanoidAppearanceIsShownTextured();
        withNoDisplayAnywhereNothingIsDrawnAndTheReasonIsShown();
        aClientRecordNeverWaitsOnTheObservedDisplays();
        aFailedObservedLookupIsAFactNotABlank();
        theDisplaySnapshotIsTheNewestCompatibleOneWithCreatureDetails();
        System.out.println("NPC previews: resolution, creature, humanoid and refusal outcomes passed");
    }

    static void assertEquals(Object expected, Object actual) {
        boolean same =
                expected instanceof Number e && actual instanceof Number a
                        ? e.longValue() == a.longValue() && e.doubleValue() == a.doubleValue()
                        : java.util.Objects.equals(expected, actual);
        if (!same) throw new AssertionError("Expected " + expected + " but was " + actual);
    }

    static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("Expected true");
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void assertFalse(boolean condition) {
        assertTrue(!condition);
    }

    static void assertNull(Object value) {
        if (value != null) throw new AssertionError("Expected null but was " + value);
    }

    private static final PreviewMesh MESH =
            new PreviewMesh(
                    new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                    new int[] {0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2},
                    List.of(
                            new PreviewMesh.Part(0, 0, 3),
                            new PreviewMesh.Part(101, 3, 3),
                            new PreviewMesh.Part(102, 6, 3),
                            new PreviewMesh.Part(1701, 9, 3)));
    private static final MeshAppearance TEXTURED =
            new MeshAppearance(
                    MESH,
                    new float[6],
                    new float[9],
                    List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                    List.of(new MeshAppearance.Texture(1, 1, new int[] {0xff808080})));

    /** No client record: EG Link lists two displays at equal weight. */
    private static final NpcPreviews.Observed EG =
            (npcId, token) ->
                    Optional.of(
                            new NpcDisplays.External(
                                    "EG Link creature_details (CC BY 4.0), wowf build"
                                            + " 1.60.1.70862, collected 2026-11-02",
                                    List.of(
                                            new NpcDisplays.Candidate(22, 1, 1.0),
                                            new NpcDisplays.Candidate(21, 1, 1.0))));

    static void anNpcWithoutAClientRecordShowsTheObservedCreatureDisplayAndItsProvenance()
            throws Exception {
        var outcome = previews(0L, races(null)).resolve(7, Cancellation.NONE);
        assertEquals(NpcDisplays.Source.EXTERNAL, outcome.resolution().source());
        assertEquals(21, outcome.resolution().shown().displayId());
        assertEquals(NpcPreviews.Kind.CREATURE, outcome.kind());
        assertTrue(outcome.textured());
        assertNull(outcome.reason());
        assertEquals(21L, outcome.idleDisplay());
        assertTrue(outcome.facts().contains("Display 21 · weight 1.000 (50%) · shown"));
        assertTrue(outcome.facts().contains("Display 22 · weight 1.000 (50%)"));
        assertTrue(
                outcome.facts().stream().anyMatch(f -> f.startsWith("Source: EG Link")),
                outcome.facts().toString());
    }

    static void aRefusedHumanoidAppearanceShowsTheUntexturedModelWithItsReason() throws Exception {
        var outcome =
                previews(
                                9L,
                                races(
                                        RaceNpcs.Appearance.refused(
                                                "CreatureDisplayInfoOption is PARTIAL")))
                        .resolve(7, Cancellation.NONE);
        assertEquals(NpcPreviews.Kind.HUMANOID, outcome.kind());
        assertFalse(outcome.textured());
        assertTrue(outcome.appearance().surfaces().isEmpty());
        assertEquals(
                "Character appearance not shown: CreatureDisplayInfoOption is PARTIAL",
                outcome.reason());
        // Default character surfaces: the body and each group's first value, not group 17.
        assertEquals(Set.of(0, 101), outcome.visibleParts());
        assertNull(outcome.idleDisplay());
    }

    static void aHumanoidAppearanceIsShownTextured() throws Exception {
        var result =
                new RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY,
                        TEXTURED,
                        Set.of(0),
                        List.of(),
                        List.of("NPC bake"),
                        false,
                        null);
        var outcome =
                previews(9L, races(new RaceNpcs.Appearance(result, 0, 0, null, false)))
                        .resolve(7, Cancellation.NONE);
        assertEquals(NpcPreviews.Kind.HUMANOID, outcome.kind());
        assertTrue(outcome.textured());
        assertEquals(Set.of(0), outcome.visibleParts());
        assertTrue(outcome.facts().contains("NPC bake"));
    }

    static void withNoDisplayAnywhereNothingIsDrawnAndTheReasonIsShown() throws Exception {
        var previews =
                new NpcPreviews(
                        NpcPreviewsTest::absent,
                        () -> displays(0L),
                        () -> null,
                        (npcId, token) -> Optional.empty(),
                        "1.60.1.70862");
        var outcome = previews.resolve(7, Cancellation.NONE);
        assertEquals(NpcPreviews.Kind.NONE, outcome.kind());
        assertNull(outcome.appearance());
        assertEquals(
                "No client Creature record for this NPC; no outside display source applies",
                outcome.reason());
    }

    static void aClientRecordNeverWaitsOnTheObservedDisplays() throws Exception {
        var previews =
                new NpcPreviews(
                        () -> creatures(declared(31)),
                        () -> displays(0L),
                        () -> null,
                        (npcId, token) -> {
                            throw new java.io.IOException("malformed EG record");
                        },
                        "1.60.1.70862");
        var outcome = previews.resolve(7, Cancellation.NONE);
        assertEquals(NpcDisplays.Source.CLIENT, outcome.resolution().source());
        assertEquals(31L, outcome.resolution().shown().displayId());
        assertTrue(outcome.appearance() != null);
        assertFalse(outcome.facts().stream().anyMatch(f -> f.contains("malformed")));
    }

    static void aFailedObservedLookupIsAFactNotABlank() throws Exception {
        var previews =
                new NpcPreviews(
                        NpcPreviewsTest::absent,
                        () -> displays(0L),
                        () -> null,
                        (npcId, token) -> {
                            throw new java.io.IOException("malformed EG record");
                        },
                        "1.60.1.70862");
        var outcome = previews.resolve(7, Cancellation.NONE);
        assertEquals(NpcDisplays.Source.NONE, outcome.resolution().source());
        assertTrue(
                outcome.facts().contains("Observed displays could not be read: malformed EG record"),
                outcome.facts().toString());
    }

    static void theDisplaySnapshotIsTheNewestCompatibleOneWithCreatureDetails() {
        var snapshots =
                List.of(
                        snapshot("a", "wowf", "1.60.1.70790", true),
                        snapshot("b", "wowf", "1.60.1.70840", true),
                        snapshot("c", "wowf", "1.60.1.70888", true), // newer than the build
                        snapshot("d", "wowf", "1.60.1.70862", false), // no creature_details
                        snapshot("e", "wow_classic_beta", "1.60.1.70850", true), // other product
                        snapshot("f", "wowf", "1.61.0.70850", true)); // other x.y.z
        assertEquals(
                "b",
                DataSourcesService.npcDisplaySnapshot(snapshots, "wowf", "1.60.1.70862", "enUS")
                        .id());
        assertNull(
                DataSourcesService.npcDisplaySnapshot(snapshots, "wowf", "1.60.1.70700", "enUS"));
        assertNull(
                DataSourcesService.npcDisplaySnapshot(snapshots, "wowf", "1.60.1.70862", "deDE"));
    }

    private static com.vibeminer.eglink.EgLinkTypes.Snapshot snapshot(
            String id, String product, String version, boolean details) {
        var datasets =
                details
                        ? List.of(
                                new com.vibeminer.eglink.EgLinkTypes.Dataset(
                                        "creature_details.enUS", 1, 1, "2026-11-02"))
                        : List.of(
                                new com.vibeminer.eglink.EgLinkTypes.Dataset(
                                        "hotfixes.enUS", 1, 1, "2026-11-02"));
        return new com.vibeminer.eglink.EgLinkTypes.Snapshot(
                id,
                new com.vibeminer.eglink.EgLinkTypes.Build(
                        "0".repeat(40), product, version, datasets),
                java.time.Instant.EPOCH,
                1,
                1L,
                1L,
                null);
    }

    private static CreatureTypes.CreatureResult declared(int displayId) {
        var summary =
                new CreatureTypes.CreatureSummary(
                        7,
                        false,
                        null,
                        new CreatureTypes.CreatureName(
                                CreatureTypes.CreatureNameState.VALID, "Test", null),
                        List.of(),
                        List.of(
                                new CreatureTypes.CreatureDisplaySlot(
                                        0, displayId, 1, CreatureTypes.CreatureSlotState.DECLARED)));
        return new CreatureTypes.CreatureResult(
                CreatureTypes.CreatureResultState.FOUND,
                new CreatureTypes.CreatureDetail(summary, null, null, List.of()),
                null,
                null,
                null,
                null);
    }

    private static NpcPreviews previews(long extended, BuildRaces races) {
        return new NpcPreviews(
                NpcPreviewsTest::absent, () -> displays(extended), () -> races, EG, "1.60.1.70862");
    }

    private static BuildCreatures absent() {
        return creatures(
                new CreatureTypes.CreatureResult(
                        CreatureTypes.CreatureResultState.ABSENT, null, null, null, null, null));
    }

    private static BuildCreatures creatures(CreatureTypes.CreatureResult answer) {
        return new BuildCreatures() {
            @Override
            public com.vibeminer.builds.BuildTypes.BuildRef build() {
                return null;
            }

            @Override
            public String locale() {
                return "enUS";
            }

            @Override
            public CompletableFuture<CreatureTypes.CreaturesPage> list(
                    CreatureTypes.CreatureQuery query,
                    TableTypes.Priority priority,
                    Cancellation token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<CreatureTypes.CreatureResult> get(
                    long id, TableTypes.Priority priority, Cancellation token) {
                return getData(id, priority, token);
            }

            @Override
            public CompletableFuture<CreatureTypes.CreatureResult> getData(
                    long id, TableTypes.Priority priority, Cancellation token) {
                return CompletableFuture.completedFuture(answer);
            }

            @Override
            public CompletableFuture<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static BuildDisplayModels displays(long extended) {
        return new BuildDisplayModels() {
            @Override
            public CompletableFuture<DisplayModelTypes.Preview> preview(
                    long displayId, Cancellation token) {
                var reference =
                        new DisplayModelTypes.Reference(
                                displayId, 3, 4, List.of(), extended, 0L, Map.of());
                return CompletableFuture.completedFuture(
                        new DisplayModelTypes.Preview(
                                DisplayModelTypes.State.READY,
                                reference,
                                TEXTURED,
                                Set.of(0, 101, 102, 1701),
                                List.of(),
                                false,
                                null));
            }

            @Override
            public CompletableFuture<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static BuildRaces races(RaceNpcs.Appearance answer) {
        return new BuildRaces() {
            @Override
            public CompletableFuture<com.vibeminer.concepts.races.RaceTypes.Catalog> catalog(
                    Cancellation token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<com.vibeminer.concepts.races.RaceTypes.Overview> overview(
                    long raceId, Cancellation token) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<RaceNpcs.Appearance> npcAppearance(
                    long extendedDisplayInfoId, Cancellation token) {
                return CompletableFuture.completedFuture(answer);
            }

            @Override
            public CompletableFuture<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
