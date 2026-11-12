package com.vibeminer.rendering.bgfx;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The session's object model geometry (#380 stage 4), shared across object sets by model asset
 * ({@link WorldStreamObjectAssets}) and reference counted, so a model many tiles place costs one
 * vertex and one index buffer, one upload and its GPU bytes once per session instead of once per
 * tile. Owner thread only; no bgfx call except through the {@link Destroyer} it is given.
 *
 * <p>As with {@link WorldStreamObjectTextures}: a set plans its models ({@link #plan}), then holds
 * one reference per model ({@link #hold}): a model another set uploaded completely takes a
 * reference to it; the first model of the set with an asset no complete entry has reserves a new
 * entry, which this set creates and fills; a later model of the same set with that asset takes a
 * reference to that entry. An entry is shared only once {@link #complete}, so a failed or cancelled
 * build destroys only its own partly filled buffers. Two sets filling one asset at once each keep
 * their own; the first to complete is shared.
 *
 * <p>An entry whose last reference is released while it is shared is kept as a zombie: still
 * charged, still shareable, so a tile that comes back costs nothing. Zombies are destroyed oldest
 * first past {@link #zombieLimit} bytes, when the object budget needs room ({@link #evict}), or at
 * {@link #close}.
 */
final class WorldStreamObjectGeometry {
    private static final short INVALID = -1;

    /** Destroys one created entry's buffers; either handle may be -1. */
    @FunctionalInterface
    interface Destroyer {
        void destroy(short vertices, short indices);
    }

    /** One model's geometry of the session, created or reserved. */
    static final class Entry {
        final WorldStreamObjectAssets.Digest key;

        /** GPU bytes of its vertex and index buffers, reserved until destroyed. */
        final long bytes;

        private short vertices = INVALID, indices = INVALID;
        private int references;
        private boolean complete, mapped;

        private Entry(WorldStreamObjectAssets.Digest key, long bytes) {
            this.key = key;
            this.bytes = bytes;
        }

        short vertices() {
            return vertices;
        }

        short indices() {
            return indices;
        }

        boolean complete() {
            return complete;
        }

        int references() {
            return references;
        }
    }

    /**
     * What a set's models need: per model the complete entry it shares ({@code shared}), or the
     * earlier model of the same set with the same asset ({@code repeats}, -1 when it is the first),
     * and the GPU bytes of the entries it would create.
     */
    record Plan(
            WorldStreamObjectAssets.Digest[] keys,
            long[] bytes,
            Entry[] shared,
            int[] repeats,
            long newBytes) {
        /** Whether model {@code model} is one this set creates and fills. */
        boolean creates(int model) {
            return shared[model] == null && repeats[model] < 0;
        }
    }

    private final Destroyer destroyer;
    private final long zombieLimit;
    private final Map<WorldStreamObjectAssets.Digest, Entry> completed = new HashMap<>();

    /** Shared entries no set holds, least recently released first. */
    private final LinkedHashMap<WorldStreamObjectAssets.Digest, Entry> zombies =
            new LinkedHashMap<>();

    private int entries, references;
    private long reservedBytes, zombieBytes, sharedReferences, revived, evicted;

    WorldStreamObjectGeometry(Destroyer destroyer, long zombieLimit) {
        this.destroyer = Objects.requireNonNull(destroyer);
        if (zombieLimit < 0) {
            throw new IllegalArgumentException("Zombie limit");
        }
        this.zombieLimit = zombieLimit;
    }

    /** Plans a set's models without holding anything; {@code bytes} are their buffer sizes. */
    Plan plan(WorldStreamObjectAssets.Digest[] keys, long[] bytes) {
        if (keys.length != bytes.length) {
            throw new IllegalArgumentException("One size per model");
        }
        var shared = new Entry[keys.length];
        var repeats = new int[keys.length];
        var first = new HashMap<WorldStreamObjectAssets.Digest, Integer>();
        long need = 0;
        for (int m = 0; m < keys.length; m++) {
            var key = Objects.requireNonNull(keys[m]);
            if (bytes[m] < 0) {
                throw new IllegalArgumentException("Model bytes");
            }
            repeats[m] = -1;
            var found = completed.get(key);
            if (found != null) {
                shared[m] = found;
                continue;
            }
            Integer earlier = first.putIfAbsent(key, m);
            if (earlier != null) {
                repeats[m] = earlier;
            } else {
                need += bytes[m];
            }
        }
        return new Plan(keys.clone(), bytes.clone(), shared, repeats, need);
    }

    /**
     * Holds one reference per model of a plan made since the last change of this library: shared
     * entries are referenced (a zombie revived), new entries reserved and repeats reference the
     * entry of their first model.
     */
    Entry[] hold(Plan plan) {
        for (var entry : plan.shared()) {
            if (entry != null && (!entry.mapped || !entry.complete)) {
                throw new IllegalStateException("Stale geometry plan");
            }
        }
        var held = new Entry[plan.keys().length];
        for (int m = 0; m < held.length; m++) {
            Entry entry;
            if (plan.shared()[m] != null) {
                entry = plan.shared()[m];
                sharedReferences++;
                if (entry.references == 0 && zombies.remove(entry.key) != null) {
                    zombieBytes -= entry.bytes;
                    revived++;
                }
            } else if (plan.repeats()[m] >= 0) {
                entry = held[plan.repeats()[m]];
            } else {
                entry = new Entry(plan.keys()[m], plan.bytes()[m]);
                entries++;
                reservedBytes += entry.bytes;
            }
            entry.references++;
            references++;
            held[m] = entry;
        }
        return held;
    }

    /** Records the buffers the creating set made for {@code entry}. */
    void created(Entry entry, short vertices, short indices) {
        if (entry.vertices != INVALID || entry.references <= 0 || vertices == INVALID) {
            throw new IllegalStateException("Geometry created twice or released");
        }
        entry.vertices = vertices;
        entry.indices = indices;
    }

    /** Marks {@code entry} fully uploaded; later sets share it unless one already does. */
    void complete(Entry entry) {
        if (entry.vertices == INVALID || entry.complete) {
            throw new IllegalStateException("Geometry not being filled");
        }
        entry.complete = true;
        if (completed.putIfAbsent(entry.key, entry) == null) {
            entry.mapped = true;
        }
    }

    /**
     * Releases one reference. The last release keeps a shared entry as a zombie (then trims the
     * zombies to {@link #zombieLimit}) and destroys any other.
     */
    void release(Entry entry) {
        if (entry.references <= 0) {
            throw new IllegalStateException("Geometry released twice");
        }
        entry.references--;
        references--;
        if (entry.references > 0) {
            return;
        }
        if (entry.mapped && zombieLimit > 0) {
            zombies.put(entry.key, entry);
            zombieBytes += entry.bytes;
            while (zombieBytes > zombieLimit) {
                destroyOldestZombie();
            }
            return;
        }
        destroy(entry);
    }

    /** Destroys zombies, oldest first, until {@code needed} bytes were freed or none is left. */
    long evict(long needed) {
        long freed = 0;
        while (freed < needed && !zombies.isEmpty()) {
            freed += destroyOldestZombie();
        }
        return freed;
    }

    /**
     * As {@link #evict(long)}, but keeps the zombies {@code keep} shares: destroying one would free
     * nothing for that plan, whose set would then create it again.
     */
    long evict(long needed, Plan keep) {
        var kept =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Entry, Boolean>());
        for (var entry : keep.shared()) {
            if (entry != null) {
                kept.add(entry);
            }
        }
        long freed = 0;
        Iterator<Entry> oldest = zombies.values().iterator();
        while (freed < needed && oldest.hasNext()) {
            var entry = oldest.next();
            if (kept.contains(entry)) {
                continue;
            }
            oldest.remove();
            zombieBytes -= entry.bytes;
            evicted++;
            destroy(entry);
            freed += entry.bytes;
        }
        return freed;
    }

    private long destroyOldestZombie() {
        Iterator<Entry> oldest = zombies.values().iterator();
        var entry = oldest.next();
        oldest.remove();
        zombieBytes -= entry.bytes;
        evicted++;
        destroy(entry);
        return entry.bytes;
    }

    private void destroy(Entry entry) {
        entries--;
        reservedBytes -= entry.bytes;
        if (entry.mapped) {
            completed.remove(entry.key);
            entry.mapped = false;
        }
        short vertices = entry.vertices, indices = entry.indices;
        entry.vertices = INVALID;
        entry.indices = INVALID;
        if (vertices != INVALID || indices != INVALID) {
            destroyer.destroy(vertices, indices);
        }
    }

    /** Destroys every zombie; held entries are their holders' to release. */
    void close() {
        evict(Long.MAX_VALUE);
    }

    /** Entries alive, created, reserved or zombie. */
    int entries() {
        return entries;
    }

    /** Model references held by all sets. */
    int references() {
        return references;
    }

    /** GPU bytes of every entry, zombies included, charged once however many sets hold it. */
    long reservedBytes() {
        return reservedBytes;
    }

    /** GPU bytes of the zombies. */
    long zombieBytes() {
        return zombieBytes;
    }

    /** Model references served by geometry another set had uploaded. */
    long sharedReferences() {
        return sharedReferences;
    }

    /** Zombies taken back into use. */
    long revived() {
        return revived;
    }

    /** Zombies destroyed for room or past their limit. */
    long evicted() {
        return evicted;
    }
}
