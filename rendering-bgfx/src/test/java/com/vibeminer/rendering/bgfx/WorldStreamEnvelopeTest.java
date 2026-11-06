package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldStreamEnvelopeTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(41, 77);
    private static final String SCOPE = "ab".repeat(32);

    @Test
    void sessionUsesPinnedLittleEndianOffsetsAndExactFixedCaps() throws Exception {
        var session = new WorldStreamEnvelope.Session(NONCE, SCOPE, 7);
        byte[] bytes = WorldStreamEnvelope.encode(session);
        assertEquals(512, bytes.length);
        assertEquals(
                "56494d4957535332030000000002000029000000000000004d00000000000000",
                HexFormat.of().formatHex(bytes, 0, 32));
        assertEquals(SCOPE, HexFormat.of().formatHex(bytes, 32, 64));
        var values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, values.getLong(80));
        assertEquals(800, values.getInt(88));
        assertEquals(500, values.getInt(92));
        // The default (near) session: 10 slots, 9 active.
        assertEquals(10, values.getInt(96));
        assertEquals(9, values.getInt(100));
        assertEquals(2_907_264, values.getInt(104));
        assertEquals(1, values.getInt(108), "STANDARD object textures by default");
        assertEquals(
                session,
                WorldStreamEnvelope.decodeSession(bytes, WorldStreamEnvelope.sha256(bytes)));
        for (int offset = 112; offset < 512; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] = 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
        }
        for (int offset : new int[] {8, 12, 64, 68, 72, 76, 88, 92, 96, 100, 104, 109}) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] ^= 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
        }
    }

    @Test
    void sessionCarriesItsViewDistanceAndRefusesOtherCaps() throws Exception {
        var far = new WorldStreamEnvelope.Session(NONCE, SCOPE, 7, true, 25);
        byte[] bytes = WorldStreamEnvelope.encode(far);
        var values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(26, values.getInt(96));
        assertEquals(25, values.getInt(100));
        assertEquals(far, decodeSession(bytes));
        assertEquals(26, far.slots());
        var high = new WorldStreamEnvelope.Session(NONCE, SCOPE, 7, true, 25, 2);
        byte[] highBytes = WorldStreamEnvelope.encode(high);
        assertEquals(2, ByteBuffer.wrap(highBytes).order(ByteOrder.LITTLE_ENDIAN).getInt(108));
        assertEquals(high, decodeSession(highBytes));
        assertEquals(256L << 20, high.objectBudgetBytes());
        assertEquals(192L << 20, far.objectBudgetBytes());
        for (int tier : new int[] {0, 3, -1}) {
            byte[] corrupt = highBytes.clone();
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(108, tier);
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorldStreamEnvelope.Session(NONCE, SCOPE, 7, true, 25, tier));
        }
        for (int[] caps : new int[][] {{26, 9}, {10, 25}, {25, 24}, {11, 10}, {27, 26}, {0, 0}}) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(96, caps[0])
                    .putInt(100, caps[1]);
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
        }
        for (int active : new int[] {0, 8, 10, 24, 26}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorldStreamEnvelope.Session(NONCE, SCOPE, 7, false, active));
        }
    }

    @Test
    void commitPreservesExplicitOrderAtMaximumMemberBoundaryAndUnusedBytesAreZero()
            throws Exception {
        var members = new ArrayList<WorldStreamEnvelope.Resident>();
        for (int slot = 24; slot >= 0; slot--) {
            members.add(new WorldStreamEnvelope.Resident(slot, slot + 17));
        }
        var commit = new WorldStreamEnvelope.Commit(NONCE, 19, 9, 10, 3, members);
        byte[] bytes = WorldStreamEnvelope.encode(commit);
        assertEquals(512, bytes.length);
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(19, in.getLong(32));
        assertEquals(9, in.getLong(40));
        assertEquals(10, in.getLong(48));
        assertEquals(3, in.getLong(56));
        assertEquals(25, in.getInt(64));
        for (int i = 0; i < 25; i++) {
            assertEquals(24 - i, bytes[72 + 16 * i]);
            assertEquals(41 - i, in.getLong(80 + 16 * i));
        }
        assertEquals(commit, decodeCommit(bytes));
        assertArrayEquals(bytes, WorldStreamEnvelope.encode(decodeCommit(bytes)));
        for (int i = 0; i < 25; i++) {
            for (int pad = 1; pad <= 7; pad++) {
                byte[] corrupt = bytes.clone();
                corrupt[72 + 16 * i + pad] = 1;
                assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
            }
        }
        for (int offset = 472; offset < 512; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] = 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
        }
        var empty =
                WorldStreamEnvelope.encode(
                        new WorldStreamEnvelope.Commit(NONCE, 1, 0, 1, 1, List.of()));
        for (int offset = 72; offset < 512; offset++) {
            byte[] corrupt = empty.clone();
            corrupt[offset] = 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
        }
    }

    @Test
    void rejectsChecksumsLengthsModesCrossSessionAndUnsafeCountsBeforeMemberAllocation() {
        byte[] bytes =
                WorldStreamEnvelope.encode(
                        new WorldStreamEnvelope.Commit(
                                NONCE,
                                1,
                                0,
                                1,
                                1,
                                List.of(new WorldStreamEnvelope.Resident(0, 1))));
        String sha = WorldStreamEnvelope.sha256(bytes);
        for (int offset = 0; offset < 512; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] ^= 1;
            assertThrows(
                    WorldStreamEnvelope.Invalid.class,
                    () -> WorldStreamEnvelope.decodeCommit(corrupt, sha, NONCE));
        }
        assertThrows(
                WorldStreamEnvelope.Invalid.class, () -> decodeCommit(Arrays.copyOf(bytes, 511)));
        assertThrows(
                WorldStreamEnvelope.Invalid.class, () -> decodeCommit(Arrays.copyOf(bytes, 513)));
        assertThrows(
                WorldStreamEnvelope.Invalid.class,
                () ->
                        WorldStreamEnvelope.decodeCommit(
                                bytes, sha.toUpperCase(java.util.Locale.ROOT), NONCE));
        assertThrows(
                WorldStreamEnvelope.Invalid.class,
                () ->
                        WorldStreamEnvelope.decodeCommit(
                                bytes, sha, new WorldStreamState.Nonce(1, 2)));
        assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(bytes));
        for (int count : new int[] {-1, 26, Integer.MAX_VALUE}) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(64, count);
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
        }
        for (int offset : new int[] {8, 12, 68, 72, 80}) {
            byte[] corrupt = bytes.clone();
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, -1);
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
        }
    }

    @Test
    void validatesCounterEdgesAndDoesNotAdmitDuplicateSlotsOrMutableMemberLists() {
        var one = new WorldStreamEnvelope.Resident(0, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamEnvelope.Commit(NONCE, 1, 0, 1, 1, List.of(one, one)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamEnvelope.Commit(NONCE, Long.MAX_VALUE, 0, 1, 1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamEnvelope.Commit(
                                NONCE, 1, Long.MAX_VALUE, Long.MIN_VALUE, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorldStreamEnvelope.Resident(26, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldStreamEnvelope.Resident(0, 0));
        var tooMany = new ArrayList<WorldStreamEnvelope.Resident>();
        for (int slot = 0; slot < 26; slot++) {
            tooMany.add(new WorldStreamEnvelope.Resident(slot, 1));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamEnvelope.Commit(NONCE, 1, 0, 1, 1, tooMany));
        var mutable = new ArrayList<>(List.of(one));
        var commit = new WorldStreamEnvelope.Commit(NONCE, 1, 0, 1, 1, mutable);
        mutable.clear();
        assertEquals(List.of(one), commit.members());
    }

    private static WorldStreamEnvelope.Session decodeSession(byte[] bytes)
            throws WorldStreamEnvelope.Invalid {
        return WorldStreamEnvelope.decodeSession(bytes, WorldStreamEnvelope.sha256(bytes));
    }

    private static WorldStreamEnvelope.Commit decodeCommit(byte[] bytes)
            throws WorldStreamEnvelope.Invalid {
        return WorldStreamEnvelope.decodeCommit(bytes, WorldStreamEnvelope.sha256(bytes), NONCE);
    }
}
