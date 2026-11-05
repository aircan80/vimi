package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.BuildModelTypes.BoneFileRead;
import com.vibeminer.formats.models.BuildModelTypes.IdleRead;
import com.vibeminer.formats.models.BuildModelTypes.ReadState;
import com.vibeminer.formats.models.BuildModelTypes.StandRead;
import com.vibeminer.formats.models.M2BoneFile;
import com.vibeminer.formats.models.M2IdleAnimation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Bone files are authored in code; Stand reads are supplied as reader results. */
final class RaceIdleAnimationsTest {
    private static final StandRead STAND_IN_MODEL =
            new StandRead(
                    ReadState.READ,
                    new M2IdleAnimation.StandFacts(0, 2667, 120, 12, null),
                    null,
                    null);

    private static M2BoneFile.Bones bones(int... ids) {
        var b =
                ByteBuffer.allocate(4 + 8 + ids.length * 2 + 8 + ids.length * 64)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(1).put("BIDA".getBytes(StandardCharsets.US_ASCII)).putInt(ids.length * 2);
        for (int id : ids) {
            b.putShort((short) id);
        }
        b.put("BOMT".getBytes(StandardCharsets.US_ASCII)).putInt(ids.length * 64);
        for (int i = 0; i < ids.length * 16; i++) {
            b.putFloat(i % 16 % 5 == 0 ? 1 : 0);
        }
        return M2BoneFile.read(AssetSource.bytes(b.array()), 64, Cancellation.NONE).join();
    }

    private static BoneFileRead valid(long id, int... bones) {
        return new BoneFileRead(ReadState.READ, id, "k" + id, bones(bones), null, null);
    }

    @Test
    void boneFilesAreReadValidatedAndStillRefuseStand() {
        var reads =
                Map.of(
                        11L,
                        valid(11, 50, 52, 54),
                        12L,
                        valid(12, 50, 60),
                        13L,
                        new BoneFileRead(ReadState.NEEDS_NETWORK, 13, null, null, null, "absent"),
                        14L,
                        new BoneFileRead(
                                ReadState.REJECTED,
                                14,
                                "k14",
                                null,
                                "MODEL_INVALID",
                                "names bone 99"));
        var asked = new ArrayList<Long>();
        var result =
                RaceIdleAnimations.explain(
                        0,
                        true,
                        List.of(11L, 0L, 12L, 13L, 14L),
                        id -> {
                            asked.add(id);
                            return reads.get(id);
                        },
                        () -> STAND_IN_MODEL,
                        Cancellation.NONE);
        assertEquals(List.of(11L, 12L, 13L, 14L), asked);
        assertEquals(1, result.reasons().size());
        String reason = result.reasons().getFirst();
        assertTrue(reason.startsWith("The model declares 4 external bone files (BFID): 2 read"));
        assertTrue(reason.contains("(2–3 bone matrices each, 4 distinct bones)"));
        assertTrue(reason.contains("1 refused (FileDataID 14: names bone 99)"));
        assertTrue(reason.contains("1 not readable (NEEDS_NETWORK)"));
        assertTrue(reason.contains("are not reviewed"));
        assertEquals(
                List.of(
                        "Bone files: 2 of 4 validated; they name bones 50–60.",
                        "Stand (sequence 0, 2667 ms) is stored in the model: 120 step/linear keys"
                                + " on 12 bones."),
                result.facts());
        // An unreadable source is reassessed rather than cached.
        assertFalse(result.complete());
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
                        77,
                        true,
                        List.of(11L),
                        id -> valid(id, 1),
                        () -> refusedStand,
                        Cancellation.NONE);
        assertEquals(3, result.reasons().size());
        assertEquals(
                "The model names external skeleton FileDataID 77 (SKID), which is not read.",
                result.reasons().get(0));
        assertTrue(result.reasons().get(1).contains("1 read and validated (1 bone matrices"));
        assertEquals(
                "Stand: A Stand bone track uses curve interpolation 2.", result.reasons().get(2));
        assertTrue(result.complete());
    }

    @Test
    void skeletonOnlyBonesSkipTheStandRead() {
        var result =
                RaceIdleAnimations.explain(
                        77,
                        false,
                        List.of(),
                        id -> {
                            throw new AssertionError("no bone files are declared");
                        },
                        () -> {
                            throw new AssertionError("Stand must not be read");
                        },
                        Cancellation.NONE);
        assertEquals(
                List.of(
                        "The model names external skeleton FileDataID 77 (SKID), which is not read."),
                result.reasons());
        assertEquals(
                List.of("Bones are stored only in the external skeleton; Stand is not read."),
                result.facts());
    }

    @Test
    void localStandHasNoBlockerSoItCanBeBound() {
        var result =
                RaceIdleAnimations.explain(
                        0, true, List.of(), id -> null, () -> STAND_IN_MODEL, Cancellation.NONE);
        assertTrue(result.reasons().isEmpty());
        assertEquals(
                List.of(
                        "Stand (sequence 0, 2667 ms) is stored in the model: 120 step/linear keys"
                                + " on 12 bones."),
                result.facts());
        assertTrue(result.complete());
        var unreadable =
                RaceIdleAnimations.explain(
                        0,
                        true,
                        List.of(),
                        id -> null,
                        () -> new StandRead(ReadState.NEEDS_NETWORK, null, null, "absent"),
                        Cancellation.NONE);
        assertEquals(
                List.of("The model's Stand sequence could not be re-read: absent"),
                unreadable.reasons());
        assertFalse(unreadable.complete());
    }

    @Test
    void boneFileCountIsBoundedAndCancellationStops() {
        var many = new ArrayList<Long>();
        for (long id = 1; id <= RaceIdleAnimations.MAX_BONE_FILES + 1; id++) {
            many.add(id);
        }
        var bounded =
                RaceIdleAnimations.explain(
                        0,
                        true,
                        many,
                        id -> {
                            throw new AssertionError("over-budget files are not opened");
                        },
                        () -> STAND_IN_MODEL,
                        Cancellation.NONE);
        assertTrue(bounded.reasons().getFirst().contains("at most 64 are assessed"));
        var token = new Cancellation();
        token.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        RaceIdleAnimations.explain(
                                0,
                                true,
                                List.of(1L),
                                id -> valid(id, 1),
                                () -> STAND_IN_MODEL,
                                token));
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
