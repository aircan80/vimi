package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.creatures.NpcDisplays;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The World's NPC display choice through the shared resolver; authored data only. */
public final class WorldNpcDisplaysTest {
    private WorldNpcDisplaysTest() {}

    public static void main(String[] args) throws Exception {
        aClientRecordKeepsTheSingleDisplayRuleAndNamesItsSource();
        withoutAClientDisplayTheHighestObservedWeightIsDrawnThenTheLowestId();
        aDisplayMissingFromTheBuildFallsThroughToTheNextSource();
        anObservedDisplayWithoutAModelIsUnresolvedNotDrawn();
        aMalformedRecordIsStatedNeverNoDisplay();
        withNoDisplayAnywhereTheClientReasonStaysWithTheResolverDetail();
        oneTileAsksTheOutsideSourcesOnceForTheNpcsTheClientLeavesOpen();
        aFailedOutsideReadIsAFailureNotARememberedBlank();
        theBatchLookupKeepsTheNpcPageOrderAndStatesMalformedRecords();
        System.out.println("World NPC displays: client rule, observed choice, batching and malformed records passed");
    }

    static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("Expected " + expected + " but was " + actual);
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Client records by NPC; displays without a model by ID; absent displays. */
    private static final class Creatures implements BuildCreatures {
        final Map<Long, CreatureTypes.CreatureResult> records;
        final Set<Long> absent, modelless;
        int reads;

        Creatures(Map<Long, CreatureTypes.CreatureResult> records, Set<Long> absent, Set<Long> modelless) {
            this.records = records;
            this.absent = absent;
            this.modelless = modelless;
        }

        @Override
        public com.vibeminer.builds.BuildTypes.BuildRef build() {
            return null;
        }

        @Override
        public String locale() {
            return "enUS";
        }

        @Override
        public CompletableFuture<CreatureTypes.CreaturesPage> list(CreatureTypes.CreatureQuery query,
                TableTypes.Priority priority, Cancellation token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<CreatureTypes.CreatureResult> get(long id, TableTypes.Priority priority,
                Cancellation token) {
            return getData(id, priority, token);
        }

        @Override
        public CompletableFuture<CreatureTypes.CreatureResult> getData(long id, TableTypes.Priority priority,
                Cancellation token) {
            reads++;
            return CompletableFuture.completedFuture(records.getOrDefault(id, new CreatureTypes.CreatureResult(
                    CreatureTypes.CreatureResultState.ABSENT, null, null, null, null, null)));
        }

        @Override
        public CompletableFuture<CreatureTypes.CreatureDisplayChain> displayChain(long displayId,
                TableTypes.Priority priority, Cancellation token) {
            return CompletableFuture.completedFuture(chain((int) displayId,
                    absent.contains(displayId) ? CreatureTypes.CreatureDisplayChainState.DISPLAY_ABSENT
                            : modelless.contains(displayId) ? CreatureTypes.CreatureDisplayChainState.NO_DECLARED_MODEL
                            : CreatureTypes.CreatureDisplayChainState.METADATA_RESOLVED));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static CreatureTypes.CreatureDisplayChain chain(int displayId, CreatureTypes.CreatureDisplayChainState state) {
        boolean resolved = state == CreatureTypes.CreatureDisplayChainState.METADATA_RESOLVED;
        return new CreatureTypes.CreatureDisplayChain(
                new CreatureTypes.CreatureDisplaySlot(0, displayId, 1, CreatureTypes.CreatureSlotState.DECLARED),
                state, null, null, resolved ? (long) displayId + 1000 : null, null, null,
                resolved ? (long) displayId + 100_000 : null, null, null, null, null, null, null, null);
    }

    /** A client record declaring {@code displays}, each resolving to a model. */
    private static CreatureTypes.CreatureResult declared(long npcId, int... displays) {
        var slots = new ArrayList<CreatureTypes.CreatureDisplaySlot>();
        var chains = new ArrayList<CreatureTypes.CreatureDisplayChain>();
        for (int i = 0; i < displays.length; i++) {
            slots.add(new CreatureTypes.CreatureDisplaySlot(i, displays[i], 1, CreatureTypes.CreatureSlotState.DECLARED));
            chains.add(chain(displays[i], CreatureTypes.CreatureDisplayChainState.METADATA_RESOLVED));
        }
        var summary = new CreatureTypes.CreatureSummary(npcId, false, null,
                new CreatureTypes.CreatureName(CreatureTypes.CreatureNameState.VALID, "Test", null), List.of(), slots);
        return new CreatureTypes.CreatureResult(CreatureTypes.CreatureResultState.FOUND,
                new CreatureTypes.CreatureDetail(summary, null, null, chains), null, null, null, null);
    }

    private static NpcDisplays.External eg(String provenance, long... weightsAndDisplays) {
        var candidates = new ArrayList<NpcDisplays.Candidate>();
        for (int i = 0; i < weightsAndDisplays.length; i += 2)
            candidates.add(new NpcDisplays.Candidate(weightsAndDisplays[i + 1], weightsAndDisplays[i], 1.0));
        return new NpcDisplays.External(provenance, candidates);
    }

    private static WorldTileCreatures.Chosen choose(Creatures creatures,
            Map<Long, List<NpcDisplays.External>> observed, long npcId) throws Exception {
        return new WorldNpcDisplays(() -> creatures, (ids, token) -> observed, "1.60.1.70862")
                .choose(npcId, Cancellation.NONE);
    }

    static void aClientRecordKeepsTheSingleDisplayRuleAndNamesItsSource() throws Exception {
        var creatures = new Creatures(Map.of(1L, declared(1, 31), 2L, declared(2, 31, 32)), Set.of(), Set.of());
        var observed = Map.of(1L, List.of(eg("EG", 1, 99)), 2L, List.of(eg("EG", 1, 99)));
        var single = choose(creatures, observed, 1).choice();
        assertEquals(CreatureModelPolicy.State.QUALIFIED, single.state());
        assertEquals(31, single.displayId());
        assertEquals(CreatureModelPolicy.Reason.SINGLE_DISPLAY, single.reason());
        assertTrue(single.detail().endsWith("source: client Creature record, build 1.60.1.70862"), single.detail());
        // Several client displays: the client picks one per spawn, so none is drawn, and the
        // outside sources are not used in its place.
        var several = choose(creatures, observed, 2).choice();
        assertEquals(CreatureModelPolicy.State.AMBIGUOUS, several.state());
        assertEquals(null, several.displayId());
    }

    static void withoutAClientDisplayTheHighestObservedWeightIsDrawnThenTheLowestId() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(), Set.of());
        var observed = Map.of(7L, List.of(eg("EG Link 70862", 25, 40, 100, 52, 100, 51)));
        var chosen = choose(creatures, observed, 7);
        var choice = chosen.choice();
        assertEquals(CreatureModelPolicy.State.QUALIFIED, choice.state());
        assertEquals(CreatureModelPolicy.Reason.OBSERVED_DISPLAY, choice.reason());
        assertEquals(51, choice.displayId());
        assertEquals(100_051L, choice.modelFileDataId());
        assertEquals(3, choice.displays());
        assertTrue(choice.detail().contains("highest probability, then lowest ID"), choice.detail());
        assertTrue(choice.detail().contains("source: EG Link 70862"), choice.detail());
        // The scale is the client data's for the display; the authored chain records none.
        assertEquals(null, chosen.scale());
    }

    static void aDisplayMissingFromTheBuildFallsThroughToTheNextSource() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(900L), Set.of());
        var observed = Map.of(639L, List.of(eg("newer", 1, 900), eg("EG (bundled)", 1, 2029)));
        var choice = choose(creatures, observed, 639).choice();
        assertEquals(2029, choice.displayId());
        assertTrue(choice.detail().contains("source: EG (bundled)"), choice.detail());
        assertTrue(choice.detail().contains("one newer source had none in this build"), choice.detail());
    }

    static void anObservedDisplayWithoutAModelIsUnresolvedNotDrawn() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(), Set.of(60L));
        var choice = choose(creatures, Map.of(7L, List.of(eg("EG", 1, 60))), 7).choice();
        assertEquals(CreatureModelPolicy.State.UNRESOLVED, choice.state());
        assertEquals(CreatureModelPolicy.Reason.NO_MODEL, choice.reason());
        assertEquals(60, choice.displayId());
    }

    static void aMalformedRecordIsStatedNeverNoDisplay() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(), Set.of());
        var malformed = new NpcDisplays.External("EG Link 70862", List.of(), "displays is not a list");
        var choice = choose(creatures, Map.of(7L, List.of(malformed)), 7).choice();
        assertEquals(CreatureModelPolicy.State.UNKNOWN, choice.state());
        assertEquals(CreatureModelPolicy.Reason.OBSERVED_MALFORMED, choice.reason());
        assertTrue(choice.detail().contains("is malformed"), choice.detail());
        assertTrue(choice.detail().contains("displays is not a list"), choice.detail());
        assertTrue(!choice.detail().contains("records no display"), choice.detail());
        // An older source still draws; the malformed newer record is stated with it.
        var drawn = choose(creatures, Map.of(7L, List.of(malformed, eg("EG (bundled)", 1, 70))), 7).choice();
        assertEquals(70, drawn.displayId());
        assertTrue(drawn.detail().contains("one outside record for this NPC is malformed"), drawn.detail());
    }

    static void withNoDisplayAnywhereTheClientReasonStaysWithTheResolverDetail() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(), Set.of());
        var choice = choose(creatures, Map.of(), 7).choice();
        assertEquals(CreatureModelPolicy.Reason.CREATURE_ABSENT, choice.reason());
        assertEquals("No client Creature record for this NPC; no outside display source applies", choice.detail());
    }

    static void oneTileAsksTheOutsideSourcesOnceForTheNpcsTheClientLeavesOpen() throws Exception {
        var creatures = new Creatures(Map.of(1L, declared(1, 31)), Set.of(), Set.of());
        var asked = new ArrayList<List<Long>>();
        WorldNpcDisplays.Observed observed = (ids, token) -> {
            asked.add(List.copyOf(ids));
            var out = new java.util.HashMap<Long, List<NpcDisplays.External>>();
            for (long id : ids) out.put(id, List.of(eg("EG", 1, id + 10)));
            return out;
        };
        var chooser = new WorldNpcDisplays(() -> creatures, observed, "b");
        var tile = chooser.forNpcs(List.of(1L, 7L, 8L), Cancellation.NONE);
        assertEquals(List.of(List.of(7L, 8L)), asked);
        assertEquals(17, tile.choose(7, Cancellation.NONE).choice().displayId());
        assertEquals(18, tile.choose(8, Cancellation.NONE).choice().displayId());
        assertEquals(31, tile.choose(1, Cancellation.NONE).choice().displayId());
        assertEquals(1, asked.size());
        // An NPC outside the tile's batch is read by itself.
        assertEquals(19, tile.choose(9, Cancellation.NONE).choice().displayId());
        assertEquals(List.of(9L), asked.get(1));
        // A tile whose NPCs all have client displays reads no outside source.
        chooser.forNpcs(List.of(1L), Cancellation.NONE);
        assertEquals(2, asked.size());
    }

    static void aFailedOutsideReadIsAFailureNotARememberedBlank() throws Exception {
        var creatures = new Creatures(Map.of(), Set.of(), Set.of());
        var chooser = new WorldNpcDisplays(() -> creatures, (ids, token) -> {
            throw new java.io.IOException("snapshot pruned");
        }, "b");
        try {
            chooser.choose(7, Cancellation.NONE);
            throw new AssertionError("expected a failure");
        } catch (java.io.IOException expected) {
            // The World counts it as failed and asks again on a later tile.
        }
    }

    static void theBatchLookupKeepsTheNpcPageOrderAndStatesMalformedRecords() throws Exception {
        var newer = snapshot("n", "1.60.1.70888");
        var older = snapshot("o", "1.60.1.70862");
        var calls = new ArrayList<String>();
        var observed = NpcPreviews.egLinkBatch(() -> List.of(newer, older), (snapshot, ids, token) -> {
            calls.add(snapshot.id() + ids);
            if (snapshot == newer)
                return Map.of(7L, Optional.of(new com.vibeminer.eglink.EgLinkTypes.CreatureDisplays("n", "wowf",
                        "1.60.1.70888", "2026-11-02", 7, List.of(), "displays is not a list")));
            return Map.of(7L, Optional.of(displays("o", 7, 21)), 8L, Optional.of(displays("o", 8, 22)));
        }, () -> null, new BuildInfo("wowf", null, "1.60.1.70862", null, null, null, false, null, 0));
        var found = observed.displays(List.of(7L, 8L), Cancellation.NONE);
        assertEquals(List.of("n[7, 8]", "o[7, 8]"), calls);
        var seven = found.get(7L);
        assertEquals(2, seven.size());
        assertTrue(seven.get(0).malformed(), seven.toString());
        assertEquals("displays is not a list", seven.get(0).problem());
        assertEquals(21L, seven.get(1).candidates().getFirst().displayId());
        var eight = found.get(8L);
        assertTrue(eight.get(0).candidates().isEmpty() && !eight.get(0).malformed(), eight.toString());
        assertEquals(22L, eight.get(1).candidates().getFirst().displayId());
        // The NPC page's single lookup states the malformed record the same way.
        var single = NpcPreviews.external("p", Optional.of(new com.vibeminer.eglink.EgLinkTypes.CreatureDisplays(
                "n", "wowf", "1.60.1.70888", "2026-11-02", 7, List.of(), "bad")));
        assertTrue(single.malformed(), single.toString());
    }

    private static com.vibeminer.eglink.EgLinkTypes.CreatureDisplays displays(String snapshot, long npc, long display) {
        return new com.vibeminer.eglink.EgLinkTypes.CreatureDisplays(snapshot, "wowf", "1.60.1.70862", "2026-11-02",
                npc, List.of(new com.vibeminer.eglink.EgLinkTypes.CreatureDisplay(display, 1, 1)));
    }

    private static com.vibeminer.eglink.EgLinkTypes.Snapshot snapshot(String id, String version) {
        return new com.vibeminer.eglink.EgLinkTypes.Snapshot(id,
                new com.vibeminer.eglink.EgLinkTypes.Build("0".repeat(40), "wowf", version,
                        List.of(new com.vibeminer.eglink.EgLinkTypes.Dataset("creature_details.enUS", 1, 1, "2026-11-02"))),
                java.time.Instant.EPOCH, 1, 1L, 1L, null);
    }
}
