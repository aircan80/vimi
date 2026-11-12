package com.vibeminer.concepts.world;

import com.vibeminer.formats.world.WorldStageTimings;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * The World session's source cache: one LRU bounded by bytes for the files World tiles read by
 * FileDataID and for what their readers parse from them, so a model, image or building group that
 * several tiles use is read once per session instead of once per tile.
 *
 * <p>Two kinds of entry share the budget:
 *
 * <ul>
 *   <li>{@link File}: a source file's complete bytes, with the content key and size the read bound
 *       them to. Only a complete read without holes is kept, so a hit is what a fresh read of the
 *       same build would return. Callers get a copy, so no reader can change what the next one
 *       sees.
 *   <li>Parsed values ({@link #parsed}): a reader's result for one file, keyed by the reader's
 *       namespace and revision as well as the FileDataID, so a reader whose output changes takes a
 *       new revision instead of reading another reader's entries.
 * </ul>
 *
 * <p>One cache belongs to one opened build ({@link WorldStreamScopes}, whose files it caches): a
 * FileDataID names one content key for the whole build, so entries never go stale while it is open.
 * A new build opens a new service and a new cache; closing the service empties it. Thread-safe.
 */
final class WorldSourceCache {
    /**
     * The budget, replacing walking collision's own 96 MiB. On 70862 the NEAR view around Stormwind
     * and then a flight out read 123 MiB of distinct files, with 62% of derived reads served from
     * the cache while filling and 63% on the flight; 256 MiB served exactly the same reads, and 64
     * MiB halved the flight's hits. Collision's parsed sources measured 35 MiB around Stormwind.
     */
    static final long BYTES = 128L << 20;

    /** What a file entry costs beyond its bytes: the key, the entry and the map node. */
    static final long ENTRY_BYTES = 128;

    /**
     * One entry's key: {@code kind} {@link #FILE} for a file's bytes, otherwise the reader's
     * namespace, with its {@code revision}.
     */
    record Key(String kind, int revision, long fileDataId) {
        Key {
            Objects.requireNonNull(kind);
        }
    }

    static final String FILE = "file";

    /** A complete source file as one read of this build bound it. */
    record File(String contentKey, byte[] bytes, Long declaredLength) {
        File {
            Objects.requireNonNull(contentKey);
            Objects.requireNonNull(bytes);
        }
    }

    private record Sized(Object value, long bytes) {}

    private final long budget;
    private final LinkedHashMap<Key, Sized> entries = new LinkedHashMap<>(256, .75f, true);
    private long held, peak, hits, misses, evictions;

    /** The key revision kept files were read under, once one was noted. */
    private long keys;

    private boolean keysKnown;

    WorldSourceCache() {
        this(BYTES);
    }

    /**
     * A cache that keeps nothing: every entry is over its budget. For tests of the paths a read
     * takes when a source is read again, which a kept file would otherwise answer.
     */
    static WorldSourceCache off() {
        return new WorldSourceCache(1);
    }

    WorldSourceCache(long budget) {
        if (budget < 1) {
            throw new IllegalArgumentException("Source cache budget");
        }
        this.budget = budget;
    }

    /** A copy of the file's bytes with its binding, or null when the cache does not hold it. */
    File file(long fileDataId) {
        Sized sized;
        synchronized (this) {
            sized = entries.get(new Key(FILE, 0, fileDataId));
            if (sized == null) {
                misses++;
                WorldStageTimings.add("source.cache.fileMiss", 1);
                return null;
            }
            hits++;
        }
        WorldStageTimings.add("source.cache.fileHit", 1);
        var file = (File) sized.value();
        return new File(file.contentKey(), file.bytes().clone(), file.declaredLength());
    }

    /**
     * Keeps a copy of a complete file's bytes, read under decryption key revision {@code
     * keyRevision}; not kept when the keys changed since ({@link #keyRevision}), as the bytes may
     * then be ones the current keys no longer give.
     */
    void putFile(long fileDataId, File file, long keyRevision) {
        Objects.requireNonNull(file);
        // The copy outside the lock; the revision check and the insert in one critical section
        // (put), so no file read under dropped keys can be kept after keyRevision dropped them.
        var copy = new File(file.contentKey(), file.bytes().clone(), file.declaredLength());
        put(new Key(FILE, 0, fileDataId), copy, copy.bytes().length + ENTRY_BYTES, keyRevision);
    }

    /**
     * Notes the build's decryption key revision ({@code BuildFiles#keyRevision}) before a file is
     * looked up or read. When it changed, every kept file is dropped in the same critical section:
     * an encrypted file read with keys since removed must not be served, and kept files do not
     * record whether they were encrypted.
     *
     * <p>Parsed values are not dropped: a reader's parse of an encrypted file stays served after
     * its keys are removed. Readers forget only what they choose to (walking collision forgets the
     * absences it found for lack of keys, so a file it could not read is tried again).
     */
    synchronized void keyRevision(long revision) {
        if (keysKnown && revision == keys) {
            return;
        }
        boolean changed = keysKnown;
        keys = revision;
        keysKnown = true;
        if (changed) {
            removeIf((key, value) -> FILE.equals(key.kind()));
        }
    }

    /** A reader's parsed value, or null. The value is shared: parsed values must be immutable. */
    synchronized Object parsed(Key key) {
        if (FILE.equals(key.kind())) {
            throw new IllegalArgumentException("Not a parsed key");
        }
        var sized = entries.get(key);
        return sized == null ? null : sized.value();
    }

    /** Keeps a reader's immutable parsed value, charged {@code bytes}. */
    void putParsed(Key key, Object value, long bytes) {
        if (FILE.equals(key.kind())) {
            throw new IllegalArgumentException("Not a parsed key");
        }
        put(key, Objects.requireNonNull(value), bytes, null);
    }

    /** Drops every entry the test accepts. */
    synchronized void removeIf(BiPredicate<Key, Object> test) {
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (test.test(entry.getKey(), entry.getValue().value())) {
                held -= entry.getValue().bytes();
                it.remove();
            }
        }
    }

    /** Empties the cache. */
    synchronized void clear() {
        entries.clear();
        held = 0;
    }

    synchronized int entries() {
        return entries.size();
    }

    /** Bytes held now. */
    synchronized long bytes() {
        return held;
    }

    /** The most bytes held at once. */
    synchronized long peakBytes() {
        return peak;
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }

    synchronized long evictions() {
        return evictions;
    }

    /** Entries of one kind. */
    synchronized int entries(String kind) {
        int count = 0;
        for (var key : entries.keySet()) {
            if (key.kind().equals(kind)) {
                count++;
            }
        }
        return count;
    }

    /** Bytes held by entries of one kind. */
    synchronized long bytes(String kind) {
        long sum = 0;
        for (var entry : entries.entrySet()) {
            if (entry.getKey().kind().equals(kind)) {
                sum += entry.getValue().bytes();
            }
        }
        return sum;
    }

    /**
     * Inserts an entry; with {@code keyRevision}, only while the noted key revision is still that
     * one, checked in the same critical section as the insert.
     */
    private void put(Key key, Object value, long bytes, Long keyRevision) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Entry bytes");
        }
        long held;
        synchronized (this) {
            if (keyRevision != null && keysKnown && keyRevision != keys) {
                return; // read under keys since changed
            }
            if (bytes > budget) {
                return; // never displaces the whole cache for one entry
            }
            var previous = entries.put(key, new Sized(value, bytes));
            if (previous != null) {
                this.held -= previous.bytes();
            }
            this.held += bytes;
            var it = entries.entrySet().iterator();
            while (this.held > budget && it.hasNext()) {
                var eldest = it.next();
                if (eldest.getKey().equals(key)) {
                    continue;
                }
                this.held -= eldest.getValue().bytes();
                it.remove();
                evictions++;
            }
            peak = Math.max(peak, this.held);
            held = this.held;
        }
        WorldStageTimings.value("source.cache.heldMiB", held >> 20);
    }
}
