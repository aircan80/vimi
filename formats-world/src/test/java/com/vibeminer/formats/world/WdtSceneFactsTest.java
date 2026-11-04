package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WdtSceneFactsTest {
    private static byte[] ints(int... values) {
        var result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            result.putInt(value);
        }
        return result.array();
    }

    private static byte[] chunk(int tag, byte[] body) {
        var result = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(tag).putInt(body.length).put(body);
        return result.array();
    }

    private static byte[] join(byte[]... values) {
        var out = new ByteArrayOutputStream();
        for (byte[] value : values) {
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    private static byte[] main(boolean active) {
        byte[] result = new byte[4096 * 8];
        if (active) {
            result[0] = 1;
            result[4095 * 8] = 1;
        }
        return chunk(0x4d41494e, result);
    }

    private static byte[] placement(int flags) {
        var out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0xffff_fffe).putInt(-1);
        for (int i = 0; i < 12; i++) {
            out.putFloat(i == 0 ? -0.0f : i + 0.25f);
        }
        out.putShort((short) flags)
                .putShort((short) 65535)
                .putShort((short) 123)
                .putShort((short) 1024);
        return chunk(0x4d4f4446, out.array());
    }

    private static byte[] source(int flags, boolean terrain, byte[]... extras) {
        return join(
                chunk(0x4d564552, ints(18)),
                chunk(0x4d504844, ints(flags)),
                main(terrain),
                join(extras));
    }

    @Test
    void preservesUnsignedRawGlobalDeclarationAndSourceFloatBits() {
        byte[] bytes = source(0x241, false, placement(8));
        var facts = WdtSceneFacts.read(bytes, () -> false);
        assertEquals(WdtSceneFacts.Mode.GLOBAL_WMO_ONLY, facts.mode());
        assertEquals(0x241, facts.flags());
        assertEquals(0, facts.terrainTiles());
        var object = facts.globalObjects().getFirst();
        assertEquals(WdtSceneFacts.ReferenceKind.FILE_DATA_ID, object.referenceKind());
        assertEquals(0xffff_fffeL, object.reference());
        assertEquals(0xffff_ffffL, object.uniqueId());
        assertEquals(0x80000000, Float.floatToRawIntBits(object.position().x()));
        assertEquals(65535, object.doodadSet());
        assertEquals(1024, object.scale());
        assertEquals(11.25f, object.upperBounds().z());
        assertEquals(0, object.ordinal());
        assertThrows(UnsupportedOperationException.class, () -> facts.globalObjects().clear());
    }

    @Test
    void reportsModesFromDeclarationsWithoutInventingTilesOrMissingObjects() {
        assertEquals(
                WdtSceneFacts.Mode.TERRAIN_ONLY,
                WdtSceneFacts.read(source(0, true), () -> false).mode());
        assertEquals(
                WdtSceneFacts.Mode.MIXED,
                WdtSceneFacts.read(source(1, true, placement(8)), () -> false).mode());
        assertEquals(
                WdtSceneFacts.Mode.UNRESOLVED,
                WdtSceneFacts.read(source(1, false), () -> false).mode());
        assertEquals(
                WdtSceneFacts.Mode.UNRESOLVED,
                WdtSceneFacts.read(source(0, true, placement(8)), () -> false).mode());
        assertEquals(
                WdtSceneFacts.Mode.UNRESOLVED,
                WdtSceneFacts.read(source(0, false), () -> false).mode());
        var missingMain =
                WdtSceneFacts.read(
                        join(chunk(0x4d564552, ints(18)), chunk(0x4d504844, ints(1)), placement(8)),
                        () -> false);
        assertFalse(missingMain.mainPresent());
        assertEquals(WdtSceneFacts.Mode.UNRESOLVED, missingMain.mode());
        assertEquals(2, WdtSceneFacts.read(source(0, true), () -> false).terrainTiles());
    }

    @Test
    void retainsNameBytesAndUnknownPresenceWithoutResolvingReference() {
        byte[] bytes =
                source(
                        1,
                        false,
                        placement(0),
                        chunk(0x4d574d4f, new byte[] {65, 0}),
                        chunk(0x12345678, new byte[] {9, 8, 7}));
        var facts = WdtSceneFacts.read(bytes, () -> false);
        java.util.Arrays.fill(bytes, (byte) 0);
        assertEquals(
                WdtSceneFacts.ReferenceKind.UNRESOLVED_UNSIGNED32,
                facts.globalObjects().getFirst().referenceKind());
        assertTrue(facts.nameBlockPresent());
        assertEquals(65, facts.nameBlock().get(0));
        assertThrows(
                java.nio.ReadOnlyBufferException.class, () -> facts.nameBlock().put(0, (byte) 5));
        assertEquals(new WdtSceneFacts.Unparsed(0x12345678, 3), facts.unparsed().getFirst());
        var empty =
                WdtSceneFacts.read(
                        source(
                                0,
                                true,
                                chunk(0x4d574d4f, new byte[0]),
                                chunk(0x4d4f4446, new byte[0])),
                        () -> false);
        assertTrue(empty.nameBlockPresent());
        assertTrue(empty.globalBlockPresent());
        assertFalse(WdtSceneFacts.read(source(0, true), () -> false).nameBlockPresent());
    }

    @Test
    void rejectsDuplicateSingletonsBadSpansVersionsAndNonfiniteCoordinates() {
        byte[] finite = placement(8);
        ByteBuffer.wrap(finite).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0x7fc00000);
        for (byte[] bytes :
                new byte[][] {
                    source(1, false, placement(8), placement(8)),
                    source(0, true, main(false)),
                    source(0, true, chunk(0x4d504844, ints(0))),
                    source(0, true, chunk(0x4d564552, ints(18))),
                    source(0, true, chunk(0x4d574d4f, new byte[0]), chunk(0x4d574d4f, new byte[0])),
                    source(1, false, finite),
                    source(0, true, ints(0x12345678, -1)),
                    source(0, true, ints(0x12345678, 100)),
                    join(chunk(0x4d564552, ints(17)), chunk(0x4d504844, ints(0))),
                    source(1, false, chunk(0x4d4f4446, new byte[63]))
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> WdtSceneFacts.read(bytes, () -> false));
        }
    }

    @Test
    void enforcesSourceNamesRecordAndChunkCapsAndCancellation() {
        for (byte[] bytes :
                new byte[][] {
                    new byte[WdtSceneFacts.MAX_BYTES + 1],
                    source(0, true, chunk(0x4d574d4f, new byte[WdtSceneFacts.MAX_NAME_BYTES + 1])),
                    source(
                            1,
                            false,
                            chunk(
                                    0x4d4f4446,
                                    new byte[(WdtSceneFacts.MAX_GLOBAL_RECORDS + 1) * 64]))
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> WdtSceneFacts.read(bytes, () -> false));
        }
        var chunks = new ByteArrayOutputStream();
        chunks.writeBytes(source(0, true));
        for (int i = 0; i < 256; i++) {
            chunks.writeBytes(chunk(0x12345678, new byte[0]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> WdtSceneFacts.read(chunks.toByteArray(), () -> false));
        var calls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> WdtSceneFacts.read(source(0, true), () -> calls.incrementAndGet() > 100));
        assertThrows(
                CancellationException.class,
                () -> WdtSceneFacts.read(source(1, false, placement(8)), () -> true));
    }
}
