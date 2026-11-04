package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldStreamMaterialDigestTest {
    private static final String PROFILE = "BASIC_DIFFUSE_V1";

    private static WorldStreamTileFile.Header header() {
        return WorldStreamTileFileTest.header(WorldStreamTileFileTest.full(false), true);
    }

    private static WorldStreamTileFile.Header copy(
            WorldStreamTileFile.Header h,
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            String digest,
            long[] coverage,
            List<WorldStreamTileFile.Source> diffuse) {
        return new WorldStreamTileFile.Header(
                nonce,
                h.scope(),
                transaction,
                lease,
                h.x(),
                h.y(),
                h.chunks(),
                h.vertices(),
                h.indices(),
                h.geometrySha256(),
                digest,
                h.outcome(),
                h.reason(),
                coverage,
                h.maxX(),
                h.maxY(),
                h.uvError(),
                h.wdt(),
                h.root(),
                h.tex0(),
                diffuse);
    }

    @Test
    void canonicalIdentityMatchesIndependentPythonSha256Oracle() {
        var structural = new java.util.ArrayList<WorldStreamTileFile.Source>();
        for (int i = 1; i <= 3; i++) {
            structural.add(
                    new WorldStreamTileFile.Source(
                            i,
                            String.format("%032x", i),
                            128,
                            128L,
                            i == 1 ? 1 << 20 : i == 2 ? 2 << 20 : 8 << 20,
                            0,
                            0,
                            0,
                            null,
                            0,
                            0));
        }
        var diffuse =
                new WorldStreamTileFile.Source(
                        4,
                        String.format("%032x", 4),
                        128,
                        null,
                        1 << 20,
                        256,
                        6,
                        1,
                        WorldStreamTileFile.Encoding.BC1,
                        0,
                        0);
        var metadata =
                new WorldStreamTileFile.Header(
                        new WorldStreamState.Nonce(1, 2),
                        "ab".repeat(32),
                        1,
                        new WorldStreamState.Lease(0, 1, 1),
                        7,
                        19,
                        256,
                        37120,
                        3,
                        "12".repeat(32),
                        "cd".repeat(32),
                        WorldStreamTileFile.Outcome.TEXTURED,
                        WorldStreamTileFile.Reason.NONE,
                        new long[] {1, 2, 3, 4},
                        1.25f,
                        -4.5f,
                        .01,
                        structural.get(0),
                        structural.get(1),
                        structural.get(2),
                        List.of(diffuse));
        byte[] rgba = new byte[WorldStreamTileFile.ATLAS_BYTES];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = (byte) (i % 251);
        }
        assertEquals(
                "ea5fe445e40cb9425196da06d06c865decbfe54f601ca916a994ed2cde5c841a",
                WorldStreamMaterialDigest.compute(
                        metadata, ByteBuffer.wrap(rgba).asReadOnlyBuffer(), PROFILE, () -> false));
    }

    @Test
    void materialIdentityIsStableAcrossTransportLifetimesAndTemplateDigests() {
        var original = header();
        var other =
                copy(
                        original,
                        new WorldStreamState.Nonce(100, 200),
                        99,
                        new WorldStreamState.Lease(0, 12, 19),
                        "ef".repeat(32),
                        original.coverage(),
                        original.diffuse());
        var pixels = WorldStreamTileFileTest.atlas();
        String first = WorldStreamMaterialDigest.compute(original, pixels, PROFILE, () -> false);
        assertEquals(first, WorldStreamMaterialDigest.compute(other, pixels, PROFILE, () -> false));
        assertEquals(0, pixels.position());
        assertEquals(WorldStreamTileFile.ATLAS_BYTES, pixels.remaining());
        assertEquals(64, first.length());
        assertNotEquals(original.materialDigest(), first);
    }

    @Test
    void coverageProfileAndActualPixelChangesCannotAliasTheSameMaterial() {
        var original = header();
        var pixels = WorldStreamTileFileTest.atlas();
        String first = WorldStreamMaterialDigest.compute(original, pixels, PROFILE, () -> false);
        var partial =
                copy(
                        original,
                        original.nonce(),
                        original.transaction(),
                        original.lease(),
                        original.materialDigest(),
                        new long[] {1, 0, 0, 0},
                        original.diffuse());
        assertNotEquals(
                first, WorldStreamMaterialDigest.compute(partial, pixels, PROFILE, () -> false));
        assertNotEquals(
                first,
                WorldStreamMaterialDigest.compute(
                        original, pixels, "BASIC_DIFFUSE_V2", () -> false));
        var changed = ByteBuffer.allocate(WorldStreamTileFile.ATLAS_BYTES);
        changed.put(pixels.duplicate()).flip();
        changed.put(1234, (byte) (changed.get(1234) ^ 1));
        assertNotEquals(
                first,
                WorldStreamMaterialDigest.compute(
                        original, changed.asReadOnlyBuffer(), PROFILE, () -> false));
    }

    @Test
    void qualifiedSourceOperandsAndTheirRecordedOrderArePartOfIdentity() {
        var original = header();
        var source = original.diffuse().getFirst();
        var changed =
                new WorldStreamTileFile.Source(
                        5,
                        "05".repeat(16),
                        source.consumedLength(),
                        source.declaredLength(),
                        source.sourceLimit(),
                        source.originalSide(),
                        source.mipLevel(),
                        source.mipFlags(),
                        source.encoding(),
                        source.alphaDepth(),
                        source.alphaEncoding());
        var a =
                copy(
                        original,
                        original.nonce(),
                        original.transaction(),
                        original.lease(),
                        original.materialDigest(),
                        original.coverage(),
                        List.of(source, changed));
        var b =
                copy(
                        original,
                        original.nonce(),
                        original.transaction(),
                        original.lease(),
                        original.materialDigest(),
                        original.coverage(),
                        List.of(changed, source));
        var pixels = WorldStreamTileFileTest.atlas();
        String first = WorldStreamMaterialDigest.compute(original, pixels, PROFILE, () -> false);
        String pair = WorldStreamMaterialDigest.compute(a, pixels, PROFILE, () -> false);
        assertNotEquals(first, pair);
        assertNotEquals(pair, WorldStreamMaterialDigest.compute(b, pixels, PROFILE, () -> false));
    }

    @Test
    void cancellationDuringPixelHashPreservesCallerBufferAndProducesNoIdentity() {
        var original = header();
        var pixels = WorldStreamTileFileTest.atlas();
        var checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamMaterialDigest.compute(
                                original, pixels, PROFILE, () -> checks.incrementAndGet() > 12));
        assertEquals(0, pixels.position());
        assertEquals(13, checks.get());
    }

    @Test
    void shapeAndProfileChecksRejectBeforeReadingPixels() {
        var original = header();
        var pixels = WorldStreamTileFileTest.atlas();
        for (String bad : List.of("", "x".repeat(129), "bad profile", "bad\nprofile", "é")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldStreamMaterialDigest.compute(original, pixels, bad, () -> false));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamMaterialDigest.compute(
                                original,
                                ByteBuffer.allocate(WorldStreamTileFile.ATLAS_BYTES),
                                PROFILE,
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamMaterialDigest.compute(
                                original,
                                ByteBuffer.allocate(4).asReadOnlyBuffer(),
                                PROFILE,
                                () -> false));
        var height = WorldStreamTileFileTest.header(WorldStreamTileFileTest.full(false), false);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamMaterialDigest.compute(height, pixels, PROFILE, () -> false));
    }
}
