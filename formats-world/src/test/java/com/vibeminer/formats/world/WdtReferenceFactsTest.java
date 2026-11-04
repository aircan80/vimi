package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WdtReferenceFactsTest {
    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] chunk(String name, byte[] body) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        return buffer(body.length + 8).putInt(tag).putInt(body.length).put(body).array();
    }

    private static byte[] join(byte[]... bodies) {
        var output = new ByteArrayOutputStream();
        for (byte[] body : bodies) {
            output.writeBytes(body);
        }
        return output.toByteArray();
    }

    private static byte[] basic(byte[]... bodies) {
        return join(
                chunk("MVER", buffer(4).putInt(18).array()),
                chunk("MPHD", buffer(4).putInt(0x241).array()),
                join(bodies));
    }

    private static WdtReferenceFacts.Facts scan(byte[] raw) {
        return WdtReferenceFacts.scan(raw, () -> false);
    }

    @Test
    void absentDomainsStayDistinctFromExplicitZeroAndEmptyModf() {
        var absent = scan(basic());
        assertFalse(absent.mainPresent());
        assertFalse(absent.maidPresent());
        assertFalse(absent.modfPresent());
        var zero =
                scan(
                        basic(
                                chunk("MAIN", new byte[32768]),
                                chunk("MAID", new byte[131072]),
                                chunk("MODF", new byte[0])));
        assertTrue(zero.mainPresent() && zero.maidPresent() && zero.modfPresent());
        assertEquals(0, zero.activeMainRecords());
        assertEquals(0, zero.nonzeroMainWords());
        assertEquals(0, zero.positiveMaidCells());
        assertEquals(0, zero.positiveMaidReferences());
        assertEquals(0, zero.modfRecords());
        assertEquals(0x241, zero.mphdFlags());
        var global = scan(basic(chunk("MODF", new byte[64])));
        assertEquals(1, global.modfRecords());
    }

    @Test
    void everyMaidColumnLastCellAndUnsignedHighBitCountDespiteInactiveMain() {
        for (int column = 0; column < 8; column++) {
            byte[] maid = new byte[131072];
            ByteBuffer.wrap(maid)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(4095 * 32 + column * 4, 0x80000000);
            var facts = scan(basic(chunk("MAIN", new byte[32768]), chunk("MAID", maid)));
            assertEquals(0, facts.activeMainRecords());
            assertEquals(1, facts.positiveMaidCells());
            assertEquals(1, facts.positiveMaidReferences());
        }
        byte[] all = new byte[131072];
        Arrays.fill(all, (byte) 255);
        var max = scan(basic(chunk("MAID", all)));
        assertEquals(4096, max.positiveMaidCells());
        assertEquals(32768, max.positiveMaidReferences());
    }

    @Test
    void mainReservedAndInactiveFlagWordsArePreservedWithoutInventingTerrain() {
        byte[] main = new byte[32768];
        var buffer = ByteBuffer.wrap(main).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 2).putInt(4, -1).putInt(4095 * 8, 1).putInt(4095 * 8 + 4, 7);
        var facts = scan(basic(chunk("MAIN", main)));
        assertEquals(1, facts.activeMainRecords());
        assertEquals(4, facts.nonzeroMainWords());
    }

    @Test
    void duplicateMissingWrongVersionAndSizedSingletonsReject() {
        for (byte[] duplicate :
                List.of(
                        chunk("MAIN", new byte[32768]),
                        chunk("MAID", new byte[131072]),
                        chunk("MODF", new byte[0]))) {
            reject(basic(duplicate, duplicate));
        }
        reject(basic(chunk("MVER", buffer(4).putInt(18).array())));
        reject(basic(chunk("MPHD", buffer(4).putInt(0).array())));
        reject(chunk("MVER", buffer(4).putInt(18).array()));
        reject(chunk("MPHD", buffer(4).putInt(0).array()));
        reject(join(chunk("MVER", buffer(4).putInt(17).array()), chunk("MPHD", new byte[4])));
        for (int size : new int[] {0, 4, 32767, 32769}) {
            reject(basic(chunk("MAIN", new byte[size])));
        }
        for (int size : new int[] {0, 4, 131071, 131073}) {
            reject(basic(chunk("MAID", new byte[size])));
        }
        for (int size : new int[] {1, 63, 65, 17 * 64}) {
            reject(basic(chunk("MODF", new byte[size])));
        }
        for (int size : new int[] {0, 3, 5, 132}) {
            reject(
                    join(
                            chunk("MVER", buffer(4).putInt(18).array()),
                            chunk("MPHD", new byte[size])));
        }
    }

    @Test
    void unknownDataIsDetachedBoundedAndDoesNotRetainInputArrays() throws Exception {
        byte[] raw =
                join(
                        chunk("MVER", buffer(4).putInt(18).array()),
                        chunk("MPHD", buffer(128).putInt(-1).array()),
                        chunk("ABCD", new byte[] {1, 2, 3}));
        var facts = scan(raw);
        Arrays.fill(raw, (byte) 0);
        assertEquals(0xffffffffL, facts.mphdFlags());
        assertEquals(
                List.of(
                        new WdtReferenceFacts.Unparsed(0x4d504844, 124),
                        new WdtReferenceFacts.Unparsed(0x41424344, 3)),
                facts.unparsed());
        assertThrows(UnsupportedOperationException.class, () -> facts.unparsed().clear());
        for (var field : facts.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertFalse(field.getType().isArray());
                assertFalse(java.nio.Buffer.class.isAssignableFrom(field.getType()));
            }
        }
        var chunks = new byte[WdtReferenceFacts.MAX_CHUNKS - 2][];
        Arrays.setAll(chunks, i -> chunk("ABCD", new byte[0]));
        var maximum = scan(basic(chunks));
        assertEquals(254, maximum.unparsed().size());
        assertTrue(maximum.metadataBytes() <= WdtReferenceFacts.MAX_METADATA_BYTES);
        reject(basic(join(chunks), chunk("ABCD", new byte[0])));
    }

    @Test
    void truncatedTrailingNegativeOverflowAndSourceCapsReject() {
        var raw = basic(chunk("MAIN", new byte[32768]));
        reject(Arrays.copyOf(raw, raw.length - 1));
        reject(join(raw, new byte[1]));
        for (int size : new int[] {-1, Integer.MAX_VALUE}) {
            byte[] invalid = basic();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(16, size);
            reject(invalid);
        }
        reject(new byte[WdtReferenceFacts.MAX_BYTES + 1]);
        reject(new byte[11]);
    }

    @Test
    void cancellationAtChunkMainMaidAndGlobalTraversalPropagates() {
        byte[] raw =
                basic(
                        chunk("MAIN", new byte[32768]),
                        chunk("MAID", new byte[131072]),
                        chunk("MODF", new byte[1024]));
        for (int at : new int[] {0, 5, 100, 4105, 8200}) {
            var count = new AtomicInteger();
            assertThrows(
                    CancellationException.class,
                    () -> WdtReferenceFacts.scan(raw, () -> count.incrementAndGet() > at));
        }
    }

    private static void reject(byte[] raw) {
        assertThrows(IllegalArgumentException.class, () -> scan(raw));
    }
}
