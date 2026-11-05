package com.vimi.gui;
import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual authored WDT/ADT/BLP bytes; no source capability is fabricated. */
final class AuthoredTerrainSource {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);
    private static final long UNUSED = 999;

    private static byte[] ints(int... values) {
        var bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            bytes.putInt(value);
        }
        return bytes.array();
    }

    private static byte[] part(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(ints(tag, bytes.length));
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static byte[] wdt(int flags) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        var mphd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(flags);
        out.writeBytes(part("MPHD", mphd.array()));
        var main = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN);
        main.putInt((TILE.x() * 64 + TILE.y()) * 8, 1);
        out.writeBytes(part("MAIN", main.array()));
        var maid = ByteBuffer.allocate(4096 * 32).order(ByteOrder.LITTLE_ENDIAN);
        int at = (TILE.x() * 64 + TILE.y()) * 32;
        maid.putInt(at, 2).putInt(at + 12, 3);
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

    private static byte[] texture() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        out.writeBytes(part("MDID", ints(10, 11, (int) UNUSED)));
        out.writeBytes(part("MHID", ints(0, 0, 0)));
        for (int i = 0; i < 256; i++) {
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(
                    part("MCLY", i < 128 ? ints(0, 0, 0, 0, 1, 0x100, 0, 0) : ints(0, 0, 0, 0)));
            byte[] alpha = new byte[i < 128 ? 4096 : 0];
            java.util.Arrays.fill(alpha, (byte) 128);
            chunk.writeBytes(part("MCAL", alpha));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] blp(int color) {
        // Exact BC2 handmade8->4->2->1 chain, all block selectors0 and sourcealpha15.
        var data = ByteBuffer.allocate(148 + 7 * 16).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
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
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            for (int block = 0; block < length / 16; block++) {
                data.position(at + block * 16);
                data.putLong(-1).putShort((short) color).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return data.array();
    }

    static class Files {
        final Map<Long, byte[]> sources = new ConcurrentHashMap<>();
        final Map<Long, AtomicInteger> stats = new ConcurrentHashMap<>(),
                opens = new ConcurrentHashMap<>();
        final AtomicInteger closed = new AtomicInteger(), borrowedClosed = new AtomicInteger();
        volatile long protectedId, changedId, wrongId;
        volatile boolean changeAfterRead;

        Files() {
            sources.put(1L, wdt(0x3ca));
            sources.put(2L, root());
            sources.put(3L, texture());
            sources.put(10L, blp(0xf800));
            sources.put(11L, blp(0x001f));
        }

        FileTypes.FileStat stat(long id, Cancellation token) {
            token.check();
            if (id == UNUSED) {
                throw new AssertionError("Unused palette may not be fetched");
            }
            stats.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = sources.get(id);
            if (bytes == null) {
                return new FileTypes.FileStat(FileTypes.FileLookupState.UNKNOWN_FILE, null);
            }
            String key =
                    changeAfterRead
                                    && id == changedId
                                    && opens.getOrDefault(id, new AtomicInteger()).get() > 0
                            ? "d".repeat(32)
                            : String.format("%032x", id);
            return new FileTypes.FileStat(
                    FileTypes.FileLookupState.FOUND,
                    new FileTypes.BuildFile(
                            id == wrongId ? id + 1 : id,
                            null,
                            FileTypes.FilePathSource.NONE,
                            (long) bytes.length,
                            key,
                            FileTypes.FileFormat.UNKNOWN,
                            FileTypes.FileFormatSource.NONE,
                            FileTypes.FileStorage.STORED,
                            false));
        }

        FileReadStream open(long id, Cancellation token) {
            token.check();
            opens.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = sources.get(id);
            return new FileReadStream() {
                int position;
                boolean finished;

                public long length() {
                    return bytes.length;
                }

                public long position() {
                    return position;
                }

                public void seek(long value) {
                    position = Math.toIntExact(value);
                }

                public List<FileTypes.FileHole> holes() {
                    return id == protectedId
                            ? List.of(new FileTypes.FileHole(0, 4, "authored"))
                            : List.of();
                }

                public CompletableFuture<Integer> read(ByteBuffer target, Cancellation cancel) {
                    cancel.check();
                    if (!target.hasRemaining()) return CompletableFuture.completedFuture(0);
                    if (position >= bytes.length) return CompletableFuture.completedFuture(-1);
                    int count = Math.min(target.remaining(), bytes.length - position);
                    target.put(bytes, position, count);
                    position += count;
                    return CompletableFuture.completedFuture(count);
                }

                public void close() {
                    if (!finished) {
                        finished = true;
                        closed.incrementAndGet();
                    }
                }
            };
        }

        BuildFiles files() {
            return (BuildFiles)
                    Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "build" -> BUILD;
                                        case "locale" -> "enUS";
                                        case "stat", "statContent" ->
                                                CompletableFuture.completedFuture(
                                                        stat(
                                                                ((FileTypes.FileRef) args[0])
                                                                        .fileDataId(),
                                                                (Cancellation) args[1]));
                                        case "openRead" -> {
                                            long id = ((FileTypes.FileRef) args[0]).fileDataId();
                                            yield CompletableFuture.completedFuture(
                                                    new FileTypes.FileOpen(
                                                            FileTypes.FileReadState.COMPLETE,
                                                            open(id, (Cancellation) args[1]),
                                                            null,
                                                            null,
                                                            null));
                                        }
                                        case "close" -> {
                                            borrowedClosed.incrementAndGet();
                                            yield null;
                                        }
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected borrowed API: "
                                                                + method.getName());
                                    });
        }

        int opens(long id) {
            return opens.getOrDefault(id, new AtomicInteger()).get();
        }

        int totalOpens() {
            return opens.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

}
