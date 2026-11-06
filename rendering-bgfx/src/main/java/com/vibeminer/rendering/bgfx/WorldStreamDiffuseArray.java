package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bookkeeping and pixels for the shared terrain layer array of shader-side splatting (issue #174
 * step 2). Every layer is one diffuse as RGBA8 at {@link #SIDE} with a full mip chain; a fixed
 * {@link #CAPACITY} of layers is shared by all resident tiles, keyed by diffuse file id and content
 * key. A layer referenced by any slot is never evicted; unreferenced layers stay cached and are
 * reused least recently used first. When no layer is free the caller falls back to the baked atlas
 * for that chunk. No bgfx calls: the renderer owns the GPU array.
 */
final class WorldStreamDiffuseArray {
    static final int SIDE = 128, LEVELS = 8, CAPACITY = 256;

    /** One layer with its mip chain, 128 x 128 down to 1 x 1: 87,380 bytes. */
    static final int LAYER_BYTES = chainBytes();

    /** The whole array: 256 layers, about 21.3 MiB. */
    static final long ARRAY_BYTES = (long) LAYER_BYTES * CAPACITY;

    /** A diffuse identity: file id and lowercase hex content key. */
    record Key(long fileId, String contentKey) {
        Key {
            Objects.requireNonNull(contentKey);
            if (fileId <= 0 || fileId > 0xffff_ffffL || !contentKey.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Invalid diffuse key");
            }
        }
    }

    /** Layers a slot resolved: one index per requested key, -1 when no layer was free. */
    record Resolution(int[] layers, List<Integer> uploads) {}

    private final HashMap<Key, Integer> layers = new HashMap<>();
    private final Key[] keys = new Key[CAPACITY];
    private final HashMap<Integer, Set<Integer>> references = new HashMap<>();
    // Unreferenced resident layers, least recently released first.
    private final LinkedHashMap<Integer, Boolean> idle = new LinkedHashMap<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final HashMap<Integer, Set<Integer>> slots = new HashMap<>();

    WorldStreamDiffuseArray() {
        for (int i = 0; i < CAPACITY; i++) {
            free.add(i);
        }
    }

    static int side(int level) {
        return SIDE >> level;
    }

    static int offset(int level) {
        int bytes = 0;
        for (int i = 0; i < level; i++) {
            bytes += side(i) * side(i) * 4;
        }
        return bytes;
    }

    private static int chainBytes() {
        return offset(LEVELS);
    }

    /**
     * Binds {@code slot} to the layers of {@code wanted} (its previous binding is released first).
     * Already resident layers are reused; new ones take a free layer or evict the least recently
     * used unreferenced one and are listed for upload. A key that finds no layer gets -1.
     */
    Resolution bind(int slot, List<Key> wanted) {
        Objects.requireNonNull(wanted);
        for (var key : wanted) {
            Objects.requireNonNull(key);
        }
        // Pin the resident layers this slot keeps before releasing its old binding, so a
        // rebinding never evicts a layer it is about to reuse.
        var bound = new HashSet<Integer>();
        for (var key : wanted) {
            Integer layer = layers.get(key);
            if (layer != null && bound.add(layer)) {
                idle.remove(layer);
                references.computeIfAbsent(layer, ignored -> new HashSet<>()).add(slot);
            }
        }
        var previous = slots.remove(slot);
        if (previous != null) {
            for (int layer : previous) {
                if (!bound.contains(layer)) {
                    unreference(layer, slot);
                }
            }
        }
        int[] resolved = new int[wanted.size()];
        var uploads = new ArrayList<Integer>();
        for (int i = 0; i < resolved.length; i++) {
            var key = wanted.get(i);
            Integer layer = layers.get(key);
            if (layer == null) {
                layer = take();
                if (layer == null) {
                    resolved[i] = -1;
                    continue;
                }
                layers.put(key, layer);
                keys[layer] = key;
                uploads.add(layer);
                references.computeIfAbsent(layer, ignored -> new HashSet<>()).add(slot);
                bound.add(layer);
            }
            resolved[i] = layer;
        }
        if (!bound.isEmpty()) {
            slots.put(slot, bound);
        }
        return new Resolution(resolved, List.copyOf(uploads));
    }

    private void unreference(int layer, int slot) {
        var holders = references.get(layer);
        holders.remove(slot);
        if (holders.isEmpty()) {
            references.remove(layer);
            idle.put(layer, Boolean.TRUE);
        }
    }

    /** Drops every reference of {@code slot}; its layers stay cached until evicted. */
    void release(int slot) {
        var bound = slots.remove(slot);
        if (bound == null) {
            return;
        }
        for (int layer : bound) {
            unreference(layer, slot);
        }
    }

    /**
     * Forgets a layer whose upload failed: it becomes free again and no key resolves to it.
     * Referencing slots must be rebound by the caller.
     */
    void discard(int layer) {
        var key = keys[layer];
        if (key == null) {
            return;
        }
        layers.remove(key);
        keys[layer] = null;
        idle.remove(layer);
        var holders = references.remove(layer);
        if (holders != null) {
            for (int slot : holders) {
                var bound = slots.get(slot);
                bound.remove(layer);
                if (bound.isEmpty()) {
                    slots.remove(slot);
                }
            }
        }
        free.add(layer);
    }

    private Integer take() {
        if (!free.isEmpty()) {
            return free.poll();
        }
        var oldest = idle.keySet().iterator();
        if (!oldest.hasNext()) {
            return null;
        }
        int layer = oldest.next();
        oldest.remove();
        layers.remove(keys[layer]);
        keys[layer] = null;
        return layer;
    }

    Key key(int layer) {
        return keys[layer];
    }

    int resident() {
        return layers.size();
    }

    int referenced() {
        return references.size();
    }

    /**
     * Builds one layer's chain from a square power-of-two RGBA8 diffuse of side 1 to {@link #SIDE}:
     * smaller sources are enlarged by repetition-aware bilinear filtering (the diffuse tiles across
     * a chunk), then each level is a 2 x 2 box average of the previous one.
     */
    static ByteBuffer chain(ByteBuffer rgba, int side) {
        Objects.requireNonNull(rgba);
        if (side < 1
                || side > SIDE
                || Integer.bitCount(side) != 1
                || rgba.remaining() != side * side * 4) {
            throw new IllegalArgumentException("Invalid layer diffuse");
        }
        var out = ByteBuffer.allocate(LAYER_BYTES);
        int base = rgba.position();
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                // Sample at the target texel center, wrapping, in source texel space.
                double sx = (x + .5) * side / SIDE - .5, sy = (y + .5) * side / SIDE - .5;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                double fx = sx - x0, fy = sy - y0;
                for (int c = 0; c < 4; c++) {
                    double value = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int tx = Math.floorMod(x0 + dx, side),
                                    ty = Math.floorMod(y0 + dy, side);
                            double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                            value += weight * (rgba.get(base + (ty * side + tx) * 4 + c) & 255);
                        }
                    }
                    out.put((y * SIDE + x) * 4 + c, (byte) Math.round(value));
                }
            }
        }
        for (int level = 1; level < LEVELS; level++) {
            int to = offset(level), from = offset(level - 1), s = side(level), child = s * 2;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    for (int c = 0; c < 4; c++) {
                        int sum = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                sum +=
                                        out.get(from + ((2 * y + dy) * child + 2 * x + dx) * 4 + c)
                                                & 255;
                            }
                        }
                        out.put(to + (y * s + x) * 4 + c, (byte) ((sum + 2) / 4));
                    }
                }
            }
        }
        return out;
    }
}
