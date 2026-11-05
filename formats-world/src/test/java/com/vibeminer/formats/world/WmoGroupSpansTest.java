package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WmoGroupSpansTest {
    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] chunk(String tag, byte[] bytes) {
        int value = 0;
        for (char c : tag.toCharArray()) {
            value = value << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(value).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] join(byte[]... bytes) {
        var out = new ByteArrayOutputStream();
        for (var body : bytes) {
            out.writeBytes(body);
        }
        return out.toByteArray();
    }

    private static byte[] floats(float... values) {
        var result = buffer(values.length * 4);
        for (float value : values) {
            result.putFloat(value);
        }
        return result.array();
    }

    private static byte[] header(int batches) {
        return buffer(68)
                .putInt(-1)
                .putInt(-2)
                .putInt(0x2a05)
                .put(floats(-9, -8, -7, 9, 8, 7))
                .putShort((short) 2)
                .putShort((short) 3)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) batches)
                .putShort((short) 0)
                .putInt(0)
                .putInt(15)
                .putInt(77)
                .putInt(0)
                .putInt(0)
                .array();
    }

    private static byte[] batch(int first, int count, int last) {
        return buffer(24)
                .put(new byte[12])
                .putInt(first)
                .putShort((short) count)
                .putShort((short) 0)
                .putShort((short) last)
                .put((byte) 2)
                .put((byte) 255)
                .array();
    }

    private static byte[][] mesh() {
        return new byte[][] {
            chunk("MOVT", floats(-3, 4, -5, 6, -7, 2, 1, 2, 3)),
            chunk("MONR", floats(1, 0, 0, 0, -1, 0, 0, 0, 1)),
            chunk("MOTV", floats(.25f, -.5f, 2, 3, 4, 5)),
            chunk(
                    "MOVI",
                    buffer(6).putShort((short) 0).putShort((short) 1).putShort((short) 2).array()),
            chunk("MOPY", new byte[] {1, (byte) 255}),
            chunk("MOBA", batch(0, 3, 2))
        };
    }

    private static byte[] group(byte[][] mesh, byte[]... extra) {
        return join(
                chunk("MVER", buffer(4).putInt(17).array()),
                chunk("MOGP", join(header(1), join(mesh), join(extra))));
    }

    /** A group whose MOGP header declares the given flags instead of the default 0x2a05. */
    private static byte[] flagged(int flags, byte[][] mesh, byte[]... extra) {
        var h = header(1);
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(8, flags);
        return join(
                chunk("MVER", buffer(4).putInt(17).array()),
                chunk("MOGP", join(h, join(mesh), join(extra))));
    }

    private static final int COLORS2 = 0x1000000, UV2 = 0x2000000, UV3 = 0x40000000;

    @Test
    void declaredExtraUvAndColorSetsAreAdmittedAndTheFirstSetIsExposed() {
        byte[] uv2 = chunk("MOTV", floats(7, 7, 8, 8, 9, 9));
        byte[] colors = chunk("MOCV", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        byte[] colors2 = chunk("MOCV", new byte[12]);
        WmoGroupSpans.withGroup(
                flagged(0x2a05 | UV2 | UV3 | COLORS2, mesh(), colors, uv2, colors2, uv2),
                () -> false,
                b -> {
                    assertEquals(.25f, b.uv().get(0));
                    assertEquals(6, b.uv().size());
                    assertEquals(1, b.colors().get(0));
                    assertEquals(2, b.triangleBytes());
                });
        // Extra sets beyond what MOGP declares stay duplicates.
        reject(flagged(0x2a05 | UV2, mesh(), uv2, uv2));
        reject(flagged(0x2a05 | UV3, mesh(), uv2, uv2));
        reject(flagged(0x2a05 | COLORS2, mesh(), colors, colors2, colors2));
        reject(flagged(0x2a05 | UV2, mesh(), colors, colors2));
        // A declared extra set must still match the vertex count and hold finite values.
        reject(flagged(0x2a05 | UV2, mesh(), chunk("MOTV", floats(1, 1, 2, 2))));
        reject(flagged(0x2a05 | UV2, mesh(), chunk("MOTV", floats(1, 1, 2, 2, 3, Float.NaN))));
        reject(flagged(0x2a05 | COLORS2, mesh(), colors, chunk("MOCV", new byte[8])));
    }

    @Test
    void mpy2TriangleRecordsReplaceMopyButNeverJoinIt() {
        var m = mesh();
        m[4] = chunk("MPY2", new byte[] {1, 0, (byte) 200, 1});
        WmoGroupSpans.withGroup(
                group(m),
                () -> false,
                b -> {
                    assertEquals(4, b.triangleBytes());
                    assertEquals(4, b.triangles().size());
                    assertEquals(200, b.triangles().get(2));
                });
        reject(group(m, chunk("MOPY", new byte[2])));
        reject(group(mesh(), chunk("MPY2", new byte[4])));
        var short2 = mesh();
        short2[4] = chunk("MPY2", new byte[2]);
        reject(group(short2));
        var twoTriangles = mesh();
        twoTriangles[4] = chunk("MPY2", new byte[8]);
        reject(group(twoTriangles));
    }

    @Test
    void rawAsymmetricOperandsMatchOwnedFactsWithoutAxisOrAppearancePolicy() {
        byte[] raw =
                group(
                        mesh(),
                        chunk("MOCV", new byte[12]),
                        chunk("MLIQ", new byte[] {9, 8}),
                        chunk("ABCD", new byte[] {7}));
        var existing = WmoFormats.group(raw, () -> false);
        WmoGroupSpans.withGroup(
                raw,
                () -> false,
                borrow -> {
                    assertEquals(existing.header(), borrow.header());
                    assertEquals(existing.batches(), borrow.batches());
                    assertEquals(existing.positions().remaining(), borrow.positions().size());
                    for (int i = 0; i < borrow.positions().size(); i++) {
                        assertEquals(existing.positions().get(i), borrow.positions().get(i));
                        assertEquals(existing.normals().get(i), borrow.normals().get(i));
                    }
                    for (int i = 0; i < borrow.uv().size(); i++) {
                        assertEquals(existing.uv(0).get(i), borrow.uv().get(i));
                    }
                    for (int i = 0; i < borrow.indices().size(); i++) {
                        assertEquals(existing.indices().get(i), borrow.indices().get(i));
                    }
                    assertEquals(255, borrow.triangles().get(1));
                    assertEquals(12, borrow.colors().size());
                    assertEquals(9, borrow.liquid().get(0));
                    assertTrue(
                            borrow.chunks().stream()
                                    .anyMatch(c -> c.tag() == 0x41424344 && !c.payloadParsed()));
                    assertTrue(borrow.metadataBytes() <= WmoGroupSpans.MAX_METADATA_BYTES);
                });
    }

    @Test
    void escapedSpansExpireAndClearRawAndCancellationReferencesEvenOnCallbackFailure()
            throws Exception {
        var saved = new ArrayList<Object>();
        var raw = group(mesh(), chunk("MOCV", new byte[12]), chunk("MLIQ", new byte[] {1}));
        var expected = new IllegalStateException("Authored callback");
        var failed =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                WmoGroupSpans.withGroup(
                                        raw,
                                        () -> false,
                                        b -> {
                                            saved.addAll(
                                                    List.of(
                                                            b,
                                                            b.positions(),
                                                            b.normals(),
                                                            b.uv(),
                                                            b.indices(),
                                                            b.triangles(),
                                                            b.colors(),
                                                            b.liquid()));
                                            throw expected;
                                        }));
        assertTrue(failed == expected);
        var borrow = (WmoGroupSpans.Borrow) saved.getFirst();
        assertThrows(IllegalStateException.class, borrow::header);
        assertThrows(IllegalStateException.class, borrow::positions);
        for (var span : saved.subList(1, saved.size())) {
            var type = span.getClass();
            var get = type.getMethod("get", int.class);
            var error =
                    assertThrows(
                            java.lang.reflect.InvocationTargetException.class,
                            () -> get.invoke(span, 0));
            assertTrue(error.getCause() instanceof IllegalStateException);
            for (var field : type.getSuperclass().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    field.setAccessible(true);
                    assertEquals(
                            null, field.get(span), "Expired span clears raw/cancellation owner");
                }
            }
        }
    }

    @Test
    void foreignThreadAndCancellationCannotReadLiveSpans() throws Exception {
        var canceled = new AtomicBoolean();
        WmoGroupSpans.withGroup(
                group(mesh()),
                canceled::get,
                b -> {
                    var span = b.positions();
                    var failure =
                            CompletableFuture.supplyAsync(
                                    () ->
                                            assertThrows(
                                                    IllegalStateException.class,
                                                    () -> span.get(0)));
                    try {
                        failure.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    canceled.set(true);
                    assertThrows(CancellationException.class, () -> span.get(0));
                    assertThrows(CancellationException.class, b::header);
                });
    }

    @Test
    void countAndByteLimitsRejectBeforeCallback() {
        for (int stream = 0; stream < 6; stream++) {
            var m = mesh();
            m[stream] =
                    switch (stream) {
                        case 0 -> chunk("MOVT", new byte[(WmoGroupSpans.MAX_VERTICES + 1) * 12]);
                        case 1 -> chunk("MONR", new byte[4]);
                        case 2 -> chunk("MOTV", new byte[4]);
                        case 3 -> chunk("MOVI", new byte[(WmoGroupSpans.MAX_TRIANGLES + 1) * 6]);
                        case 4 -> chunk("MOPY", new byte[3]);
                        default -> chunk("MOBA", new byte[(WmoGroupSpans.MAX_BATCHES + 1) * 24]);
                    };
            reject(group(m));
        }
        reject(new byte[WmoGroupSpans.MAX_BYTES + 1]);
        var extras = new byte[WmoGroupSpans.MAX_CHUNKS][];
        Arrays.setAll(extras, i -> chunk("ABCD", new byte[0]));
        reject(group(mesh(), extras));
    }

    @Test
    void realInteriorGroupSizesAboveTheOldSixteenThousandAndOneMebibyteCapsParse() {
        // Shaped like Blackrock Depths group 14: 19,919 vertices and 14,632 triangles in a file
        // over 1 MiB, and the unused Monastery group 42: 22,194 triangles.
        for (int[] shape : new int[][] {{19919, 14632}, {17302, 22194}, {28721, 19288}}) {
            int vertices = shape[0], triangles = shape[1];
            var indices = buffer(triangles * 6);
            for (int t = 0; t < triangles; t++) {
                indices.putShort((short) 0).putShort((short) 1).putShort((short) (vertices - 1));
            }
            var parts = new ArrayList<byte[]>();
            parts.add(header(1));
            parts.add(chunk("MOVT", new byte[vertices * 12]));
            parts.add(chunk("MONR", new byte[vertices * 12]));
            parts.add(chunk("MOTV", new byte[vertices * 8]));
            parts.add(chunk("MOVI", indices.array()));
            parts.add(chunk("MOPY", new byte[triangles * 2]));
            parts.add(chunk("MOBA", batch(0, Math.min(triangles, 21845) * 3, vertices - 1)));
            parts.add(chunk("MOCV", new byte[vertices * 4]));
            parts.add(chunk("ZZZZ", new byte[1 << 20])); // unparsed bulk such as MOBR or MLIQ
            byte[] raw =
                    join(
                            chunk("MVER", buffer(4).putInt(17).array()),
                            chunk("MOGP", join(parts.toArray(byte[][]::new))));
            assertTrue(raw.length > 1 << 20, "over the old 1 MiB cap");
            WmoGroupSpans.withGroup(
                    raw,
                    () -> false,
                    b -> {
                        assertEquals(vertices * 3, b.positions().size());
                        assertEquals(triangles * 3, b.indices().size());
                    });
        }
        // The format's own bound: 16-bit indices address at most 65,536 vertices. Every vertex
        // stream at N, one triangle using index N - 1.
        for (int n : new int[] {65536, 65537}) {
            int last = Math.min(n, 65536) - 1;
            var full =
                    new byte[][] {
                        chunk("MOVT", new byte[n * 12]),
                        chunk("MONR", new byte[n * 12]),
                        chunk("MOTV", new byte[n * 8]),
                        chunk(
                                "MOVI",
                                buffer(6)
                                        .putShort((short) 0)
                                        .putShort((short) 1)
                                        .putShort((short) last)
                                        .array()),
                        chunk("MOPY", new byte[2]),
                        chunk("MOBA", batch(0, 3, last)),
                        chunk("MOCV", new byte[n * 4])
                    };
            if (n == 65536) {
                WmoGroupSpans.withGroup(
                        group(full),
                        () -> false,
                        b -> assertEquals(65536 * 3, b.positions().size()));
            } else {
                var refused =
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> WmoGroupSpans.withGroup(group(full), () -> false, b -> {}));
                assertEquals("Group stream count", refused.getMessage());
            }
        }
    }

    @Test
    void maximumShapeKeepsOneRawAliasAndBoundedMetadataWithoutPayloadArrays() {
        var batches = new byte[WmoGroupSpans.MAX_BATCHES][];
        Arrays.setAll(batches, i -> batch(0, 3, 0));
        var parts = new ArrayList<byte[]>();
        parts.add(header(WmoGroupSpans.MAX_BATCHES));
        parts.add(chunk("MOVT", new byte[WmoGroupSpans.MAX_VERTICES * 12]));
        parts.add(chunk("MONR", new byte[WmoGroupSpans.MAX_VERTICES * 12]));
        parts.add(chunk("MOTV", new byte[WmoGroupSpans.MAX_VERTICES * 8]));
        parts.add(chunk("MOVI", new byte[WmoGroupSpans.MAX_TRIANGLES * 6]));
        parts.add(chunk("MOPY", new byte[WmoGroupSpans.MAX_TRIANGLES * 2]));
        parts.add(chunk("MOBA", join(batches)));
        parts.add(chunk("MOCV", new byte[WmoGroupSpans.MAX_VERTICES * 4]));
        parts.add(chunk("MLIQ", new byte[0]));
        for (int i = 10; i < WmoGroupSpans.MAX_CHUNKS; i++) {
            parts.add(chunk("ABCD", new byte[0]));
        }
        byte[] raw =
                join(
                        chunk("MVER", buffer(4).putInt(17).array()),
                        chunk("MOGP", join(parts.toArray(byte[][]::new))));
        WmoGroupSpans.withGroup(
                raw,
                () -> false,
                b -> {
                    assertEquals(WmoGroupSpans.MAX_CHUNKS, b.chunks().size());
                    assertEquals(WmoGroupSpans.MAX_VERTICES * 3, b.positions().size());
                    assertEquals(WmoGroupSpans.MAX_TRIANGLES * 3, b.indices().size());
                    assertEquals(WmoGroupSpans.MAX_BATCHES, b.batches().size());
                    assertTrue(b.metadataBytes() <= WmoGroupSpans.MAX_METADATA_BYTES);
                    for (Object span :
                            List.of(
                                    b.positions(),
                                    b.normals(),
                                    b.uv(),
                                    b.indices(),
                                    b.triangles(),
                                    b.colors(),
                                    b.liquid())) {
                        for (var field : span.getClass().getSuperclass().getDeclaredFields()) {
                            if (field.getType().isArray()) {
                                field.setAccessible(true);
                                try {
                                    assertTrue(
                                            field.get(span) == raw,
                                            "Every payload alias is original raw");
                                } catch (IllegalAccessException failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                        }
                    }
                });
    }

    @Test
    void requiredStreamsSingletonsTruncationAndVersionAreStrict() {
        for (int i = 0; i < 6; i++) {
            var streams = new ArrayList<>(List.of(mesh()));
            byte[] duplicate = streams.remove(i);
            reject(group(streams.toArray(byte[][]::new)));
            reject(group(mesh(), duplicate));
        }
        reject(group(mesh(), chunk("MOCV", new byte[8])));
        reject(group(mesh(), chunk("MOCV", new byte[12]), chunk("MOCV", new byte[12])));
        reject(group(mesh(), chunk("MLIQ", new byte[0]), chunk("MLIQ", new byte[0])));
        byte[] valid = group(mesh());
        reject(Arrays.copyOf(valid, valid.length - 1));
        valid[8] = 18;
        reject(valid);
        byte[] badSpan = group(mesh());
        ByteBuffer.wrap(badSpan).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Integer.MAX_VALUE);
        reject(badSpan);
    }

    @Test
    void invalidOperandsIndicesAndBatchRangesReject() {
        for (int stream : new int[] {0, 1, 2}) {
            var m = mesh();
            ByteBuffer.wrap(m[stream]).order(ByteOrder.LITTLE_ENDIAN).putFloat(8, Float.NaN);
            reject(group(m));
        }
        var indices = mesh();
        ByteBuffer.wrap(indices[3]).order(ByteOrder.LITTLE_ENDIAN).putShort(8, (short) 65535);
        reject(group(indices));
        for (byte[] invalid : List.of(batch(1, 3, 2), batch(0, 6, 2), batch(0, 3, 1))) {
            var m = mesh();
            m[5] = chunk("MOBA", invalid);
            reject(group(m));
        }
    }

    @Test
    void cancellationDuringScanAndValidationNeverEntersCallback() {
        for (int threshold : new int[] {0, 5, 20, 40}) {
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var called = new AtomicBoolean();
            assertThrows(
                    CancellationException.class,
                    () ->
                            WmoGroupSpans.withGroup(
                                    group(mesh()),
                                    () -> checks.incrementAndGet() > threshold,
                                    b -> called.set(true)));
            assertFalse(called.get());
        }
    }

    @Test
    void guardedApiExposesNoBuffersArraysOrBackReferenceToParser() {
        for (Class<?> type :
                List.of(
                        WmoGroupSpans.Borrow.class,
                        WmoGroupSpans.Floats.class,
                        WmoGroupSpans.Indices.class,
                        WmoGroupSpans.Bytes.class)) {
            for (var method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                assertFalse(method.getReturnType().isArray());
                assertFalse(java.nio.Buffer.class.isAssignableFrom(method.getReturnType()));
            }
        }
    }

    private static void reject(byte[] raw) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WmoGroupSpans.withGroup(
                                raw,
                                () -> false,
                                b -> {
                                    throw new AssertionError("Rejected raw input reached callback");
                                }));
    }
}
