package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.formats.ArchiveLocations;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectWriter;
import com.vibeminer.store.StoreTypes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Application-library-scoped immutable resolution reuse; never retains build/table/source handles.
 */
public final class BuildResolutionCache implements AutoCloseable {
    public record Storage(ObjectReader reader, ObjectWriter writer) {
        public Storage {
            java.util.Objects.requireNonNull(reader);
            java.util.Objects.requireNonNull(writer);
        }
    }

    private record Key(BuildTypes.BuildRef build, BuildOpener.Options options) {}

    private record Entry(ArchiveLocations locations, long weight) {}

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(4, 0.75f, true);
    private final long budget;
    private long bytes;
    private final Semaphore saving = new Semaphore(1);
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private boolean closed;

    public BuildResolutionCache() {
        this(1L << 30);
    }

    public BuildResolutionCache(long estimatedBytes) {
        if (estimatedBytes < 0) {
            throw new IllegalArgumentException("Invalid cache budget");
        }
        budget = estimatedBytes;
    }

    synchronized ArchiveLocations memory(BuildTypes.BuildRef build, BuildOpener.Options options) {
        var value = entries.get(new Key(build, options));
        return closed || value == null ? null : value.locations();
    }

    synchronized void remember(
            BuildTypes.BuildRef build, BuildOpener.Options options, ArchiveLocations value) {
        if (closed) {
            return;
        }
        long weight =
                256L * 1024
                        + 192L * value.entryCount()
                        + 96L * value.looseCount()
                        + 32L * value.archiveCount();
        if (weight > budget) {
            return;
        }
        var key = new Key(build, options);
        var prior = entries.remove(key);
        if (prior != null) {
            bytes -= prior.weight();
        }
        entries.put(key, new Entry(value, weight));
        bytes += weight;
        while (entries.size() > 2 || bytes > budget) {
            var oldest = entries.pollFirstEntry();
            bytes -= oldest.getValue().weight();
        }
    }

    @SuppressWarnings("try")
    ArchiveLocations restore(
            BuildTypes.BuildRef build,
            BuildOpener.Options options,
            List<CdnKey> archives,
            Storage storage,
            Cancellation token) {
        if (storage == null) {
            return null;
        }
        var cancel = new com.vibeminer.store.Cancellation();
        try (var registration = token.onCancel(cancel::cancel)) {
            byte[] identity = identity(build, options);
            var read = storage.reader().read(key(identity), cancel).join();
            token.check();
            if (read.state() == StoreTypes.ReadState.ABSENT) {
                return null;
            }
            if (read.state() != StoreTypes.ReadState.PRESENT) {
                return null;
            }
            return ArchiveLocations.readProjection(
                    readChunks(read.bytes(), storage, cancel, token::check),
                    archives,
                    identity,
                    token::check);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException | java.io.IOException invalid) {
            token.check();
            System.getLogger(BuildResolutionCache.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Stored file-resolution projection unavailable; authenticated indexes will be used");
            return null;
        }
    }

    void save(
            BuildTypes.BuildRef build,
            BuildOpener.Options options,
            List<CdnKey> archives,
            ArchiveLocations locations,
            Storage storage) {
        if (storage == null || locations.projectionLength(archives) > (512L << 20)) {
            return;
        }
        synchronized (this) {
            if (closed || !saving.tryAcquire()) {
                return;
            }
            var done = new CompletableFuture<Void>();
            pending = done;
            Thread.startVirtualThread(
                    () -> {
                        try {
                            byte[] identity = identity(build, options);
                            ObjectKey key = key(identity);
                            var previous = storage.reader().read(key).join();
                            if (previous.state() == StoreTypes.ReadState.PRESENT) {
                                try {
                                    ArchiveLocations.readProjection(
                                            readChunks(
                                                    previous.bytes(),
                                                    storage,
                                                    com.vibeminer.store.Cancellation.NONE,
                                                    () -> {}),
                                            archives,
                                            identity,
                                            () -> {});
                                    return;
                                } catch (java.io.IOException | IllegalArgumentException invalid) {
                                    /* Repair this derived projection only. */
                                }
                            }
                            if (previous.state() != StoreTypes.ReadState.ABSENT) {
                                storage.writer().delete(List.of(key)).join();
                            }
                            var manifest = new ByteArrayOutputStream();
                            var header = new DataOutputStream(manifest);
                            header.writeInt(0x564d4943);
                            header.writeLong(locations.projectionLength(archives));
                            class Chunks extends OutputStream {
                                final byte[] buffer = new byte[8 << 20];
                                int used;

                                public void write(int value) {
                                    buffer[used++] = (byte) value;
                                    if (used == buffer.length) {
                                        flush();
                                    }
                                }

                                public void write(byte[] data, int offset, int length) {
                                    while (length > 0) {
                                        int count = Math.min(length, buffer.length - used);
                                        System.arraycopy(data, offset, buffer, used, count);
                                        used += count;
                                        offset += count;
                                        length -= count;
                                        if (used == buffer.length) {
                                            flush();
                                        }
                                    }
                                }

                                public void flush() {
                                    if (used == 0) {
                                        return;
                                    }
                                    byte[] bytes = Arrays.copyOf(buffer, used);
                                    byte[] hash = digest(bytes);
                                    ObjectKey chunk = key(hash);
                                    put(storage, chunk, bytes);
                                    try {
                                        header.write(hash);
                                        header.writeInt(used);
                                    } catch (java.io.IOException impossible) {
                                        throw new AssertionError(impossible);
                                    }
                                    used = 0;
                                }
                            }
                            var chunks = new Chunks();
                            var output = new DataOutputStream(chunks);
                            locations.writeProjection(output, archives, identity, () -> {});
                            output.flush();
                            put(storage, key, manifest.toByteArray());
                        } catch (RuntimeException | java.io.IOException failure) {
                            System.getLogger(BuildResolutionCache.class.getName())
                                    .log(
                                            System.Logger.Level.WARNING,
                                            "File-resolution projection could not be saved; source indexes remain available",
                                            failure);
                        } finally {
                            saving.release();
                            done.complete(null);
                        }
                    });
        }
    }

    private static byte[] readChunks(
            byte[] manifest,
            Storage storage,
            com.vibeminer.store.Cancellation token,
            Runnable cancellation)
            throws java.io.IOException {
        if (manifest.length < 48
                || manifest.length > 12 + 64 * 36
                || (manifest.length - 12) % 36 != 0) {
            throw new java.io.IOException("Invalid projection manifest");
        }
        var input = new DataInputStream(new ByteArrayInputStream(manifest));
        if (input.readInt() != 0x564d4943) {
            throw new java.io.IOException("Invalid projection manifest version");
        }
        long length = input.readLong();
        if (length < 1 || length > (512L << 20)) {
            throw new java.io.IOException("Projection exceeds bounds");
        }
        byte[] result = new byte[(int) length];
        int at = 0;
        while (input.available() > 0) {
            cancellation.run();
            byte[] hash = input.readNBytes(32);
            int count = input.readInt();
            if (count < 1 || count > (8 << 20) || count > result.length - at) {
                throw new java.io.IOException("Invalid projection chunk bounds");
            }
            var read = storage.reader().read(key(hash), token).join();
            if (read.state() != StoreTypes.ReadState.PRESENT
                    || read.bytes().length != count
                    || !MessageDigest.isEqual(hash, digest(read.bytes()))) {
                throw new java.io.IOException("Projection chunk unavailable or invalid");
            }
            System.arraycopy(read.bytes(), 0, result, at, count);
            at += count;
        }
        if (at != result.length) {
            throw new java.io.IOException("Incomplete projection");
        }
        return result;
    }

    private static void put(Storage storage, ObjectKey key, byte[] bytes) {
        var prior = storage.reader().read(key).join();
        if (prior.state() != StoreTypes.ReadState.ABSENT
                && (prior.state() != StoreTypes.ReadState.PRESENT
                        || !Arrays.equals(prior.bytes(), bytes))) {
            storage.writer().delete(List.of(key)).join();
        }
        var result =
                storage.writer()
                        .put(
                                List.of(
                                        new StoreTypes.PutObject(
                                                key,
                                                bytes.length,
                                                bytes,
                                                StoreTypes.Origin.UNKNOWN)),
                                true,
                                com.vibeminer.store.Cancellation.NONE)
                        .join()
                        .getFirst();
        if (result.outcome() != StoreTypes.PutOutcome.STORED
                && result.outcome() != StoreTypes.PutOutcome.ALREADY_PRESENT) {
            throw new IllegalStateException("Resolution projection write refused: " + result);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] identity(BuildTypes.BuildRef build, BuildOpener.Options options) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(
                            String.join(
                                            "\n",
                                            "vimi-file-resolution-v1",
                                            build.product(),
                                            build.buildConfig(),
                                            build.cdnConfig(),
                                            build.region(),
                                            Long.toString(options.maxEncodedBytes()),
                                            Long.toString(options.maxDecodedBytes()))
                                    .getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static ObjectKey key(byte[] identity) {
        return new ObjectKey(ObjectKey.GENERATED, Arrays.copyOf(identity, 16));
    }

    public synchronized CompletableFuture<Void> drain() {
        return pending.copy();
    }

    @Override
    public void close() {
        CompletableFuture<Void> waiting;
        synchronized (this) {
            closed = true;
            entries.clear();
            bytes = 0;
            waiting = pending;
        }
        waiting.join();
    }
}
