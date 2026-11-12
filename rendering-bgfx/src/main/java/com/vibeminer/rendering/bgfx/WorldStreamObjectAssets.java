package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The shareable assets of an objects file (#380 stage 4): each model's geometry and each image's
 * mip chain, identified by a digest of exactly the bytes the helper would upload for it, so two
 * tiles' files that carry the same geometry or chain name the same asset.
 *
 * <ul>
 *   <li>A model's geometry: its meshes' vertex and index counts, vertices, vertex colours,
 *       mesh-relative indices and portal-group ordinals. Its draw ranges are not part of it: which
 *       ranges a tile draws, and with which of its image slots, is the tile's own.
 *   <li>An image's chain: the same identity the helper shares textures by ({@link
 *       WorldStreamPackedObjects#identity}): its stored format, top-level size and chain bytes.
 * </ul>
 *
 * <p>{@link Ledger} counts, per transport session, the asset bytes each delivered file carries and
 * how many of them no earlier file of the session carried: what a reference-based file would no
 * longer send. Nothing here changes what is sent.
 */
final class WorldStreamObjectAssets {
    private WorldStreamObjectAssets() {}

    enum Kind {
        MODEL,
        IMAGE
    }

    /** The first 128 bits of an asset's SHA-256. */
    record Digest(long high, long low) {}

    /** One asset of a file: its kind, digest and the bytes it costs in the file. */
    record Asset(Kind kind, Digest digest, long bytes) {
        Asset {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(digest);
            if (bytes < 0) {
                throw new IllegalArgumentException("Asset bytes");
            }
        }
    }

    /** A vertex in the file: 8 floats (position, normal, uv). */
    static final int VERTEX_BYTES = 32;

    /** Every model and image of {@code data}, in file order. */
    static List<Asset> of(WorldStreamObjectsFile.Data data) {
        var digest = sha256();
        var assets = new ArrayList<Asset>(data.modelCount() + data.imageCount());
        for (int m = 0; m < data.modelCount(); m++) {
            assets.add(model(digest, data, m));
        }
        int from = 0;
        for (int i = 0; i < data.imageCount(); i++) {
            int width = data.images[i * 3], height = data.images[i * 3 + 1];
            int format = data.images[i * 3 + 2];
            int stored =
                    com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                            format, width, height);
            long[] id =
                    WorldStreamPackedObjects.identity(
                            digest, format, width, height, data.pixels, from, stored);
            assets.add(new Asset(Kind.IMAGE, new Digest(id[0], id[1]), stored));
            from += stored;
        }
        return assets;
    }

    /** Model {@code m}'s geometry asset. */
    static Asset model(MessageDigest digest, WorldStreamObjectsFile.Data data, int m) {
        digest.reset();
        int first = data.models[m * 2], count = data.models[m * 2 + 1];
        long bytes = 0;
        var head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(count);
        digest.update(head.array(), 0, 4);
        for (int mesh = first; mesh < first + count; mesh++) {
            int at = mesh * 6;
            int vertex = data.meshes[at], vertices = data.meshes[at + 1];
            int index = data.meshes[at + 2], indices = data.meshes[at + 3];
            head.clear();
            head.putInt(vertices).putInt(indices).putInt(data.meshGroups[mesh]);
            digest.update(head.array(), 0, 12);
            var floats =
                    ByteBuffer.allocate(vertices * VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            floats.asFloatBuffer().put(data.vertices, vertex * 8, vertices * 8);
            digest.update(floats.array());
            var colours = ByteBuffer.allocate(vertices * 4).order(ByteOrder.LITTLE_ENDIAN);
            colours.asIntBuffer().put(data.colours, vertex, vertices);
            digest.update(colours.array());
            var shorts = ByteBuffer.allocate(indices * 2).order(ByteOrder.LITTLE_ENDIAN);
            shorts.asShortBuffer().put(data.indices, index, indices);
            digest.update(shorts.array());
            bytes += vertices * (long) (VERTEX_BYTES + 4) + indices * 2L;
        }
        var sum = ByteBuffer.wrap(digest.digest());
        return new Asset(Kind.MODEL, new Digest(sum.getLong(), sum.getLong()), bytes);
    }

    /** Counts of one delivered file's assets: all of them, and those new to the session. */
    record Tally(
            int models,
            int newModels,
            long modelBytes,
            long newModelBytes,
            int images,
            int newImages,
            long imageBytes,
            long newImageBytes) {}

    /**
     * The assets one transport session's delivered files carried. A new session starts a new
     * ledger, as a new helper starts with nothing. Thread-safe.
     */
    static final class Ledger {
        private final Set<Digest> models = new HashSet<>(), images = new HashSet<>();

        /** Records one delivered file's assets and returns what it carried. */
        synchronized Tally record(List<Asset> assets) {
            int m = 0, newM = 0, i = 0, newI = 0;
            long mb = 0, newMb = 0, ib = 0, newIb = 0;
            for (var asset : assets) {
                if (asset.kind() == Kind.MODEL) {
                    m++;
                    mb += asset.bytes();
                    if (models.add(asset.digest())) {
                        newM++;
                        newMb += asset.bytes();
                    }
                } else {
                    i++;
                    ib += asset.bytes();
                    if (images.add(asset.digest())) {
                        newI++;
                        newIb += asset.bytes();
                    }
                }
            }
            return new Tally(m, newM, mb, newMb, i, newI, ib, newIb);
        }

        synchronized int distinct(Kind kind) {
            return kind == Kind.MODEL ? models.size() : images.size();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
