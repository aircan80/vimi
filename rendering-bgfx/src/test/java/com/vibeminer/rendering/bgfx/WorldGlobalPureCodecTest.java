package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class WorldGlobalPureCodecTest {
    private static final WorldGlobalLayout.Counts COUNTS =
            new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 6, 1, 1);
    private static final String BUILD =
            "86b676b77f0a4bf074895ae21f87b1b4fe029334eb79e77a0cc33af0b59e13a7";
    private static final String RECIPE =
            "249543217b8b3c89d1fe88f0556d628d3abf135ede0ddb3d21ca380b4f9d3230";

    private static byte[] modf() {
        var b =
                ByteBuffer.allocate(64)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(2)
                        .putInt(-1)
                        .putInt(0x80000000);
        b.position(32);
        for (float f : new float[] {-1, -2, -3, 4, 5, 6}) {
            b.putFloat(f);
        }
        b.putShort((short) 8).putShort((short) 0).putShort((short) 0).putShort((short) 1024);
        return b.array();
    }

    private static WorldGlobalHashes.RawModf placement() {
        return WorldGlobalHashes.RawModf.copyOf(modf(), 2, 0xffff_ffffL);
    }

    private static List<WorldGlobalHashes.Source> sources() {
        return List.of(
                new WorldGlobalHashes.Source(
                        1,
                        WorldGlobalHashes.Role.WDT,
                        0,
                        128,
                        128L,
                        "a".repeat(32),
                        "1".repeat(64)),
                new WorldGlobalHashes.Source(
                        2,
                        WorldGlobalHashes.Role.ROOT,
                        0,
                        64,
                        null,
                        "b".repeat(32),
                        "2".repeat(64)),
                new WorldGlobalHashes.Source(
                        3,
                        WorldGlobalHashes.Role.GROUP,
                        0,
                        256,
                        256L,
                        "c".repeat(32),
                        "3".repeat(64)),
                new WorldGlobalHashes.Source(
                        4,
                        WorldGlobalHashes.Role.IMAGE,
                        0,
                        156,
                        156L,
                        "d".repeat(32),
                        "4".repeat(64)));
    }

    private static WorldGlobalHashes.Material material() {
        long[] words = new long[16];
        words[3] = 4;
        words[4] = 0xff000000L;
        words[8] = 10;
        return new WorldGlobalHashes.Material(words);
    }

    private static WorldGlobalHashes.Image image() {
        return new WorldGlobalHashes.Image(
                4, 1, 1, 2, 8, 7, 1, 0, 0, 1, "5".repeat(64), "6".repeat(64));
    }

    private static String recipe(
            WorldGlobalHashes.RawModf modf, List<WorldGlobalHashes.Source> sources) {
        return WorldGlobalHashes.recipe(
                249, "7".repeat(64), BUILD, modf, sources, List.of(material()), List.of(image()));
    }

    @Test
    void exactLayoutMaximumAndPolicyMaskHaveIndependentByteTotals() {
        var first = new WorldGlobalLayout.Counts(24, 8, 64, 18, 32768, 98304, 32768, 1179648);
        // The first profile's maximum, now with 96-byte image records and 32-bit indices.
        assertEquals(5_577_600 + 18 * 32 + 98304 * 2, first.offsets().actualBytes());
        var max =
                new WorldGlobalLayout.Counts(256, 256, 4096, 128, 524288, 1572864, 524288, 8388608);
        int sources = 2 + 256 + 128;
        assertEquals(
                1024
                        + sources * 64
                        + 256 * 64
                        + 256 * 64
                        + 4096 * 16
                        + 128 * 96
                        + 524288 * 20
                        + 1572864 * 4
                        + 8388608 * 4,
                max.offsets().actualBytes());
        assertEquals(WorldGlobalLayout.MAX_ENCODED_BYTES, max.offsets().actualBytes());
        assertTrue(max.offsets().actualBytes() < WorldGlobalLayout.MAX_FILE_BYTES);
        assertEquals(1024, COUNTS.offsets().source());
        assertEquals(1280, COUNTS.offsets().material());
        assertEquals(1344, COUNTS.offsets().group());
        assertEquals(1408, COUNTS.offsets().batch());
        assertEquals(1424, COUNTS.offsets().image());
        assertEquals(1520, COUNTS.offsets().vertices());
        assertEquals(1580, COUNTS.offsets().indices());
        assertEquals(1604, COUNTS.offsets().rgba());
        assertEquals(1608, COUNTS.offsets().actualBytes());
        assertEquals(
                511,
                Arrays.stream(WorldGlobalLayout.Omission.values())
                        .mapToInt(WorldGlobalLayout.Omission::bit)
                        .reduce(0, (a, b) -> a | b));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(Integer.MAX_VALUE, 1, 1, 1, 3, 6, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 9, 9, 1, 524289, 27, 9, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 7, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 6, 1, 65537));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(257, 1, 1, 1, 3, 6, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 257, 257, 1, 257, 771, 257, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 4097, 1, 3, 6, 4097, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 1, 1, 65537, 6, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 196611, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 6, 1, 65537));
    }

    @Test
    void independentFramedShaVectorsPreserveRawSourceZeroBitsAndSource64() {
        assertEquals(
                BUILD,
                WorldGlobalHashes.buildLocale(
                        "wowf", "a".repeat(32), "b".repeat(32), "us", "enUS"));
        assertNotEquals(
                BUILD,
                WorldGlobalHashes.buildLocale(
                        "wowf", "a".repeat(32), "b".repeat(32), null, "enUS"));
        assertEquals(RECIPE, recipe(placement(), sources()));
        var expected =
                ByteBuffer.allocate(64)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(2)
                        .put((byte) 2)
                        .put((byte) 0)
                        .putShort((short) 1)
                        .putInt(64)
                        .putInt(0)
                        .put(HexFormat.of().parseHex("b".repeat(32)))
                        .put(HexFormat.of().parseHex("2".repeat(64)))
                        .array();
        assertArrayEquals(expected, WorldGlobalHashes.sourceBytes(sources().get(1)));
        byte[] raw = modf();
        var detached = WorldGlobalHashes.RawModf.copyOf(raw, 2, 0xffff_ffffL);
        Arrays.fill(raw, (byte) 0);
        assertEquals(
                0x80000000,
                ByteBuffer.wrap(detached.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(8));
        byte[] positive = modf();
        ByteBuffer.wrap(positive).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0);
        assertNotEquals(
                RECIPE,
                recipe(WorldGlobalHashes.RawModf.copyOf(positive, 2, 0xffff_ffffL), sources()));
        byte[] copy = detached.bytes();
        copy[0] = 0;
        assertEquals(RECIPE, recipe(detached, sources()));
    }

    @Test
    void rawModfIdentityAndProfileCannotBeSubstituted() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalHashes.RawModf.copyOf(modf(), 3, 0xffff_ffffL));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalHashes.RawModf.copyOf(modf(), 2, 0));
        for (int mode = 0; mode < 5; mode++) {
            var b = ByteBuffer.wrap(modf()).order(ByteOrder.LITTLE_ENDIAN);
            switch (mode) {
                case 0 -> b.putFloat(8, 1);
                case 1 -> b.putShort(56, (short) 4);
                case 2 -> b.putShort(62, (short) 512);
                case 3 -> b.putFloat(32, Float.NaN);
                case 4 -> b.putShort(58, (short) 1);
                default -> throw new AssertionError();
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldGlobalHashes.RawModf.copyOf(b.array(), 2, 0xffff_ffffL));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGlobalHashes.buildLocale(
                                "w".repeat(33), "a".repeat(32), "b".repeat(32), "us", "enUS"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGlobalHashes.buildLocale(
                                "wowf", "A".repeat(32), "b".repeat(32), "us", "enUS"));
        var duplicate =
                List.of(
                        sources().get(0),
                        sources().get(1),
                        sources().get(2),
                        new WorldGlobalHashes.Source(
                                3,
                                WorldGlobalHashes.Role.IMAGE,
                                0,
                                156,
                                156L,
                                "d".repeat(32),
                                "4".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> recipe(placement(), duplicate));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGlobalHashes.Image(
                                4, 1, 1, 4, 0, 0, 1, 0, 0, 1, "5".repeat(64), "6".repeat(64)));
    }

    private static class Mesh implements WorldGlobalHashes.Mesh {
        float[] vertices = {0, 0, 0, -0f, 0, -1, 0, 0, 1, 0, 0, -2, 0, 0, 1};
        int[] indices = {0, 1, 2, 2, 1, 0};
        final AtomicInteger reads = new AtomicInteger();

        public WorldGlobalLayout.Counts counts() {
            return COUNTS;
        }

        public List<WorldGlobalHashes.Group> groups() {
            return List.of(new WorldGlobalHashes.Group(0, 3, 0, 6, 0, 1, 0xa09));
        }

        public List<WorldGlobalHashes.Batch> batches() {
            return List.of(new WorldGlobalHashes.Batch(0, 0, 0, 3));
        }

        public float vertex(int v, int c) {
            reads.incrementAndGet();
            return vertices[v * 5 + c];
        }

        public int index(int i) {
            reads.incrementAndGet();
            return indices[i];
        }
    }

    @Test
    void independentNativeGroupAndMeshShaVectorsBindDerivativesNotSourceHashes() {
        var mesh = new Mesh();
        var hashes = WorldGlobalHashes.mesh(mesh, () -> false);
        assertEquals(
                List.of("0216dfa7117c353a4da64c44e38e7b1be8975f08c1750a54c34b660b7e01d779"),
                hashes.groups());
        assertEquals(
                "7ae598bd2bf095d09077bfcd2f224506d45bba34718a48799149203673028e37",
                hashes.nativeWire());
        assertEquals(21, mesh.reads.get());
        assertNotEquals(RECIPE, hashes.nativeWire());
        mesh.vertices[3] = 0;
        assertNotEquals(
                hashes.nativeWire(), WorldGlobalHashes.mesh(mesh, () -> false).nativeWire());
        mesh.vertices[0] = -0f;
        assertThrows(
                IllegalArgumentException.class, () -> WorldGlobalHashes.mesh(mesh, () -> false));
        mesh.vertices[0] = 0;
        mesh.indices[0] = 3;
        assertThrows(
                IllegalArgumentException.class, () -> WorldGlobalHashes.mesh(mesh, () -> false));
        var invalid =
                new Mesh() {
                    @Override
                    public List<WorldGlobalHashes.Batch> batches() {
                        return List.of(new WorldGlobalHashes.Batch(0, 0, 6, 3));
                    }
                };
        assertThrows(
                IllegalArgumentException.class, () -> WorldGlobalHashes.mesh(invalid, () -> false));
        assertEquals(0, invalid.reads.get());
        assertThrows(
                CancellationException.class, () -> WorldGlobalHashes.mesh(new Mesh(), () -> true));
    }

    @Test
    void streamingWholeFileShaAndCrcAgreeWithIndependentSmallByteOracle() throws Exception {
        byte[] file = new byte[COUNTS.offsets().actualBytes()];
        for (int n = 1024; n < file.length; n++) {
            file[n] = (byte) (n * 17);
        }
        var body = new CRC32C();
        body.update(file, 1024, file.length - 1024);
        var header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(424, (int) body.getValue());
        byte[] crcInput = Arrays.copyOf(file, 1024);
        Arrays.fill(crcInput, 160, 192, (byte) 0);
        Arrays.fill(crcInput, 428, 432, (byte) 0);
        var crc = new CRC32C();
        crc.update(crcInput);
        header.putInt(428, (int) crc.getValue());
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        header.position(160).put(HexFormat.of().parseHex(expected));
        var hasher = new WorldGlobalHashes.FileHasher(COUNTS);
        int[] boundaries = {0, 159, 170, 193, 1023, 1025, file.length};
        for (int n = 0; n < boundaries.length - 1; n++) {
            var chunk = ByteBuffer.wrap(file).position(boundaries[n]).limit(boundaries[n + 1]);
            int position = chunk.position(), limit = chunk.limit();
            hasher.update(chunk.asReadOnlyBuffer());
            assertEquals(position, chunk.position());
            assertEquals(limit, chunk.limit());
        }
        var actual = hasher.finish();
        assertEquals(expected, actual.sha256());
        assertEquals(body.getValue(), actual.bodyCrc32c());
        byte[] boundedHeader = Arrays.copyOf(file, 1024);
        WorldGlobalHashes.verifyIntegrity(boundedHeader, actual, expected);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalHashes.verifyIntegrity(boundedHeader, actual, "f".repeat(64)));
        boundedHeader[160] ^= 1;
        assertEquals(crc.getValue(), WorldGlobalHashes.headerCrc(boundedHeader));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalHashes.verifyIntegrity(boundedHeader, actual, expected));
        boundedHeader[160] ^= 1;
        boundedHeader[424] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalHashes.verifyIntegrity(boundedHeader, actual, expected));
        assertThrows(IllegalArgumentException.class, hasher::finish);
        assertThrows(IllegalArgumentException.class, () -> hasher.update(ByteBuffer.allocate(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalHashes.FileHasher(COUNTS).finish());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGlobalHashes.FileHasher(
                                        new WorldGlobalLayout.Counts(
                                                24, 8, 64, 18, 32768, 98304, 32768, 1179648))
                                .update(ByteBuffer.allocate(262145)));
    }

    @Test
    void statusesHaveExactCanonicalBoundsAndDoNotClaimParentPixelAcceptance() {
        var id = new WorldGlobalStatus.Identity("a".repeat(32), "b".repeat(64), "c".repeat(64));
        var ready = new WorldGlobalStatus.Ready(id, 524288, 524288, 524288, 256, 4096, 128);
        assertEquals(WorldGlobalStatus.READY_BYTES, ready.protocolLine().length() + 1);
        assertEquals(216, WorldGlobalStatus.READY_BYTES);
        assertEquals(ready, WorldGlobalStatus.parse(ready.protocolLine()));
        var consumed = new WorldGlobalStatus.InputConsumed(id.nonce(), id.fileSha256());
        assertEquals(120, consumed.protocolLine().length() + 1);
        assertEquals(consumed, WorldGlobalStatus.parse(consumed.protocolLine()));
        assertTrue(consumed.matches(id));
        for (var code : WorldGlobalStatus.Code.values()) {
            var unavailable = new WorldGlobalStatus.Unavailable(id, code);
            assertEquals(unavailable, WorldGlobalStatus.parse(unavailable.protocolLine()));
        }
        for (String line :
                List.of(
                        ready.protocolLine() + "\n",
                        ready.protocolLine() + " ",
                        ready.protocolLine().replace(" 511", " 512"),
                        ready.protocolLine().replace(" 524288", " 0524288"),
                        ready.protocolLine().replace(" 524288", " +524288"),
                        ready.protocolLine().replace(" 524288", " 524289"),
                        ready.protocolLine().replace(" 256 ", " 257 "),
                        ready.protocolLine().replace(" 4096 ", " 4097 "),
                        "GLOBAL_READY " + "a".repeat(10000),
                        "GLOBAL_OTHER",
                        consumed.protocolLine().replace('a', 'é'))) {
            assertThrows(IllegalArgumentException.class, () -> WorldGlobalStatus.parse(line));
        }
        var first = new WorldGlobalStatus.Ready(id, 3, 2, 1, 1, 1, 1);
        assertTrue(first.matches(id, COUNTS));
        assertFalse(
                first.matches(
                        new WorldGlobalStatus.Identity("d".repeat(32), id.scope(), id.fileSha256()),
                        COUNTS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldGlobalStatus.Ready(id, 1, 65537, 1, 1, 1, 1));
    }

    @Test
    void boundedMetadataNeverCallsProviderBulkCopyOrHashesUnboundedFields() {
        var calls = new AtomicInteger();
        var tooLarge =
                new java.util.AbstractList<WorldGlobalHashes.Source>() {
                    @Override
                    public int size() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public WorldGlobalHashes.Source get(int at) {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }

                    @Override
                    public Object[] toArray() {
                        throw new AssertionError("Unbounded provider bulk allocation");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> recipe(placement(), tooLarge));
        assertEquals(0, calls.get());
        var indexedOnly =
                new java.util.AbstractList<WorldGlobalHashes.Source>() {
                    @Override
                    public int size() {
                        return 4;
                    }

                    @Override
                    public WorldGlobalHashes.Source get(int at) {
                        return sources().get(at);
                    }

                    @Override
                    public Object[] toArray() {
                        throw new AssertionError("Provider bulk allocation");
                    }
                };
        assertEquals(RECIPE, recipe(placement(), indexedOnly));
        long[] words = material().words();
        var copied = new WorldGlobalHashes.Material(words);
        words[3] = 99;
        long[] returned = copied.words();
        returned[3] = 100;
        assertEquals(
                RECIPE,
                WorldGlobalHashes.recipe(
                        249,
                        "7".repeat(64),
                        BUILD,
                        placement(),
                        sources(),
                        List.of(copied),
                        List.of(image())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGlobalHashes.Source(
                                1,
                                WorldGlobalHashes.Role.ROOT,
                                0,
                                GlobalInteriorLimits.ROOT_BYTES + 1,
                                null,
                                "a".repeat(32),
                                "b".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldGlobalHashes.Source(
                                1,
                                WorldGlobalHashes.Role.ROOT,
                                0,
                                64,
                                65L,
                                "a".repeat(32),
                                "b".repeat(64)));
    }
}
