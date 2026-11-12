package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.models.M2Collision;
import com.vibeminer.formats.world.WmoCollision;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WmoLiquid;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.rendering.world.LiquidSurface;
import com.vibeminer.rendering.world.ObjectCollision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * What walking collides with on one ADT tile besides its terrain: every obj0 placement, never the
 * capped draw set. Each MODF building places all of its root's LOD0 groups (solid triangles and
 * BSP, {@link WmoCollision}) and its doodad sets (set 0 and the placement's own, or its MWDR
 * range), and each MDDF doodad its model's collision mesh ({@link M2Collision}). Transforms are the
 * drawn objects' own: the ADT origin and heading rule with pitch and roll ({@link
 * WorldExteriorObjects.Rotation#mddf}) and set doodads composed with their building. Placements the
 * client does not place by FileDataID, and sources that cannot be read, are counted by reason and
 * left out; nothing is guessed.
 *
 * <p>Each building group's liquid (MLIQ, {@link WmoLiquid}) is placed with the same transform, for
 * swimming ({@link LiquidSurface#with}); a group liquid under a building tilted by pitch or roll is
 * left out and counted.
 *
 * <p>Parsed roots, groups and models are shared across tiles in a cache bounded by bytes ({@link
 * #CACHE_BYTES}). Thread-safe; reads run on the caller's thread.
 */
public final class WorldTileCollision {
    /**
     * Shared parsed sources. The whole 3x3 around Stormwind measured 35 MiB on 70862 (Ironforge 19,
     * Orgrimmar 19), placements included, so this holds that neighbourhood with room to move.
     */
    public static final long CACHE_BYTES = 96L << 20;

    static final double MAX_SCALE = WorldExteriorObjects.MAX_SCALE;
    private static final int DIRECT_ID = 0x8, SCALED = 0x4, SETS_FROM_MWDS = 0x80;
    private static final WmoFormats.Limits ROOT_LIMITS =
            new WmoFormats.Limits(
                    1 << 20,
                    1 << 20,
                    WmoFormats.MAX_GROUPS,
                    256,
                    4096,
                    65536,
                    131072,
                    256,
                    4,
                    4L << 20);

    /**
     * One tile's placements and what was left out by reason. {@code complete} is false when a
     * source could not be read now (not malformed or empty): reading the tile again later may find
     * more.
     */
    public record Part(
            int tileX,
            int tileY,
            List<ObjectCollision.Placement> placements,
            List<LiquidSurface.Placed> liquids,
            Map<String, Integer> skipped,
            boolean complete) {
        public Part {
            placements = List.copyOf(placements);
            liquids = List.copyOf(liquids);
            skipped = Map.copyOf(skipped);
        }

        /** A part without liquids. */
        public Part(
                int tileX,
                int tileY,
                List<ObjectCollision.Placement> placements,
                Map<String, Integer> skipped,
                boolean complete) {
            this(tileX, tileY, placements, List.of(), skipped, complete);
        }
    }

    /** Raw file bytes by FileDataID; throws {@link Unreadable} when a source cannot be read now. */
    @FunctionalInterface
    interface Source {
        byte[] read(long fileDataId, Cancellation token);

        /** Changes when the decryption keys change ({@link BuildFiles#keyRevision}). */
        default long keyRevision() {
            return 0;
        }
    }

    /**
     * A source this build will not give (not in the build, encrypted without a key, protected):
     * remembered like a malformed one and never read again in the session.
     */
    static final class Absent extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** True when a different key set could give it (a missing or wrong key, a hole). */
        final boolean keyed;

        Absent(String message, boolean keyed) {
            super(message, null);
            this.keyed = keyed;
        }
    }

    /** A source that cannot be read now (missing locally, a network or I/O failure). */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Root(long[] groups, WmoFormats.Doodads doodads, int flags) {}

    /** A group's collision mesh and liquid; either may be null, not both. */
    private record Group(WmoMesh mesh, WmoLiquid liquid) {}

    private static final Object MISSING = new Object();

    /** Remembered for a source refused for its keys: forgotten when the keys change. */
    private static final Object KEYED_MISSING = new Object();

    /** What {@link #read} returns for an {@link Absent} source, and for a keyed one. */
    private static final byte[] ABSENT = new byte[0], KEYED_ABSENT = new byte[0];

    /** The key revision the cache's keyed absences were found under. */
    private long keys;

    private final Source source;
    private final long budget;
    private final LinkedHashMap<Long, Object> cache = new LinkedHashMap<>(256, .75f, true);
    private long cached;

    WorldTileCollision(BuildFiles files) {
        this(files(Objects.requireNonNull(files)), CACHE_BYTES);
    }

    /** A reader over any source, with its own cache budget in bytes (tests). */
    WorldTileCollision(Source source, long budget) {
        this.source = Objects.requireNonNull(source);
        this.budget = budget;
    }

    /** The build's files; any failure other than cancellation reads as unreadable for now. */
    private static Source files(BuildFiles files) {
        Source read =
                (id, token) -> {
                    FileTypes.FileOpen opened;
                    try {
                        opened = files.openRead(FileTypes.FileRef.id(id), token).join();
                    } catch (java.util.concurrent.CancellationException canceled) {
                        throw canceled;
                    } catch (RuntimeException failure) {
                        token.check();
                        throw new Unreadable(String.valueOf(failure.getMessage()), failure);
                    }
                    if (opened.stream() == null) {
                        if (PERMANENT.contains(opened.state())) {
                            throw new Absent(
                                    "File is " + opened.state(), KEYED.contains(opened.state()));
                        }
                        throw new Unreadable("File is " + opened.state(), null);
                    }
                    try (var stream = opened.stream()) {
                        if (!stream.holes().isEmpty()) {
                            throw new Absent("File has protected sections", true);
                        }
                        if (stream.length() > WorldFormats.MAX_FILE_BYTES) {
                            throw new Absent("File exceeds the world file limit", false);
                        }
                        var target = java.nio.ByteBuffer.allocate((int) stream.length());
                        while (target.hasRemaining()) {
                            token.check();
                            if (stream.read(target, token).join() <= 0) {
                                throw new Unreadable("Truncated file", null);
                            }
                        }
                        return target.array();
                    } catch (Absent
                            | Unreadable
                            | java.util.concurrent.CancellationException known) {
                        throw known;
                    } catch (RuntimeException failure) {
                        token.check();
                        throw new Unreadable(String.valueOf(failure.getMessage()), failure);
                    }
                };
        return new Source() {
            @Override
            public byte[] read(long fileDataId, Cancellation token) {
                return read.read(fileDataId, token);
            }

            @Override
            public long keyRevision() {
                return files.keyRevision();
            }
        };
    }

    /** Permanent outcomes that another key set may change: forgotten when the keys change. */
    static final java.util.Set<FileTypes.FileReadState> KEYED =
            java.util.EnumSet.of(
                    FileTypes.FileReadState.PROTECTED, FileTypes.FileReadState.WRONG_KEY);

    /** Read outcomes that reading again in this session will not change. */
    static final java.util.Set<FileTypes.FileReadState> PERMANENT =
            java.util.EnumSet.of(
                    FileTypes.FileReadState.PROTECTED,
                    FileTypes.FileReadState.UNKNOWN_FILE,
                    FileTypes.FileReadState.NOT_IN_LOCALE,
                    FileTypes.FileReadState.NOT_IN_ENCODING,
                    FileTypes.FileReadState.WRONG_KEY,
                    FileTypes.FileReadState.UNSUPPORTED,
                    FileTypes.FileReadState.CORRUPT,
                    FileTypes.FileReadState.TOO_LARGE);

    /** Sources the shared cache holds now. */
    synchronized int cachedEntries() {
        return cache.size();
    }

    /** Bytes the shared cache holds now. */
    public synchronized long cachedBytes() {
        return cached;
    }

    /** Reads one tile's placements and everything they place. */
    public Part prepare(WorldFormats.Tile tile, Cancellation token) {
        Objects.requireNonNull(tile);
        Objects.requireNonNull(token);
        forgetKeyedAbsences(source.keyRevision());
        var skipped = new TreeMap<String, Integer>();
        var placements = new ArrayList<ObjectCollision.Placement>();
        var liquids = new ArrayList<LiquidSurface.Placed>();
        byte[] obj0 = read(tile.obj0Id(), token, skipped, "obj0");
        if (obj0 == null || obj0 == ABSENT || obj0 == KEYED_ABSENT) {
            return part(tile, placements, liquids, skipped);
        }
        WorldPlacements.Metadata recorded;
        try {
            recorded = WorldPlacements.readObj0(obj0, token::isCanceled);
        } catch (IllegalArgumentException malformed) {
            skipped.merge("obj0 malformed", 1, Integer::sum);
            return part(tile, placements, liquids, skipped);
        }
        for (var building : recorded.worldObjects()) {
            token.check();
            building(building, recorded, placements, liquids, skipped, token);
        }
        for (var doodad : recorded.doodads()) {
            token.check();
            doodad(doodad, placements, skipped, token);
        }
        return part(tile, placements, liquids, skipped);
    }

    private static Part part(
            WorldFormats.Tile tile,
            List<ObjectCollision.Placement> placements,
            List<LiquidSurface.Placed> liquids,
            Map<String, Integer> skipped) {
        boolean complete =
                skipped.keySet().stream().noneMatch(reason -> reason.endsWith(" unreadable"));
        return new Part(tile.x(), tile.y(), placements, liquids, skipped, complete);
    }

    private void building(
            WorldPlacements.WorldObject p,
            WorldPlacements.Metadata recorded,
            List<ObjectCollision.Placement> out,
            List<LiquidSurface.Placed> liquids,
            Map<String, Integer> skipped,
            Cancellation token) {
        if ((p.flags() & DIRECT_ID) == 0
                || p.reference().kind() != WorldPlacements.ReferenceKind.FILE_DATA_ID
                || p.reference().value() <= 0) {
            skipped.merge("building not by file ID", 1, Integer::sum);
            return;
        }
        double scale = (p.flags() & SCALED) != 0 ? p.scale() / 1024.0 : 1;
        if (!(scale > 0) || scale > MAX_SCALE) {
            skipped.merge("building scale", 1, Integer::sum);
            return;
        }
        var transform = new WorldBasicWmo.Transform(p, scale);
        var rotation = WorldExteriorObjects.buildingRotation(p, transform.cos(), transform.sin());
        if (!rotation.finite()
                || !finite(transform.originX(), transform.originY(), transform.originZ())) {
            skipped.merge("building transform", 1, Integer::sum);
            return;
        }
        var root = root(p.reference().value(), token, skipped);
        if (root == null) {
            return;
        }
        double[] r = rotation.rowMajor();
        for (int g = 0; g < root.groups().length; g++) {
            var group = group(root.groups()[g], token, skipped);
            var mesh = group == null ? null : group.mesh();
            if (group != null && group.liquid() != null) {
                if (LiquidSurface.upright(r)) {
                    liquids.add(
                            new LiquidSurface.Placed(
                                    new Key('L', p.uniqueId(), g),
                                    new Local(
                                            group.liquid(),
                                            group.liquid().liquidType(root.flags())),
                                    // Resolved by the stream from the LiquidType rows building
                                    // water is drawn by (WorldPreparedTerrain.collision).
                                    LiquidSurface.Kind.OTHER,
                                    r,
                                    scale,
                                    transform.originX(),
                                    transform.originY(),
                                    transform.originZ()));
                } else {
                    skipped.merge("building liquid tilted", 1, Integer::sum);
                }
            }
            if (mesh != null) {
                out.add(
                        new ObjectCollision.Placement(
                                new Key('W', p.uniqueId(), g),
                                mesh,
                                r,
                                scale,
                                transform.originX(),
                                transform.originY(),
                                transform.originZ()));
            }
        }
        var doodads = root.doodads();
        if (!doodads.retained() || doodads.sets().isEmpty()) {
            return;
        }
        var sets = new ArrayList<Integer>(List.of(0));
        if ((p.flags() & SETS_FROM_MWDS) != 0) {
            if (p.doodadSet() >= recorded.setRanges().size()) {
                skipped.merge("doodad set range", 1, Integer::sum);
            } else {
                var range = recorded.setRanges().get(p.doodadSet());
                for (long i = range.begin();
                        i <= range.end() && i < recorded.setIds().size();
                        i++) {
                    int set = recorded.setIds().get((int) i);
                    if (set < doodads.sets().size() && !sets.contains(set)) {
                        sets.add(set);
                    }
                }
            }
        } else if (p.doodadSet() != 0 && p.doodadSet() < doodads.sets().size()) {
            sets.add(p.doodadSet());
        }
        for (int set : sets) {
            var range = doodads.sets().get(set);
            for (long d = range.firstDefinition();
                    d < range.firstDefinition() + range.count() && d < doodads.definitions().size();
                    d++) {
                setDoodad(p.uniqueId(), (int) d, doodads, r, scale, transform, out, skipped, token);
            }
        }
    }

    private void setDoodad(
            long buildingId,
            int ordinal,
            WmoFormats.Doodads doodads,
            double[] r,
            double scale,
            WorldBasicWmo.Transform transform,
            List<ObjectCollision.Placement> out,
            Map<String, Integer> skipped,
            Cancellation token) {
        var def = doodads.definitions().get(ordinal);
        long id = def.reference() < doodads.fileIdCount() ? doodads.fileId(def.reference()) : 0;
        double qx = def.qx(), qy = def.qy(), qz = def.qz(), qw = def.qw();
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        double size = scale * def.scale();
        if (id <= 0
                || !finite(def.x(), def.y(), def.z())
                || !(Math.abs(norm - 1) <= 1e-3)
                || !(def.scale() > 0)
                || !(size <= MAX_SCALE)) {
            skipped.merge("set doodad unsupported", 1, Integer::sum);
            return;
        }
        var mesh = model(id, token, skipped);
        if (mesh == null) {
            return;
        }
        double a = def.x(), b = def.y(), c = def.z();
        double x = transform.originX() + scale * (r[0] * a + r[1] * b + r[2] * c);
        double y = transform.originY() + scale * (r[3] * a + r[4] * b + r[5] * c);
        double z = transform.originZ() + scale * (r[6] * a + r[7] * b + r[8] * c);
        var building =
                new WorldExteriorObjects.Rotation(
                        r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
        var rotation = building.times(WorldExteriorObjects.Rotation.quaternion(qx, qy, qz, qw));
        out.add(
                new ObjectCollision.Placement(
                        new Key('S', buildingId, ordinal),
                        mesh,
                        rotation.rowMajor(),
                        size,
                        x,
                        y,
                        z));
    }

    private void doodad(
            WorldPlacements.Doodad d,
            List<ObjectCollision.Placement> out,
            Map<String, Integer> skipped,
            Cancellation token) {
        if (d.reference().kind() != WorldPlacements.ReferenceKind.FILE_DATA_ID
                || d.reference().value() <= 0
                || d.scale() <= 0) {
            skipped.merge("doodad not by file ID", 1, Integer::sum);
            return;
        }
        double scale = d.scale() / 1024.0;
        var transform = new WorldBasicWmo.Transform(d.position(), d.rotation().y(), scale);
        var rotation =
                WorldExteriorObjects.Rotation.mddf(
                        transform.cos(), transform.sin(), d.rotation().x(), d.rotation().z());
        if (scale > MAX_SCALE
                || !rotation.finite()
                || !finite(transform.originX(), transform.originY(), transform.originZ())) {
            skipped.merge("doodad transform", 1, Integer::sum);
            return;
        }
        var mesh = model(d.reference().value(), token, skipped);
        if (mesh != null) {
            out.add(
                    new ObjectCollision.Placement(
                            new Key('D', d.uniqueId(), 0),
                            mesh,
                            rotation.rowMajor(),
                            scale,
                            transform.originX(),
                            transform.originY(),
                            transform.originZ()));
        }
    }

    /**
     * A placement's identity: kind ('W' a building group, 'S' a set doodad, 'D' an MDDF doodad, 'L'
     * a building group's liquid), the unique ID (the building's for its groups and set doodads) and
     * the group or doodad ordinal.
     */
    public record Key(char kind, long uniqueId, int ordinal) {}

    private Root root(long id, Cancellation token, Map<String, Integer> skipped) {
        var known = cached(id);
        if (known == MISSING || known == KEYED_MISSING) {
            skipped.merge("building root absent or malformed", 1, Integer::sum);
            return null;
        }
        if (known instanceof Root root) {
            return root;
        }
        byte[] bytes = read(id, token, skipped, "building root");
        if (bytes == null) {
            // Unreadable now: not remembered, so a later read tries again.
            return null;
        }
        if (bytes == ABSENT || bytes == KEYED_ABSENT) {
            remember(id, bytes == ABSENT ? MISSING : KEYED_MISSING, 64);
            return null;
        }
        Root root = null;
        try {
            var parsed = WmoFormats.root(bytes, ROOT_LIMITS, token::isCanceled);
            int count = (int) Math.min(parsed.header().groupCount(), parsed.gfidCount());
            long[] groups = new long[count];
            for (int g = 0; g < count; g++) {
                groups[g] = parsed.gfid(g);
            }
            root = new Root(groups, parsed.doodads(), parsed.header().flags());
        } catch (IllegalArgumentException malformed) {
            skipped.merge("building root malformed", 1, Integer::sum);
        }

        remember(
                id,
                root == null ? MISSING : root,
                root == null
                        ? 64
                        : 512
                                + root.groups().length * 8L
                                + root.doodads().definitions().size() * 64L);
        return root;
    }

    private Group group(long id, Cancellation token, Map<String, Integer> skipped) {
        if (id <= 0) {
            return null;
        }
        var known = cached(id);
        if (known == MISSING || known == KEYED_MISSING) {
            return null;
        }
        if (known instanceof Group group) {
            return group;
        }
        byte[] bytes = read(id, token, skipped, "building group");
        if (bytes == null) {
            // Unreadable now: not remembered, so a later read tries again.
            return null;
        }
        if (bytes == ABSENT || bytes == KEYED_ABSENT) {
            remember(id, bytes == ABSENT ? MISSING : KEYED_MISSING, 64);
            return null;
        }
        WmoMesh mesh = null;
        WmoLiquid liquid = null;
        try {
            var parsed = WmoCollision.read(bytes, token::isCanceled);
            if (parsed.solidTriangles() > 0) {
                mesh = new WmoMesh(parsed);
            }
        } catch (IllegalArgumentException malformed) {
            skipped.merge("building group malformed", 1, Integer::sum);
        }
        try {
            liquid = WmoLiquid.read(bytes, token::isCanceled);
        } catch (IllegalArgumentException malformed) {
            skipped.merge("building liquid malformed", 1, Integer::sum);
        }
        var group = mesh == null && liquid == null ? null : new Group(mesh, liquid);
        remember(
                id,
                group == null ? MISSING : group,
                group == null
                        ? 64
                        : (mesh == null ? 0 : mesh.bytes())
                                + (liquid == null ? 0 : liquid.retainedBytes()));
        return group;
    }

    /** A group's liquid in its own coordinates, for {@link LiquidSurface}. */
    record Local(WmoLiquid liquid, int liquidType) implements LiquidSurface.Local {
        @Override
        public double heightAt(double x, double y) {
            return liquid.heightAt(x, y);
        }

        @Override
        public double[] bounds() {
            return new double[] {
                liquid.baseX(),
                liquid.baseY(),
                liquid.baseX() + liquid.xTiles() * WmoLiquid.UNIT,
                liquid.baseY() + liquid.yTiles() * WmoLiquid.UNIT
            };
        }

        @Override
        public double minHeight() {
            return liquid.minHeight();
        }

        @Override
        public double maxHeight() {
            return liquid.maxHeight();
        }
    }

    private ObjectCollision.Mesh model(long id, Cancellation token, Map<String, Integer> skipped) {
        var known = cached(id);
        if (known == MISSING || known == KEYED_MISSING) {
            return null;
        }
        if (known instanceof M2Mesh mesh) {
            return mesh;
        }
        byte[] bytes = read(id, token, skipped, "doodad model");
        if (bytes == null) {
            // Unreadable now: not remembered, so a later read tries again.
            return null;
        }
        if (bytes == ABSENT || bytes == KEYED_ABSENT) {
            remember(id, bytes == ABSENT ? MISSING : KEYED_MISSING, 64);
            return null;
        }
        M2Mesh mesh = null;
        try {
            var parsed = M2Collision.read(bytes);
            if (parsed.triangles() > 0) {
                mesh = new M2Mesh(parsed);
            }
        } catch (IllegalArgumentException malformed) {
            skipped.merge("doodad model malformed", 1, Integer::sum);
        }

        remember(id, mesh == null ? MISSING : mesh, mesh == null ? 64 : mesh.bytes());
        return mesh;
    }

    /** Drops sources refused for their keys once the keys changed, so they are read again. */
    private synchronized void forgetKeyedAbsences(long revision) {
        if (revision == keys) {
            return;
        }
        keys = revision;
        var it = cache.values().iterator();
        while (it.hasNext()) {
            var sized = (Sized) it.next();
            if (sized.value() == KEYED_MISSING) {
                cached -= sized.bytes();
                it.remove();
            }
        }
    }

    private synchronized Object cached(long id) {
        return cache.get(id) instanceof Sized sized ? sized.value() : null;
    }

    private synchronized void remember(long id, Object value, long bytes) {
        var previous = cache.put(id, new Sized(value, bytes));
        if (previous instanceof Sized old) {
            cached -= old.bytes();
        }
        cached += bytes;
        var it = cache.entrySet().iterator();
        while (cached > budget && it.hasNext()) {
            var eldest = it.next();
            if (eldest.getKey() == id) {
                continue;
            }
            cached -= ((Sized) eldest.getValue()).bytes();
            it.remove();
        }
    }

    private record Sized(Object value, long bytes) {}

    private byte[] read(long id, Cancellation token, Map<String, Integer> skipped, String kind) {
        if (id <= 0) {
            skipped.merge(kind + " without a file ID", 1, Integer::sum);
            return null;
        }
        try {
            return source.read(id, token);
        } catch (Unreadable unreadable) {
            skipped.merge(kind + " unreadable", 1, Integer::sum);
            return null;
        } catch (Absent absent) {
            skipped.merge(kind + " absent", 1, Integer::sum);
            return absent.keyed ? KEYED_ABSENT : ABSENT;
        }
    }

    private static boolean finite(double... values) {
        for (double v : values) {
            if (!Double.isFinite(v) || Math.abs(v) > 1_000_000) {
                return false;
            }
        }
        return true;
    }

    /** A WMO group's solid triangles, narrowed by its BSP. */
    static final class WmoMesh implements ObjectCollision.Mesh {
        private final WmoCollision group;

        WmoMesh(WmoCollision group) {
            this.group = group;
        }

        @Override
        public int triangles() {
            return group.triangles();
        }

        @Override
        public int corner(int triangle, int corner) {
            return group.corner(triangle, corner);
        }

        @Override
        public float position(int vertex, int axis) {
            return group.position(vertex, axis);
        }

        @Override
        public void query(
                float x0, float y0, float z0, float x1, float y1, float z1, IntConsumer triangle) {
            group.query(x0, y0, z0, x1, y1, z1, triangle);
        }

        @Override
        public long bytes() {
            return group.retainedBytes();
        }
    }

    /** An M2 collision mesh; small enough to offer whole. */
    static final class M2Mesh implements ObjectCollision.Mesh {
        private final M2Collision model;

        M2Mesh(M2Collision model) {
            this.model = model;
        }

        @Override
        public int triangles() {
            return model.triangles();
        }

        @Override
        public int corner(int triangle, int corner) {
            return model.corner(triangle, corner);
        }

        @Override
        public float position(int vertex, int axis) {
            return model.position(vertex, axis);
        }

        @Override
        public void query(
                float x0, float y0, float z0, float x1, float y1, float z1, IntConsumer triangle) {
            for (int t = 0, n = model.triangles(); t < n; t++) {
                triangle.accept(t);
            }
        }

        @Override
        public long bytes() {
            return model.retainedBytes();
        }
    }
}
