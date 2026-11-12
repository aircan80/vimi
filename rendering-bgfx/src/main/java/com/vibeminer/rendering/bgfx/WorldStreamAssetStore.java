package com.vibeminer.rendering.bgfx;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The model and image assets a session's helper keeps for objects files that name them by digest
 * (#380 stage 4), least recently named first, within {@link #CAPACITY_BYTES} of payload bytes.
 *
 * <p>The helper holds the decoded assets; the parent holds a mirror of the same class without them,
 * which tells it which assets an objects file may name without sending them. Both change only by
 * the same steps, so the mirror stays exact:
 *
 * <ul>
 *   <li>{@link #reset} when a command carries a new epoch: the parent starts one (empty) whenever
 *       its mirror may be wrong, and the helper empties its store on the first command of it;
 *   <li>{@link #apply} once per objects file whose every named asset the helper had or received:
 *       the file's assets in reference order become the most recently named, those received are
 *       added, and the least recently named of the others go until the store fits.
 * </ul>
 *
 * <p>Every reply tells the parent whether the helper applied the file and what it holds after
 * ({@link Report}); the parent applies the same file and compares, and starts a new epoch on any
 * difference. A wrong mirror therefore only costs bytes: an asset it lacks is sent again, and an
 * asset it wrongly holds makes the helper answer MISSING, after which the parent resends in a new
 * epoch with every asset. Not thread-safe: the helper's store is confined to its objects decoder,
 * the parent's mirror to its objects worker.
 *
 * @param <V> the decoded asset the helper keeps, or {@link Void} for the parent's mirror
 */
final class WorldStreamAssetStore<V> {
    /**
     * Payload bytes the helper keeps; the parent's mirror uses the same, as both ship together.
     * About two fills' worth of distinct assets at the #771 measurement.
     */
    static final long CAPACITY_BYTES = 96L << 20;

    /**
     * What the helper's store holds after an objects command: its epoch, whether it applied the
     * file ({@link #apply}), how many assets that evicted, and its entries, payload bytes and
     * {@link #hash}.
     */
    record Report(long epoch, boolean applied, int evicted, int entries, long bytes, long hash) {
        Report {
            if (epoch < 0 || evicted < 0 || entries < 0 || bytes < 0 || !applied && evicted != 0) {
                throw new IllegalArgumentException("Asset store report");
            }
        }

        /** Its status fields: epoch, applied (0 or 1), evicted, entries, bytes, hex hash. */
        String text() {
            return epoch
                    + " "
                    + (applied ? 1 : 0)
                    + " "
                    + evicted
                    + " "
                    + entries
                    + " "
                    + bytes
                    + " "
                    + java.util.HexFormat.of().toHexDigits(hash);
        }

        /** The same holdings, compared without the per-command fields. */
        boolean holdsAs(Report other) {
            return epoch == other.epoch
                    && entries == other.entries
                    && bytes == other.bytes
                    && hash == other.hash;
        }
    }

    private record Entry<V>(long bytes, V value) {}

    private final long capacity;
    private final LinkedHashMap<WorldStreamObjectAssets.Digest, Entry<V>> entries =
            new LinkedHashMap<>(256);
    private long epoch, bytes, hash;

    // The current command's fields for its report: whether it applied a file, and evicted.
    private boolean applied;
    private int evicted;

    WorldStreamAssetStore() {
        this(CAPACITY_BYTES);
    }

    WorldStreamAssetStore(long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Asset store capacity");
        }
        this.capacity = capacity;
    }

    long epoch() {
        return epoch;
    }

    /** Starts a command: its report says nothing applied until {@link #apply}. */
    void begin() {
        applied = false;
        evicted = 0;
    }

    /** Empties the store into epoch {@code epoch}. */
    void reset(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("Asset store epoch");
        }
        entries.clear();
        applied = false;
        evicted = 0;
        bytes = 0;
        hash = 0;
        this.epoch = epoch;
    }

    /** Whether the store holds {@code digest}; does not count as naming it. */
    boolean holds(WorldStreamObjectAssets.Digest digest) {
        return entries.containsKey(digest);
    }

    /** The decoded asset kept for {@code digest}, or null; does not count as naming it. */
    V value(WorldStreamObjectAssets.Digest digest) {
        var entry = entries.get(digest);
        return entry == null ? null : entry.value;
    }

    /** Every held asset, least recently named first. */
    Map<WorldStreamObjectAssets.Digest, V> values() {
        var out = new LinkedHashMap<WorldStreamObjectAssets.Digest, V>();
        entries.forEach((digest, entry) -> out.put(digest, entry.value));
        return out;
    }

    /**
     * One objects file's assets, in reference order ({@link #order}): each already held becomes the
     * most recently named; each not held is added with its payload bytes and value from {@code
     * received}, which must hold it. Then the least recently named assets the file does not name go
     * until the store fits its capacity. Returns how many went.
     */
    int apply(
            List<WorldStreamObjectAssets.Digest> named,
            Map<WorldStreamObjectAssets.Digest, Received<V>> received) {
        var file = new LinkedHashSet<>(named);
        for (var digest : file) {
            var held = entries.remove(digest);
            if (held != null) {
                entries.put(digest, held); // now the most recently named
                continue;
            }
            var add = received.get(digest);
            if (add == null) {
                throw new IllegalArgumentException("Asset neither held nor received");
            }
            entries.put(digest, new Entry<>(add.bytes(), add.value()));
            bytes += add.bytes();
            hash ^= mix(digest);
        }
        int evicted = 0;
        if (applied) {
            throw new IllegalStateException("One file per command");
        }
        Iterator<Map.Entry<WorldStreamObjectAssets.Digest, Entry<V>>> oldest =
                entries.entrySet().iterator();
        while (bytes > capacity && oldest.hasNext()) {
            var next = oldest.next();
            if (file.contains(next.getKey())) {
                break; // the rest are this file's own, the most recently named
            }
            oldest.remove();
            bytes -= next.getValue().bytes;
            hash ^= mix(next.getKey());
            evicted++;
        }
        applied = true;
        this.evicted = evicted;
        return evicted;
    }

    /** An asset an objects command delivered: its payload bytes and decoded value. */
    record Received<V>(long bytes, V value) {
        Received {
            if (bytes < 0) {
                throw new IllegalArgumentException("Asset bytes");
            }
        }
    }

    /** What the store holds now, and whether the current command applied a file. */
    Report report() {
        return new Report(epoch, applied, evicted, entries.size(), bytes, hash);
    }

    long bytes() {
        return bytes;
    }

    int size() {
        return entries.size();
    }

    /** The order a file names its assets: referenced models, then referenced images. */
    static List<WorldStreamObjectAssets.Digest> order(
            WorldStreamObjectsFile.References references) {
        Objects.requireNonNull(references);
        var out = new java.util.ArrayList<WorldStreamObjectAssets.Digest>(references.count());
        out.addAll(List.of(references.modelDigests()));
        out.addAll(List.of(references.imageDigests()));
        return out;
    }

    /** An order-free fingerprint of a digest (XOR of these over the held assets). */
    static long mix(WorldStreamObjectAssets.Digest digest) {
        long z = digest.high() ^ Long.rotateLeft(digest.low(), 29) ^ 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
