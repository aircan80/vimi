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
 * The shareable assets of an objects file (#380 stage 4): each model and each image's mip chain,
 * identified by a digest of everything the helper would upload and draw for it, so two tiles' files
 * that carry the same model or chain name the same asset.
 *
 * <ul>
 *   <li>A model ({@link Kind#MODEL}): everything the file carries for it except its draw ranges, in
 *       tagged sections so no two sections can be mistaken for each other: its meshes (vertex and
 *       index counts, vertices, vertex colours, mesh-relative indices, portal-group ordinals), its
 *       portal set (#383), its animation entry (#384) and its meshes' liquids. These are the
 *       model's own wherever it repeats, so including them costs no sharing. The draw ranges are
 *       not part of it: which ranges a tile draws, and with which of its image slots, is the tile's
 *       own.
 *   <li>An image's chain ({@link Kind#IMAGE}): the same identity the helper shares textures by
 *       ({@link WorldStreamPackedObjects#identity}): its stored format, top-level size and chain
 *       bytes.
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

    /** Section tags of a model digest, each followed by its section's length in values. */
    private static final int MESHES = 1, PORTALS = 2, ANIMATION = 3, LIQUID = 4;

    /** Every model and image of {@code data}, in file order. */
    static List<Asset> of(WorldStreamObjectsFile.Data data) {
        var hasher = new Hasher();
        var assets = new ArrayList<Asset>(data.modelCount() + data.imageCount());
        for (int m = 0; m < data.modelCount(); m++) {
            assets.add(model(hasher, data, m));
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
                            hasher.digest, format, width, height, data.pixels, from, stored);
            assets.add(new Asset(Kind.IMAGE, new Digest(id[0], id[1]), stored));
            from += stored;
        }
        return assets;
    }

    /** One digest and one little-endian scratch buffer, reused for every value written. */
    static final class Hasher {
        final MessageDigest digest = sha256();
        private ByteBuffer scratch = ByteBuffer.allocate(64 << 10).order(ByteOrder.LITTLE_ENDIAN);
        private long bytes;

        private ByteBuffer room(int need) {
            if (scratch.remaining() < need) {
                flush();
                if (scratch.capacity() < need) {
                    scratch = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN);
                }
            }
            return scratch;
        }

        private void flush() {
            digest.update(scratch.array(), 0, scratch.position());
            scratch.clear();
        }

        void ints(int... values) {
            for (int v : values) {
                room(4).putInt(v);
            }
            bytes += values.length * 4L;
        }

        void ints(int[] values, int from, int count) {
            for (int i = from; i < from + count; i++) {
                room(4).putInt(values[i]);
            }
            bytes += count * 4L;
        }

        void floats(float[] values, int from, int count) {
            for (int i = from; i < from + count; i++) {
                room(4).putFloat(values[i]);
            }
            bytes += count * 4L;
        }

        void shorts(short[] values, int from, int count) {
            for (int i = from; i < from + count; i++) {
                room(2).putShort(values[i]);
            }
            bytes += count * 2L;
        }

        void bytes(byte[] values) {
            room(4).putInt(values.length);
            flush();
            digest.update(values);
            bytes += values.length;
        }

        void start() {
            digest.reset();
            scratch.clear();
            bytes = 0;
        }

        Digest finish() {
            flush();
            var sum = ByteBuffer.wrap(digest.digest());
            return new Digest(sum.getLong(), sum.getLong());
        }

        long written() {
            return bytes;
        }
    }

    /** Model {@code m}'s asset: its meshes, portal set, animation entry and liquids. */
    static Asset model(Hasher h, WorldStreamObjectsFile.Data data, int m) {
        h.start();
        int first = data.models[m * 2], count = data.models[m * 2 + 1];
        h.ints(MESHES, count);
        for (int mesh = first; mesh < first + count; mesh++) {
            int at = mesh * 6;
            int vertex = data.meshes[at], vertices = data.meshes[at + 1];
            int index = data.meshes[at + 2], indices = data.meshes[at + 3];
            h.ints(vertices, indices, data.meshGroups[mesh]);
            h.floats(data.vertices, vertex * 8, vertices * 8);
            h.ints(data.colours, vertex, vertices);
            h.shorts(data.indices, index, indices);
        }
        portals(h, data, m);
        var animations = data.animations;
        for (int i = 0; i < animations.count(); i++) {
            if (animations.models[i] == m) {
                h.ints(ANIMATION, animations.sources[i].length);
                h.ints(animations.sources[i], 0, animations.sources[i].length);
                h.bytes(animations.animations[i]);
            }
        }
        var liquids = data.liquids;
        for (int i = 0; i < liquids.count(); i++) {
            int mesh = liquids.meshes[i];
            if (mesh < first || mesh >= first + count) {
                continue;
            }
            h.ints(
                    LIQUID,
                    mesh - first,
                    liquids.kinds[i],
                    liquids.colours[i],
                    liquids.grids[i * 2],
                    liquids.grids[i * 2 + 1]);
            h.floats(liquids.corners, i * 3, 3);
            h.floats(liquids.heights[i], 0, liquids.heights[i].length);
            h.bytes(liquids.live[i]);
        }
        var digest = h.finish();
        return new Asset(Kind.MODEL, digest, h.written());
    }

    /** Model {@code m}'s portal set, when it has one, with its tables' running offsets. */
    private static void portals(Hasher h, WorldStreamObjectsFile.Data data, int m) {
        int groups = 0, portals = 0, refs = 0, vertexFloats = 0;
        int setInts = WorldStreamObjectsFile.PORTAL_SET_INTS;
        for (int s = 0; s < data.portalSetCount(); s++) {
            int at = s * setInts;
            int model = data.portalSets[at], g = data.portalSets[at + 1];
            int p = data.portalSets[at + 2], v = data.portalSets[at + 3];
            int r = data.portalSets[at + 4];
            if (model == m) {
                h.ints(PORTALS, g, p, v, r);
                h.ints(
                        data.portalGroups,
                        groups * WorldStreamObjectsFile.PORTAL_GROUP_INTS,
                        g * WorldStreamObjectsFile.PORTAL_GROUP_INTS);
                h.floats(data.portalBoxes, groups * 6, g * 6);
                h.ints(data.portalVertexCounts, portals, p);
                h.floats(data.portalPlanes, portals * 4, p * 4);
                h.floats(data.portalVertices, vertexFloats, v * 3);
                h.ints(
                        data.portalRefs,
                        refs * WorldStreamObjectsFile.PORTAL_REF_INTS,
                        r * WorldStreamObjectsFile.PORTAL_REF_INTS);
            }
            groups += g;
            portals += p;
            vertexFloats += v * 3;
            refs += r;
        }
    }

    /**
     * Records {@code data}'s assets in {@code ledger} only while stage timings are on, and returns
     * what it carried; null, digesting nothing, while they are off. The files do not need the
     * digests yet, so a shipped session never pays for them.
     */
    static Tally tallyIfMeasuring(Ledger ledger, WorldStreamObjectsFile.Data data) {
        if (!com.vibeminer.formats.world.WorldStageTimings.enabled()) {
            return null;
        }
        return ledger.record(of(data));
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
