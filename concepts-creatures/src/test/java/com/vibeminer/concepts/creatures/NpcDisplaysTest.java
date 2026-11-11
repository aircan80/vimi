package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDetail;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplaySlot;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSummary;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpcDisplaysTest {
    private static final NpcDisplays.External EG =
            new NpcDisplays.External(
                    "EG Link",
                    List.of(
                            new NpcDisplays.Candidate(6924, 1.0, 1.0),
                            new NpcDisplays.Candidate(6921, 1.0, 1.0),
                            new NpcDisplays.Candidate(6930, 0.25, 1.0)));

    @Test
    void theClientRecordComesFirstAndItsHighestProbabilityDisplayIsShown() {
        var resolution =
                NpcDisplays.resolve(
                        found(new int[] {12, 11, 0, 13}, new float[] {0.25f, 0.5f, 0, 0.5f}),
                        "client",
                        EG);
        assertEquals(NpcDisplays.Source.CLIENT, resolution.source());
        // Highest probability, then lowest ID: 11 and 13 tie at 0.5, so 11 is shown.
        assertEquals(11, resolution.shown().displayId());
        assertEquals(
                List.of(11L, 13L, 12L),
                resolution.candidates().stream().map(NpcDisplays.Candidate::displayId).toList());
        assertEquals("client", resolution.provenance());
        assertTrue(resolution.detail().contains("of 3"), resolution.detail());
    }

    @Test
    void repeatedDisplaysAreOneCandidateWhoseWeightsAddUp() {
        var resolution =
                NpcDisplays.resolve(
                        found(new int[] {9, 9}, new float[] {0.25f, 0.75f}), "client", null);
        assertEquals(1, resolution.candidates().size());
        assertEquals(1.0, resolution.shown().probability(), 1e-6);
        assertEquals("Display 9 (client Creature)", resolution.detail());
        // A at 0.3 twice outweighs B at 0.4.
        var summed =
                NpcDisplays.resolve(
                        found(new int[] {5, 4, 5}, new float[] {0.3f, 0.4f, 0.3f}), "client", null);
        assertEquals(5, summed.shown().displayId());
        assertEquals(0.6, summed.shown().probability(), 1e-6);
    }

    @Test
    void anAbsentOrEmptyClientRecordFallsBackToTheObservedDisplays() {
        for (var client :
                List.of(
                        new CreatureResult(
                                CreatureResultState.ABSENT, null, null, null, null, null),
                        found(new int[] {0, 0}, new float[] {0, 0}))) {
            var resolution = NpcDisplays.resolve(client, "client", EG);
            assertEquals(NpcDisplays.Source.EXTERNAL, resolution.source());
            // The tie at 1.0 between 6924 and 6921 goes to the lower ID.
            assertEquals(6921, resolution.shown().displayId());
            assertEquals(
                    List.of(6921L, 6924L, 6930L),
                    resolution.candidates().stream()
                            .map(NpcDisplays.Candidate::displayId)
                            .toList());
            assertEquals("EG Link", resolution.provenance());
        }
    }

    @Test
    void withNeitherSourceNothingIsShownAndTheReasonsAreKept() {
        var none =
                NpcDisplays.resolve(
                        new CreatureResult(
                                CreatureResultState.ABSENT, null, null, null, null, null),
                        "client",
                        null);
        assertEquals(NpcDisplays.Source.NONE, none.source());
        assertNull(none.shown());
        assertEquals(
                "No client Creature record for this NPC; no outside display source applies",
                none.detail());
        var empty =
                NpcDisplays.resolve(
                        found(new int[] {0}, new float[] {0}),
                        "client",
                        new NpcDisplays.External("EG Link", List.of()));
        assertEquals(
                "The client Creature record declares no display; the outside source records no"
                        + " display for this NPC",
                empty.detail());
        assertEquals(NpcDisplays.Source.NONE, NpcDisplays.resolve(null, "client", null).source());
    }

    private static CreatureResult found(int[] displays, float[] probabilities) {
        var slots = new ArrayList<CreatureDisplaySlot>();
        for (int slot = 0; slot < displays.length; slot++) {
            slots.add(
                    new CreatureDisplaySlot(
                            slot,
                            displays[slot],
                            probabilities[slot],
                            displays[slot] > 0
                                    ? CreatureSlotState.DECLARED
                                    : CreatureSlotState.NO_DECLARED_DISPLAY));
        }
        var summary =
                new CreatureSummary(
                        100,
                        false,
                        null,
                        new CreatureName(CreatureNameState.VALID, "Test", null),
                        List.of(),
                        slots);
        return new CreatureResult(
                CreatureResultState.FOUND,
                new CreatureDetail(summary, null, null, List.of()),
                null,
                null,
                null,
                null);
    }
}
