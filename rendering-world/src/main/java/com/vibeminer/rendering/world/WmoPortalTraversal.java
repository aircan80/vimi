package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WmoFormats;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Which groups of one WMO are visible from a camera, by walking its portals (#383). Everything is
 * in WMO-local axes; the caller transforms the camera and its frustum into them.
 *
 * <p>The walk starts from every interior group whose bounding box holds the camera, or, when there
 * is none, from every exterior group; the start groups are visible. When neither exists (an
 * all-interior WMO seen from outside every group box) every group is visible. From each group it
 * follows that group's MOPR references: a portal is passable when the camera is on the referencing
 * group's side of it and the portal polygon is not outside the current frustum; the group behind it
 * is visible and is walked with the frustum narrowed to the portal's edges. Groups reached by no
 * portal are not visible.
 *
 * <p>Every approximation errs toward drawing more, never toward hiding a room the camera is in or
 * looks into. Pinned conventions and approximations (no per-WMO cases):
 *
 * <ul>
 *   <li>Side sign: the camera is on the referencing group's side when {@code side < 0 ? dot <= 0 :
 *       dot >= 0}, with {@code dot = normal . camera + distance} on the recorded MOPT plane
 *       (WebWowViewerCpp {@code wmoObject.cpp} at 56402e5, {@code isInsidePortalThis}; wowdev.wiki
 *       WMO leaves the sign undefined). To be checked with the #423 probe on real WMOs.
 *   <li>The caller's frustum must not include its near plane: a portal the camera is about to pass
 *       through lies closer than the near plane and would be culled with the room behind it. Side
 *       and far planes are fine.
 *   <li>Inconsistent references: a reference whose group's bounding-box centre lies on the other
 *       side of the portal plane from its recorded side, or within {@link #PLANE_EPSILON} of it,
 *       cannot be trusted (real data has exterior groups nested under a room whose portal planes do
 *       not separate them). From that group the portal is always passable: the side test is
 *       skipped, and the frustum is narrowed to the portal's edges only when the camera is on the
 *       recorded side and off the plane; otherwise it passes unchanged. From the wrong side the
 *       portal is passed when either its polygon or the destination group's box is in view. {@link
 *       #inconsistentReferences()} counts them.
 *   <li>Near a portal plane ({@code |dot| <= }{@link #PLANE_EPSILON}) the edge planes degenerate,
 *       so the portal is passed with the current frustum unchanged, whichever side the camera is on
 *       (as WebWowViewerCpp's near-portal case).
 *   <li>Start groups: every interior group (MOGP flag 0x2000) whose box holds the camera. The
 *       client resolves the one group with the group BSP (MOBN/MOBR), which is not read; with
 *       overlapping or L-shaped boxes this starts from more groups than the client, never fewer.
 *   <li>A portal is culled only when all its vertices are outside one frustum plane, a conservative
 *       polygon test. Portals are treated as convex: an edge plane that would cut off any vertex of
 *       its own portal (a reflex edge of a non-convex polygon) is dropped, which keeps the planes
 *       that bound the polygon's hull. Narrowed frusta keep at most {@link #MAX_PORTAL_PLANES}
 *       portal edge planes, dropping the oldest first, which only widens them.
 *   <li>Each group is walked at most {@link #MAX_ENTRIES_PER_GROUP} times with a narrowed frustum,
 *       so cycles terminate. A group reached again after that is walked once more with the caller's
 *       frustum alone, which holds every narrowed one.
 * </ul>
 *
 * <p>An instance keeps its walk state between calls, and with a reused {@link Scratch} a walk
 * allocates nothing; the {@code List<Plane>} forms allocate per call and are meant for tests. An
 * instance is not thread-safe.
 */
public final class WmoPortalTraversal {
    /** MOGP flags: exterior (0x8) and interior (0x2000). */
    public static final long EXTERIOR = 0x8, INTERIOR = 0x2000;

    public static final double PLANE_EPSILON = 0.05;
    public static final int MAX_PORTAL_PLANES = 32, MAX_ENTRIES_PER_GROUP = 8;

    /** Plane {@code a x + b y + c z + d}; a point is inside when the value is at least zero. */
    public record Plane(double a, double b, double c, double d) {
        public Plane {
            if (!Double.isFinite(a)
                    || !Double.isFinite(b)
                    || !Double.isFinite(c)
                    || !Double.isFinite(d)) {
                throw new IllegalArgumentException("Non-finite plane");
            }
        }

        double value(double x, double y, double z) {
            return a * x + b * y + c * z + d;
        }
    }

    /** One group: its MOGP flags, bounding box and MOPR range. */
    public record Group(
            long flags,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            int firstRef,
            int refCount) {
        boolean interior() {
            return (flags & INTERIOR) != 0;
        }

        boolean exterior() {
            return (flags & EXTERIOR) != 0;
        }

        boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** One portal polygon ({@code x, y, z} per vertex) and its recorded plane. */
    public record Portal(double[] vertices, double nx, double ny, double nz, double distance) {
        public Portal {
            vertices = vertices.clone();
            if (vertices.length < 9 || vertices.length % 3 != 0) {
                throw new IllegalArgumentException("A portal needs at least three vertices");
            }
            // A non-finite portal could never be passed and would hide the room behind it.
            for (double value : vertices) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Non-finite portal vertex");
                }
            }
            if (!Double.isFinite(nx)
                    || !Double.isFinite(ny)
                    || !Double.isFinite(nz)
                    || !Double.isFinite(distance)) {
                throw new IllegalArgumentException("Non-finite portal plane");
            }
        }

        @Override
        public double[] vertices() {
            return vertices.clone();
        }

        double dot(double x, double y, double z) {
            return nx * x + ny * y + nz * z + distance;
        }
    }

    /** One MOPR reference: from the referencing group through {@code portal} to {@code group}. */
    public record Ref(int portal, int group, int side) {}

    /** Whether the camera is in an interior group, and the visible groups. */
    public record Result(boolean inside, BitSet visible) {
        public Result {
            visible = (BitSet) visible.clone();
        }

        @Override
        public BitSet visible() {
            return (BitSet) visible.clone();
        }

        public boolean visible(int group) {
            return visible.get(group);
        }
    }

    private final List<Group> groups;
    private final List<Ref> refs;
    private final double[][] portalVertices;
    private final double[][] portalPlanes;
    private final double[][] centroids;
    // Per group, per reference in its range: true when the reference is inconsistent.
    private final boolean[][] inconsistent;
    private final int inconsistentCount;
    // Groups and references as primitives, so a walk reads no records.
    private final double[] boxes;
    private final long[] flags;
    private final int[] firstRefs, refCounts, refPortals, refGroups, refSides;
    // Walk state reused by every walk.
    private final int[] entries;
    private final boolean[] widened;
    private final int[] queueGroups;

    private WmoPortalTraversal(List<Group> groups, List<Portal> portals, List<Ref> refs) {
        this.groups = groups;
        this.refs = refs;
        portalVertices = new double[portals.size()][];
        portalPlanes = new double[portals.size()][];
        centroids = new double[portals.size()][];
        for (int p = 0; p < portals.size(); p++) {
            var portal = portals.get(p);
            var v = portal.vertices();
            portalVertices[p] = v;
            portalPlanes[p] =
                    new double[] {portal.nx(), portal.ny(), portal.nz(), portal.distance()};
            int n = v.length / 3;
            var c = new double[3];
            for (int i = 0; i < n; i++) {
                c[0] += v[i * 3] / n;
                c[1] += v[i * 3 + 1] / n;
                c[2] += v[i * 3 + 2] / n;
            }
            centroids[p] = c;
        }
        inconsistent = new boolean[groups.size()][];
        int count = 0;
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            double cx = (group.minX() + group.maxX()) / 2;
            double cy = (group.minY() + group.maxY()) / 2;
            double cz = (group.minZ() + group.maxZ()) / 2;
            var flags = new boolean[Math.max(0, group.refCount())];
            for (int i = 0; i < flags.length; i++) {
                int r = group.firstRef() + i;
                if (r < 0 || r >= refs.size()) {
                    continue; // Refused by of() before any walk.
                }
                var ref = refs.get(r);
                if (ref.portal() < 0 || ref.portal() >= portals.size()) {
                    continue;
                }
                double dot = portals.get(ref.portal()).dot(cx, cy, cz);
                boolean agrees = ref.side() < 0 ? dot < -PLANE_EPSILON : dot > PLANE_EPSILON;
                if (!agrees) {
                    flags[i] = true;
                    count++;
                }
            }
            inconsistent[g] = flags;
        }
        inconsistentCount = count;
        entries = new int[groups.size()];
        widened = new boolean[groups.size()];
        // Each group enters at most MAX_ENTRIES_PER_GROUP + 1 times.
        queueGroups = new int[groups.size() * (MAX_ENTRIES_PER_GROUP + 1)];
        boxes = new double[groups.size() * 6];
        flags = new long[groups.size()];
        firstRefs = new int[groups.size()];
        refCounts = new int[groups.size()];
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            boxes[g * 6] = group.minX();
            boxes[g * 6 + 1] = group.minY();
            boxes[g * 6 + 2] = group.minZ();
            boxes[g * 6 + 3] = group.maxX();
            boxes[g * 6 + 4] = group.maxY();
            boxes[g * 6 + 5] = group.maxZ();
            flags[g] = group.flags();
            firstRefs[g] = group.firstRef();
            refCounts[g] = group.refCount();
        }
        refPortals = new int[refs.size()];
        refGroups = new int[refs.size()];
        refSides = new int[refs.size()];
        for (int r = 0; r < refs.size(); r++) {
            refPortals[r] = refs.get(r).portal();
            refGroups[r] = refs.get(r).group();
            refSides[r] = refs.get(r).side();
        }
    }

    /**
     * Validates that every group's MOPR range lies inside {@code refs} and every reference names a
     * portal and a group that exist; anything else is refused, and the caller draws without
     * culling.
     */
    public static WmoPortalTraversal of(List<Group> groups, List<Portal> portals, List<Ref> refs) {
        groups = List.copyOf(groups);
        portals = List.copyOf(portals);
        refs = List.copyOf(refs);
        for (var group : groups) {
            if (group.firstRef() < 0
                    || group.refCount() < 0
                    || group.firstRef() > refs.size() - group.refCount()) {
                throw new IllegalArgumentException("Group portal range outside MOPR");
            }
        }
        for (var ref : refs) {
            if (ref.portal() < 0
                    || ref.portal() >= portals.size()
                    || ref.group() < 0
                    || ref.group() >= groups.size()) {
                throw new IllegalArgumentException("Portal reference outside MOPT or groups");
            }
        }
        return new WmoPortalTraversal(groups, portals, refs);
    }

    /**
     * The traversal of one root's retained portals and its groups' headers, in group order. Refused
     * when the portals were not retained, or as {@link #of}: this is where each MOGP portal range
     * is checked against MOPR.
     */
    public static WmoPortalTraversal of(
            WmoFormats.Portals recorded, List<WmoFormats.GroupHeader> headers) {
        return WmoPortalSet.of(recorded, headers).traversal();
    }

    public int groupCount() {
        return groups.size();
    }

    /**
     * References whose group's box centre is not on their recorded side of the portal plane; each
     * is always passable from its group.
     */
    public int inconsistentReferences() {
        return inconsistentCount;
    }

    /** Whether group {@code group} is an interior group (MOGP flag 0x2000). */
    public boolean interior(int group) {
        return (flags[group] & INTERIOR) != 0;
    }

    /**
     * Storage for walks, reused across calls (#383 step 3b): it grows to the largest walk it has
     * served and then allocates nothing. Not thread-safe; one per thread that walks.
     */
    public static final class Scratch {
        private double[] planes = new double[0];
        private int[] counts = new int[0];
        private double[] edges = new double[0];

        private void ensure(int entries) {
            if (counts.length < entries) {
                counts = new int[entries];
                planes = new double[entries * BLOCK];
            }
        }

        private double[] edges(int vertices) {
            if (edges.length < vertices * 4) {
                edges = new double[vertices * 4];
            }
            return edges;
        }
    }

    /** Doubles one queued walk's planes may take: {@link #MAX_PORTAL_PLANES} planes. */
    private static final int BLOCK = MAX_PORTAL_PLANES * 4;

    /** As {@link #visible(double, double, double, List, BitSet)}, into a new result. */
    public Result visible(double x, double y, double z, List<Plane> frustum) {
        var visible = new BitSet(groups.size());
        boolean inside = visible(x, y, z, frustum, visible);
        return new Result(inside, visible);
    }

    /**
     * Sets {@code visible} to the groups visible from a camera at {@code x, y, z} with {@code
     * frustum} (WMO-local, without its near plane) and returns whether the camera is in an interior
     * group.
     */
    public boolean visible(double x, double y, double z, List<Plane> frustum, BitSet visible) {
        var planes = new double[frustum.size() * 4];
        for (int i = 0; i < frustum.size(); i++) {
            var plane = frustum.get(i);
            planes[i * 4] = plane.a();
            planes[i * 4 + 1] = plane.b();
            planes[i * 4 + 2] = plane.c();
            planes[i * 4 + 3] = plane.d();
        }
        return visible(x, y, z, planes, frustum.size(), visible, new Scratch());
    }

    /**
     * As {@link #visible(double, double, double, List, BitSet)}, with the frustum as {@code
     * frustumPlanes} planes {@code a, b, c, d} in {@code frustum} and walk storage from {@code
     * scratch}: once the scratch has grown, a walk allocates nothing.
     */
    public boolean visible(
            double x,
            double y,
            double z,
            double[] frustum,
            int frustumPlanes,
            BitSet visible,
            Scratch scratch) {
        if (frustumPlanes < 0 || frustumPlanes * 4 > frustum.length) {
            throw new IllegalArgumentException("Frustum planes");
        }
        visible.clear();
        Arrays.fill(entries, 0);
        Arrays.fill(widened, false);
        scratch.ensure(queueGroups.length);
        double[] store = scratch.planes;
        int[] counts = scratch.counts;
        int head = 0, tail = 0;
        boolean inside = false;
        int groupCount = flags.length;
        for (int g = 0; g < groupCount; g++) {
            if ((flags[g] & INTERIOR) != 0 && contains(g, x, y, z)) {
                inside = true;
                tail = enter(g, store, counts, -1, 0, visible, tail);
            }
        }
        if (!inside) {
            for (int g = 0; g < groupCount; g++) {
                if ((flags[g] & EXTERIOR) != 0) {
                    tail = enter(g, store, counts, -1, 0, visible, tail);
                }
            }
        }
        if (tail == 0) {
            // No interior box holds the camera and there is no exterior group (an all-interior
            // WMO seen from outside every box): every group is visible, never none.
            visible.set(0, groupCount);
            return false;
        }
        while (head < tail) {
            int at = head++;
            int fromGroup = queueGroups[at];
            int base = at * BLOCK, count = counts[at];
            int first = firstRefs[fromGroup];
            for (int r = first; r < first + refCounts[fromGroup]; r++) {
                int p = refPortals[r];
                var plane = portalPlanes[p];
                double dot = plane[0] * x + plane[1] * y + plane[2] * z + plane[3];
                boolean near = Math.abs(dot) <= PLANE_EPSILON;
                boolean facing = refSides[r] < 0 ? dot <= 0 : dot >= 0;
                boolean trusted = !inconsistent[fromGroup][r - first];
                if (trusted && !facing && !near) {
                    continue;
                }
                var v = portalVertices[p];
                boolean polygonOut =
                        outside(v, frustum, 0, frustumPlanes) || outside(v, store, base, count);
                if (!trusted && !facing && !near) {
                    // From the wrong side the destination may lie on the camera's side of the
                    // portal, so the polygon alone does not say whether it is in view: pass when
                    // either the polygon or the destination group's box is (frustum unnarrowed).
                    int to = refGroups[r];
                    if (polygonOut
                            && (outsideBox(to, frustum, 0, frustumPlanes)
                                    || outsideBox(to, store, base, count))) {
                        continue;
                    }
                } else if (polygonOut) {
                    continue;
                }
                // The edge planes bound the view through the portal only from its recorded side.
                tail =
                        near || !facing
                                ? enter(refGroups[r], store, counts, base, count, visible, tail)
                                : enterNarrowed(
                                        refGroups[r],
                                        p,
                                        x,
                                        y,
                                        z,
                                        base,
                                        count,
                                        scratch,
                                        visible,
                                        tail);
            }
        }
        return inside;
    }

    private boolean contains(int g, double x, double y, double z) {
        int b = g * 6;
        return x >= boxes[b]
                && x <= boxes[b + 3]
                && y >= boxes[b + 1]
                && y <= boxes[b + 4]
                && z >= boxes[b + 2]
                && z <= boxes[b + 5];
    }

    /**
     * Queues {@code group} with {@code count} planes copied from {@code store} at {@code from}
     * (none when {@code from} is negative); returns the new tail.
     */
    private int enter(
            int group,
            double[] store,
            int[] counts,
            int from,
            int count,
            BitSet visible,
            int tail) {
        int slot = admit(group, visible, tail);
        if (slot < 0) {
            return tail;
        }
        if (slot == widenedSlot || from < 0) {
            count = 0;
        } else if (count > 0) {
            System.arraycopy(store, from, store, slot * BLOCK, count * 4);
        }
        counts[slot] = count;
        return tail + 1;
    }

    private int widenedSlot = -1;

    /**
     * Marks {@code group} visible and decides whether it is walked again: the slot to queue it in
     * ({@code tail}), or -1. Past {@link #MAX_ENTRIES_PER_GROUP} narrowed walks, a group is walked
     * once more with the caller's frustum alone ({@link #widenedSlot} names that slot).
     */
    private int admit(int group, BitSet visible, int tail) {
        visible.set(group);
        widenedSlot = -1;
        if (entries[group] < MAX_ENTRIES_PER_GROUP) {
            entries[group]++;
        } else if (!widened[group]) {
            // The caller's frustum alone holds every narrowed one this group could still get.
            widened[group] = true;
            widenedSlot = tail;
        } else {
            return -1;
        }
        queueGroups[tail] = group;
        return tail;
    }

    /**
     * Queues {@code group} with the parent's planes at {@code from} plus one plane through the
     * camera and each edge of portal {@code portal} that keeps every vertex of the portal inside,
     * keeping the newest {@link #MAX_PORTAL_PLANES}.
     */
    private int enterNarrowed(
            int group,
            int portal,
            double x,
            double y,
            double z,
            int from,
            int count,
            Scratch scratch,
            BitSet visible,
            int tail) {
        int slot = admit(group, visible, tail);
        if (slot < 0) {
            return tail;
        }
        double[] store = scratch.planes;
        int[] counts = scratch.counts;
        if (slot == widenedSlot) {
            counts[slot] = 0;
            return tail + 1;
        }
        var v = portalVertices[portal];
        var centroid = centroids[portal];
        int n = v.length / 3;
        double[] edges = scratch.edges(n);
        int found = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = v[i * 3] - x, ay = v[i * 3 + 1] - y, az = v[i * 3 + 2] - z;
            double bx = v[j * 3] - x, by = v[j * 3 + 1] - y, bz = v[j * 3 + 2] - z;
            double a = ay * bz - az * by, b = az * bx - ax * bz, c = ax * by - ay * bx;
            double length = Math.sqrt(a * a + b * b + c * c);
            if (!(length > 1e-9)) {
                continue; // A degenerate edge (repeated vertex, camera on the edge) adds nothing.
            }
            a /= length;
            b /= length;
            c /= length;
            double d = -(a * x + b * y + c * z);
            double centre = a * centroid[0] + b * centroid[1] + c * centroid[2] + d;
            if (Math.abs(centre) <= 1e-9) {
                continue;
            }
            if (centre < 0) {
                a = -a;
                b = -b;
                c = -c;
                d = -d;
            }
            if (holdsEvery(a, b, c, d, v)) {
                edges[found * 4] = a;
                edges[found * 4 + 1] = b;
                edges[found * 4 + 2] = c;
                edges[found * 4 + 3] = d;
                found++;
            }
        }
        // The newest MAX_PORTAL_PLANES of the parent's planes followed by the edges.
        int keptEdges = Math.min(found, MAX_PORTAL_PLANES);
        int keptParent = Math.min(count, MAX_PORTAL_PLANES - keptEdges);
        int to = slot * BLOCK;
        System.arraycopy(store, from + (count - keptParent) * 4, store, to, keptParent * 4);
        System.arraycopy(edges, (found - keptEdges) * 4, store, to + keptParent * 4, keptEdges * 4);
        counts[slot] = keptParent + keptEdges;
        return tail + 1;
    }

    /** Whether all eight corners of group {@code g}'s box are outside one of the planes. */
    private boolean outsideBox(int g, double[] planes, int from, int count) {
        int b = g * 6;
        for (int i = 0; i < count; i++) {
            int at = from + i * 4;
            boolean all = true;
            for (int corner = 0; corner < 8 && all; corner++) {
                double cx = boxes[b + ((corner & 1) == 0 ? 0 : 3)];
                double cy = boxes[b + ((corner & 2) == 0 ? 1 : 4)];
                double cz = boxes[b + ((corner & 4) == 0 ? 2 : 5)];
                all =
                        planes[at] * cx + planes[at + 1] * cy + planes[at + 2] * cz + planes[at + 3]
                                < -1e-6;
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean outside(double[] v, double[] planes, int from, int count) {
        for (int i = 0; i < count; i++) {
            int at = from + i * 4;
            boolean all = true;
            for (int k = 0; k < v.length && all; k += 3) {
                all =
                        planes[at] * v[k]
                                        + planes[at + 1] * v[k + 1]
                                        + planes[at + 2] * v[k + 2]
                                        + planes[at + 3]
                                < -1e-6;
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsEvery(double a, double b, double c, double d, double[] v) {
        for (int i = 0; i < v.length; i += 3) {
            if (a * v[i] + b * v[i + 1] + c * v[i + 2] + d < -1e-6) {
                return false;
            }
        }
        return true;
    }
}
