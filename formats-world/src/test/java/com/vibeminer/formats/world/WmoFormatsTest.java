package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WmoFormatsTest {
    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int tag(String name) {
        int result = 0;
        for (char c : name.toCharArray()) {
            result = (result << 8) | c;
        }
        return result;
    }

    private static byte[] chunk(String name, byte[] body) {
        return buffer(body.length + 8).putInt(tag(name)).putInt(body.length).put(body).array();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] version() {
        return chunk("MVER", buffer(4).putInt(17).array());
    }

    private static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float v : values) {
            b.putFloat(v);
        }
        return b.array();
    }

    private static byte[] header() {
        var h = buffer(64);
        h.putInt(1)
                .putInt(1)
                .putInt(0)
                .putInt(-1)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(-1)
                .putInt(-2);
        h.put(floats(-1, -1, -1, 1, 1, 1));
        h.putShort((short) 0x8010).putShort((short) 3);
        return h.array();
    }

    private static byte[] material() {
        var m = buffer(64);
        m.putInt(-1).putInt(999).putInt(77).putInt(-2);
        return m.array();
    }

    private static byte[] root() {
        return join(
                version(),
                chunk("MOHD", header()),
                chunk("MOMT", material()),
                chunk("GFID", buffer(12).putInt(-1).putInt(0).putInt(123).array()));
    }

    private static byte[] groupHeader() {
        var h = buffer(68);
        h.putInt(-1).putInt(-2).putInt(0x2009).put(floats(-1, -1, -1, 2, 2, 1));
        h.putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 1)
                .putShort((short) 0x1234)
                .putInt(-3)
                .putInt(-4)
                .putInt(-5)
                .putInt(-6)
                .putInt(-7);
        return h.array();
    }

    private static byte[] batch() {
        return buffer(24)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) -1)
                .putInt(0)
                .putShort((short) 3)
                .putShort((short) 0)
                .putShort((short) 2)
                .put((byte) 2)
                .put((byte) 255)
                .array();
    }

    private static byte[] group(byte[]... extra) {
        return groupWith(
                floats(0, 0, 0, 1, 0, 0, 0, 2, 0),
                buffer(6).putShort((short) 0).putShort((short) 1).putShort((short) 2).array(),
                batch(),
                extra);
    }

    private static byte[] groupWith(
            byte[] positions, byte[] indices, byte[] batches, byte[]... extra) {
        return join(
                version(),
                chunk(
                        "MOGP",
                        join(
                                groupHeader(),
                                chunk("MOVT", positions),
                                chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                chunk("MOTV", floats(0, 0, 1, 0, 0, 1)),
                                chunk("MOVI", indices),
                                chunk(
                                        "MOPY",
                                        new byte[] {
                                            1, (byte) 255,
                                        }),
                                chunk("MOBA", batches),
                                join(extra))));
    }

    @Test
    void rawRootReferenceDomainsAndLodOrderRemainUnresolved() {
        var result = WmoFormats.root(root(), () -> false);
        assertEquals(1, result.header().groupCount());
        assertEquals(0xffffffffL, result.header().counts().get(3));
        assertEquals(0xffffffffL, result.header().ambientColor());
        assertEquals(0x8010, result.header().flags());
        assertEquals(3, result.header().lodCount());
        assertEquals(3, result.gfidCount());
        assertEquals(0xffffffffL, result.gfid(0));
        assertEquals(0, result.gfid(1));
        assertEquals(999, result.materials().getFirst().word(1));
        assertEquals(0xfffffffeL, result.materials().getFirst().word(3));
        assertEquals(
                WmoFormats.ReferenceDomain.UNRESOLVED_UNSIGNED32,
                result.materials().getFirst().textureDomain());
        assertFalse(result.textureNameBlockPresent());
    }

    @Test
    void missingGfidIsDistinctFromRecordedEmptyAndDoesNotGuessFilename() {
        var missing =
                WmoFormats.root(
                        join(version(), chunk("MOHD", header()), chunk("MOMT", material())),
                        () -> false);
        var empty =
                WmoFormats.root(
                        join(
                                version(),
                                chunk("MOHD", header()),
                                chunk("MOMT", material()),
                                chunk("GFID", new byte[0])),
                        () -> false);
        assertFalse(missing.gfidPresent());
        assertTrue(empty.gfidPresent());
        assertEquals(0, missing.gfidCount());
        assertEquals(0, empty.gfidCount());
    }

    @Test
    void groupPreservesRawAxesNormalsUvAndAlternateMaterialEncoding() {
        var result =
                WmoFormats.group(
                        group(chunk("MOCV", new byte[12]), chunk("ZZZZ", new byte[7])),
                        () -> false);
        assertEquals(2, result.positions().get(7));
        assertEquals(1, result.normals().get(2));
        assertEquals(1, result.uvStreams());
        assertEquals(1, result.uv(0).get(2));
        assertEquals(2, result.indices().get(2));
        assertEquals(2, result.batches().getFirst().flags());
        assertEquals(255, result.batches().getFirst().materialByte());
        assertEquals(65535, result.batches().getFirst().rawBounds().getLast());
        assertEquals(0x1234, result.header().rawBatchPadding());
        assertEquals(0xfffffffdL, result.header().rawUnused());
        assertEquals(0xfffffff9L, result.header().tail1());
        assertTrue(
                result.chunks().stream()
                        .anyMatch(
                                c ->
                                        c.tag() == tag("MOCV")
                                                && c.bytes() == 12
                                                && !c.payloadParsed()));
        assertTrue(
                result.chunks().stream().anyMatch(c -> c.tag() == tag("ZZZZ") && c.bytes() == 7));
        assertEquals(255, Byte.toUnsignedInt(result.triangleDeclarations().get(1)));
    }

    @Test
    void factsDoNotAliasSourceAndViewsAreReadOnly() {
        var source = group();
        var result = WmoFormats.group(source, () -> false);
        Arrays.fill(source, (byte) 0);
        assertEquals(2, result.positions().get(7));
        assertThrows(ReadOnlyBufferException.class, () -> result.positions().put(0, 9));
        assertThrows(ReadOnlyBufferException.class, () -> result.indices().put(0, 9));
        assertThrows(
                ReadOnlyBufferException.class,
                () -> result.triangleDeclarations().put(0, (byte) 9));
        assertThrows(UnsupportedOperationException.class, () -> result.chunks().clear());
        var root = WmoFormats.root(join(root(), chunk("MOTX", new byte[] {'a', 0})), () -> false);
        assertEquals('a', root.textureNameBlock().get(0));
        assertThrows(
                ReadOnlyBufferException.class, () -> root.textureNameBlock().put(0, (byte) 'b'));
    }

    @Test
    void repeatedUvStreamsAreFactsAndStrictSliceDeclinesExtraStream() {
        var source = group(chunk("MOTV", floats(0, 0, 1, 0, 0, 1)));
        assertEquals(2, WmoFormats.group(source, () -> false).uvStreams());
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.group(source, WmoFormats.Limits.basicPreview(), () -> false));
    }

    @Test
    void duplicateSingletonsAndWrongVersionAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.root(join(root(), chunk("MOHD", header())), () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.group(group(chunk("MOVT", floats(0, 0, 0))), () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.root(join(root(), version()), () -> false));
        var wrong = root();
        ByteBuffer.wrap(wrong).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 18);
        assertThrows(IllegalArgumentException.class, () -> WmoFormats.root(wrong, () -> false));
    }

    @Test
    void rootCountAndMaterialRecordMismatchRejectInsteadOfTruncating() {
        var h = header();
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 257);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.root(
                                join(version(), chunk("MOHD", h), chunk("MOMT", material())),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.root(
                                join(
                                        version(),
                                        chunk("MOHD", header()),
                                        chunk("MOMT", new byte[0])),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.root(join(root(), chunk("MOTX", new byte[65537])), () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.root(root(), WmoFormats.Limits.basicPreview(), () -> false));
    }

    @Test
    void recordedGroupNamesAreReadOnlyAtTerminatedPrintableOffsets() {
        byte[] block = {0, 0, 'H', 'a', 'l', 'l', 0, 'b', 1, 0, 'o', 'p', 'e', 'n'};
        var root = WmoFormats.root(join(root(), chunk("MOGN", block)), () -> false);
        var names = root.groupNames();
        assertTrue(names.present());
        assertEquals("", names.name(0).orElseThrow());
        assertEquals("Hall", names.name(2).orElseThrow());
        assertEquals("all", names.name(3).orElseThrow());
        assertFalse(names.name(7).isPresent(), "nonprintable byte");
        assertFalse(names.name(10).isPresent(), "unterminated at block end");
        assertFalse(names.name(block.length).isPresent(), "offset outside block");
        assertFalse(names.name(-1).isPresent());
        assertFalse(names.name(0xffffffffL).isPresent());
        assertTrue(
                root.chunks().stream().anyMatch(c -> c.tag() == tag("MOGN") && c.payloadParsed()));
        block[2] = 'X';
        assertEquals("Hall", names.name(2).orElseThrow(), "retained bytes are detached");
        var tooLong = new byte[WmoFormats.GroupNames.MAX_NAME_BYTES + 2];
        Arrays.fill(tooLong, (byte) 'a');
        tooLong[tooLong.length - 1] = 0;
        assertFalse(WmoFormats.GroupNames.of(tooLong).name(0).isPresent());
        tooLong[tooLong.length - 2] = 0;
        assertEquals(
                WmoFormats.GroupNames.MAX_NAME_BYTES,
                WmoFormats.GroupNames.of(tooLong).name(0).orElseThrow().length());
    }

    @Test
    void missingOversizedOrOverBudgetGroupNamesStayAbsentWithoutDecliningRoot() {
        assertFalse(WmoFormats.root(root(), () -> false).groupNames().present());
        assertFalse(WmoFormats.root(root(), () -> false).groupNames().name(0).isPresent());
        var oversized = new byte[WmoFormats.GroupNames.MAX_BLOCK_BYTES + 1];
        assertFalse(
                WmoFormats.root(join(root(), chunk("MOGN", oversized)), () -> false)
                        .groupNames()
                        .present());
        var tight = new WmoFormats.Limits(1 << 20, 1 << 20, 8, 24, 8, 16384, 16384, 64, 1, 4096);
        var root = WmoFormats.root(join(root(), chunk("MOGN", new byte[4096])), tight, () -> false);
        assertFalse(root.groupNames().present());
        assertEquals(1, root.materials().size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.root(
                                join(
                                        root(),
                                        chunk("MOGN", new byte[1]),
                                        chunk("MOGN", new byte[1])),
                                () -> false));
    }

    @Test
    void badIndicesBatchSpansAndShapeReject() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.group(
                                groupWith(
                                        floats(0, 0, 0, 1, 0, 0, 0, 2, 0),
                                        buffer(6)
                                                .putShort((short) 0)
                                                .putShort((short) 1)
                                                .putShort((short) 3)
                                                .array(),
                                        batch()),
                                () -> false));
        var badBatch = batch();
        ByteBuffer.wrap(badBatch).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.group(
                                groupWith(floats(0, 0, 0, 1, 0, 0, 0, 2, 0), new byte[6], badBatch),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.group(groupWith(new byte[11], new byte[6], batch()), () -> false));
    }

    @Test
    void nonfiniteAndInvertedBoundsReject() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.group(
                                groupWith(
                                        floats(Float.NaN, 0, 0, 1, 0, 0, 0, 2, 0),
                                        new byte[6],
                                        batch()),
                                () -> false));
        var h = header();
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putFloat(36, 2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoFormats.root(
                                join(version(), chunk("MOHD", h), chunk("MOMT", material())),
                                () -> false));
    }

    @Test
    void intervalsTruncationMissingCoreAndBudgetRefuse() {
        var original = group();
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.group(Arrays.copyOf(original, original.length - 1), () -> false));
        var bad = group();
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> WmoFormats.group(bad, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.group(join(version(), chunk("MOGP", groupHeader())), () -> false));
        var l =
                new WmoFormats.Limits(
                        8 << 20, 8 << 20, 256, 256, 4096, 65536, 131072, 256, 4, 1024);
        assertThrows(
                IllegalArgumentException.class, () -> WmoFormats.group(group(), l, () -> false));
    }

    @Test
    void strictShapeCapsRejectBeforeReadingBigVectorsAndCannotRaiseCeilings() {
        var excessive = new byte[513 * 12];
        ByteBuffer.wrap(excessive).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, Float.NaN);
        var refusal =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                WmoFormats.group(
                                        groupWith(excessive, new byte[6], batch()),
                                        WmoFormats.Limits.basicPreview(),
                                        () -> false));
        assertTrue(refusal.getMessage().contains("array shape/count"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WmoFormats.Limits(
                                8 << 20, 8 << 20, 257, 256, 4096, 65536, 131072, 256, 4,
                                12L << 20));
    }

    @Test
    void cancellationBeforeAndInsideParsingPublishesNoFacts() {
        assertThrows(CancellationException.class, () -> WmoFormats.root(root(), () -> true));
        var calls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> WmoFormats.group(group(), () -> calls.incrementAndGet() > 10));
    }

    @Test
    void excessiveUnknownChunkInventoryRefusesRatherThanTruncates() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(root());
        for (int i = 0; i < WmoFormats.MAX_CHUNKS; i++) {
            out.writeBytes(chunk("ZZZZ", new byte[0]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoFormats.root(out.toByteArray(), () -> false));
    }

    @Test
    void repeatedBatchInspectionHasAnIndependentWorkCeiling() {
        var source =
                groupWith(
                        floats(0, 0, 0, 1, 0, 0, 0, 2, 0),
                        buffer(6)
                                .putShort((short) 0)
                                .putShort((short) 1)
                                .putShort((short) 2)
                                .array(),
                        join(batch(), batch()));
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putShort(64, (short) 2);
        assertEquals(2, WmoFormats.group(source, () -> false).batches().size());
        var limits = new WmoFormats.Limits(1024, 32768, 1, 6, 1, 512, 1, 6, 1, 256L << 10);
        var refusal =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WmoFormats.group(source, limits, () -> false));
        assertTrue(refusal.getMessage().contains("work limit"));
    }

    @Test
    void explicitEmptyRootAndGroupAreDifferentFromMissingDeclarations() {
        var h = header();
        var buffer = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 0).putInt(4, 0);
        var root =
                WmoFormats.root(
                        join(
                                version(),
                                chunk("MOHD", h),
                                chunk("MOMT", new byte[0]),
                                chunk("GFID", new byte[0])),
                        () -> false);
        assertEquals(0, root.header().groupCount());
        assertTrue(root.gfidPresent());
        var gh = groupHeader();
        ByteBuffer.wrap(gh).order(ByteOrder.LITTLE_ENDIAN).putShort(44, (short) 0);
        var group =
                WmoFormats.group(
                        join(
                                version(),
                                chunk(
                                        "MOGP",
                                        join(
                                                gh,
                                                chunk("MOVT", new byte[0]),
                                                chunk("MONR", new byte[0]),
                                                chunk("MOVI", new byte[0]),
                                                chunk("MOPY", new byte[0]),
                                                chunk("MOBA", new byte[0])))),
                        () -> false);
        assertEquals(0, group.positions().remaining());
        assertEquals(0, group.uvStreams());
    }

    @Test
    void strictSourceBytesRefuseBeforeAnyChunkInterpretation() {
        var root =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                WmoFormats.root(
                                        new byte[1025],
                                        WmoFormats.Limits.basicPreview(),
                                        () -> false));
        var group =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                WmoFormats.group(
                                        new byte[32769],
                                        WmoFormats.Limits.basicPreview(),
                                        () -> false));
        assertTrue(root.getMessage().contains("source byte limit"));
        assertTrue(group.getMessage().contains("source byte limit"));
    }
}
