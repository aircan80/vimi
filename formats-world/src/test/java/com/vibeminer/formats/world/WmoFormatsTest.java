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
import java.util.List;
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
        // Flag 0x2 alone: the MOMT index is the sixth bounds word, not the material byte.
        assertEquals(65535, result.batches().getFirst().materialIndex());
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
    void batchMaterialIndexReadsTheByteOrTheLargeIndexAndRefusesOtherFlags() {
        var bounds = List.of(1, 2, 3, 4, 5, 300);
        assertEquals(7, new WmoFormats.Batch(bounds, 0, 3, 0, 2, 0, 7).materialIndex());
        assertEquals(300, new WmoFormats.Batch(bounds, 0, 3, 0, 2, 2, 7).materialIndex());
        for (int flags : new int[] {1, 3, 4, 0x80}) {
            assertEquals(-1, new WmoFormats.Batch(bounds, 0, 3, 0, 2, flags, 7).materialIndex());
        }
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

    /** A root declaring {@code groups} groups, each with one GFID entry, plus extras. */
    private static byte[] rootWithGroups(int groups, byte[]... extras) {
        var h = buffer(64);
        h.putInt(1).putInt(groups);
        h.position(36);
        h.put(floats(-1, -1, -1, 1, 1, 1));
        var gfid = buffer(4 * groups);
        for (int g = 0; g < groups; g++) {
            gfid.putInt(1000 + g);
        }
        return join(
                version(),
                chunk("MOHD", h.array()),
                chunk("MOMT", material()),
                chunk("GFID", gfid.array()),
                join(extras));
    }

    /** MOGI: one 32-byte SMOGroupInfo per group, flags first, then bounds and name offset. */
    private static byte[] mogi(int... flags) {
        var b = buffer(32 * flags.length);
        for (int i = 0; i < flags.length; i++) {
            b.putInt(i * 32, flags[i]).putFloat(i * 32 + 4, -1).putInt(i * 32 + 28, -1);
        }
        return chunk("MOGI", b.array());
    }

    @Test
    void rootsUpToFiveHundredTwelveGroupsParse() {
        assertEquals(512, WmoFormats.MAX_GROUPS);
        assertEquals(512, WmoFormats.Limits.standard().groups());
        var root = WmoFormats.root(rootWithGroups(512), () -> false);
        assertEquals(512, root.header().groupCount());
        assertEquals(512, root.gfidCount());
        var over =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WmoFormats.root(rootWithGroups(513), () -> false));
        assertTrue(over.getMessage().contains("Root counts exceed limit"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WmoFormats.Limits(1024, 1024, 513, 1, 1, 1, 1, 1, 1, 1024));
    }

    @Test
    void groupInfoFlagsAreRetainedInGroupOrder() {
        var flags = new int[300];
        for (int g = 0; g < flags.length; g++) {
            flags[g] = g % 3 == 0 ? 0x8 : 0x2000 | 0x8;
        }
        flags[299] = 0x80002000; // all 32 bits are kept unsigned
        var root = WmoFormats.root(rootWithGroups(300, mogi(flags)), () -> false);
        assertTrue(root.groupInfoRetained());
        assertEquals(0x8, root.groupInfoFlags(0));
        assertEquals(0x2008, root.groupInfoFlags(1));
        assertEquals(0x80002000L, root.groupInfoFlags(299));
        assertTrue(
                root.chunks().stream().anyMatch(c -> c.tag() == tag("MOGI") && c.payloadParsed()));
    }

    @Test
    void absentMismatchedPartialDuplicateOrOverBudgetGroupInfoIsNotRetained() {
        var absent = WmoFormats.root(root(), () -> false);
        assertFalse(absent.groupInfoRetained());
        assertThrows(IllegalStateException.class, () -> absent.groupInfoFlags(0));
        // The fixture root declares one group.
        assertTrue(WmoFormats.root(join(root(), mogi(0x2000)), () -> false).groupInfoRetained());
        assertFalse(
                WmoFormats.root(join(root(), mogi(0x2000, 0x8)), () -> false).groupInfoRetained(),
                "record count differs from MOHD");
        assertFalse(
                WmoFormats.root(join(root(), chunk("MOGI", new byte[31])), () -> false)
                        .groupInfoRetained(),
                "partial record");
        assertFalse(
                WmoFormats.root(join(root(), mogi(0x2000), mogi(0x2000)), () -> false)
                        .groupInfoRetained(),
                "duplicate");
        // A budget that holds the root but not its group info leaves only the info absent: the
        // smallest budget that parses the root with a mismatched (unclaimed) MOGI is too small
        // for 300 retained flags.
        int needed = 0;
        for (int budget = 1024; budget < 64 << 10 && needed == 0; budget += 8) {
            var limits =
                    new WmoFormats.Limits(
                            1 << 20, 1 << 20, 512, 24, 4096, 16384, 16384, 64, 1, budget);
            try {
                WmoFormats.root(rootWithGroups(300, mogi(new int[299])), limits, () -> false);
                needed = budget;
            } catch (IllegalArgumentException overBudget) {
                // keep looking for the smallest budget that holds the root alone
            }
        }
        assertTrue(needed > 0);
        var squeezed =
                new WmoFormats.Limits(1 << 20, 1 << 20, 512, 24, 4096, 16384, 16384, 64, 1, needed);
        var root = WmoFormats.root(rootWithGroups(300, mogi(new int[300])), squeezed, () -> false);
        assertFalse(root.groupInfoRetained());
        assertEquals(300, root.gfidCount());
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
        // MOGN before GFID, as in client roots: later declarations still claim first.
        var ordered =
                join(
                        version(),
                        chunk("MOHD", header()),
                        chunk("MOMT", material()),
                        chunk("MOGN", new byte[4096]),
                        chunk("GFID", buffer(12).putInt(-1).putInt(0).putInt(123).array()));
        var early = WmoFormats.root(ordered, tight, () -> false);
        assertFalse(early.groupNames().present());
        assertEquals(3, early.gfidCount());
        var roomy = new WmoFormats.Limits(1 << 20, 1 << 20, 8, 24, 8, 16384, 16384, 64, 1, 8192);
        var named = WmoFormats.root(ordered, roomy, () -> false);
        assertTrue(named.groupNames().present());
        assertEquals("", named.groupNames().name(0).orElseThrow());
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
                                8 << 20, 8 << 20, 513, 256, 4096, 65536, 131072, 256, 4,
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

    @Test
    void doodadDeclarationsAreRawAndOversizedOrOverBudgetOnesAreNotRetained() {
        var absent = WmoFormats.root(root(), () -> false).doodads();
        assertFalse(absent.declared());
        assertFalse(absent.retained());
        var sets = buffer(64);
        sets.put("Set_$DefaultGlobal".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        sets.putInt(20, 0).putInt(24, 1).putInt(52, 1).putInt(56, 1);
        var def = buffer(80);
        def.putInt(0x05000002).put(floats(1, 2, 3, 0, 0, 0.5f, 0.75f)).putFloat(1.5f);
        def.putInt(-1).putInt(1).put(floats(Float.NaN, 0, 0, 0, 0, 0, 1)).putFloat(1).putInt(0);
        var ids = buffer(12).putInt(10).putInt(0).putInt(-1);
        var bytes =
                join(
                        root(),
                        chunk("MODS", sets.array()),
                        chunk("MODI", ids.array()),
                        chunk("MODD", def.array()));
        var doodads = WmoFormats.root(bytes, () -> false).doodads();
        assertTrue(doodads.declared() && doodads.retained() && doodads.fileIdsPresent());
        assertFalse(doodads.namesPresent());
        assertEquals(
                java.util.List.of(new WmoFormats.DoodadSet(0, 1), new WmoFormats.DoodadSet(1, 1)),
                doodads.sets());
        var first = doodads.definitions().getFirst();
        assertEquals(2, first.reference());
        assertEquals(5, first.flags());
        assertEquals(3, first.z(), 0);
        assertEquals(0.75f, first.qw(), 0);
        assertEquals(1.5f, first.scale(), 0);
        // Raw: a non-finite definition is retained for the consumer to refuse.
        assertTrue(Float.isNaN(doodads.definitions().get(1).x()));
        assertEquals(3, doodads.fileIdCount());
        assertEquals(0xffffffffL, doodads.fileId(2));
        // A partial record or a duplicate declaration leaves the doodads not retained, and the
        // root still parses as it did before doodads were read.
        for (var broken :
                new byte[][] {
                    join(root(), chunk("MODS", sets.array()), chunk("MODD", new byte[39])),
                    join(root(), chunk("MODS", new byte[33]), chunk("MODD", def.array())),
                    join(
                            root(),
                            chunk("MODS", sets.array()),
                            chunk("MODI", new byte[5]),
                            chunk("MODD", def.array())),
                    join(
                            root(),
                            chunk("MODS", sets.array()),
                            chunk("MODD", def.array()),
                            chunk("MODD", def.array())),
                    join(
                            root(),
                            chunk("MODS", sets.array()),
                            chunk("MODN", new byte[4]),
                            chunk("MODN", new byte[4]),
                            chunk("MODD", def.array()))
                }) {
            var parsed = WmoFormats.root(broken, () -> false);
            assertEquals(1, parsed.materials().size());
            assertTrue(parsed.doodads().declared());
            assertFalse(parsed.doodads().retained());
        }
        // Names without MODI are retained but flagged.
        var named =
                WmoFormats.root(
                                join(
                                        root(),
                                        chunk("MODS", sets.array()),
                                        chunk("MODN", new byte[8]),
                                        chunk("MODD", def.array())),
                                () -> false)
                        .doodads();
        assertTrue(named.retained() && named.namesPresent() && !named.fileIdsPresent());
        // Over the fact budget: declared but not retained, and the root still parses.
        var tight = new WmoFormats.Limits(1 << 20, 1 << 20, 8, 24, 8, 16384, 16384, 64, 1, 4096);
        var many = new byte[40 * 64];
        var big =
                WmoFormats.root(
                        join(root(), chunk("MODS", sets.array()), chunk("MODD", many)),
                        tight,
                        () -> false);
        assertTrue(big.doodads().declared());
        assertFalse(big.doodads().retained());
        assertEquals(1, big.materials().size());
    }

    private static byte[] portalVertices() {
        // One quad in the x = 0 plane.
        return floats(0, -1, 0, 0, 1, 0, 0, 1, 2, 0, -1, 2);
    }

    private static byte[] portal(int first, int count, float nx, float distance) {
        var b = buffer(20).putShort((short) first).putShort((short) count);
        return b.put(floats(nx, 0, 0, distance)).array();
    }

    private static byte[] portalRef(int portal, int group, int side) {
        return buffer(8)
                .putShort((short) portal)
                .putShort((short) group)
                .putShort((short) side)
                .putShort((short) 0)
                .array();
    }

    @Test
    void portalDeclarationsAreRawAndPartialOrOutOfRangeOnesAreNotRetained() {
        var absent = WmoFormats.root(root(), () -> false).portals();
        assertFalse(absent.declared());
        assertFalse(absent.retained());
        var bytes =
                join(
                        root(),
                        chunk("MOPV", portalVertices()),
                        chunk("MOPT", portal(0, 4, 1, 0)),
                        chunk("MOPR", join(portalRef(0, 0, 1), portalRef(0, 0, -1))));
        var portals = WmoFormats.root(bytes, () -> false).portals();
        assertTrue(portals.declared() && portals.retained());
        assertEquals(4, portals.vertexCount());
        assertEquals(new WorldPlacements.Vec3(0, 1, 2), portals.vertex(2));
        assertEquals(
                java.util.List.of(
                        new WmoFormats.Portal(0, 4, new WorldPlacements.Vec3(1, 0, 0), 0)),
                portals.portals());
        // The side word is signed and raw.
        assertEquals(
                java.util.List.of(
                        new WmoFormats.PortalRef(0, 0, 1), new WmoFormats.PortalRef(0, 0, -1)),
                portals.refs());
        var ref = chunk("MOPR", portalRef(0, 0, 1));
        var vertices = chunk("MOPV", portalVertices());
        var quad = chunk("MOPT", portal(0, 4, 1, 0));
        for (var broken :
                new byte[][] {
                    // A missing declaration, a partial record or a duplicate.
                    join(root(), vertices, quad),
                    join(root(), quad, ref),
                    join(root(), chunk("MOPV", new byte[13]), quad, ref),
                    join(root(), vertices, chunk("MOPT", new byte[19]), ref),
                    join(root(), vertices, quad, chunk("MOPR", new byte[9])),
                    join(root(), vertices, quad, ref, ref),
                    // A degenerate portal or a vertex range outside MOPV.
                    join(root(), vertices, chunk("MOPT", portal(0, 2, 1, 0)), ref),
                    join(root(), vertices, chunk("MOPT", portal(1, 4, 1, 0)), ref),
                    // Non-finite vertices or planes.
                    join(
                            root(),
                            chunk("MOPV", floats(0, 0, 0, 0, 1, 0, Float.NaN, 0, 0)),
                            chunk("MOPT", portal(0, 3, 1, 0)),
                            ref),
                    join(root(), vertices, chunk("MOPT", portal(0, 4, Float.NaN, 0)), ref),
                    join(
                            root(),
                            vertices,
                            chunk("MOPT", portal(0, 4, 1, Float.POSITIVE_INFINITY)),
                            ref),
                    // A reference to a portal outside MOPT or a group outside MOHD's one group.
                    join(root(), vertices, quad, chunk("MOPR", portalRef(1, 0, 1))),
                    join(root(), vertices, quad, chunk("MOPR", portalRef(0, 1, 1)))
                }) {
            var parsed = WmoFormats.root(broken, () -> false);
            assertEquals(1, parsed.materials().size());
            assertTrue(parsed.portals().declared());
            assertFalse(parsed.portals().retained());
            assertTrue(parsed.portals().portals().isEmpty());
        }
        // Zero-length declarations are retained and empty.
        var empty =
                WmoFormats.root(
                                join(
                                        root(),
                                        chunk("MOPV", new byte[0]),
                                        chunk("MOPT", new byte[0]),
                                        chunk("MOPR", new byte[0])),
                                () -> false)
                        .portals();
        assertTrue(empty.declared() && empty.retained());
        assertEquals(0, empty.vertexCount());
        assertTrue(empty.portals().isEmpty() && empty.refs().isEmpty());
        // One past each ceiling: declared but not retained.
        var overVertices = new byte[12 * (WmoFormats.MAX_PORTAL_VERTICES + 1)];
        var overPortals = new byte[20 * (WmoFormats.MAX_PORTALS + 1)];
        for (int i = 0; i <= WmoFormats.MAX_PORTALS; i++) {
            System.arraycopy(portal(0, 4, 1, 0), 0, overPortals, i * 20, 20);
        }
        var overRefs = new byte[8 * (WmoFormats.MAX_PORTAL_REFS + 1)];
        for (var over :
                new byte[][] {
                    join(root(), chunk("MOPV", overVertices), quad, ref),
                    join(root(), vertices, chunk("MOPT", overPortals), ref),
                    join(root(), vertices, quad, chunk("MOPR", overRefs))
                }) {
            var parsed = WmoFormats.root(over, () -> false);
            assertTrue(parsed.portals().declared());
            assertFalse(parsed.portals().retained());
        }
        // Over the fact budget: declared but not retained, and the root still parses.
        var tight = new WmoFormats.Limits(1 << 20, 1 << 20, 8, 24, 8, 16384, 16384, 64, 1, 4096);
        var many = new byte[12 * 256];
        var big = WmoFormats.root(join(root(), chunk("MOPV", many), quad, ref), tight, () -> false);
        assertTrue(big.portals().declared());
        assertFalse(big.portals().retained());
        assertEquals(1, big.materials().size());
    }
}
