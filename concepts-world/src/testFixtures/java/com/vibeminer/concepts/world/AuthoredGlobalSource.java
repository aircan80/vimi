package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Authored bytes through actual native Map capture, global declaration and G1/G2 Source factories.
 * Test fixture only: no generated positive owner/receipt or game assets, and no network.
 */
public final class AuthoredGlobalSource implements AutoCloseable {
    public static final long WDT = 1, ROOT = 123, GROUP = 201, IMAGE = 301;
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private final Map<Long, byte[]> sources = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> opens = new ConcurrentHashMap<>(),
            stats = new ConcurrentHashMap<>();
    private final java.util.Set<Long> changed = ConcurrentHashMap.newKeySet();

    /** Opens that fail with a state and code instead of returning a stream. */
    final java.util.Map<Long, FileTypes.FileOpen> failedOpens = new ConcurrentHashMap<>();

    private final AtomicInteger closedStreams = new AtomicInteger(),
            borrowedClosed = new AtomicInteger(),
            fullStats = new AtomicInteger();
    private final AtomicReference<PendingGate> requested = new AtomicReference<>();
    private volatile ReadGate lastGate;
    private volatile LongConsumer statObserver = ignored -> {};
    private final AuthoredNativeMap nativeMap;
    private final WorldGlobalScopes core;
    private final WorldGlobalScenes source;
    private final WorldGlobalScenes.Scope scope;

    public AuthoredGlobalSource() {
        sources.put(WDT, wdt());
        sources.put(ROOT, root(new long[] {GROUP}, IMAGE));
        sources.put(GROUP, group(false, false));
        sources.put(IMAGE, image(true, false));
        var files = files();
        nativeMap = new AuthoredNativeMap(files);
        core = new WorldGlobalScopes(nativeMap.tables(), files);
        source = new WorldGlobalScenes(core);
        scope =
                source.createScope(
                        new WorldService.MapInfo(7, "Authored global", null, 0, 999L),
                        Cancellation.NONE);
        source.activate(scope);
    }

    /** Makes every later open of {@code id} fail with {@code state} and {@code code}. */
    public void failOpen(long id, FileTypes.FileReadState state, String code) {
        failedOpens.put(id, new FileTypes.FileOpen(state, null, code, null, null));
    }

    public WorldGlobalScenes source() {
        return source;
    }

    public WorldGlobalScenes.Scope scope() {
        return scope;
    }

    public int opened(long id) {
        return opens.getOrDefault(id, new AtomicInteger()).get();
    }

    public int stated(long id) {
        return stats.getOrDefault(id, new AtomicInteger()).get();
    }

    /** Full stats (with names and format) requested; World source reads need index facts only. */
    public int fullStats() {
        return fullStats.get();
    }

    public int closedStreams() {
        return closedStreams.get();
    }

    public int borrowedClosed() {
        return borrowedClosed.get();
    }

    public int payloadCredits() {
        return core.payloadCredits();
    }

    public void markChanged(long id) {
        changed.add(id);
    }

    public void onStat(LongConsumer observer) {
        statObserver = java.util.Objects.requireNonNull(observer);
    }

    public void invalidate() {
        source.invalidate();
    }

    public CompletableFuture<Void> closeAsync() {
        return core.closeAsync();
    }

    public ReadGate holdNextRead(long id) {
        if (!sources.containsKey(id)) {
            throw new IllegalArgumentException("Unknown authored source");
        }
        var prior = lastGate;
        if (prior != null && !prior.result.isDone()) {
            throw new IllegalStateException("Existing read gate");
        }
        var gate = new ReadGate();
        if (!requested.compareAndSet(null, new PendingGate(id, gate))) {
            throw new IllegalStateException("Existing requested read");
        }
        lastGate = gate;
        return gate;
    }

    private record PendingGate(long id, ReadGate gate) {}

    public static final class ReadGate {
        private final CompletableFuture<Void> entered = new CompletableFuture<>(),
                canceled = new CompletableFuture<>();
        private final CompletableFuture<Integer> result = new CompletableFuture<>();
        private Runnable transfer;
        private boolean bound, completed;

        private ReadGate() {}

        public CompletableFuture<Void> entered() {
            return entered.copy();
        }

        public CompletableFuture<Void> canceled() {
            return canceled.copy();
        }

        private void bind(Runnable transfer) {
            synchronized (this) {
                if (bound || completed) {
                    throw new IllegalStateException("Read gate reused");
                }
                this.transfer = transfer;
                bound = true;
            }
            entered.complete(null);
        }

        public void release() {
            Runnable action;
            synchronized (this) {
                if (!bound || completed) {
                    throw new IllegalStateException("Read gate unavailable");
                }
                completed = true;
                action = transfer;
                transfer = null;
            }
            action.run();
        }

        public void fail(Throwable original) {
            java.util.Objects.requireNonNull(original);
            synchronized (this) {
                if (!bound || completed) {
                    throw new IllegalStateException("Read gate unavailable");
                }
                completed = true;
                transfer = null;
            }
            result.completeExceptionally(original);
        }

        private void retireForCleanup() {
            synchronized (this) {
                if (!bound || completed) {
                    return;
                }
                completed = true;
                transfer = null;
            }
            result.completeExceptionally(new java.util.concurrent.CancellationException());
        }
    }

    private FileTypes.FileStat stat(long id, Cancellation token) {
        token.check();
        statObserver.accept(id);
        stats.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        byte[] bytes = sources.get(id);
        if (bytes == null && id != 100) {
            return new FileTypes.FileStat(FileTypes.FileLookupState.UNKNOWN_FILE, null);
        }
        String key =
                changed.contains(id)
                        ? "d".repeat(32)
                        : id == 100 ? "e".repeat(32) : String.format("%032x", id);
        return new FileTypes.FileStat(
                FileTypes.FileLookupState.FOUND,
                new FileTypes.BuildFile(
                        id,
                        null,
                        FileTypes.FilePathSource.NONE,
                        bytes == null ? null : (long) bytes.length,
                        key,
                        FileTypes.FileFormat.UNKNOWN,
                        FileTypes.FileFormatSource.NONE,
                        FileTypes.FileStorage.STORED,
                        false));
    }

    private FileReadStream open(long id, Cancellation token) {
        token.check();
        byte[] bytes = sources.get(id);
        if (bytes == null) {
            throw new IllegalArgumentException("Unknown authored input");
        }
        opens.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        return new FileReadStream() {
            int position;
            boolean closed;
            Cancellation.Registration registration;

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
                return List.of();
            }

            private int transfer(ByteBuffer target) {
                int count = Math.min(target.remaining(), bytes.length - position);
                target.put(bytes, position, count);
                position += count;
                return count;
            }

            public CompletableFuture<Integer> read(ByteBuffer target, Cancellation cancel) {
                cancel.check();
                if (!target.hasRemaining()) {
                    return CompletableFuture.completedFuture(0);
                }
                if (position == bytes.length) {
                    return CompletableFuture.completedFuture(-1);
                }
                var request = requested.get();
                if (request != null
                        && request.id() == id
                        && requested.compareAndSet(request, null)) {
                    var gate = request.gate();
                    registration = cancel.onCancel(() -> gate.canceled.complete(null));
                    gate.bind(
                            () -> {
                                try {
                                    cancel.check();
                                    gate.result.complete(transfer(target));
                                } catch (RuntimeException | Error failure) {
                                    gate.result.completeExceptionally(failure);
                                }
                            });
                    return gate.result;
                }
                return CompletableFuture.completedFuture(transfer(target));
            }

            public void close() {
                if (!closed) {
                    closed = true;
                    if (registration != null) {
                        registration.close();
                    }
                    closedStreams.incrementAndGet();
                }
            }
        };
    }

    private BuildFiles files() {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "build" -> BUILD;
                                    case "locale" -> "enUS";
                                    case "stat", "statContent" -> {
                                        if (method.getName().equals("stat")) {
                                            fullStats.incrementAndGet();
                                        }
                                        yield CompletableFuture.completedFuture(
                                                stat(
                                                        ((FileTypes.FileRef) args[0]).fileDataId(),
                                                        (Cancellation) args[1]));
                                    }
                                    case "openRead" -> {
                                        long id = ((FileTypes.FileRef) args[0]).fileDataId();
                                        var failed = failedOpens.get(id);
                                        if (failed != null) {
                                            yield CompletableFuture.completedFuture(failed);
                                        }
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
                                                    "Unexpected authored files API "
                                                            + method.getName());
                                });
    }

    @Override
    public void close() {
        statObserver = ignored -> {};
        var gate = lastGate;
        if (gate != null) {
            gate.retireForCleanup();
        }
        var drain = closeAsync();
        try {
            drain.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Authored Source close interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Authored Source close failed", failure);
        } finally {
            if (drain.isDone()) {
                nativeMap.close();
            }
        }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float v : values) {
            b.putFloat(v);
        }
        return b.array();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var bytes : parts) {
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] chunk(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(tag).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] version(int v) {
        return chunk("MVER", buffer(4).putInt(v).array());
    }

    private static byte[] wdt() {
        var mphd = buffer(32).putInt(0x241);
        var placement = buffer(64).putInt(123).putInt(-1);
        placement.putShort(56, (short) 8).putShort(62, (short) 1024);
        return join(
                version(18),
                chunk("MPHD", mphd.array()),
                chunk("MAIN", new byte[32768]),
                chunk("MAID", new byte[131072]),
                chunk("MODF", placement.array()));
    }

    private static byte[] root(long[] groups, long... images) {
        var h = buffer(64).putInt(images.length).putInt(groups.length);
        h.position(28);
        h.putInt(0xff123456)
                .putInt(77)
                .put(floats(-1, -1, -1, 2, 2, 2))
                .putShort((short) 5)
                .putShort((short) 0);
        var m = buffer(images.length * 64);
        for (int i = 0; i < images.length; i++) {
            m.putInt(i * 64 + 12, (int) images[i])
                    .putInt(i * 64 + 16, 0xff000000)
                    .putInt(i * 64 + 32, 10);
        }
        var g = buffer(groups.length * 4);
        for (long id : groups) {
            g.putInt((int) id);
        }
        return join(
                version(17),
                chunk("MOHD", h.array()),
                chunk("MOMT", m.array()),
                chunk("GFID", g.array()));
    }

    private static byte[] group(boolean overlap, boolean extraUv) {
        var h = buffer(68);
        h.putInt(8, 0x0a09);
        h.position(12);
        h.put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) (overlap ? 2 : 1));
        var batch = buffer(overlap ? 48 : 24);
        for (int i = 0; i < (overlap ? 2 : 1); i++) {
            batch.putInt(i * 24 + 12, 0)
                    .putShort(i * 24 + 16, (short) 3)
                    .putShort(i * 24 + 20, (short) 2);
        }
        byte[] uv = chunk("MOTV", floats(0, 0, 1, 0, 0, 1));
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 2, 0)),
                                chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                uv,
                                chunk(
                                        "MOVI",
                                        buffer(12)
                                                .putShort((short) 0)
                                                .putShort((short) 1)
                                                .putShort((short) 2)
                                                .putShort((short) 2)
                                                .putShort((short) 1)
                                                .putShort((short) 0)
                                                .array()),
                                chunk("MOPY", new byte[4]),
                                chunk("MOBA", batch.array()),
                                extraUv ? uv : new byte[0])));
    }

    private static byte[] image(boolean bc3, boolean transparentBc1) {
        int block = bc3 ? 16 : 8;
        var b = buffer(148 + 3 * block);
        b.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) (bc3 ? 8 : transparentBc1 ? 1 : 0))
                .put((byte) (bc3 ? 7 : 0))
                .put((byte) 1)
                .putInt(4)
                .putInt(4);
        for (int i = 0; i < 3; i++) {
            int at = 148 + i * block;
            b.putInt(20 + i * 4, at).putInt(84 + i * 4, block);
            int color = bc3 ? at + 8 : at;
            b.putShort(color, (short) (transparentBc1 ? 0 : 0xf800))
                    .putShort(color + 2, (short) (transparentBc1 ? 0xf800 : 0));
            b.putInt(color + 4, transparentBc1 ? -1 : 0);
            if (bc3) {
                b.put(at, (byte) 0).put(at + 1, (byte) 217).put(at + 2, (byte) 8);
            }
        }
        return b.array();
    }
}
