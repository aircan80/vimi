package com.vibeminer.concepts.world;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Bounded least-recently-used cache of terrain layer diffuses decoded for shader-side splatting
 * (issue #174 step 2). One entry is one diffuse file's largest declared mip within {@link
 * #MAX_SIDE}, keyed by its file id and content key, so tiles that share a texture decode it once.
 * Entries are immutable and retain only their own pixels.
 */
final class TerrainDiffuseDetails {
    /** Largest decoded side; the renderer's layer array is this size. */
    static final int MAX_SIDE = 128;

    static final int MAX_ENTRIES = 256;

    /** Every entry at {@link #MAX_SIDE}: 256 x 64 KiB = 16 MiB of retained pixels. */
    static final long MAX_BYTES = (long) MAX_ENTRIES * MAX_SIDE * MAX_SIDE * 4;

    /** One decoded layer diffuse: square power-of-two RGBA8, side 1 to {@link #MAX_SIDE}. */
    record Detail(long fileId, String contentKey, int side, byte[] rgba) {
        Detail {
            Objects.requireNonNull(contentKey);
            Objects.requireNonNull(rgba);
            if (fileId <= 0
                    || fileId > 0xffff_ffffL
                    || !contentKey.matches("[0-9a-f]{32}")
                    || side < 1
                    || side > MAX_SIDE
                    || Integer.bitCount(side) != 1
                    || rgba.length != side * side * 4) {
                throw new IllegalArgumentException("Invalid terrain diffuse detail");
            }
        }

        /** Read-only view; the array is owned by this immutable entry. */
        ByteBuffer pixels() {
            return ByteBuffer.wrap(rgba).asReadOnlyBuffer();
        }

        long bytes() {
            return rgba.length;
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

    /** Publishes a decoded detail, evicting least recently used entries to stay in bounds. */
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
