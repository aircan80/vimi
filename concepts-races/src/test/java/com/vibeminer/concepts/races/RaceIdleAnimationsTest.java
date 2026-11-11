package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.BuildModelTypes.IdleRead;
import com.vibeminer.formats.models.BuildModelTypes.ReadState;
import com.vibeminer.formats.models.BuildModelTypes.StandRead;
import com.vibeminer.formats.models.M2IdleAnimation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Stand reads are supplied as reader results; no game data. */
final class RaceIdleAnimationsTest {
    private static final StandRead STAND_IN_MODEL =
            new StandRead(
                    ReadState.READ,
                    new M2IdleAnimation.StandFacts(0, 2667, 120, 12, null),
                    null,
                    null);

    private static final String BONE_FACT = "Bone files (BFID): 2 declared, none applied.";

    @Test
    void boneFilesAreNotAReasonAndOnlyAddAFact() {
        var result =
                RaceIdleAnimations.explain(
                        0, true, () -> BONE_FACT, () -> STAND_IN_MODEL, Cancellation.NONE);
        assertTrue(result.reasons().isEmpty());
        assertEquals(
                List.of(
                        "Stand (plays sequence 0, 2667 ms) is stored in the model: 120 step/linear keys"
                                + " on 12 bones.",
                        BONE_FACT),
                result.facts());
        assertTrue(result.complete());
    }

    @Test
    void everyBlockerIsReportedTogether() {
        var refusedStand =
                new StandRead(
                        ReadState.READ,
                        new M2IdleAnimation.StandFacts(
                                -1, 0, 0, 0, "A Stand bone track uses curve interpolation 2."),
                        null,
                        null);
        var result =
                RaceIdleAnimations.explain(
                        77, true, () -> BONE_FACT, () -> refusedStand, Cancellation.NONE);
        assertEquals(
                List.of(
                        "The model names external skeleton FileDataID 77 (SKID) and also stores"
                                + " bones of its own; which rig its Stand moves is not reviewed.",
                        "Stand: A Stand bone track uses curve interpolation 2."),
                result.reasons());
        assertEquals(List.of(BONE_FACT), result.facts());
        assertTrue(result.complete());
    }

    @Test
    void skeletonOnlyBonesPlayTheSkeletonStand() {
        var asked = new ArrayList<String>();
        var result =
                RaceIdleAnimations.explain(
                        77,
                        false,
                        () -> {
                            asked.add("bone files");
                            return BONE_FACT;
                        },
                        () -> {
                            asked.add("stand");
                            return STAND_IN_MODEL;
                        },
                        Cancellation.NONE);
        // The skeleton's bone files are known only once the skeleton is read.
        assertEquals(List.of("stand", "bone files"), asked);
        assertTrue(result.reasons().isEmpty());
        assertEquals(
                List.of(
                        "Stand (plays sequence 0, 2667 ms) is stored in external skeleton FileDataID"
                                + " 77: 120 step/linear keys on 12 bones.",
                        BONE_FACT),
                result.facts());
        assertTrue(result.complete());
        var absent =
                RaceIdleAnimations.explain(
                        77,
                        false,
                        () -> null,
                        () -> new StandRead(ReadState.NEEDS_NETWORK, null, null, "absent"),
                        Cancellation.NONE);
        assertEquals(
                List.of("The model's Stand sequence could not be re-read: absent"),
                absent.reasons());
        assertFalse(absent.complete());
    }

    @Test
    void localStandHasNoBlockerSoItCanBeBound() {
        var result =
                RaceIdleAnimations.explain(
                        0, true, () -> null, () -> STAND_IN_MODEL, Cancellation.NONE);
        assertTrue(result.reasons().isEmpty());
        assertEquals(
                List.of(
                        "Stand (plays sequence 0, 2667 ms) is stored in the model: 120 step/linear keys"
                                + " on 12 bones."),
                result.facts());
        assertTrue(result.complete());
        var unreadable =
                RaceIdleAnimations.explain(
                        0,
                        true,
                        () -> null,
                        () -> new StandRead(ReadState.NEEDS_NETWORK, null, null, "absent"),
                        Cancellation.NONE);
        assertEquals(
                List.of("The model's Stand sequence could not be re-read: absent"),
                unreadable.reasons());
        assertFalse(unreadable.complete());
    }

    @Test
    void cancellationStopsTheAssessment() {
        var token = new Cancellation();
        token.cancel();
        assertThrows(
                CancellationException.class,
                () -> RaceIdleAnimations.explain(0, true, () -> null, () -> STAND_IN_MODEL, token));
    }

    @Test
    void boneFileChoiceIsTheFirstRecordedChoiceOfTheFirstSelectingOption() {
        // Option 10 'Face' has choices 20-22 (each with a bone file); option 11 has choice 30
        // with one; option 12 has none.
        Map<Long, Long> choiceOption =
                Map.of(20L, 10L, 21L, 10L, 22L, 10L, 30L, 11L, 40L, 12L, 41L, 12L);
        Map<Long, String> names = Map.of(10L, "Face", 11L, "Jaw");
        var choice =
                RaceIdleAnimations.boneFileChoice(
                        choiceOption, names, Map.of(21L, 501L, 20L, 500L, 22L, 502L, 30L, 600L));
        assertEquals(new RaceIdleAnimations.BoneFileChoice(10, "Face", 20, 500, 4), choice);
        assertEquals(
                "Bone files (BFID): 22 declared, none applied; Stand plays on the base rig, and the"
                        + " face keeps the base rig's shape, as in the static preview. 4"
                        + " customization choices select one; the first recorded 'Face' (option 10)"
                        + " choice, 20, selects bone file 500, which is not applied.",
                RaceIdleAnimations.boneFileFact(22, choice, null, true));
        // The first recorded choice may select none.
        var later = RaceIdleAnimations.boneFileChoice(choiceOption, Map.of(), Map.of(22L, 502L));
        assertEquals(new RaceIdleAnimations.BoneFileChoice(10, null, 20, 0, 1), later);
        assertTrue(
                RaceIdleAnimations.boneFileFact(3, later, null, true)
                        .endsWith(
                                "1 customization choices select one; the first recorded option 10"
                                        + " choice, 20, selects none."));
        assertEquals(null, RaceIdleAnimations.boneFileChoice(choiceOption, names, Map.of()));
        assertTrue(
                RaceIdleAnimations.boneFileFact(14, null, null, true)
                        .endsWith("No customization choice of this model selects one."));
        // Tables read only in part do not establish that no choice selects one.
        assertTrue(
                RaceIdleAnimations.boneFileFact(14, null, null, false)
                        .endsWith(
                                "the customization tables could not be fully read, so which choice"
                                        + " selects one is not known."));
        // A found choice is still named when a table is partial.
        assertTrue(
                RaceIdleAnimations.boneFileFact(22, choice, null, false)
                        .endsWith("selects bone file 500, which is not applied."));
        assertTrue(
                RaceIdleAnimations.boneFileFact(14, null, "which choice could not be read: x", true)
                        .endsWith(
                                "Bone files are customization choices; which choice could not be"
                                        + " read: x"));
    }

    @Test
    void onlyReadyResultsCarryAnAnimation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RaceMediaTypes.Idle(
                                RaceTypes.State.READY, null, null, List.of(), List.of(), "ready"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RaceMediaTypes.Idle(
                                RaceTypes.State.PARTIAL,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                "partial"));
        var unavailable = RaceMediaTypes.Idle.unavailable(null, "Model file is MISSING.");
        assertEquals(RaceTypes.State.UNAVAILABLE, unavailable.state());
        assertTrue(unavailable.reasons().isEmpty());
        assertEquals(null, unavailable.animation());
    }

    @Test
    void evaluableReadRefusalsAreReportedLikeStandFacts() {
        var refused =
                RaceIdleAnimations.standOf(
                        new IdleRead(
                                ReadState.REJECTED,
                                null,
                                "MODEL_UNSUPPORTED",
                                "A Stand bone track uses curve interpolation 2."));
        assertEquals(ReadState.READ, refused.state());
        assertEquals("A Stand bone track uses curve interpolation 2.", refused.stand().refusal());
        var limit =
                RaceIdleAnimations.standOf(
                        new IdleRead(ReadState.REJECTED, null, "MODEL_LIMIT", "too many keys"));
        assertEquals("too many keys", limit.stand().refusal());
        var invalid =
                RaceIdleAnimations.standOf(
                        new IdleRead(ReadState.REJECTED, null, "MODEL_INVALID", "bad header"));
        assertEquals(ReadState.REJECTED, invalid.state());
        assertEquals("bad header", invalid.message());
        var absent =
                RaceIdleAnimations.standOf(
                        new IdleRead(ReadState.NEEDS_NETWORK, null, null, "absent"));
        assertEquals(ReadState.NEEDS_NETWORK, absent.state());
    }
}
