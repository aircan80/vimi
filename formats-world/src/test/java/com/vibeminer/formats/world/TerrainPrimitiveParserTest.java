package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixed authored inputs; goldens are captured from the pre-refactor parser before modification. */
final class TerrainPrimitiveParserTest {
    private record Case(
            String name, int chunks, int holes, int indices, String geometry, String areas) {}

    private static final List<Case> CASES =
            List.of(
                    new Case(
                            "full",
                            256,
                            0,
                            196608,
                            "09c95231fe38b8f079d88dfba0e1971856ee0ce48a527c478e9b83ba0d734600",
                            "8a2084c16a0a0e2447f9b63c342d2e447337f1de760e28286c232dbd283fc74e"),
                    new Case(
                            "partial-mixed",
                            17,
                            1,
                            11808,
                            "49c5115a8b44e94238f68553204d12c94403402eeb28fdd05e8cd1a41170e3ce",
                            "c0e05c8e08daa7b00afe3e22f583309a8208f14c20023213008be2fd0a75fc08"),
                    new Case(
                            "high-holes",
                            256,
                            2,
                            172032,
                            "b72f49557dd1bf3427285534239681efbf40b147faa8e99996c1510da198b3b3",
                            "2991ebb8ad8337fdc9b00009561206970e6c3c0c57b16fc038e43de20bc812e3"),
                    new Case(
                            "low-holes",
                            256,
                            3,
                            184320,
                            "2dfba6b15c6e44c5ad5ba739cfc357c427104ce1bdae1953f19c6979895f2b48",
                            "75ee3a2bf553809ebb5966f1cc294ca3be16ea81a0461e39b005dddc60cfa022"),
                    new Case(
                            "fully-holed",
                            256,
                            4,
                            0,
                            "fad71aff698b71eee130675144f763e19dd792e6a7e5e509a08fc424250a47c6",
                            "584b1888094bcc6bf2bb4213dfe9a5f9e003746071f495f1f037aec1b769f2d0"));
    private static volatile WorldFormats.Terrain retained;

    @Test
    void authoredGeometryAndAreaEvidenceHaveFixedOldParserFingerprints() {
        for (var test : CASES) {
            var result = WorldFormats.adtTile(authored(test.chunks(), test.holes()), () -> false);
            assertEquals(test.chunks() * 435, result.terrain().vertices().length);
            assertEquals(test.indices(), result.terrain().triangles().length);
            assertEquals(
                    test.geometry(),
                    geometry(result.terrain()),
                    test.name() + " old native geometry fingerprint");
            assertEquals(
                    test.areas(), areas(result.areas()), test.name() + " old source area evidence");
        }
    }

    @Test
    void authoredParserAllocationDiagnostic() {
        var platform = ManagementFactory.getThreadMXBean();
        if (!(platform instanceof com.sun.management.ThreadMXBean meter)
                || !meter.isThreadAllocatedMemorySupported()) {
            System.out.println("TERRAIN_ALLOCATION unsupported");
            return;
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        byte[] full = authored(256, 0), partial = authored(1, 0);
        byte[] bad =
                ByteBuffer.allocate(12)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x4d564552)
                        .putInt(4)
                        .putInt(17)
                        .array();
        for (int i = 0; i < 32; i++) {
            retained = WorldFormats.adtTile(full, () -> false).terrain();
        }
        long thread = Thread.currentThread().threadId();
        for (var test : List.of(full, partial, bad)) {
            long[] bytes = new long[12], elapsed = new long[12];
            for (int i = 0; i < bytes.length; i++) {
                long before = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
                try {
                    retained = WorldFormats.adtTile(test, () -> false).terrain();
                } catch (IllegalArgumentException invalid) {
                    assertTrue(test == bad);
                }
                elapsed[i] = System.nanoTime() - start;
                bytes[i] = meter.getThreadAllocatedBytes(thread) - before;
            }
            java.util.Arrays.sort(bytes);
            java.util.Arrays.sort(elapsed);
            if (test != full) {
                assertTrue(
                        bytes[11] < 64 << 10,
                        "Small/bad source cannot reserve full-tile primitive payloads");
            }
            System.out.println(
                    "TERRAIN_ALLOCATION case="
                            + (test == full ? "full" : test == partial ? "single" : "bad")
                            + " runs=12 median_bytes="
                            + bytes[6]
                            + " max_bytes="
                            + bytes[11]
                            + " median_us="
                            + elapsed[6] / 1000
                            + " max_us="
                            + elapsed[11] / 1000);
        }
        retained = null;
    }

    @Test
    void rawFloatBitsFanOrderAndMidSourceCancellationRemainExact() {
        var terrain = WorldFormats.adt(authored(1, 0), () -> false);
        float[] vertices = terrain.vertices();
        assertEquals(0x80000000, Float.floatToRawIntBits(vertices[0]));
        assertEquals(0x80000000, Float.floatToRawIntBits(vertices[2]));
        assertEquals(1, Float.floatToRawIntBits(vertices[8]));
        assertEquals(0x80000001, Float.floatToRawIntBits(vertices[11]));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {0, 9, 1, 1, 9, 18, 18, 9, 17, 17, 9, 0},
                java.util.Arrays.copyOf(terrain.triangles(), 12));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> WorldFormats.adt(authored(256, 0), () -> calls.incrementAndGet() > 10));
        assertEquals(11, calls.get());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WorldFormats.adt(authored(257, 0), () -> false));
    }

    static byte[] authored(int chunks, int mode) {
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < chunks; i++) {
            boolean high = mode == 2 || mode == 4 || mode == 1 && i % 2 == 0;
            int low = mode == 3 || mode == 1 && !high ? 1 << (i % 16) : 0;
            long holes = mode == 4 ? -1L : high ? Long.rotateLeft(0x0102040810204080L, i % 64) : 0;
            byte[] chunk = WorldFormatsTest.adt(high ? 0x10000 : 0, low, holes);
            var data = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
            int ordinal = i * 73 % 256;
            data.putInt(24, ordinal % 16).putInt(28, ordinal / 16).putInt(72, 0xf0000000 + ordinal);
            data.putFloat(124, i % 2 == 0 ? -0.0f : 999_000f);
            data.putFloat(128, -900_000f + i % 7 * 10.25f)
                    .putFloat(132, i % 3 == 0 ? -0.0f : -23.875f);
            float[] heights = {
                -0.0f,
                0.0f,
                Float.intBitsToFloat(1),
                Float.intBitsToFloat(0x80000001),
                .125f,
                -7.5f,
                1_000f
            };
            for (int sample = 0; sample < 145; sample++) {
                data.putFloat(156 + sample * 4, heights[(sample + i) % heights.length]);
            }
            out.writeBytes(i == 0 ? chunk : java.util.Arrays.copyOfRange(chunk, 12, chunk.length));
        }
        return out.toByteArray();
    }

    private static String geometry(WorldFormats.Terrain terrain) {
        var digest = digest();
        digest.update("VIMI_TERRAIN_GEOMETRY_V1\0".getBytes(StandardCharsets.US_ASCII));
        float[] vertices = terrain.vertices();
        int[] indices = terrain.triangles();
        for (int value :
                new int[] {13, 21, terrain.chunks(), vertices.length / 3, indices.length}) {
            integer(digest, value);
        }
        for (float value : vertices) {
            integer(digest, Float.floatToRawIntBits(value));
        }
        for (int index : indices) {
            integer(digest, index);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String areas(WorldFormats.AreaMetadata areas) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                for (var area : areas.chunks()) {
                    out.writeInt(area.x());
                    out.writeInt(area.y());
                    out.writeLong(area.areaId());
                    out.writeInt(Float.floatToRawIntBits(area.originX()));
                    out.writeInt(Float.floatToRawIntBits(area.originY()));
                    out.writeInt(Float.floatToRawIntBits(area.originZ()));
                    out.writeBoolean(area.entryReference() != null);
                    if (area.entryReference() != null) {
                        out.writeInt(Float.floatToRawIntBits(area.entryReference().x()));
                        out.writeInt(Float.floatToRawIntBits(area.entryReference().y()));
                        out.writeInt(Float.floatToRawIntBits(area.entryReference().z()));
                    }
                }
            }
            return HexFormat.of().formatHex(digest().digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update((byte) value);
        digest.update((byte) (value >>> 8));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 24));
    }
}
