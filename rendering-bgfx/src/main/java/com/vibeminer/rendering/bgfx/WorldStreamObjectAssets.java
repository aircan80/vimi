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

    /**
     * One asset of a file: its kind, digest and its payload's length ({@link #modelPayload}, {@link
     * #imagePayload}): the bytes an asset pack carries for it, its framing (section tags, counts
     * and length prefixes) included.
     */
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
            hasher.start();
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
            // The payload: format, width and height, then the chain (imagePayload).
            assets.add(new Asset(Kind.IMAGE, new Digest(id[0], id[1]), 12L + stored));
            from += stored;
        }
        return assets;
    }

    /** One digest and one little-endian scratch buffer, reused for every value written. */
    static final class Hasher {
        final MessageDigest digest = sha256();
        private ByteBuffer scratch = ByteBuffer.allocate(64 << 10).order(ByteOrder.LITTLE_ENDIAN);
        private long bytes;

        /** While capturing, every byte digested is also kept: the asset's payload. */
        private java.io.ByteArrayOutputStream capture;

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
            if (capture != null) {
                capture.write(scratch.array(), 0, scratch.position());
            }
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
            if (capture != null) {
                capture.writeBytes(values);
            }
            bytes += 4L + values.length;
        }

        void start() {
            digest.reset();
            scratch.clear();
            bytes = 0;
            capture = null;
        }

        /** As {@link #start}, keeping the payload for {@link #captured}. */
        void startCapture() {
            start();
            capture = new java.io.ByteArrayOutputStream();
        }

        /** The payload since {@link #startCapture}, after {@link #finish}. */
        byte[] captured() {
            return capture.toByteArray();
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

    /**
     * Model {@code m}'s asset: its meshes, portal set, animation entry and liquids, written to
     * {@code h} after its {@link Hasher#start} or {@link Hasher#startCapture}.
     */
    static Asset model(Hasher h, WorldStreamObjectsFile.Data data, int m) {
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

    /** One asset with its payload: what an asset pack carries for it. */
    record Payload(Kind kind, Digest digest, byte[] bytes) {
        Payload {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(digest);
            Objects.requireNonNull(bytes);
        }
    }

    /**
     * Model {@code m}'s payload: the stream its digest is taken over (meshes, portal set, animation
     * entry, liquids), so the digest of a payload is its asset's digest.
     */
    static Payload modelPayload(WorldStreamObjectsFile.Data data, int m) {
        var h = new Hasher();
        h.startCapture();
        var asset = model(h, data, m);
        return new Payload(Kind.MODEL, asset.digest(), h.captured());
    }

    /**
     * Image {@code i}'s payload: its stored format, width and height (little-endian ints) and its
     * chain, the bytes {@link WorldStreamPackedObjects#identity} digests.
     */
    static Payload imagePayload(WorldStreamObjectsFile.Data data, int i) {
        int from = 0;
        for (int k = 0; k < i; k++) {
            from += chainBytes(data, k);
        }
        int width = data.images[i * 3], height = data.images[i * 3 + 1];
        int format = data.images[i * 3 + 2];
        int stored = chainBytes(data, i);
        var payload = ByteBuffer.allocate(12 + stored).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(format).putInt(width).putInt(height).put(data.pixels, from, stored);
        long[] id =
                WorldStreamPackedObjects.identity(
                        sha256(), format, width, height, data.pixels, from, stored);
        return new Payload(Kind.IMAGE, new Digest(id[0], id[1]), payload.array());
    }

    private static int chainBytes(WorldStreamObjectsFile.Data data, int i) {
        return com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                data.images[i * 3 + 2], data.images[i * 3], data.images[i * 3 + 1]);
    }

    /** The first 128 bits of the SHA-256 of {@code payload}: its asset's digest. */
    static Digest digestOf(byte[] payload) {
        var sum = ByteBuffer.wrap(sha256().digest(payload));
        return new Digest(sum.getLong(), sum.getLong());
    }

    /** One mesh of a model payload. */
    record MeshPart(int group, float[] vertices, int[] colours, short[] indices) {
        int vertexCount() {
            return colours.length;
        }
    }

    /** A model payload's portal set: its tables as the objects file holds them, set-relative. */
    record PortalPart(
            int[] groups,
            float[] boxes,
            int[] vertexCounts,
            float[] planes,
            float[] vertices,
            int[] refs) {}

    /** One liquid of a model payload, on the mesh {@code mesh} of the model. */
    record LiquidPart(
            int mesh,
            int kind,
            int colour,
            int xVertices,
            int yVertices,
            float[] corner,
            float[] heights,
            byte[] live) {}

    /** A decoded model payload. {@code portals}, {@code animationSources} may be null. */
    record ModelPart(
            List<MeshPart> meshes,
            PortalPart portals,
            int[] animationSources,
            byte[] animation,
            List<LiquidPart> liquids) {}

    /** A decoded image payload. */
    record ImagePart(int format, int width, int height, byte[] chain) {}

    /**
     * Decodes a model payload, every count bounded by the bytes left and the objects file's own
     * limits; {@link IllegalArgumentException} for any payload {@link #modelPayload} would not
     * write.
     */
    static ModelPart decodeModel(byte[] payload) {
        var in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        try {
            require(in.getInt() == MESHES, "Model payload meshes");
            int count = in.getInt();
            require(
                    count >= 1 && count <= WorldStreamObjectsFile.MAX_MESHES,
                    "Model payload mesh count");
            var meshes = new ArrayList<MeshPart>(count);
            for (int k = 0; k < count; k++) {
                int vertices = in.getInt(), indices = in.getInt(), group = in.getInt();
                require(
                        vertices >= 0
                                && vertices <= WorldStreamObjectsFile.MAX_MESH_VERTICES
                                && indices >= 0
                                && indices <= WorldStreamObjectsFile.MAX_INDICES
                                && (long) vertices * (VERTEX_BYTES + 4) + indices * 2L
                                        <= in.remaining(),
                        "Model payload mesh");
                var v = new float[vertices * 8];
                in.asFloatBuffer().get(v);
                in.position(in.position() + v.length * 4);
                var c = new int[vertices];
                in.asIntBuffer().get(c);
                in.position(in.position() + c.length * 4);
                var i = new short[indices];
                in.asShortBuffer().get(i);
                in.position(in.position() + i.length * 2);
                meshes.add(new MeshPart(group, v, c, i));
            }
            PortalPart portals = null;
            int[] sources = null;
            byte[] animation = null;
            var liquids = new ArrayList<LiquidPart>();
            int last = MESHES;
            while (in.hasRemaining()) {
                int tag = in.getInt();
                require(
                        tag > last || tag == LIQUID && last == LIQUID,
                        "Model payload section order");
                last = tag;
                if (tag == PORTALS) {
                    int g = in.getInt(), p = in.getInt(), v = in.getInt(), r = in.getInt();
                    require(
                            g >= 1
                                    && g <= WorldStreamObjectsFile.MAX_PORTAL_GROUPS
                                    && p >= 0
                                    && p <= WorldStreamObjectsFile.MAX_PORTALS
                                    && v >= 0
                                    && v <= WorldStreamObjectsFile.MAX_PORTAL_VERTICES
                                    && r >= 0
                                    && r <= WorldStreamObjectsFile.MAX_PORTAL_REFS,
                            "Model payload portal counts");
                    portals =
                            new PortalPart(
                                    ints(in, g * WorldStreamObjectsFile.PORTAL_GROUP_INTS),
                                    floats(in, g * 6),
                                    ints(in, p),
                                    floats(in, p * 4),
                                    floats(in, v * 3),
                                    ints(in, r * WorldStreamObjectsFile.PORTAL_REF_INTS));
                } else if (tag == ANIMATION) {
                    int n = in.getInt();
                    require(
                            n >= 0 && n <= WorldStreamObjectsFile.MAX_VERTICES,
                            "Model payload animation");
                    sources = ints(in, n);
                    animation = bytes(in, WorldStreamObjectsFile.MAX_ENTRY_ANIMATION_BYTES);
                } else if (tag == LIQUID) {
                    int mesh = in.getInt(), kind = in.getInt(), colour = in.getInt();
                    int xv = in.getInt(), yv = in.getInt();
                    require(
                            mesh >= 0
                                    && mesh < count
                                    && xv >= 2
                                    && xv <= 1024
                                    && yv >= 2
                                    && yv <= 1024,
                            "Model payload liquid");
                    liquids.add(
                            new LiquidPart(
                                    mesh,
                                    kind,
                                    colour,
                                    xv,
                                    yv,
                                    floats(in, 3),
                                    floats(in, xv * yv),
                                    bytes(in, (xv - 1) * (yv - 1))));
                } else {
                    throw new IllegalArgumentException("Model payload section");
                }
            }
            return new ModelPart(
                    List.copyOf(meshes), portals, sources, animation, List.copyOf(liquids));
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException truncated) {
            throw new IllegalArgumentException("Model payload truncated", truncated);
        }
    }

    /** Decodes an image payload; {@link IllegalArgumentException} for any other bytes. */
    static ImagePart decodeImage(byte[] payload) {
        require(payload.length >= 12, "Image payload head");
        var in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int format = in.getInt(), width = in.getInt(), height = in.getInt();
        require(
                width >= 1
                        && width <= WorldStreamObjectsFile.MAX_IMAGE_SIDE
                        && height >= 1
                        && height <= WorldStreamObjectsFile.MAX_IMAGE_SIDE
                        && com.vibeminer.rendering.world.ObjectTextureFormats.valid(format)
                        && com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                                        format, width, height)
                                == payload.length - 12,
                "Image payload");
        return new ImagePart(
                format, width, height, java.util.Arrays.copyOfRange(payload, 12, payload.length));
    }

    private static int[] ints(ByteBuffer in, int count) {
        require(count >= 0 && count * 4L <= in.remaining(), "Payload ints");
        var out = new int[count];
        in.asIntBuffer().get(out);
        in.position(in.position() + count * 4);
        return out;
    }

    private static float[] floats(ByteBuffer in, int count) {
        require(count >= 0 && count * 4L <= in.remaining(), "Payload floats");
        var out = new float[count];
        in.asFloatBuffer().get(out);
        in.position(in.position() + count * 4);
        return out;
    }

    /** A length-prefixed byte run of at most {@code max} bytes. */
    private static byte[] bytes(ByteBuffer in, int max) {
        int length = in.getInt();
        require(length >= 0 && length <= max && length <= in.remaining(), "Payload bytes");
        var out = new byte[length];
        in.get(out);
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
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
