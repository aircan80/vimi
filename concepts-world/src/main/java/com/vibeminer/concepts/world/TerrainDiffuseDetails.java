package com.vibeminer.concepts.world;

import com.vibeminer.rendering.world.TerrainLayerBlocks;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Bounded least-recently-used cache of terrain layer diffuses prepared for shader-side splatting
 * (issue #174 step 2). One entry is one diffuse file as a {@link TerrainLayerBlocks} BC1 chain (256
 * x 256 down to 1 x 1), keyed by its file id and content key, so tiles that share a texture read it
 * once. Entries are immutable and retain only their own blocks.
 */
final class TerrainDiffuseDetails {
    /** Side of every prepared layer; the renderer's layer array is this size. */
    static final int SIDE = TerrainLayerBlocks.SIDE;

    static final int MAX_ENTRIES = 256;

    /** Every entry: 256 x 43,704 bytes, about 10.7 MiB of retained blocks. */
    static final long MAX_BYTES = (long) MAX_ENTRIES * TerrainLayerBlocks.CHAIN_BYTES;

    /**
     * One prepared layer diffuse: its BC1 chain, and whether that chain is the file's own blocks
     * ({@code fileBlocks}) or an encoding of decoded pixels.
     */
    record Detail(long fileId, String contentKey, byte[] chain, boolean fileBlocks) {
        Detail {
            Objects.requireNonNull(contentKey);
            Objects.requireNonNull(chain);
            if (fileId <= 0
                    || fileId > 0xffff_ffffL
                    || !contentKey.matches("[0-9a-f]{32}")
                    || chain.length != TerrainLayerBlocks.CHAIN_BYTES) {
                throw new IllegalArgumentException("Invalid terrain diffuse detail");
            }
            chain = chain.clone();
        }

        /** A copy: the cached blocks stay immutable. */
        @Override
        public byte[] chain() {
            return chain.clone();
        }

        /** Read-only view; the array is owned by this immutable entry. */
        ByteBuffer blocks() {
            return ByteBuffer.wrap(chain).asReadOnlyBuffer();
        }

        long bytes() {
            return chain.length;
        }
    }

    private final LinkedHashMap<String, Detail> entries = new LinkedHashMap<>(64, .75f, true);
    private long bytes;
    private long hits, misses;

    static String key(long fileId, String contentKey) {
        return fileId + ":" + contentKey;
    }

    synchronized Detail get(long fileId, String contentKey) {
        var hit = entries.get(key(fileId, contentKey));
        if (hit == null) {
            misses++;
        } else {
            hits++;
        }
        return hit;
    }

    /** Publishes a prepared detail, evicting least recently used entries to stay in bounds. */
    synchronized Detail put(Detail detail) {
        Objects.requireNonNull(detail);
        String key = key(detail.fileId(), detail.contentKey());
        var previous = entries.remove(key);
        if (previous != null) {
            bytes -= previous.bytes();
        }
        entries.put(key, detail);
        bytes += detail.bytes();
        var oldest = entries.entrySet().iterator();
        while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            var evicted = oldest.next().getValue();
            oldest.remove();
            bytes -= evicted.bytes();
        }
        return detail;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }
}
