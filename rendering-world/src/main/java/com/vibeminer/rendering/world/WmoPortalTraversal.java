package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WmoFormats;
import java.util.ArrayList;
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
 * <p>An instance keeps its scratch between calls so a per-frame walk allocates only the narrowed
 * plane lists; it is not thread-safe.
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
    // Scratch reused by every walk.
    private final int[] entries;
    private final boolean[] widened;
    private final int[] queueGroups;
    private final List<List<Plane>> queuePlanes;

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
        entries = new int[groups.size()];
        widened = new boolean[groups.size()];
        // Each group enters at most MAX_ENTRIES_PER_GROUP + 1 times.
        queueGroups = new int[groups.size() * (MAX_ENTRIES_PER_GROUP + 1)];
        queuePlanes = new ArrayList<>(queueGroups.length);
        for (int i = 0; i < queueGroups.length; i++) {
            queuePlanes.add(List.of());
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
        if (!recorded.retained()) {
            throw new IllegalArgumentException("Portals not retained");
        }
        var groups = new ArrayList<Group>(headers.size());
        for (var h : headers) {
            groups.add(
                    new Group(
                            h.flags(),
                            h.lower().x(),
                            h.lower().y(),
                            h.lower().z(),
                            h.upper().x(),
                            h.upper().y(),
                            h.upper().z(),
                            h.firstPortal(),
                            h.portalCount()));
        }
        var portals = new ArrayList<Portal>(recorded.portals().size());
        for (var p : recorded.portals()) {
            var vertices = new double[p.vertexCount() * 3];
            for (int i = 0; i < p.vertexCount(); i++) {
                var v = recorded.vertex(p.firstVertex() + i);
                vertices[i * 3] = v.x();
                vertices[i * 3 + 1] = v.y();
                vertices[i * 3 + 2] = v.z();
            }
            portals.add(
                    new Portal(
                            vertices,
                            p.normal().x(),
                            p.normal().y(),
                            p.normal().z(),
                            p.distance()));
        }
        var refs = new ArrayList<Ref>(recorded.refs().size());
        for (var r : recorded.refs()) {
            refs.add(new Ref(r.portal(), r.group(), r.side()));
        }
        return of(groups, portals, refs);
    }

    public int groupCount() {
        return groups.size();
    }

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
        visible.clear();
        Arrays.fill(entries, 0);
        Arrays.fill(widened, false);
        int head = 0, tail = 0;
        boolean inside = false;
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            if (group.interior() && group.contains(x, y, z)) {
                inside = true;
                tail = enter(g, List.of(), visible, tail);
            }
        }
        if (!inside) {
            for (int g = 0; g < groups.size(); g++) {
                if (groups.get(g).exterior()) {
                    tail = enter(g, List.of(), visible, tail);
                }
            }
        }
        if (tail == 0) {
            // No interior box holds the camera and there is no exterior group (an all-interior
            // WMO seen from outside every box): every group is visible, never none.
            visible.set(0, groups.size());
            return false;
        }
        while (head < tail) {
            int at = head++;
            var from = groups.get(queueGroups[at]);
            var planes = queuePlanes.get(at);
            queuePlanes.set(at, List.of());
            for (int r = from.firstRef(); r < from.firstRef() + from.refCount(); r++) {
                var ref = refs.get(r);
                int p = ref.portal();
                var plane = portalPlanes[p];
                double dot = plane[0] * x + plane[1] * y + plane[2] * z + plane[3];
                boolean near = Math.abs(dot) <= PLANE_EPSILON;
                boolean facing = ref.side() < 0 ? dot <= 0 : dot >= 0;
                if (!facing && !near) {
                    continue;
                }
                if (outside(portalVertices[p], frustum) || outside(portalVertices[p], planes)) {
                    continue;
                }
                var narrowed = near ? planes : narrow(planes, p, x, y, z);
                tail = enter(ref.group(), narrowed, visible, tail);
            }
        }
        return inside;
    }

    private int enter(int group, List<Plane> planes, BitSet visible, int tail) {
        visible.set(group);
        if (entries[group] < MAX_ENTRIES_PER_GROUP) {
            entries[group]++;
        } else if (!widened[group]) {
            // The caller's frustum alone holds every narrowed one this group could still get.
            widened[group] = true;
            planes = List.of();
        } else {
            return tail;
        }
        queueGroups[tail] = group;
        queuePlanes.set(tail, planes);
        return tail + 1;
    }

    private static boolean outside(double[] v, List<Plane> planes) {
        for (var plane : planes) {
            boolean all = true;
            for (int i = 0; i < v.length && all; i += 3) {
                all = plane.value(v[i], v[i + 1], v[i + 2]) < -1e-6;
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * The portal planes plus one plane through the camera and each portal edge that keeps every
     * vertex of the portal inside.
     */
    private List<Plane> narrow(List<Plane> planes, int portal, double x, double y, double z) {
        var v = portalVertices[portal];
        var centroid = centroids[portal];
        int n = v.length / 3;
        var result = new ArrayList<Plane>(planes.size() + n);
        result.addAll(planes);
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
            var edge = centre > 0 ? new Plane(a, b, c, d) : new Plane(-a, -b, -c, -d);
            if (holdsEvery(edge, v)) {
                result.add(edge);
            }
        }
        int excess = result.size() - MAX_PORTAL_PLANES;
        return excess > 0 ? List.copyOf(result.subList(excess, result.size())) : result;
    }

    private static boolean holdsEvery(Plane plane, double[] v) {
        for (int i = 0; i < v.length; i += 3) {
            if (plane.value(v[i], v[i + 1], v[i + 2]) < -1e-6) {
                return false;
            }
        }
        return true;
    }
}
