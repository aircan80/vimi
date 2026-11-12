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
        displaySnapshotsAreTriedNewestBuildThenNewestCommitFirst();
        anNpcOnlyAnOlderSnapshotRecordsIsFoundThere();
        theBundledHistoryIsTheLastSourceOfTheSameProductAndRelease();
        bundledEntriesPreferTheNewestAtOrBelowTheBuildThenNewerOnes();
        prunedSnapshotsKeepWhatLibraryAndOpenedBuildsRead();
        aDisplayMissingFromTheBuildFallsThroughToTheNextSource();
        aTexturedHumanoidPlaysItsFittedStand();
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
                    List.of(
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
                        (npcId, token) -> List.of(),
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

    static void displaySnapshotsAreTriedNewestBuildThenNewestCommitFirst() {
        var snapshots =
                List.of(
                        snapshot("a", "wowf", "1.60.1.70790", true, 1),
                        snapshot("b", "wowf", "1.60.1.70862", true, 1),
                        snapshot("c", "wowf", "1.60.1.70862", true, 2),
                        snapshot("d", "wowf", "1.60.1.70888", true, 3)); // newer than the build
        assertEquals(
                List.of("c", "b", "a"),
                DataSourcesService.npcDisplaySnapshots(snapshots, "wowf", "1.60.1.70862", "enUS")
                        .stream()
                        .map(com.vibeminer.eglink.EgLinkTypes.Snapshot::id)
                        .toList());
    }

    /** NPC 639 (VanCleef): only the 70862 snapshot records him; 70888 is downloaded too. */
    static void anNpcOnlyAnOlderSnapshotRecordsIsFoundThere() throws Exception {
        var newer = snapshot("n", "wowf", "1.60.1.70888", true, 2);
        var older = snapshot("o", "wowf", "1.60.1.70862", true, 1);
        var observed =
                NpcPreviews.egLink(
                        () -> List.of(newer, older),
                        (snapshot, npcId, token) ->
                                snapshot == older
                                        ? Optional.of(
                                                new com.vibeminer.eglink.EgLinkTypes
                                                        .CreatureDisplays(
                                                        "o",
                                                        "wowf",
                                                        "1.60.1.70862",
                                                        "2026-11-10",
                                                        639,
                                                        List.of(
                                                                new com.vibeminer.eglink
                                                                        .EgLinkTypes
                                                                        .CreatureDisplay(
                                                                        2029, 1, 1))))
                                        : Optional.empty(),
                        () -> null,
                        build("1.60.1.70888"));
        var outcome =
                new NpcPreviews(
                                NpcPreviewsTest::absent,
                                () -> displays(0L),
                                () -> null,
                                observed,
                                "1.60.1.70888")
                        .resolve(639, Cancellation.NONE);
        assertEquals(NpcDisplays.Source.EXTERNAL, outcome.resolution().source());
        assertEquals(2029, outcome.resolution().shown().displayId());
        assertTrue(
                outcome.resolution().provenance().contains("build 1.60.1.70862")
                        && outcome.resolution().provenance().contains("collected 2026-11-10"),
                outcome.resolution().provenance());
    }

    static void theBundledHistoryIsTheLastSourceOfTheSameProductAndRelease() throws Exception {
        var bundled = com.vibeminer.eglink.BundledNpcHistory.bundled();
        var observed =
                NpcPreviews.egLink(
                        List::of,
                        (snapshot, npcId, token) -> Optional.empty(),
                        () -> bundled,
                        build("1.60.1.70888"));
        var sources = observed.displays(639, Cancellation.NONE);
        assertEquals(1, sources.size());
        assertTrue(sources.getFirst().provenance().endsWith("(bundled)"), sources.toString());
        assertEquals(2029L, sources.getFirst().candidates().getFirst().displayId());
        // An older build of the same x.y.z is offered it too; the display check guards it.
        assertEquals(
                1,
                NpcPreviews.egLink(
                                List::of,
                                (snapshot, npcId, token) -> Optional.empty(),
                                () -> bundled,
                                build("1.60.1.70840"))
                        .displays(639, Cancellation.NONE)
                        .size());
        // Not another x.y.z, nor another product.
        assertTrue(
                NpcPreviews.egLink(
                                List::of,
                                (snapshot, npcId, token) -> Optional.empty(),
                                () -> bundled,
                                build("1.61.0.70888"))
                        .displays(639, Cancellation.NONE)
                        .isEmpty());
        assertTrue(
                NpcPreviews.egLink(
                                List::of,
                                (snapshot, npcId, token) -> Optional.empty(),
                                () -> bundled,
                                new BuildInfo(
                                        "wow_classic_beta",
                                        null,
                                        "1.60.1.70888",
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        0))
                        .displays(639, Cancellation.NONE)
                        .isEmpty());
    }

    static void bundledEntriesPreferTheNewestAtOrBelowTheBuildThenNewerOnes() {
        // NPC 30 (Forest Spider) is recorded by both 70862 and 70888.
        var entries = com.vibeminer.eglink.BundledNpcHistory.bundled().entries(30);
        java.util.function.Function<String, List<String>> order =
                version ->
                        NpcPreviews.bundledOrder(entries, version).stream()
                                .map(entry -> entry.source().build())
                                .toList();
        assertEquals(List.of("1.60.1.70862", "1.60.1.70888"), order.apply("1.60.1.70862"));
        assertEquals(List.of("1.60.1.70888", "1.60.1.70862"), order.apply("1.60.1.70888"));
        // Nothing at or below: the newest overall first.
        assertEquals(List.of("1.60.1.70888", "1.60.1.70862"), order.apply("1.60.1.70700"));
        assertEquals(List.of(), order.apply("1.61.0.70888"));
    }

    static void prunedSnapshotsKeepWhatLibraryAndOpenedBuildsRead() {
        var snapshots =
                List.of(
                        snapshot("old", "wowf", "1.60.1.70532", true, 1),
                        snapshot("used", "wowf", "1.60.1.70790", true, 2),
                        snapshot("newer", "wowf", "1.60.1.70888", true, 3),
                        snapshot("hotfix", "wowf", "1.60.1.70700", false, 4));
        var kept =
                DataSourcesService.keptEgLink(
                        snapshots,
                        List.of(new DataSourcesService.BuildKey("wowf", "1.60.1.70800")),
                        List.of("chosen"));
        // 70800 reads 70790's creature details and 70700's hotfixes; 70888 is newer than it.
        assertEquals(java.util.Set.of("used", "hotfix", "chosen"), kept);
    }

    static void aDisplayMissingFromTheBuildFallsThroughToTheNextSource() throws Exception {
        NpcPreviews.Observed observed =
                (npcId, token) ->
                        List.of(
                                new NpcDisplays.External(
                                        "newer", List.of(new NpcDisplays.Candidate(900, 1, 1.0))),
                                new NpcDisplays.External(
                                        "EG (bundled)",
                                        List.of(new NpcDisplays.Candidate(2029, 1, 1.0))));
        var creatures = chains(900);
        var outcome =
                new NpcPreviews(() -> creatures, () -> displays(0L), () -> null, observed, "b")
                        .resolve(639, Cancellation.NONE);
        assertEquals(2029, outcome.resolution().shown().displayId());
        assertEquals("EG (bundled)", outcome.resolution().provenance());
        assertTrue(
                outcome.facts().contains("Source: EG (bundled)"), outcome.facts().toString());
    }

    /** No client record; display {@code missing} is absent from CreatureDisplayInfo. */
    private static BuildCreatures chains(long missing) {
        var base = absent();
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
                return base.getData(id, priority, token);
            }

            @Override
            public CompletableFuture<CreatureTypes.CreatureResult> getData(
                    long id, TableTypes.Priority priority, Cancellation token) {
                return base.getData(id, priority, token);
            }

            @Override
            public CompletableFuture<CreatureTypes.CreatureDisplayChain> displayChain(
                    long displayId, TableTypes.Priority priority, Cancellation token) {
                return CompletableFuture.completedFuture(
                        new CreatureTypes.CreatureDisplayChain(
                                null,
                                displayId == missing
                                        ? CreatureTypes.CreatureDisplayChainState.DISPLAY_ABSENT
                                        : CreatureTypes.CreatureDisplayChainState.NO_DECLARED_MODEL,
                                null, null, null, null, null, null, null, null, null, null,
                                null, null, null));
            }

            @Override
            public CompletableFuture<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static BuildInfo build(String version) {
        return new BuildInfo("wowf", null, version, null, null, null, false, null, 0);
    }

    private static com.vibeminer.eglink.EgLinkTypes.Snapshot snapshot(
            String id, String product, String version, boolean details, long downloaded) {
        var plain = snapshot(id, product, version, details);
        return new com.vibeminer.eglink.EgLinkTypes.Snapshot(
                plain.id(),
                new com.vibeminer.eglink.EgLinkTypes.Build(
                        String.valueOf(downloaded).repeat(40), product, version,
                        plain.build().datasets()),
                java.time.Instant.ofEpochSecond(downloaded),
                1,
                1L,
                1L,
                null);
    }

    static void aTexturedHumanoidPlaysItsFittedStand() throws Exception {
        var result =
                new RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY,
                        TEXTURED,
                        Set.of(0),
                        List.of(),
                        List.of(),
                        false,
                        null);
        var bone =
                new com.vibeminer.rendering.models.PreviewAnimation.Bone(
                        -1, 0, 0, 0, null, null, null, false);
        var stand =
                new com.vibeminer.rendering.models.PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(bone),
                        TEXTURED.mesh(),
                        TEXTURED.normals(),
                        new int[12],
                        new int[] {255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0});
        long[] asked = {0};
        BuildRaces races =
                new BuildRaces() {
                    @Override
                    public CompletableFuture<com.vibeminer.concepts.races.RaceTypes.Catalog>
                            catalog(Cancellation token) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public CompletableFuture<com.vibeminer.concepts.races.RaceTypes.Overview>
                            overview(long raceId, Cancellation token) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public CompletableFuture<RaceNpcs.Appearance> npcAppearance(
                            long extendedDisplayInfoId, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new RaceNpcs.Appearance(result, 0, 0, null, false));
                    }

                    @Override
                    public CompletableFuture<com.vibeminer.concepts.races.RaceMediaTypes.Idle>
                            npcIdle(
                                    long extendedDisplayInfoId,
                                    MeshAppearance appearance,
                                    Cancellation token) {
                        asked[0] = extendedDisplayInfoId;
                        return CompletableFuture.completedFuture(
                                new com.vibeminer.concepts.races.RaceMediaTypes.Idle(
                                        com.vibeminer.concepts.races.RaceTypes.State.READY,
                                        new com.vibeminer.concepts.races.RaceMediaTypes.Variant(
                                                1, 0, 49, 149, 1490),
                                        stand,
                                        List.of(),
                                        List.of(),
                                        "Stand"));
                    }

                    @Override
                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var previews = previews(9L, races);
        var outcome = previews.resolve(7, Cancellation.NONE);
        assertEquals(9L, outcome.extendedDisplayInfoId());
        var idle = previews.idle(outcome, Cancellation.NONE);
        assertEquals(9L, asked[0]);
        assertTrue(idle.animation() == stand);
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
