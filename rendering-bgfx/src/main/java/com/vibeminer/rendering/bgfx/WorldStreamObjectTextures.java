package com.vibeminer.rendering.bgfx;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The session's object textures, shared across object sets by image identity and reference counted,
 * so an image that many tiles draw costs one bgfx texture handle, one upload and its GPU bytes once
 * per session instead of once per tile. Owner thread only; no bgfx call except through the {@link
 * Destroyer} it is given.
 *
 * <p>Identity is exact ({@link Key}): the upload format, the top level's size and a digest of the
 * chain's stored bytes. Two tiles share a texture only when they upload the same level of the same
 * image in the same format, so a set samples exactly the chain its own pixel budget chose and every
 * set's byte charge is what it would have uploaded alone; the bytes of a shared texture are charged
 * once, for as long as any set holds it.
 *
 * <p>A set plans its images ({@link #plan}), then holds one reference per image ({@link #hold}): an
 * image another set already uploaded completely takes a reference to that texture; the first of the
 * set's images with an identity no complete texture has reserves a new entry, which this set
 * creates and fills; a later image of the same set with that identity takes a reference to that
 * entry. An entry is shared with other sets only once {@link #complete}: a texture still being
 * filled belongs to the one set filling it, so a failed or cancelled build destroys only its own
 * partly filled textures. Each reference is released exactly once ({@link #release}); the last
 * release destroys the texture, created or not.
 *
 * <p>Two sets may fill the same identity at once, since neither texture is shared while filling.
 * The first to complete is shared; the other stays a spare that only its own set draws. When the
 * shared one is destroyed while a spare lives, the oldest spare is shared in its place, so later
 * sets reuse it instead of uploading the chain again.
 */
final class WorldStreamObjectTextures {
    private static final short INVALID = -1;

    /**
     * Exact identity of one uploaded chain: its upload format ({@link
     * com.vibeminer.rendering.world.ObjectTextureFormats}), top-level width and height, and the
     * first 128 bits of the SHA-256 of its stored format, size and chain bytes.
     */
    record Key(int format, int width, int height, long high, long low) {}

    /** Destroys one created bgfx texture. */
    @FunctionalInterface
    interface Destroyer {
        void destroy(short handle);
    }

    /** One texture of the session, created or reserved. */
    static final class Entry {
        final Key key;

        /** Full GPU bytes of the chain, reserved against the object budget until destroyed. */
        final long bytes;

        private short handle = INVALID;
        private long issued;
        private int references;
        private boolean complete, mapped;

        private Entry(Key key, long bytes) {
            this.key = key;
            this.bytes = bytes;
        }

        /** The bgfx handle, or -1 while the creating set has not created it. */
        short handle() {
            return handle;
        }

        boolean complete() {
            return complete;
        }

        int references() {
            return references;
        }
    }

    /**
     * What a set's images need: per image the complete texture it shares ({@code shared}), or the
     * earlier image of the same set it repeats ({@code repeats}, -1 when it is the first); the
     * handles and GPU bytes of the entries it would create.
     */
    record Plan(
            Key[] keys, long[] bytes, Entry[] shared, int[] repeats, int handles, long newBytes) {
        /** Whether image {@code image} is one this set creates and fills. */
        boolean creates(int image) {
            return shared[image] == null && repeats[image] < 0;
        }
    }

    private final Destroyer destroyer;
    private final Map<Key, Entry> completed = new HashMap<>();

    /** Complete entries of an identity another entry already shares, oldest first. */
    private final Map<Key, ArrayDeque<Entry>> spares = new HashMap<>();

    private int entries, uncreated, references;
    private long reservedBytes, issuedBytes, sharedReferences;

    WorldStreamObjectTextures(Destroyer destroyer) {
        this.destroyer = Objects.requireNonNull(destroyer);
    }

    /** Plans a set's images without holding anything; {@code bytes} are their full chain sizes. */
    Plan plan(Key[] keys, long[] bytes) {
        if (keys.length != bytes.length) {
            throw new IllegalArgumentException("One size per image");
        }
        var shared = new Entry[keys.length];
        var repeats = new int[keys.length];
        var first = new HashMap<Key, Integer>();
        int handles = 0;
        long need = 0;
        for (int i = 0; i < keys.length; i++) {
            var key = Objects.requireNonNull(keys[i]);
            if (bytes[i] < 0) {
                throw new IllegalArgumentException("Image bytes");
            }
            repeats[i] = -1;
            var found = completed.get(key);
            if (found != null) {
                shared[i] = found;
                continue;
            }
            Integer earlier = first.putIfAbsent(key, i);
            if (earlier != null) {
                repeats[i] = earlier;
            } else {
                handles++;
                need += bytes[i];
            }
        }
        return new Plan(keys.clone(), bytes.clone(), shared, repeats, handles, need);
    }

    /**
     * Holds one reference per image of a plan made since the last change of this cache: shared
     * textures are referenced, new entries reserved (one reference each, uncreated) and repeats
     * reference the entry of their first image.
     */
    Entry[] hold(Plan plan) {
        for (var entry : plan.shared()) {
            if (entry != null && (entry.references <= 0 || !entry.mapped)) {
                // Checked before anything is held, so a refused plan changes nothing.
                throw new IllegalStateException("Stale texture plan");
            }
        }
        var held = new Entry[plan.keys().length];
        for (int i = 0; i < held.length; i++) {
            Entry entry;
            if (plan.shared()[i] != null) {
                entry = plan.shared()[i];
                sharedReferences++;
            } else if (plan.repeats()[i] >= 0) {
                entry = held[plan.repeats()[i]];
            } else {
                entry = new Entry(plan.keys()[i], plan.bytes()[i]);
                entries++;
                uncreated++;
                reservedBytes += entry.bytes;
            }
            entry.references++;
            references++;
            held[i] = entry;
        }
        return held;
    }

    /** Records the handle the creating set made for {@code entry}. */
    void created(Entry entry, short handle) {
        if (entry.handle != INVALID || entry.references <= 0 || handle == INVALID) {
            throw new IllegalStateException("Texture created twice or released");
        }
        entry.handle = handle;
        uncreated--;
    }

    /** Counts bytes the creating set issued into {@code entry}'s chain. */
    void issued(Entry entry, long bytes) {
        if (entry.handle == INVALID || entry.complete || bytes < 0) {
            throw new IllegalStateException("Texture not being filled");
        }
        entry.issued += bytes;
        issuedBytes += bytes;
    }

    /**
     * Marks {@code entry}'s chain fully issued; later sets share it. If another set completed a
     * texture of the same identity meanwhile, this one is a spare, private to its holders until
     * that one is destroyed.
     */
    void complete(Entry entry) {
        if (entry.handle == INVALID || entry.complete) {
            throw new IllegalStateException("Texture not being filled");
        }
        entry.complete = true;
        if (completed.putIfAbsent(entry.key, entry) == null) {
            entry.mapped = true;
        } else {
            spares.computeIfAbsent(entry.key, key -> new ArrayDeque<>()).add(entry);
        }
    }

    /** Releases one reference; the last destroys the texture and frees its reservation. */
    void release(Entry entry) {
        if (entry.references <= 0) {
            throw new IllegalStateException("Texture released twice");
        }
        entry.references--;
        references--;
        if (entry.references > 0) {
            return;
        }
        entries--;
        reservedBytes -= entry.bytes;
        issuedBytes -= entry.issued;
        entry.issued = 0;
        if (entry.mapped) {
            completed.remove(entry.key);
            entry.mapped = false;
            // A spare of the same identity is shared in its place.
            var waiting = spares.get(entry.key);
            if (waiting != null) {
                var spare = waiting.removeFirst();
                if (waiting.isEmpty()) {
                    spares.remove(entry.key);
                }
                spare.mapped = true;
                completed.put(spare.key, spare);
            }
        } else if (entry.complete) {
            var waiting = spares.get(entry.key);
            waiting.remove(entry);
            if (waiting.isEmpty()) {
                spares.remove(entry.key);
            }
        }
        if (entry.handle == INVALID) {
            uncreated--;
        } else {
            short handle = entry.handle;
            entry.handle = INVALID;
            destroyer.destroy(handle);
        }
    }

    /** Texture entries alive, created or reserved: the object sets' texture handles. */
    int entries() {
        return entries;
    }

    /** Entries reserved whose texture is not created yet. */
    int uncreated() {
        return uncreated;
    }

    /** Image references held by all sets; at least {@link #entries()}. */
    int references() {
        return references;
    }

    /** Full GPU bytes of every entry, charged once however many sets hold it. */
    long reservedBytes() {
        return reservedBytes;
    }

    /** GPU bytes issued into every entry so far. */
    long issuedBytes() {
        return issuedBytes;
    }

    /** Image references this session served by a texture another set had uploaded. */
    long sharedReferences() {
        return sharedReferences;
    }
}
