package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.TerrainLayerBlocks;
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
 * Bookkeeping and layout of the shared terrain layer array of shader-side splatting (issue #174
 * step 2). Every layer is one diffuse as a {@link TerrainLayerBlocks} BC1 chain at {@link #SIDE};
 * where the backend samples no BC1 array, it is decoded to RGBA8 at {@link #FALLBACK_SIDE} (its
 * levels 1 to 8). A fixed {@link #CAPACITY} of layers is shared by all resident tiles, keyed by
 * diffuse file id and content key. A layer referenced by any slot is never evicted; unreferenced
 * layers stay cached and are reused least recently used first. When no layer is free the caller
 * falls back to the baked atlas for that chunk. No bgfx calls: the renderer owns the GPU array.
 */
final class WorldStreamDiffuseArray {
    static final int SIDE = TerrainLayerBlocks.SIDE, LEVELS = TerrainLayerBlocks.LEVELS;

    /**
     * Layers 0 to 254: the packet's chunk table marks "no layer" with {@link
     * WorldStreamTileFile#SPLAT_NONE} (255), so 255 is never a layer.
     */
    static final int CAPACITY = WorldStreamTileFile.SPLAT_NONE;

    /** One BC1 layer with its mip chain, 256 x 256 down to 1 x 1: 43,704 bytes. */
    static final int LAYER_BYTES = TerrainLayerBlocks.CHAIN_BYTES;

    /** The whole BC1 array: 255 layers, about 10.6 MiB. */
    static final long ARRAY_BYTES = (long) LAYER_BYTES * CAPACITY;

    /** RGBA8 fallback layer: the BC1 chain's levels 1 to 8, 128 x 128 down to 1 x 1. */
    static final int FALLBACK_SIDE = SIDE / 2, FALLBACK_LEVELS = LEVELS - 1;

    /** One fallback layer: 87,380 bytes; the fallback array is about 21.3 MiB. */
    static final int FALLBACK_LAYER_BYTES = fallbackOffset(FALLBACK_LEVELS);

    static final long FALLBACK_ARRAY_BYTES = (long) FALLBACK_LAYER_BYTES * CAPACITY;

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

    /** Side of fallback level {@code level}: 128 down to 1. */
    static int fallbackSide(int level) {
        return FALLBACK_SIDE >> level;
    }

    /** Byte offset of fallback level {@code level} in its RGBA8 chain. */
    static int fallbackOffset(int level) {
        int bytes = 0;
        for (int i = 0; i < level; i++) {
            bytes += fallbackSide(i) * fallbackSide(i) * 4;
        }
        return bytes;
    }

    /** The RGBA8 fallback chain of one BC1 layer chain: its levels 1 to 8, decoded. */
    static ByteBuffer fallback(ByteBuffer chain) {
        Objects.requireNonNull(chain);
        if (chain.remaining() != LAYER_BYTES) {
            throw new IllegalArgumentException("Invalid layer chain");
        }
        var source = chain.slice();
        var out = ByteBuffer.allocate(FALLBACK_LAYER_BYTES);
        for (int level = 0; level < FALLBACK_LEVELS; level++) {
            int side = fallbackSide(level);
            out.put(
                    fallbackOffset(level),
                    ObjectTextureFormats.decodeLevel(
                            ObjectTextureFormats.BC1,
                            side,
                            side,
                            source,
                            TerrainLayerBlocks.levelOffset(level + 1)));
        }
        return out;
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

    /**
     * Releases every slot outside {@code live}: a slot whose resident a commit retired holds its
     * layers no longer, so they become cached and evictable. Returns the slots released.
     */
    List<Integer> retain(Set<Integer> live) {
        Objects.requireNonNull(live);
        var released = new ArrayList<Integer>();
        for (int slot : List.copyOf(slots.keySet())) {
            if (!live.contains(slot)) {
                release(slot);
                released.add(slot);
            }
        }
        return released;
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
}
