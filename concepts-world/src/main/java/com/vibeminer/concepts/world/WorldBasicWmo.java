package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Source-qualified one-exterior recipe. No public DTO can construct its checked callback borrow.
 */
final class WorldBasicWmo {
    static final String RECIPE = "BASIC_WMO_PREVIEW_V1";
    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(65536, 256, 65536, 65536, 262144);
    private static final Set<Integer> ROOT_CORE =
            Set.of(tag("MVER"), tag("MOHD"), tag("MOMT"), tag("GFID"));
    private static final Set<Integer> GROUP_CORE =
            Set.of(
                    tag("MVER"),
                    tag("MOGP"),
                    tag("MOVT"),
                    tag("MOVI"),
                    tag("MONR"),
                    tag("MOTV"),
                    tag("MOBA"),
                    tag("MOPY"));

    private WorldBasicWmo() {}

    record Selection(long mapId, WorldFormats.Tile selector, int ordinal, long uniqueId) {
        Selection {
            Objects.requireNonNull(selector);
            require(
                    mapId >= 0
                            && mapId <= 0xffffffffL
                            && ordinal >= 0
                            && ordinal < WorldPlacements.MAX_RECORDS_PER_KIND
                            && uniqueId >= 0
                            && uniqueId <= 0xffffffffL,
                    "SELECTION_BOUNDS");
        }
    }

    interface Output<R extends AutoCloseable> {
        /**
         * Own every unreturned allocation locally; only unadvertised output may leave this
         * callback.
         */
        R writeUnadvertised(Borrow borrow, SceneMatch scene, Cancellation token);
    }

    /**
     * Detached descriptive facts; these cannot authorize another source read or positive factory.
     */
    record Binding(
            long fileDataId, String contentKey, long length, Long declaredLength, int limit) {}

    static final class SceneMatch {
        private final TerrainScene scene;
        private final Borrow borrow;
        private final String geometry;
        private final int x, y;

        private SceneMatch(TerrainScene scene, String geometry, int x, int y, Borrow borrow) {
            this.borrow = borrow;
            this.scene = scene;
            this.geometry = geometry;
            this.x = x;
            this.y = y;
        }

        TerrainScene scene() {
            borrow.check();
            return scene;
        }

        String geometry() {
            borrow.check();
            return geometry;
        }

        int x() {
            borrow.check();
            return x;
        }

        int y() {
            borrow.check();
            return y;
        }
    }

    /** Synchronous borrow only; primitive NIO views must not escape the callback. */
    static final class Borrow implements AutoCloseable {
        private WmoFormats.Root root;
        private WmoFormats.Group group;
        private float[] normals;
        private List<Pixels> images;
        private List<Binding> bindings;
        private final WorldStreamScopes.AdmissionMetadata metadata;
        private final Transform transform;
        private final WorldPlacements.WorldObject placement;
        private final Thread worker = Thread.currentThread();

        private Borrow(
                WmoFormats.Root root,
                WmoFormats.Group group,
                float[] normals,
                List<Pixels> images,
                List<Binding> bindings,
                WorldStreamScopes.AdmissionMetadata metadata,
                Transform transform,
                WorldPlacements.WorldObject placement) {
            this.root = root;
            this.group = group;
            this.normals = normals;
            this.images = List.copyOf(images);
            this.bindings = List.copyOf(bindings);
            this.metadata = metadata;
            this.transform = transform;
            this.placement = placement;
        }

        private void check() {
            require(group != null && Thread.currentThread() == worker, "EXPIRED_BORROW");
        }

        FloatBuffer positions() {
            check();
            return group.positions();
        }

        FloatBuffer normals() {
            check();
            return FloatBuffer.wrap(normals).asReadOnlyBuffer();
        }

        FloatBuffer uv() {
            check();
            return group.uv(0);
        }

        IntBuffer indices() {
            check();
            return group.indices();
        }

        List<WmoFormats.Batch> batches() {
            check();
            return group.batches();
        }

        List<WmoFormats.Material> materials() {
            check();
            return root.materials();
        }

        long ambientColor() {
            check();
            return root.header().ambientColor();
        }

        int imageCount() {
            check();
            return images.size();
        }

        long imageId(int index) {
            check();
            return images.get(index).id;
        }

        int imageWidth(int index) {
            check();
            return images.get(index).width;
        }

        int imageHeight(int index) {
            check();
            return images.get(index).height;
        }

        ByteBuffer rgba(int index) {
            check();
            return ByteBuffer.wrap(images.get(index).rgba).asReadOnlyBuffer();
        }

        List<Binding> bindings() {
            check();
            return bindings;
        }

        WorldStreamScopes.AdmissionMetadata metadata() {
            check();
            return metadata;
        }

        WorldPlacements.WorldObject placement() {
            check();
            return placement;
        }

        Transform transform() {
            check();
            return transform;
        }

        @Override
        public void close() {
            root = null;
            group = null;
            normals = null;
            images = null;
            bindings = null;
        }
    }

    private record Pixels(long id, int width, int height, byte[] rgba) {}

    /** Exact ADT origin policy; global WDT placements are deliberately outside this type. */
    static final class Transform {
        private final double x, y, z, scale, co, si;

        private Transform(WorldPlacements.WorldObject p) {
            this(p, p.scale() / 1024.0);
        }

        /** Same ADT heading rule with a caller-qualified scale (BASIC_EXTERIOR_V1 placements). */
        Transform(WorldPlacements.WorldObject p, double scale) {
            this(p.position(), p.rotation().y(), scale);
        }

        /**
         * The same ADT origin and heading rule for any ADT placement record (MODF or MDDF): raw
         * position and the raw heading (rotation y) in degrees.
         */
        Transform(WorldPlacements.Vec3 position, float heading, double scale) {
            double c = 51200.0 / 3.0;
            x = c - (double) position.z();
            y = c - (double) position.x();
            z = position.y();
            this.scale = scale;
            double theta = ((double) heading) * (StrictMath.PI / 180.0);
            co = StrictMath.cos(theta);
            si = StrictMath.sin(theta);
        }

        /** An already qualified transform (a drawn BASIC_EXTERIOR building's placement). */
        Transform(double x, double y, double z, double scale, double co, double si) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.co = co;
            this.si = si;
        }

        double x(double a, double b) {
            return x + scale * ((-co * a) + (si * b));
        }

        double y(double a, double b) {
            return y + scale * ((-si * a) + (-co * b));
        }

        double z(double c) {
            return z + scale * c;
        }

        double originX() {
            return x;
        }

        double originY() {
            return y;
        }

        double originZ() {
            return z;
        }

        double scale() {
            return scale;
        }

        double cos() {
            return co;
        }

        double sin() {
            return si;
        }

        double normalX(double a, double b) {
            return (-co * a) + (si * b);
        }

        double normalY(double a, double b) {
            return (-si * a) + (-co * b);
        }
    }

    static <R extends AutoCloseable> R prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            Selection selection,
            TerrainScene scene,
            Output<R> output,
            Cancellation token) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(selection);
        Objects.requireNonNull(scene);
        Objects.requireNonNull(output);
        Objects.requireNonNull(token);
        require(scope.mapId() == selection.mapId(), "STALE_SELECTION");
        return engine.withRecordedTile(
                scope,
                selection.selector(),
                WorldStreamScopes.UseProfile.BASIC_WMO,
                token,
                admission -> compose(admission, selection, scene, output));
    }

    private static <R extends AutoCloseable> R compose(
            WorldStreamScopes.TileAdmission admission,
            Selection selection,
            TerrainScene scene,
            Output<R> output) {
        var token = admission.cancellation();
        var bindings = new ArrayList<Binding>(11);
        var metadata = admission.metadata();
        bindings.add(
                new Binding(
                        metadata.wdtId(),
                        metadata.wdtKey(),
                        metadata.wdtLength(),
                        metadata.wdtDeclaredLength(),
                        metadata.wdtSourceLimit()));
        String geometry;
        try (var input = admission.readRecorded(WorldStreamScopes.RecordedInput.ROOT)) {
            var grid = WorldFormats.adtGrid(input.bytes(), token::isCanceled);
            geometry =
                    TerrainGeometryFingerprint.sha256(
                            selection.selector().x(), selection.selector().y(), grid);
            bindings.add(binding(input));
        }
        // The complete grid/raw bytes leave scope before the larger OBJ0 allocation.
        WorldPlacements.WorldObject placement;
        try (var input = admission.readRecorded(WorldStreamScopes.RecordedInput.OBJ0)) {
            var declarations =
                    WorldPlacements.readObj0(input.bytes(), token::isCanceled).worldObjects();
            require(selection.ordinal() < declarations.size(), "STALE_SELECTION");
            placement = declarations.get(selection.ordinal());
            require(placement.uniqueId() == selection.uniqueId(), "STALE_SELECTION");
            int matches = 0;
            for (var p : declarations) {
                token.check();
                if (p.uniqueId() == selection.uniqueId()) {
                    matches++;
                }
            }
            require(matches == 1, "STALE_SELECTION");
            qualifyPlacement(placement);
            bindings.add(binding(input));
        }
        var transform = new Transform(placement);
        WmoFormats.Root root;
        try (var input =
                admission.readDerivedCandidate(
                        placement.reference().value(), WorldStreamScopes.DerivedKind.WMO_ROOT)) {
            checkEmptySets(input.bytes());
            root =
                    WmoFormats.root(
                            input.bytes(), WmoFormats.Limits.basicPreview(), token::isCanceled);
            qualifyRoot(root);
            bindings.add(binding(input));
        }
        WmoFormats.Group group;
        try (var input =
                admission.readDerivedCandidate(
                        root.gfid(0), WorldStreamScopes.DerivedKind.WMO_GROUP)) {
            group =
                    WmoFormats.group(
                            input.bytes(), WmoFormats.Limits.basicPreview(), token::isCanceled);
            qualifyGroup(root, group);
            bindings.add(binding(input));
        }
        var normalized = normalizeAndCheck(group, transform, token);
        var images = new LinkedHashMap<Long, Pixels>();
        long pixels = 0;
        for (var material : root.materials()) {
            token.check();
            long id = material.word(3);
            if (images.containsKey(id)) {
                continue;
            }
            try (var input =
                    admission.readDerivedCandidate(id, WorldStreamScopes.DerivedKind.WMO_IMAGE)) {
                // This source copy and decoder/defensive copies are charged to the 1MiB decode
                // phase.
                var image =
                        Blp2Decoder.decode(AssetSource.bytes(input.bytes()), 0, IMAGE_LIMITS, token)
                                .join();
                require(
                        image.encoding() == Blp2Types.Encoding.BC1
                                && image.alphaDepth() == 0
                                && image.alphaEncoding() == 0
                                && image.mipFlags() == 1
                                && image.mipLevel() == 0,
                        "UNSUPPORTED_IMAGE");
                pixels += (long) image.width() * image.height();
                require(pixels <= 393216, "IMAGE_PIXEL_LIMIT");
                images.put(id, new Pixels(id, image.width(), image.height(), image.rgba()));
                bindings.add(binding(input));
            }
        }
        TerrainScene.Tile match = null;
        for (var tile : scene.tiles()) {
            token.check();
            if (tile.x() == selection.selector().x() && tile.y() == selection.selector().y()) {
                require(
                        match == null && geometry.equals(TerrainGeometryFingerprint.sha256(tile)),
                        "SCENE_MISMATCH");
                match = tile;
            }
        }
        require(match != null, "SCENE_MISMATCH");
        int matchX = match.x(), matchY = match.y();
        try (var borrow =
                new Borrow(
                        root,
                        group,
                        normalized,
                        new ArrayList<>(images.values()),
                        bindings,
                        admission.metadata(),
                        transform,
                        placement)) {
            token.check();
            var sceneMatch = new SceneMatch(scene, geometry, matchX, matchY, borrow);
            return Objects.requireNonNull(output.writeUnadvertised(borrow, sceneMatch, token));
        }
    }

    private static Binding binding(WorldStreamScopes.Input input) {
        return new Binding(
                input.fileDataId(),
                input.contentKey(),
                input.consumedLength(),
                input.declaredLength(),
                input.sourceLimit());
    }

    private static void checkEmptySets(byte[] bytes) {
        // Raw parser deliberately retains no MODS body. Validate the admitted body before
        // retirement.
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            require(b.remaining() >= 8, "ROOT_CHUNK_BOUNDS");
            int tag = b.getInt(), size = b.getInt();
            require(size >= 0 && size <= b.remaining(), "ROOT_CHUNK_BOUNDS");
            if (tag == tag("MODS")) {
                require(size == 0 || size == 32, "UNSUPPORTED_DOODAD_SET");
                if (size == 32) {
                    require(
                            b.getInt(b.position() + 20) == 0
                                    && b.getInt(b.position() + 24) == 0
                                    && b.getInt(b.position() + 28) == 0,
                            "UNSUPPORTED_DOODAD_SET");
                }
            }
            b.position(b.position() + size);
        }
    }

    private static void qualifyPlacement(WorldPlacements.WorldObject p) {
        require(
                p.flags() == 0xc
                        && p.reference().kind() == WorldPlacements.ReferenceKind.FILE_DATA_ID
                        && p.reference().value() > 0
                        && p.rotation().x() == 0
                        && p.rotation().z() == 0
                        && p.scale() > 0
                        && p.doodadSet() == 0
                        && p.nameSet() == 0,
                "UNSUPPORTED_PLACEMENT");
        require(
                p.lowerBounds().x() <= p.upperBounds().x()
                        && p.lowerBounds().y() <= p.upperBounds().y()
                        && p.lowerBounds().z() <= p.upperBounds().z(),
                "PLACEMENT_BOUNDS");
    }

    private static void qualifyRoot(WmoFormats.Root root) {
        var h = root.header();
        require(
                h.flags() == 0
                        && h.lodCount() == 0
                        && h.groupCount() == 1
                        && root.gfidPresent()
                        && root.gfidCount() == 1
                        && root.gfid(0) > 0
                        && !root.textureNameBlockPresent()
                        && h.materialCount() >= 1
                        && h.materialCount() <= 6,
                "UNSUPPORTED_ROOT");
        for (int i = 2; i < 6; i++) {
            require(h.counts().get(i) == 0, "UNSUPPORTED_ROOT_CONTENT");
        }
        require(h.counts().get(6) <= 1, "UNSUPPORTED_ROOT_CONTENT");
        for (var m : root.materials()) {
            for (int i : new int[] {0, 1, 2, 6, 9, 11, 12, 13, 14, 15}) {
                require(m.word(i) == 0, "UNSUPPORTED_MATERIAL");
            }
            require(m.word(3) > 0, "UNSUPPORTED_TEXTURE_DOMAIN");
        }
        var seen = new HashSet<Integer>();
        for (var c : root.chunks()) {
            require(seen.add(c.tag()), "DUPLICATE_ROOT_CHUNK");
            if (ROOT_CORE.contains(c.tag())) {
                continue;
            }
            boolean allowed =
                    switch (c.tag()) {
                        case 0x4d4f474e -> c.bytes() <= 64; // MOGN names
                        case 0x4d4f4749 -> c.bytes() == 32; // MOGI descriptive group record
                        case 0x4d4f5342 -> c.bytes() == 4; // MOSB sky ignored
                        case 0x4d464f47 -> c.bytes() == 0 || c.bytes() == 48; // MFOG ignored
                        case 0x4d4f4453 ->
                                c.bytes() == 32 * h.counts().get(6); // MODS empty default set
                        case 0x4d4f5056,
                                0x4d4f5054,
                                0x4d4f5052,
                                0x4d4f5656,
                                0x4d4f5642,
                                0x4d4f4c54,
                                0x4d4f4444 ->
                                c.bytes() == 0;
                        default -> false;
                    };
            require(allowed, "UNSUPPORTED_ROOT_CHUNK");
        }
    }

    private static void qualifyGroup(WmoFormats.Root root, WmoFormats.Group group) {
        var h = group.header();
        require(
                h.flags() == 9
                        && group.uvStreams() == 1
                        && h.portalCount() == 0
                        && h.firstPortal() == 0
                        && h.rawBatchPadding() == 0
                        && h.rawUnused() == 0
                        // These two recorded header values are inert in this exact flags9,
                        // no-MLIQ profile; neither admits water or invents a liquid material.
                        && (h.liquidType() == 0 || h.liquidType() == 15)
                        && h.tail0() == 0
                        && h.tail1() == 0,
                "UNSUPPORTED_GROUP");
        require(
                group.positions().remaining() > 0 && group.indices().remaining() > 0,
                "EMPTY_GROUP");
        boolean[] covered = new boolean[group.indices().remaining()];
        for (var b : group.batches()) {
            require(
                    b.flags() == 0
                            && b.materialByte() < root.materials().size()
                            && b.indexCount() > 0
                            && b.firstIndex() % 3 == 0
                            && b.indexCount() % 3 == 0,
                    "UNSUPPORTED_BATCH");
            for (int i = (int) b.firstIndex(); i < b.firstIndex() + b.indexCount(); i++) {
                require(!covered[i], "BATCH_OVERLAP");
                covered[i] = true;
            }
        }
        for (boolean c : covered) {
            require(c, "BATCH_GAP");
        }
        var seen = new HashSet<Integer>();
        for (var c : group.chunks()) {
            require(seen.add(c.tag()), "DUPLICATE_GROUP_CHUNK");
            if (GROUP_CORE.contains(c.tag())) {
                continue;
            }
            require(
                    (c.tag() == tag("MOBN") && c.bytes() % 16 == 0)
                            || (c.tag() == tag("MOBR") && c.bytes() % 2 == 0),
                    "UNSUPPORTED_GROUP_CHUNK");
        }
    }

    private static float[] normalizeAndCheck(
            WmoFormats.Group group, Transform transform, Cancellation token) {
        var p = group.positions();
        var n = group.normals();
        float[] normalized = new float[n.remaining()];
        for (int i = 0; i < normalized.length; i += 3) {
            token.check();
            double x = p.get(), y = p.get(), z = p.get();
            for (double q : new double[] {transform.x(x, y), transform.y(x, y), transform.z(z)}) {
                require(
                        Double.isFinite(q) && Math.abs(q) <= TerrainScene.MAX_COORDINATE,
                        "TRANSFORM_BOUNDS");
            }
            double a = n.get(), b = n.get(), c = n.get();
            double length = StrictMath.sqrt(a * a + b * b + c * c);
            require(Double.isFinite(length) && Math.abs(length - 1) <= .001, "UNSUPPORTED_NORMAL");
            normalized[i] = (float) (a / length);
            normalized[i + 1] = (float) (b / length);
            normalized[i + 2] = (float) (c / length);
        }
        return normalized;
    }

    private static int tag(String name) {
        return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3);
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }
}
