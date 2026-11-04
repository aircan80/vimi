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
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(17, 21, 2, 0, 0, 0, 0, 3, 0, 0);
    private final TerrainAtlasPool pool = new TerrainAtlasPool();
    private final WorldTerrainTextureSource source;
    private final List<WorldTextureSnapshot> snapshots = new ArrayList<>();
    final TerrainScene scene;
    private final int expectedChunks;

    TerrainGpuPreviewTextureFixture() {
        this(false);
    }

    TerrainGpuPreviewTextureFixture(boolean partial) {
        expectedChunks = partial ? 128 : 256;
        byte[] root = root();
        scene =
                new TerrainScene(
                        List.of(
                                new TerrainScene.Tile(
                                        TILE.x(), TILE.y(), WorldFormats.adt(root, () -> false))));
        Map<Long, byte[]> bytes = Map.of(1L, wdt(), 2L, root, 3L, texture(partial), 10L, blp());
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

    TerrainAtlasPool.Stats stats() {
        return pool.stats();
    }

    WorldTextureSnapshot snapshot() throws Exception {
        var batch =
                source.read(
                                new WorldService.MapInfo(7, "Authored", null, 0, 1L),
                                List.of(TILE),
                                Cancellation.NONE,
                                p -> {})
                        .get(5, TimeUnit.SECONDS);
        var snapshot = WorldTextureSnapshot.prepare(scene, batch, () -> false);
        if (snapshot.atlasCount() != 1 || snapshot.coveredChunks() != expectedChunks) {
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

    private static byte[] wdt() {
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
        int ordinal = TILE.x() * 64 + TILE.y();
        main.putInt(ordinal * 8, 1);
        out.writeBytes(part("MAIN", main.array()));
        var maid = ByteBuffer.allocate(4096 * 32).order(ByteOrder.LITTLE_ENDIAN);
        maid.putInt(ordinal * 32, 2).putInt(ordinal * 32 + 12, 3);
        out.writeBytes(part("MAID", maid.array()));
        return out.toByteArray();
    }

    private static byte[] root() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
            int x = ordinal % 16, y = ordinal / 16;
            header.putInt(4, x)
                    .putInt(8, y)
                    .putFloat(104, (float) (-y * WorldFormats.TILE_SIZE / 16))
                    .putFloat(108, (float) (-x * WorldFormats.TILE_SIZE / 16))
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
