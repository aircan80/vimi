package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
class WorldStreamTileWriterTest {
    @TempDir Path directory;
    private static final String SCOPE = "ab".repeat(32);

    /** Structural fixture only: no source capability or game record is manufactured. */
    private WorldStreamTransport.PacketDestination destination(Path parent) throws Exception {
        Files.createDirectories(parent);
        var descriptor =
                new WorldStreamEnvelope.Session(new WorldStreamState.Nonce(12, -34), SCOPE, 1);
        var state = new WorldStreamParentState(descriptor);
        state.status(new WorldStreamStatus.Session(descriptor.nonce()));
        state.status(
                new WorldStreamStatus.Credit(
                        descriptor.nonce(), new WorldStreamState.Lease(0, 1, 1)));
        var ticket = state.reserve(7, 19);
        var cancellation = new WorldStreamProducerCancellation().begin();
        var ctor =
                WorldStreamTransport.PacketDestination.class.getDeclaredConstructor(
                        WorldStreamEnvelope.Session.class,
                        WorldStreamParentState.Ticket.class,
                        Path.class,
                        WorldStreamProducerCancellation.Producer.class);
        ctor.setAccessible(true);
        return ctor.newInstance(descriptor, ticket, parent.resolve("tile-1.wst"), cancellation);
    }

    private static WorldStreamTileWriter.Source structural(long id, int limit) {
        return new WorldStreamTileWriter.Source(
                id, String.format("%032x", id), 128, 128L, limit, 0, 0, 0, null, 0, 0);
    }

    private static WorldStreamTileWriter.Source diffuse() {
        return new WorldStreamTileWriter.Source(
                4,
                "04".repeat(16),
                128,
                null,
                1 << 20,
                256,
                6,
                1,
                WorldStreamTileWriter.Encoding.BC1,
                0,
                0);
    }

    private static TerrainScene.Tile triangle() {
        return TerrainScene.Tile.copyOf(
                7, 19, new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[] {0, 1, 2}, 1);
    }

    private static WorldStreamTileWriter.Data data(
            TerrainScene.Tile tile, String scope, TerrainTextureBinding texture, String profile) {
        var m = texture == null ? null : texture.metadata();
        long[] coverage = new long[4];
        if (m != null) {
            for (int i = 0; i < 4; i++) {
                coverage[i] = m.rectangleCoverage().get(i);
            }
        }
        return new WorldStreamTileWriter.Data(
                scope,
                profile,
                TerrainGeometryFingerprint.sha256(tile),
                texture == null
                        ? WorldStreamTileWriter.Outcome.HEIGHT_FALLBACK
                        : WorldStreamTileWriter.Outcome.TEXTURED,
                texture == null
                        ? WorldStreamTileWriter.Reason.TERMINAL_MATERIAL_UNAVAILABLE
                        : WorldStreamTileWriter.Reason.NONE,
                coverage,
                m == null ? 0 : m.nativeMaxX(),
                m == null ? 0 : m.nativeMaxY(),
                m == null ? 0 : m.maxSourceUvErrorTexels(),
                structural(1, 1 << 20),
                structural(2, 2 << 20),
                texture == null ? null : structural(3, 8 << 20),
                texture == null ? List.of() : List.of(diffuse()));
    }

    private static WorldStreamTileFile.Payload read(WorldStreamTransport.PacketDestination target)
            throws Exception {
        var c = target.context();
        byte[] bytes = Files.readAllBytes(target.path());
        var expected =
                new WorldStreamTileFile.Expected(
                        c.nonce(),
                        c.scope(),
                        c.transaction(),
                        c.lease(),
                        bytes.length,
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return WorldStreamTileFile.read(target.path(), expected, () -> false);
    }

    @Test
    void heightPacketMovesOnceAndSourceShellDropsAllDestinationAndHeaderReferences()
            throws Exception {
        var target = destination(directory.resolve("height"));
        var tile = triangle();
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        var decoded = read(target);
        assertEquals(WorldStreamTileFile.Outcome.HEIGHT_FALLBACK, decoded.header().outcome());
        assertEquals(TerrainGeometryFingerprint.sha256(tile), decoded.header().geometrySha256());
        decoded.close();
        var moved = output.move();
        output.close();
        assertTrue(Files.exists(target.path()));
        for (String name : List.of("destination", "writtenHeader")) {
            var field = output.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(
                    null, field.get(output), "Inert source R retains no transport callback graph");
        }
        assertThrows(IllegalStateException.class, output::move);
        moved.close();
        assertFalse(Files.exists(target.path()));
    }

    @Test
    void partialAtlasHasIndependentActualPayloadIdentityWithoutACompositionCacheKey()
            throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        long[] coverage = {1, 0, 0, 0};
        byte[] rgba = new byte[TerrainTextureBinding.PIXEL_BYTES];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int at = (y * 512 + x) * 4;
                rgba[at] = 17;
                rgba[at + 1] = 34;
                rgba[at + 2] = 51;
                rgba[at + 3] = (byte) 255;
            }
        }
        var metadata =
                TerrainTextureBinding.fromSnapshot(
                        0,
                        tile,
                        0,
                        0,
                        coverage,
                        new TerrainTextureBinding.Sources(
                                2, String.format("%032x", 2), 3, String.format("%032x", 3)),
                        () -> false);
        var texture =
                TerrainTextureBinding.borrow(metadata, ByteBuffer.wrap(rgba).asReadOnlyBuffer());
        var first = destination(directory.resolve("partial-a"));
        try (var output =
                WorldStreamTileWriter.writeUnadvertised(
                        first,
                        data(tile, SCOPE, texture, "BASIC_V1"),
                        tile,
                        texture,
                        () -> false)) {
            var decoded = read(first);
            assertEquals(1, Long.bitCount(decoded.header().coverage()[0]));
            String identity = decoded.header().materialDigest();
            assertEquals(64, identity.length());
            assertEquals(0, decoded.atlas().get(32 * 4 + 3));
            decoded.close();
            rgba[0]++;
            var second = destination(directory.resolve("partial-b"));
            try (var changed =
                    WorldStreamTileWriter.writeUnadvertised(
                            second,
                            data(tile, SCOPE, texture, "BASIC_V1"),
                            tile,
                            texture,
                            () -> false)) {
                var next = read(second);
                assertNotEquals(identity, next.header().materialDigest());
                next.close();
            }
        }
        assertFalse(Files.exists(first.path()));
    }

    @Test
    void scopeCoordinatesAndCancellationRejectBeforeAnyFileIsCreated() throws Exception {
        var target = destination(directory.resolve("reject"));
        var tile = triangle();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamTileWriter.writeUnadvertised(
                                target,
                                data(tile, "cd".repeat(32), null, "BASIC_V1"),
                                tile,
                                null,
                                () -> false));
        var wrong =
                TerrainScene.Tile.copyOf(
                        8, 19, new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[] {0, 1, 2}, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamTileWriter.writeUnadvertised(
                                target,
                                data(wrong, SCOPE, null, "BASIC_V1"),
                                wrong,
                                null,
                                () -> false));
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamTileWriter.writeUnadvertised(
                                target,
                                data(tile, SCOPE, null, "BASIC_V1"),
                                tile,
                                null,
                                () -> true));
        try (var files = Files.list(target.path().getParent())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void existingTargetAndPartRemainUntouchedWhenCreateNewPublicationFails() throws Exception {
        var target = destination(directory.resolve("existing"));
        byte[] sentinel = {9, 8, 7};
        Files.write(target.path(), sentinel);
        var tile = triangle();
        assertThrows(
                java.io.IOException.class,
                () ->
                        WorldStreamTileWriter.writeUnadvertised(
                                target,
                                data(tile, SCOPE, null, "BASIC_V1"),
                                tile,
                                null,
                                () -> false));
        assertTrue(java.util.Arrays.equals(sentinel, Files.readAllBytes(target.path())));
        Files.delete(target.path());
        var part = target.path().resolveSibling("tile-1.wst.part");
        Files.write(part, sentinel);
        assertThrows(
                java.io.IOException.class,
                () ->
                        WorldStreamTileWriter.writeUnadvertised(
                                target,
                                data(tile, SCOPE, null, "BASIC_V1"),
                                tile,
                                null,
                                () -> false));
        assertTrue(java.util.Arrays.equals(sentinel, Files.readAllBytes(part)));
    }

    @Test
    void movedButUnadoptedPacketCleansItselfWhenDestinationHasExpired() throws Exception {
        var target = destination(directory.resolve("expired"));
        var tile = triangle();
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        var active = target.getClass().getDeclaredField("active");
        active.setAccessible(true);
        active.setBoolean(target, false);
        assertThrows(IllegalStateException.class, output::move);
        assertFalse(Files.exists(directory.resolve("expired/tile-1.wst")));
        output.close();
    }

    @Test
    void descriptorCountIsSampledOnceBeforeBoundedCopy() {
        var calls = new AtomicInteger();
        var changing =
                new java.util.AbstractList<WorldStreamTileWriter.Source>() {
                    public int size() {
                        return calls.getAndIncrement() == 0 ? 1 : Integer.MAX_VALUE;
                    }

                    public WorldStreamTileWriter.Source get(int index) {
                        return diffuse();
                    }
                };
        new WorldStreamTileWriter.Data(
                SCOPE,
                "BASIC_V1",
                "12".repeat(32),
                WorldStreamTileWriter.Outcome.TEXTURED,
                WorldStreamTileWriter.Reason.NONE,
                new long[] {1, 0, 0, 0},
                0,
                0,
                0,
                structural(1, 1 << 20),
                structural(2, 2 << 20),
                structural(3, 8 << 20),
                changing);
        assertEquals(1, calls.get());
    }

    private static WorldStreamTileWriter.Water publicWater(int cells, float offsetX) {
        var wire = WorldStreamTileWaterTest.water(cells, offsetX);
        return new WorldStreamTileWriter.Water(
                wire.positions(), wire.depths(), wire.indices(), wire.layers());
    }

    @Test
    void waterMovesInsideItsTerrainPacketAndDecodesInTheChild() throws Exception {
        var target = destination(directory.resolve("water"));
        var tile = WorldStreamTileFileTest.full(false);
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        try (var moved = output.move(publicWater(4, 0), () -> false)) {
            assertTrue(output.waterAttached());
            assertFalse(output.waterDropped());
            try (var decoded = read(target)) {
                assertEquals(4, decoded.water().cells());
            }
        }
    }

    @Test
    void offTileWaterIsDroppedBeforeAppendSoTerrainStillMoves() throws Exception {
        var target = destination(directory.resolve("offtile"));
        var tile = WorldStreamTileFileTest.full(false);
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        long terrainOnly = Files.size(target.path());
        try (var moved = output.move(publicWater(1, 2000), () -> false)) {
            assertFalse(output.waterAttached());
            assertTrue(output.waterDropped());
            assertEquals(terrainOnly, Files.size(target.path()));
            try (var decoded = read(target)) {
                assertEquals(null, decoded.water());
            }
        }
    }

    @Test
    void canceledWaterMovePropagatesAndLeavesTheTerrainPacketOwned() throws Exception {
        var target = destination(directory.resolve("canceled"));
        var tile = WorldStreamTileFileTest.full(false);
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        long terrainOnly = Files.size(target.path());
        assertThrows(CancellationException.class, () -> output.move(publicWater(2, 0), () -> true));
        assertEquals(terrainOnly, Files.size(target.path()));
        output.close();
        assertFalse(Files.exists(target.path()));
    }

    @Test
    void changedUnadvertisedPacketIsNeverMovedWithOrWithoutWater() throws Exception {
        var target = destination(directory.resolve("changed"));
        var tile = WorldStreamTileFileTest.full(false);
        var output =
                WorldStreamTileWriter.writeUnadvertised(
                        target, data(tile, SCOPE, null, "BASIC_V1"), tile, null, () -> false);
        try (var channel =
                java.nio.channels.FileChannel.open(
                        target.path(), java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {9}), 5000);
        }
        assertThrows(
                java.io.UncheckedIOException.class,
                () -> output.move(publicWater(2, 0), () -> false));
        output.close();
        assertFalse(Files.exists(target.path()));
    }
}
