package com.vibeminer.concepts.world;

import com.vibeminer.rendering.world.TerrainTileAtlas;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Application-owned bounded atlas storage; build sources must explicitly share this pool. */
public final class TerrainAtlasPool {
    /** Every World output's atlas lease plus six for other borrowers, as before issue #173. */
    public static final int MAX_ENTRIES = WorldPreparedTerrain.MAX_OUTPUTS + 6;

    /**
     * The most one entry may retain: the largest atlas with HIGH splatting inputs. Stats charge
     * each entry less: a published atlas its own {@link TerrainTileAtlas#retainedBytes()}, a
     * reservation the bound for the current splat setting ({@link #reservedBytes()}).
     */
    public static final long ENTRY_BYTES = TerrainTileAtlas.MAX_RETAINED_BYTES;

    /**
     * The most the pool charges in all (issue #491): twenty atlases with HIGH splatting inputs,
     * about 100 MiB. That is the NEAR ring's ten slots (nine active and one staging) plus a full
     * nine-tile texture batch and one spare, the only setting where HIGH masks are composed. FAR
     * and STANDARD tiles stay bounded by {@link #MAX_ENTRIES} first (34 x about 2.02 MiB with
     * STANDARD splatting, 34 x about 1.02 MiB without), so the count bound is unchanged there.
     * Before this bound, NEAR + HIGH could retain 34 x about 5.02 MiB, about 171 MiB.
     */
    public static final long MAX_BYTES =
            20
                    * TerrainTileAtlas.maxRetainedBytes(
                            com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK);

    /**
     * What a reservation is charged until its atlas is published: the largest atlas the source
     * composes now, with no splat inputs, or with the current tier's mask side.
     */
    static long reservedBytes() {
        return reservedBytes(
                WorldTerrainTextureSource.splat ? WorldTerrainTextureSource.splatMask : 0);
    }

    private static long reservedBytes(int maskSide) {
        return TerrainTileAtlas.maxRetainedBytes(maskSide);
    }

    /**
     * A snapshot of the pool. {@code leaseClaims} counts every open lease, so one atlas counts
     * several times while a retained copy and its original are both open (a texture batch hands a
     * tile's atlas to its consumer that way). {@code claimedEntries} counts the atlases with at
     * least one open lease, and {@code reservedEntries} the slots reserved but not yet published:
     * together they are the slots owners hold.
     */
    public record Stats(
            int entries,
            int reservedEntries,
            int cachedEntries,
            int leaseClaims,
            int borrowPins,
            long chargedBytes,
            long receiptMetadataBytes,
            int claimedEntries) {}

    private static final class Entry {
        final int slot;
        boolean reserved = true, cached;
        long charge;
        int claims, borrows;
        TerrainTileAtlas atlas;
        WorldTerrainTextureSource.Receipt receipt;

        Entry(int slot, long charge) {
            this.slot = slot;
            this.charge = charge;
        }
    }

    /** Scoped access only: atlas and pixel views must not escape withAtlas's callback. */
    public static final class Lease implements AutoCloseable {
        private final TerrainAtlasPool pool;
        private Entry entry;

        private Lease(TerrainAtlasPool pool, Entry entry) {
            this.pool = pool;
            this.entry = entry;
            entry.claims++;
        }

        public Lease retain() {
            synchronized (pool) {
                pool.requireOpen();
                return new Lease(pool, requireEntry());
            }
        }

        public void withAtlas(Consumer<TerrainTileAtlas> action) {
            Objects.requireNonNull(action);
            Entry borrowed;
            synchronized (pool) {
                pool.requireOpen();
                borrowed = requireEntry();
                borrowed.borrows++;
            }
            try {
                // No lock around caller code. The independent pin survives concurrent close().
                action.accept(borrowed.atlas);
            } finally {
                synchronized (pool) {
                    borrowed.borrows--;
                    pool.releaseIfUnused(borrowed);
                }
            }
        }

        private Entry requireEntry() {
            if (entry == null) {
                throw new IllegalStateException("Atlas lease closed");
            }
            return entry;
        }

        @Override
        public void close() {
            synchronized (pool) {
                if (entry != null) {
                    Entry released = entry;
                    entry = null;
                    released.claims--;
                    pool.releaseIfUnused(released);
                }
            }
        }
    }

    // Source-only allocation authority. Reserving must precede compose/output allocation.
    final class Reservation implements AutoCloseable {
        private Entry entry;
        private final int maskSide;

        private Reservation(Entry entry, int maskSide) {
            this.entry = entry;
            this.maskSide = maskSide;
        }

        /**
         * The splat mask side this reservation was charged for, 0 for no splatting inputs. The
         * source composes with it, so the published atlas never retains more than its charge even
         * if the setting changes while it composes.
         */
        int maskSide() {
            return maskSide;
        }

        Lease publish(TerrainTileAtlas atlas, String completeCacheKey) {
            return publish(atlas, completeCacheKey, null);
        }

        Lease publish(
                TerrainTileAtlas atlas,
                String completeCacheKey,
                WorldTerrainTextureSource.Receipt receipt) {
            Objects.requireNonNull(atlas);
            if (atlas.retainedBytes() > ENTRY_BYTES
                    || atlas.supportedChunks() == 0
                    || (completeCacheKey != null
                            && (!atlas.complete() || !validKey(completeCacheKey)))) {
                throw new IllegalArgumentException("Invalid reserved atlas publication");
            }
            synchronized (TerrainAtlasPool.this) {
                requireOpen();
                if (entry == null) {
                    throw new IllegalStateException("Atlas reservation closed");
                }
                if (atlas.retainedBytes() > entry.charge) {
                    throw new IllegalArgumentException("Atlas exceeds its reservation's charge");
                }
                charged += atlas.retainedBytes() - entry.charge;
                entry.charge = atlas.retainedBytes();
                if (completeCacheKey != null && cache.containsKey(completeCacheKey)) {
                    throw new IllegalStateException("Duplicate ready atlas cache publication");
                }
                Entry published = entry;
                published.atlas = atlas;
                published.receipt = receipt;
                published.reserved = false;
                if (completeCacheKey != null) {
                    cache.put(completeCacheKey, published);
                    published.cached = true;
                }
                entry = null;
                return new Lease(TerrainAtlasPool.this, published);
            }
        }

        @Override
        public void close() {
            synchronized (TerrainAtlasPool.this) {
                if (entry != null) {
                    Entry released = entry;
                    entry = null;
                    released.reserved = false;
                    releaseIfUnused(released);
                }
            }
        }
    }

    private final Entry[] slots = new Entry[MAX_ENTRIES];
    private long charged;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(MAX_ENTRIES, .75f, true);
    private CompletableFuture<Void> closing;
    private boolean closeCompletionStarted;
    private boolean workDrained = true;
    private Throwable workFailure;
    private TerrainTextureTasks tasks;

    record Cached(Lease lease, WorldTerrainTextureSource.Receipt receipt) {}

    synchronized boolean isClosing() {
        return closing != null;
    }

    synchronized TerrainTextureTasks tasks() {
        requireOpen();
        if (tasks == null) {
            tasks = new TerrainTextureTasks();
            workDrained = false;
        }
        return tasks;
    }

    synchronized Optional<Cached> cachedReceipt(String key) {
        requireOpen();
        if (!validKey(key)) {
            throw new IllegalArgumentException("Invalid atlas cache key");
        }
        Entry hit = cache.get(key);
        return hit == null || hit.receipt == null
                ? Optional.empty()
                : Optional.of(new Cached(new Lease(this, hit), hit.receipt));
    }

    public TerrainAtlasPool() {}

    synchronized Optional<Reservation> reserve() {
        requireOpen();
        int maskSide = WorldTerrainTextureSource.splat ? WorldTerrainTextureSource.splatMask : 0;
        long charge = reservedBytes(maskSide);
        var oldest = cache.entrySet().iterator();
        // Least recently used first, until both a slot and the bytes are free. Removing pinned
        // cache claims frees neither and destroys useful overlap.
        while ((freeSlot() < 0 || charged + charge > MAX_BYTES) && oldest.hasNext()) {
            Entry candidate = oldest.next().getValue();
            if (candidate.claims == 0 && candidate.borrows == 0) {
                oldest.remove();
                candidate.cached = false;
                releaseIfUnused(candidate);
            }
        }
        int free = freeSlot();
        if (free < 0 || charged + charge > MAX_BYTES) {
            return Optional.empty();
        }
        Entry reserved = new Entry(free, charge);
        slots[free] = reserved;
        charged += charge;
        return Optional.of(new Reservation(reserved, maskSide));
    }

    synchronized Optional<Lease> cached(String key) {
        requireOpen();
        if (!validKey(key)) {
            throw new IllegalArgumentException("Invalid atlas cache key");
        }
        Entry hit = cache.get(key);
        return hit == null ? Optional.empty() : Optional.of(new Lease(this, hit));
    }

    public synchronized Stats stats() {
        int entries = 0, reserved = 0, claims = 0, borrows = 0, claimed = 0;
        long metadata = 0, charged = 0;
        for (Entry entry : slots) {
            if (entry != null) {
                entries++;
                charged += entry.charge;
                reserved += entry.reserved ? 1 : 0;
                claims += entry.claims;
                claimed += entry.claims > 0 ? 1 : 0;
                borrows += entry.borrows;
                if (entry.receipt != null) {
                    metadata += entry.receipt.metadataBytes();
                }
            }
        }
        return new Stats(
                entries, reserved, cache.size(), claims, borrows, charged, metadata, claimed);
    }

    public CompletableFuture<Void> closeAsync() {
        TerrainTextureTasks retiring;
        CompletableFuture<Void> result;
        synchronized (this) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            retiring = tasks;
            var retired = cache.values().toArray(Entry[]::new);
            cache.clear();
            for (Entry entry : retired) {
                entry.cached = false;
                releaseIfUnused(entry);
            }
            finishCloseIfDrained();
            result = closing.copy();
        }
        // Never acquire the scheduler lock while holding the pool lock.
        if (retiring != null) {
            retiring.closeAsync()
                    .whenComplete(
                            (unused, failure) -> {
                                synchronized (TerrainAtlasPool.this) {
                                    workDrained = true;
                                    workFailure = failure;
                                    finishCloseIfDrained();
                                }
                            });
        }
        return result;
    }

    private void requireOpen() {
        if (closing != null) {
            throw new IllegalStateException("Terrain atlas pool closed");
        }
    }

    private int freeSlot() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private void releaseIfUnused(Entry entry) {
        if (!entry.reserved
                && !entry.cached
                && entry.claims == 0
                && entry.borrows == 0
                && slots[entry.slot] == entry) {
            slots[entry.slot] = null;
            charged -= entry.charge;
            entry.atlas = null;
            entry.receipt = null;
        }
        finishCloseIfDrained();
    }

    private void finishCloseIfDrained() {
        if (closing != null
                && workDrained
                && !closeCompletionStarted
                && freeCount() == MAX_ENTRIES) {
            closeCompletionStarted = true;
            // Never execute caller completion code under the pool lock or on a lease closer.
            Thread.startVirtualThread(
                    () -> {
                        if (workFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(workFailure);
                        }
                    });
        }
    }

    private int freeCount() {
        int free = 0;
        for (Entry entry : slots) {
            if (entry == null) {
                free++;
            }
        }
        return free;
    }

    private static boolean validKey(String key) {
        return key != null && key.length() == 64 && key.matches("[0-9a-f]{64}");
    }
}
