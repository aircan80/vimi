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
        assertEquals(256, bytes.length);
        assertEquals(
                "56494d4957535332020000000001000029000000000000004d00000000000000",
                HexFormat.of().formatHex(bytes, 0, 32));
        assertEquals(SCOPE, HexFormat.of().formatHex(bytes, 32, 64));
        var values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, values.getLong(80));
        assertEquals(800, values.getInt(88));
        assertEquals(500, values.getInt(92));
        assertEquals(10, values.getInt(96));
        assertEquals(9, values.getInt(100));
        assertEquals(2_284_544, values.getInt(104));
        assertEquals(
                session,
                WorldStreamEnvelope.decodeSession(bytes, WorldStreamEnvelope.sha256(bytes)));
        for (int offset = 108; offset < 256; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] = 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
        }
        for (int offset : new int[] {8, 12, 64, 68, 72, 76, 88, 92, 96, 100, 104}) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] ^= 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeSession(corrupt));
        }
    }

    @Test
    void commitPreservesExplicitOrderAtNineMemberBoundaryAndUnusedBytesAreZero() throws Exception {
        var members = new ArrayList<WorldStreamEnvelope.Resident>();
        for (int slot = 8; slot >= 0; slot--) {
            members.add(new WorldStreamEnvelope.Resident(slot, slot + 17));
        }
        var commit = new WorldStreamEnvelope.Commit(NONCE, 19, 9, 10, 3, members);
        byte[] bytes = WorldStreamEnvelope.encode(commit);
        assertEquals(256, bytes.length);
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(19, in.getLong(32));
        assertEquals(9, in.getLong(40));
        assertEquals(10, in.getLong(48));
        assertEquals(3, in.getLong(56));
        assertEquals(9, in.getInt(64));
        for (int i = 0; i < 9; i++) {
            assertEquals(8 - i, bytes[72 + 16 * i]);
            assertEquals(25 - i, in.getLong(80 + 16 * i));
        }
        assertEquals(commit, decodeCommit(bytes));
        assertArrayEquals(bytes, WorldStreamEnvelope.encode(decodeCommit(bytes)));
        for (int i = 0; i < 9; i++) {
            for (int pad = 1; pad <= 7; pad++) {
                byte[] corrupt = bytes.clone();
                corrupt[72 + 16 * i + pad] = 1;
                assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
            }
        }
        for (int offset = 216; offset < 256; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] = 1;
            assertThrows(WorldStreamEnvelope.Invalid.class, () -> decodeCommit(corrupt));
        }
        var empty =
                WorldStreamEnvelope.encode(
                        new WorldStreamEnvelope.Commit(NONCE, 1, 0, 1, 1, List.of()));
        for (int offset = 72; offset < 256; offset++) {
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
        for (int offset = 0; offset < 256; offset++) {
            byte[] corrupt = bytes.clone();
            corrupt[offset] ^= 1;
            assertThrows(
                    WorldStreamEnvelope.Invalid.class,
                    () -> WorldStreamEnvelope.decodeCommit(corrupt, sha, NONCE));
        }
        assertThrows(
                WorldStreamEnvelope.Invalid.class, () -> decodeCommit(Arrays.copyOf(bytes, 255)));
        assertThrows(
                WorldStreamEnvelope.Invalid.class, () -> decodeCommit(Arrays.copyOf(bytes, 257)));
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
        for (int count : new int[] {-1, 10, Integer.MAX_VALUE}) {
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
        assertThrows(IllegalArgumentException.class, () -> new WorldStreamEnvelope.Resident(10, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldStreamEnvelope.Resident(0, 0));
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
