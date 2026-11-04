package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Authored ADT18/MAID/BC2 source through the real qualification path; no fabricated receipts. */
final class TerrainGpuPreviewTextureFixture implements AutoCloseable {
    static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(17, 21, 2, 0, 0, 0, 0, 3, 0, 0);
    private final TerrainAtlasPool pool = new TerrainAtlasPool();
    private final WorldTerrainTextureSource source;
    private final List<WorldTextureSnapshot> snapshots = new ArrayList<>();
    final TerrainScene scene;
    final WorldFormats.Terrain terrain;
    final List<WorldFormats.Tile> tiles;
    private final Map<String, WorldFormats.Terrain> terrains = new java.util.HashMap<>();
    private final int expectedChunks;

    TerrainGpuPreviewTextureFixture() {
        this(false);
    }

    TerrainGpuPreviewTextureFixture(boolean partial) {
        this(partial, List.of(TILE));
    }

    static TerrainGpuPreviewTextureFixture neighborhood() { return neighborhood(false); }

    static TerrainGpuPreviewTextureFixture neighborhood(boolean partial) {
        return new TerrainGpuPreviewTextureFixture(partial, List.of(TILE,
                new WorldFormats.Tile(18, 21, 101, 0, 0, 0, 0, 3, 0, 0),
                new WorldFormats.Tile(20, 21, 103, 0, 0, 0, 0, 3, 0, 0)));
    }

    private TerrainGpuPreviewTextureFixture(boolean partial, List<WorldFormats.Tile> tiles) {
        this.tiles = List.copyOf(tiles);
        expectedChunks = (partial ? 128 : 256) * tiles.size();
        Map<Long, byte[]> bytes = new java.util.HashMap<>();
        var geometry = new ArrayList<TerrainScene.Tile>();
        for (var tile : tiles) {
            byte[] root = root(tile);
            var parsed = WorldFormats.adt(root, () -> false);
            terrains.put(tile.key(), parsed);
            geometry.add(new TerrainScene.Tile(tile.x(), tile.y(), parsed));
            bytes.put(tile.rootId(), root);
        }
        terrain = terrains.get(tiles.getFirst().key());
        scene = new TerrainScene(geometry);
        bytes.put(1L, wdt(tiles));
        bytes.put(3L, texture(partial));
        bytes.put(10L, blp());
        var files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" ->
                                                    new BuildTypes.BuildRef(
                                                            "wowf",
                                                            "a".repeat(32),
                                                            "b".repeat(32),
                                                            "us");
                                            case "locale" -> "enUS";
                                            case "stat" -> {
                                                ((Cancellation) args[1]).check();
                                                long id =
                                                        ((FileTypes.FileRef) args[0]).fileDataId();
                                                byte[] body = bytes.get(id);
                                                yield CompletableFuture.completedFuture(
                                                        new FileTypes.FileStat(
                                                                FileTypes.FileLookupState.FOUND,
                                                                new FileTypes.BuildFile(
                                                                        id,
                                                                        null,
                                                                        FileTypes.FilePathSource
                                                                                .NONE,
                                                                        (long) body.length,
                                                                        String.format("%032x", id),
                                                                        FileTypes.FileFormat
                                                                                .UNKNOWN,
                                                                        FileTypes.FileFormatSource
                                                                                .NONE,
                                                                        FileTypes.FileStorage
                                                                                .STORED,
                                                                        false)));
                                            }
                                            case "openRead" -> {
                                                ((Cancellation) args[1]).check();
                                                byte[] body =
                                                        bytes.get(
                                                                ((FileTypes.FileRef) args[0])
                                                                        .fileDataId());
                                                yield CompletableFuture.completedFuture(
                                                        new FileTypes.FileOpen(
                                                                FileTypes.FileReadState.COMPLETE,
                                                                new FileReadStream() {
                                                                    int at;

                                                                    public long length() {
                                                                        return body.length;
                                                                    }

                                                                    public long position() {
                                                                        return at;
                                                                    }

                                                                    public void seek(long next) {
                                                                        at = Math.toIntExact(next);
                                                                    }

                                                                    public List<FileTypes.FileHole>
                                                                            holes() {
                                                                        return List.of();
                                                                    }

                                                                    public CompletableFuture<
                                                                                    Integer>
                                                                            read(
                                                                                    ByteBuffer
                                                                                            target,
                                                                                    Cancellation
                                                                                            token) {
                                                                        token.check();
                                                                        int count =
                                                                                Math.min(
                                                                                        target
                                                                                                .remaining(),
                                                                                        body.length
                                                                                                - at);
                                                                        target.put(body, at, count);
                                                                        at += count;
                                                                        return CompletableFuture
                                                                                .completedFuture(
                                                                                        count);
                                                                    }

                                                                    public void close() {}
                                                                },
                                                                null,
                                                                null,
                                                                null));
                                            }
                                            case "close" ->
                                                    throw new AssertionError(
                                                            "Borrowed source closed");
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected source call "
                                                                    + method.getName());
                                        });
        source = new WorldTerrainTextureSource(files, pool);
    }

    static WorldService.MapInfo mapInfo() {
        return new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    }

    CompletableFuture<WorldTerrainTextureSource.BatchResult> batch(Cancellation token,
            java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
        if (javafx.application.Platform.isFxApplicationThread()) throw new AssertionError("Source on FX");
        return batch(tiles, token, progress);
    }

    TerrainAtlasPool.Stats stats() {
        return pool.stats();
    }

    CompletableFuture<WorldTerrainTextureSource.BatchResult> batch(
            List<WorldFormats.Tile> requested, Cancellation token,
            java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
        return source.read(mapInfo(), requested, token, progress);
    }

    WorldFormats.Terrain terrain(WorldFormats.Tile tile) { return terrains.get(tile.key()); }

    WorldTextureSnapshot snapshot() throws Exception {
        var batch =
                source.read(
                                new WorldService.MapInfo(7, "Authored", null, 0, 1L),
                                tiles,
                                Cancellation.NONE,
                                p -> {})
                        .get(5, TimeUnit.SECONDS);
        var snapshot = WorldTextureSnapshot.prepare(scene, batch, () -> false);
        if (snapshot.atlasCount() != tiles.size() || snapshot.coveredChunks() != expectedChunks) {
            throw new AssertionError("Authored source did not qualify");
        }
        snapshots.add(snapshot);
        return snapshot;
    }

    private static byte[] ints(int... values) {
        var out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            out.putInt(v);
        }
        return out.array();
    }

    private static byte[] part(String name, byte[] payload) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(ints(tag, payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] wdt(List<WorldFormats.Tile> tiles) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(
                part(
                        "MPHD",
                        ByteBuffer.allocate(32)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(0x3ca)
                                .array()));
        var main = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN);
        var maid = ByteBuffer.allocate(4096 * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (var tile : tiles) {
            int ordinal = tile.x() * 64 + tile.y();
            main.putInt(ordinal * 8, 1);
            maid.putInt(ordinal * 32, (int) tile.rootId()).putInt(ordinal * 32 + 12, 3);
        }
        out.writeBytes(part("MAIN", main.array()));
        out.writeBytes(part("MAID", maid.array()));
        return out.toByteArray();
    }

    private static byte[] root(WorldFormats.Tile tile) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
            int x = ordinal % 16, y = ordinal / 16;
            header.putInt(4, x)
                    .putInt(8, y)
                    .putFloat(104, (float) (-(tile.x() - TILE.x()) * WorldFormats.TILE_SIZE - y * WorldFormats.TILE_SIZE / 16))
                    .putFloat(108, (float) (-(tile.y() - TILE.y()) * WorldFormats.TILE_SIZE - x * WorldFormats.TILE_SIZE / 16))
                    .putFloat(112, 10);
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(header.array());
            chunk.writeBytes(part("MCVT", new byte[580]));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] texture(boolean partial) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        out.writeBytes(part("MDID", ints(10)));
        out.writeBytes(part("MHID", ints(0)));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(
                    part("MCLY", partial && ordinal >= 128 ? new byte[0] : ints(0, 0, 0, 0)));
            chunk.writeBytes(part("MCAL", new byte[0]));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] blp() {
        var out = ByteBuffer.allocate(148 + 7 * 16).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 8)
                .put((byte) 1)
                .put((byte) 2)
                .putInt(8)
                .putInt(8);
        int at = 148;
        for (int level = 0; level < 4; level++) {
            int length = level == 0 ? 64 : 16;
            out.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            for (int block = 0; block < length / 16; block++) {
                out.position(at + block * 16);
                out.putLong(-1).putShort((short) 0xf800).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return out.array();
    }

    public void close() {
        for (var snapshot : snapshots) {
            snapshot.close();
        }
        source.closeAsync().join();
        if (pool.stats().leaseClaims() != 0 || pool.stats().borrowPins() != 0) {
            throw new AssertionError("Snapshot claims leaked " + pool.stats());
        }
        pool.closeAsync().join();
    }
}
