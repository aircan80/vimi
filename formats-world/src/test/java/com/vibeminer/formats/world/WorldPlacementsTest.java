package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldPlacementsTest {
    private static final int MVER = 0x4d564552, MDDF = 0x4d444446, MODF = 0x4d4f4446;

    @Test
    void directIdsUnsignedFieldsAndSourceAxesArePreservedWithoutResolution() {
        var input =
                file(
                        chunk(MDDF, doodad(-1, -2, 65535, 0x8040)),
                        chunk(MODF, object(-3, -4, 0xffff)));
        var facts = WorldPlacements.readObj0(input, () -> false);
        var m2 = facts.doodads().getFirst();
        assertEquals(0xffff_ffffL, m2.reference().value());
        assertEquals(WorldPlacements.ReferenceKind.FILE_DATA_ID, m2.reference().kind());
        assertEquals(0xffff_fffeL, m2.uniqueId());
        assertEquals(new WorldPlacements.Vec3(1.25f, -2.5f, 300.75f), m2.position());
        assertEquals(new WorldPlacements.Vec3(-90, 180, 270), m2.rotation());
        assertEquals(65535, m2.scale());
        assertEquals(0x8040, m2.flags());
        var wmo = facts.worldObjects().getFirst();
        assertEquals(0xffff_fffdL, wmo.reference().value());
        assertEquals(WorldPlacements.ReferenceKind.FILE_DATA_ID, wmo.reference().kind());
        assertEquals(0xffff_fffcL, wmo.uniqueId());
        assertEquals(new WorldPlacements.Vec3(-10, -20, -30), wmo.lowerBounds());
        assertEquals(new WorldPlacements.Vec3(10, 20, 30), wmo.upperBounds());
        assertEquals(65535, wmo.flags());
        assertEquals(65534, wmo.doodadSet());
        assertEquals(65533, wmo.nameSet());
        assertEquals(65532, wmo.scale());
        java.util.Arrays.fill(input, (byte) 0);
        assertEquals(1.25f, m2.position().x());
    }

    @Test
    void flagsSelectReferenceKindEvenWithoutNameChunksAndDuplicateIdsRemainRawFacts() {
        var m2 = doodad(-1, 7, 0, 0);
        var second = doodad(18, 7, 1024, 0x40);
        var facts =
                WorldPlacements.readObj0(
                        file(chunk(MDDF, concat(m2, second)), chunk(MODF, object(0, 8, 0))),
                        () -> false);
        assertEquals(
                WorldPlacements.ReferenceKind.NAME_TABLE_INDEX,
                facts.doodads().getFirst().reference().kind());
        assertEquals(0xffff_ffffL, facts.doodads().getFirst().reference().value());
        assertEquals(0, facts.doodads().getFirst().scale());
        assertEquals(1, facts.doodads().get(1).ordinal());
        assertEquals(7, facts.doodads().get(1).uniqueId());
        assertEquals(
                WorldPlacements.ReferenceKind.NAME_TABLE_INDEX,
                facts.worldObjects().getFirst().reference().kind());
        assertThrows(UnsupportedOperationException.class, () -> facts.doodads().clear());
        var mutable = new ArrayList<>(facts.doodads());
        var detached = new WorldPlacements.Metadata(mutable, List.of());
        mutable.clear();
        assertEquals(2, detached.doodads().size());
    }

    @Test
    void emptyIsValidOnlyWithExactVersionAndChunkIntervals() {
        assertTrue(WorldPlacements.readObj0(file(), () -> false).doodads().isEmpty());
        assertTrue(
                WorldPlacements.readObj0(
                                file(
                                        chunk(MDDF, new byte[0]),
                                        chunk(MODF, new byte[0]),
                                        chunk(123, new byte[] {4, 5, 6})),
                                () -> false)
                        .worldObjects()
                        .isEmpty());
        reject(new byte[0]);
        reject(chunk(123, new byte[4]));
        reject(concat(chunk(MVER, integer(17)), chunk(MDDF, new byte[0])));
        reject(concat(file(), chunk(MVER, integer(18))));
        reject(concat(file(), new byte[7]));
        reject(concat(file(), buffer(8).putInt(MDDF).putInt(Integer.MAX_VALUE).array()));
        reject(concat(file(), buffer(8).putInt(MODF).putInt(-1).array()));
        reject(concat(file(), chunk(MVER, new byte[3])));
        reject(file(chunk(MDDF, new byte[35])));
        reject(file(chunk(MODF, new byte[63])));
        reject(file(chunk(MDDF, new byte[0]), chunk(MDDF, new byte[0])));
        reject(file(chunk(MODF, new byte[0]), chunk(MODF, new byte[0])));
    }

    @Test
    void everyVectorChannelRequiresFiniteSourceFloat() {
        for (int offset : new int[] {8, 12, 16, 20, 24, 28}) {
            var record = doodad(91, 1, 1024, 0x40);
            ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, Float.NaN);
            reject(file(chunk(MDDF, record)));
        }
        for (int offset : new int[] {8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52}) {
            var record = object(91, 1, 0x8);
            ByteBuffer.wrap(record)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(offset, Float.POSITIVE_INFINITY);
            reject(file(chunk(MODF, record)));
        }
    }

    @Test
    void sourceRecordAndChunkCapsHaveExactAcceptedBoundaries() {
        var m2 = new byte[4096 * 36];
        var wmo = new byte[4096 * 64];
        var maximum =
                WorldPlacements.readObj0(file(chunk(MDDF, m2), chunk(MODF, wmo)), () -> false);
        assertEquals(4096, maximum.doodads().size());
        assertEquals(4096, maximum.worldObjects().size());
        assertEquals(4095, maximum.worldObjects().getLast().ordinal());
        reject(file(chunk(MDDF, new byte[4097 * 36])));
        reject(file(chunk(MODF, new byte[4097 * 64])));
        var chunks = new byte[4095][];
        java.util.Arrays.fill(chunks, chunk(123, new byte[0]));
        assertTrue(WorldPlacements.readObj0(file(chunks), () -> false).doodads().isEmpty());
        reject(concat(file(chunks), chunk(123, new byte[0])));
        var exact = file(chunk(123, new byte[WorldPlacements.MAX_SOURCE_BYTES - 20]));
        assertTrue(WorldPlacements.readObj0(exact, () -> false).worldObjects().isEmpty());
        reject(java.util.Arrays.copyOf(exact, exact.length + 1));
    }

    @Test
    void cancellationIsCheckedBeforeSourceAndInsideRecordLoop() {
        assertThrows(
                CancellationException.class,
                () -> WorldPlacements.readObj0(new byte[0], () -> true));
        var polls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldPlacements.readObj0(
                                file(chunk(MDDF, new byte[4096 * 36])),
                                () -> polls.incrementAndGet() == 100));
        assertEquals(100, polls.get());
    }

    private static byte[] doodad(int id, int uniqueId, int scale, int flags) {
        return buffer(36)
                .putInt(id)
                .putInt(uniqueId)
                .putFloat(1.25f)
                .putFloat(-2.5f)
                .putFloat(300.75f)
                .putFloat(-90)
                .putFloat(180)
                .putFloat(270)
                .putShort((short) scale)
                .putShort((short) flags)
                .array();
    }

    private static byte[] object(int id, int uniqueId, int flags) {
        return buffer(64)
                .putInt(id)
                .putInt(uniqueId)
                .putFloat(1)
                .putFloat(2)
                .putFloat(3)
                .putFloat(4)
                .putFloat(5)
                .putFloat(6)
                .putFloat(-10)
                .putFloat(-20)
                .putFloat(-30)
                .putFloat(10)
                .putFloat(20)
                .putFloat(30)
                .putShort((short) flags)
                .putShort((short) 65534)
                .putShort((short) 65533)
                .putShort((short) 65532)
                .array();
    }

    private static byte[] file(byte[]... chunks) {
        return concat(chunk(MVER, integer(18)), concat(chunks));
    }

    private static byte[] chunk(int tag, byte[] payload) {
        return buffer(8 + payload.length).putInt(tag).putInt(payload.length).put(payload).array();
    }

    private static byte[] integer(int value) {
        return buffer(4).putInt(value).array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (var part : parts) {
            size += part.length;
        }
        var output = buffer(size);
        for (var part : parts) {
            output.put(part);
        }
        return output.array();
    }

    private static void reject(byte[] bytes) {
        assertThrows(
                IllegalArgumentException.class, () -> WorldPlacements.readObj0(bytes, () -> false));
    }
}
