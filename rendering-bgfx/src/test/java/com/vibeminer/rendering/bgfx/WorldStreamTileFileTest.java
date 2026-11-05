package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldStreamTileFileTest {
    @TempDir Path directory;
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(12, -34);
    private static final WorldStreamState.Lease LEASE = new WorldStreamState.Lease(9, 8, 7);
    private static final String SCOPE = "ab".repeat(32);

    private static WorldStreamTileFile.Source structural(long id, int limit) {
        return new WorldStreamTileFile.Source(
                id, String.format("%032x", id), 128, 128L, limit, 0, 0, 0, null, 0, 0);
    }

    private static TerrainScene.Tile triangle() {
        return TerrainScene.Tile.copyOf(
                7, 19, new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[] {0, 1, 2}, 1);
    }

    static TerrainScene.Tile full(boolean maximumIndices) {
        return full(7, 19, maximumIndices);
    }

    static TerrainScene.Tile full(int x, int y, boolean maximumIndices) {
        float[] vertices = new float[37120 * 3];
        int at = 0;
        for (int chunk = 0; chunk < 256; chunk++) {
            double ax = -(chunk / 16) * (1600.0 / 3 / 16);
            double ay = -(chunk % 16) * (1600.0 / 3 / 16);
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (ax - row * (1600.0 / 3 / 256));
                    vertices[at++] = (float) (ay - (col * 2 + row % 2) * (1600.0 / 3 / 256));
                    vertices[at++] = 10;
                }
            }
        }
        return TerrainScene.Tile.copyOf(
                x, y, vertices, maximumIndices ? new int[196608] : new int[] {0, 1, 2}, 256);
    }

    static WorldStreamTileFile.Header header(TerrainScene.Tile tile, boolean textured) {
        var diffuse =
                new WorldStreamTileFile.Source(
                        4,
                        "04".repeat(16),
                        128,
                        null,
                        1 << 20,
                        256,
                        6,
                        1,
                        WorldStreamTileFile.Encoding.BC1,
                        0,
                        0);
        return header(tile, textured, diffuse);
    }

    private static WorldStreamTileFile.Header header(
            TerrainScene.Tile tile, boolean textured, WorldStreamTileFile.Source diffuse) {
        return new WorldStreamTileFile.Header(
                NONCE,
                SCOPE,
                42,
                LEASE,
                tile.x(),
                tile.y(),
                tile.chunks(),
                tile.vertexCount(),
                tile.indexCount(),
                TerrainGeometryFingerprint.sha256(tile),
                textured ? "cd".repeat(32) : null,
                textured
                        ? WorldStreamTileFile.Outcome.TEXTURED
                        : WorldStreamTileFile.Outcome.HEIGHT_FALLBACK,
                textured
                        ? WorldStreamTileFile.Reason.NONE
                        : WorldStreamTileFile.Reason.TERMINAL_MATERIAL_UNAVAILABLE,
                textured ? new long[] {-1, -1, -1, -1} : new long[4],
                0,
                0,
                textured ? 1.0 / 32 : 0,
                structural(1, 1 << 20),
                structural(2, 2 << 20),
                textured ? structural(3, 8 << 20) : null,
                textured ? List.of(diffuse) : List.of());
    }

    static ByteBuffer atlas() {
        byte[] bytes = new byte[WorldStreamTileFile.ATLAS_BYTES];
        for (int i = 0; i < bytes.length; i += 4) {
            bytes[i] = 17;
            bytes[i + 1] = 34;
            bytes[i + 2] = 51;
            bytes[i + 3] = (byte) 255;
        }
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    private Path path() {
        return directory.resolve("tile-42.wst");
    }

    private static WorldStreamTileFile.Expected signed(byte[] bytes) throws Exception {
        return new WorldStreamTileFile.Expected(
                NONCE,
                SCOPE,
                42,
                LEASE,
                bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void handmadeBc3DiffuseTupleSurvivesTexturedPacketRoundtrip() throws Exception {
        var tile = full(false);
        var diffuse =
                new WorldStreamTileFile.Source(
                        188808,
                        "04".repeat(16),
                        87408,
                        null,
                        1 << 20,
                        256,
                        6,
                        2,
                        WorldStreamTileFile.Encoding.BC3,
                        8,
                        7);
        var expected =
                WorldStreamTileFile.write(
                        directory, header(tile, true, diffuse), tile, atlas(), () -> false);
        var decoded = WorldStreamTileFile.read(path(), expected, () -> false);
        assertEquals(diffuse, decoded.header().diffuse().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamTileFile.Source(
                                188808,
                                "04".repeat(16),
                                87408,
                                null,
                                1 << 20,
                                256,
                                6,
                                2,
                                WorldStreamTileFile.Encoding.BC3,
                                72,
                                7));
    }

    @Test
    void heightRoundtripKeepsExactGeometryAndIndependentDomainHash() throws Exception {
        var tile = triangle();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        byte[] bytes = Files.readAllBytes(path());
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4096 + 48, bytes.length);
        assertEquals(7, input.getInt(92));
        assertEquals(19, input.getInt(96));
        assertEquals(2, input.getInt(200));
        assertEquals(1, input.getInt(204));
        var hash = MessageDigest.getInstance("SHA-256");
        hash.update(
                "VIMI_TERRAIN_GEOMETRY_V1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        hash.update(
                ByteBuffer.allocate(20)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(7)
                        .putInt(19)
                        .putInt(1)
                        .putInt(3)
                        .putInt(3)
                        .array());
        hash.update(bytes, 4096, 48);
        assertEquals(
                HexFormat.of().formatHex(hash.digest()), HexFormat.of().formatHex(bytes, 128, 160));
        var decoded = WorldStreamTileFile.read(path(), expected, () -> false);
        assertEquals(
                TerrainGeometryFingerprint.sha256(tile),
                TerrainGeometryFingerprint.sha256(decoded.tile()));
        assertNull(decoded.atlas());
        assertThrows(ReadOnlyBufferException.class, () -> decoded.tile().vertices().put(0, 1));
    }

    @Test
    void maximumTexturedPacketHasOneDetachedReadonlyAtlasAndExactBound() throws Exception {
        var tile = full(true);
        var pixels = atlas();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, true), tile, pixels, () -> false);
        assertEquals(2_284_544, Files.size(path()));
        assertEquals(0, pixels.position());
        var decoded = WorldStreamTileFile.read(path(), expected, () -> false);
        assertEquals(37120, decoded.tile().vertexCount());
        assertEquals(196608, decoded.tile().indexCount());
        assertEquals(1_048_576, decoded.atlas().remaining());
        assertThrows(ReadOnlyBufferException.class, () -> decoded.atlas().put(0, (byte) 0));
        Files.delete(path());
        assertEquals(17, decoded.atlas().get(0));
        var coverage = decoded.header().coverage();
        coverage[0] = 0;
        assertEquals(-1, decoded.header().coverage()[0]);
    }

    @Test
    void partialCoverageKeepsOpaqueMaterialIdentityWithoutClaimingCompleteCache() throws Exception {
        var tile = full(false);
        var base = header(tile, true);
        String identity = "ef".repeat(32);
        var partial =
                new WorldStreamTileFile.Header(
                        base.nonce(),
                        base.scope(),
                        base.transaction(),
                        base.lease(),
                        base.x(),
                        base.y(),
                        base.chunks(),
                        base.vertices(),
                        base.indices(),
                        base.geometrySha256(),
                        identity,
                        base.outcome(),
                        base.reason(),
                        new long[] {1, 0, 0, 0},
                        base.maxX(),
                        base.maxY(),
                        base.uvError(),
                        base.wdt(),
                        base.root(),
                        base.tex0(),
                        base.diffuse());
        byte[] rgba = new byte[WorldStreamTileFile.ATLAS_BYTES];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int pixel = (y * 512 + x) * 4;
                rgba[pixel] = 17;
                rgba[pixel + 1] = 34;
                rgba[pixel + 2] = 51;
                rgba[pixel + 3] = (byte) 255;
            }
        }
        var expected =
                WorldStreamTileFile.write(
                        directory,
                        partial,
                        tile,
                        ByteBuffer.wrap(rgba).asReadOnlyBuffer(),
                        () -> false);
        var decoded = WorldStreamTileFile.read(path(), expected, () -> false);
        assertEquals(identity, decoded.header().materialDigest());
        assertEquals(1, Long.bitCount(decoded.header().coverage()[0]));
        assertEquals(0, decoded.header().coverage()[1]);
        assertEquals(255, Byte.toUnsignedInt(decoded.atlas().get(3)));
        assertEquals(0, decoded.atlas().get(32 * 4 + 3));
        // The wire carries no composition-cache completeness or persistence claim.
        var bytes = Files.readAllBytes(path());
        assertEquals(identity, HexFormat.of().formatHex(bytes, 160, 192));
    }

    @Test
    void resigningHostileHeadersNeverBypassesSchemaOrReservedChecks() throws Exception {
        var tile = triangle();
        WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        byte[] clean = Files.readAllBytes(path());
        for (int offset :
                new int[] {
                    0, 8, 12, 16, 24, 40, 72, 80, 88, 100, 104, 112, 116, 120, 124, 160, 192, 196,
                    200, 204, 208, 240, 248, 256, 260, 551, 656, 1024
                }) {
            byte[] bad = clean.clone();
            bad[offset] ^= (byte) 0x80;
            Files.write(path(), bad);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), signed(bad), () -> false),
                    "Header byte " + offset);
        }
    }

    @Test
    void wrongLeaseStopsAtHeaderBeforeAnyGeometryPhase() throws Exception {
        var tile = full(true);
        var expected =
                WorldStreamTileFile.write(
                        directory, header(tile, true), tile, atlas(), () -> false);
        var wrong =
                new WorldStreamTileFile.Expected(
                        NONCE,
                        SCOPE,
                        42,
                        new WorldStreamState.Lease(9, 9, 7),
                        expected.length(),
                        expected.sha256());
        var calls = new AtomicInteger();
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamTileFile.read(
                                path(),
                                wrong,
                                () -> {
                                    assertTrue(
                                            calls.incrementAndGet() <= 2,
                                            "Entered geometry phase for wrong lease");
                                    return false;
                                }));
    }

    @Test
    void digestGeometryAndIndicesAreIndependentlyChecked() throws Exception {
        var tile = triangle();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        byte[] clean = Files.readAllBytes(path());
        var wrongSha =
                new WorldStreamTileFile.Expected(
                        NONCE, SCOPE, 42, LEASE, expected.length(), "00".repeat(32));
        assertThrows(
                IOException.class, () -> WorldStreamTileFile.read(path(), wrongSha, () -> false));
        for (int mode = 0; mode < 4; mode++) {
            byte[] bad = clean.clone();
            if (mode == 0) {
                bad[128] ^= 1;
            } else {
                ByteBuffer.wrap(bad)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(
                                mode == 1 ? 4096 + 36 : 4096,
                                mode == 1 ? 3 : mode == 2 ? 0x7fc00000 : 0x7f7fffff);
            }
            Files.write(path(), bad);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), signed(bad), () -> false));
        }
    }

    @Test
    void atlasPixelsAndSourceUvMetadataAreValidatedBeyondFileDigest() throws Exception {
        var tile = full(false);
        WorldStreamTileFile.write(directory, header(tile, true), tile, atlas(), () -> false);
        byte[] clean = Files.readAllBytes(path());
        for (int offset : new int[] {244, 248, 1024 + 36, 1024 + 39, 4096 + 445440 + 12 + 3}) {
            byte[] bad = clean.clone();
            if (offset == 244) {
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(244, 1);
            } else {
                bad[offset] ^= 1;
            }
            Files.write(path(), bad);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), signed(bad), () -> false),
                    "Atlas/source byte " + offset);
        }
    }

    @Test
    void cancellationRemovesUnadvertisedFileAndRejectsReadWithoutLeakingChannel() throws Exception {
        var tile = triangle();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamTileFile.write(
                                directory,
                                header(tile, false),
                                tile,
                                null,
                                () -> Files.exists(directory.resolve("tile-42.wst.part"))));
        assertFalse(Files.exists(path()));
        assertFalse(Files.exists(directory.resolve("tile-42.wst.part")));
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        var calls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamTileFile.read(
                                path(), expected, () -> calls.incrementAndGet() > 2));
        Files.delete(path());
    }

    @Test
    void exactHandleReadSurvivesPathReplacementAndRejectsLengthChanges() throws Exception {
        var tile = triangle();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        Path replacement = directory.resolve("replacement");
        Files.write(replacement, new byte[expected.length()]);
        var calls = new AtomicInteger();
        var decoded =
                WorldStreamTileFile.read(
                        path(),
                        expected,
                        () -> {
                            if (calls.incrementAndGet() == 3) {
                                try {
                                    Files.move(
                                            replacement,
                                            path(),
                                            StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException failure) {
                                    throw new java.io.UncheckedIOException(failure);
                                }
                            }
                            return false;
                        });
        assertNotNull(decoded);
        Files.delete(path());
        for (int length : new int[] {expected.length() - 1, expected.length() + 1}) {
            Files.write(path(), new byte[length]);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), expected, () -> false));
        }
        Files.delete(path());
        Files.createDirectory(path());
        assertThrows(
                IOException.class, () -> WorldStreamTileFile.read(path(), expected, () -> false));
    }

    @Test
    void exactHandleReadRejectsSymbolicLinks() throws Exception {
        SymbolicLinks.assumeAvailable(directory);
        var tile = triangle();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        assertNotNull(WorldStreamTileFile.read(path(), expected, () -> false));
        // The link targets the valid packet, still readable under its own name, so the link itself
        // is the only reason to reject it.
        Path original =
                Files.createDirectory(directory.resolve("actual")).resolve(path().getFileName());
        Files.move(path(), original);
        assertNotNull(WorldStreamTileFile.read(original, expected, () -> false));
        Files.createSymbolicLink(path(), original);
        assertThrows(
                IOException.class, () -> WorldStreamTileFile.read(path(), expected, () -> false));
    }

    @Test
    void existingPublicationAndCanceledWriterNeverOverwriteAcceptedPacket() throws Exception {
        var tile = triangle();
        var expected =
                WorldStreamTileFile.write(directory, header(tile, false), tile, null, () -> false);
        byte[] clean = Files.readAllBytes(path());
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamTileFile.write(
                                directory, header(tile, false), tile, null, () -> false));
        assertTrue(Arrays.equals(clean, Files.readAllBytes(path())));
        assertFalse(Files.exists(directory.resolve("tile-42.wst.part")));
        assertNotNull(WorldStreamTileFile.read(path(), expected, () -> false));
    }

    @Test
    void pinnedWirePredicatesExactlyMatchExistingDecoderAcrossAllByteCombinations() {
        for (var encoding : WorldStreamTileFile.Encoding.values()) {
            for (int depth = 0; depth < 256; depth++) {
                for (int alpha = 0; alpha < 256; alpha++) {
                    assertEquals(
                            Blp2Decoder.isSupported(
                                    Blp2Types.Encoding.valueOf(encoding.name()), depth, alpha),
                            WorldStreamTileFile.supported(encoding, depth, alpha));
                }
            }
        }
        for (int flags = 0; flags < 256; flags++) {
            assertEquals(Blp2Decoder.isMipFlags(flags), WorldStreamTileFile.mipFlags(flags));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamTileFile.Source(
                                4,
                                "04".repeat(16),
                                128,
                                null,
                                1024,
                                4,
                                0,
                                2,
                                WorldStreamTileFile.Encoding.BC2,
                                4,
                                1));
        assertNotNull(
                new WorldStreamTileFile.Source(
                        4,
                        "04".repeat(16),
                        128,
                        null,
                        1024,
                        4,
                        0,
                        2,
                        WorldStreamTileFile.Encoding.BC2,
                        8,
                        1));
    }
}
