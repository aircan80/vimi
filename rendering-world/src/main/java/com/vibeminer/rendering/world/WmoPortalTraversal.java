package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WmoFormats;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Which groups of one WMO are visible from a camera, by walking its portals (#383). Everything is
 * in WMO-local axes; the caller transforms the camera and its frustum into them.
 *
 * <p>The walk starts from the group the camera is in, or, when it is in no interior group, from
 * every exterior group. From each group it follows that group's MOPR references: a portal is
 * passable when the camera is on the referencing group's side of it and the portal polygon is not
 * outside the current frustum; the group behind it is visible and is walked with the frustum
 * narrowed to the portal's edges. Groups reached by no portal are not visible.
 *
 * <p>Pinned conventions and approximations (no per-WMO cases):
 *
 * <ul>
 *   <li>Side sign: the camera is on the referencing group's side when {@code side < 0 ? dot <= 0 :
 *       dot >= 0}, with {@code dot = normal . camera + distance} on the recorded MOPT plane
 *       (WebWowViewerCpp {@code wmoObject.cpp} at 56402e5, {@code isInsidePortalThis}; wowdev.wiki
 *       WMO leaves the sign undefined). To be checked with the #423 probe on real WMOs.
 *   <li>Near a portal plane ({@code |dot| <= }{@link #PLANE_EPSILON}) the edge planes degenerate,
 *       so the portal is passed with the current frustum unchanged, whichever side the camera is on
 *       (as WebWowViewerCpp's near-portal case).
 *   <li>Camera group: the smallest-volume interior group (MOGP flag 0x2000) whose bounding box
 *       holds the camera. The client resolves this with the group BSP (MOBN/MOBR), which is not
 *       read; overlapping boxes can therefore pick a neighbor, and the walk then starts one room
 *       off. This errs toward drawing more, never toward a hollow room the camera is in.
 *   <li>A portal is culled only when all its vertices are outside one frustum plane, a conservative
 *       polygon test. Narrowed frusta keep the caller's planes plus at most {@link
 *       #MAX_PORTAL_PLANES} portal edge planes, dropping the oldest first, which only widens them.
 *   <li>Each group is walked at most {@link #MAX_ENTRIES_PER_GROUP} times, so cycles terminate.
 * </ul>
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

        double volume() {
            return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        }
    }

    /** One portal polygon ({@code x, y, z} per vertex) and its recorded plane. */
    public record Portal(double[] vertices, double nx, double ny, double nz, double distance) {
        public Portal {
            vertices = vertices.clone();
            if (vertices.length < 9 || vertices.length % 3 != 0) {
                throw new IllegalArgumentException("A portal needs at least three vertices");
            }
        }

        double dot(double x, double y, double z) {
            return nx * x + ny * y + nz * z + distance;
        }
    }

    /** One MOPR reference: from the referencing group through {@code portal} to {@code group}. */
    public record Ref(int portal, int group, int side) {}

    /** The camera's group ({@code -1} when it is in no interior group) and the visible groups. */
    public record Result(int cameraGroup, BitSet visible) {
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
    private final List<Portal> portals;
    private final List<Ref> refs;

    private WmoPortalTraversal(List<Group> groups, List<Portal> portals, List<Ref> refs) {
        this.groups = groups;
        this.portals = portals;
        this.refs = refs;
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
     * when the portals were not retained, or as {@link #of}.
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

    /** The visible groups from a camera at {@code x, y, z} with {@code frustum} (WMO-local). */
    public Result visible(double x, double y, double z, List<Plane> frustum) {
        var base = List.copyOf(frustum);
        var visible = new BitSet(groups.size());
        var entries = new int[groups.size()];
        var work = new ArrayDeque<Entry>();
        int camera = cameraGroup(x, y, z);
        if (camera >= 0) {
            enter(camera, List.of(), visible, entries, work);
        } else {
            for (int g = 0; g < groups.size(); g++) {
                if (groups.get(g).exterior()) {
                    enter(g, List.of(), visible, entries, work);
                }
            }
        }
        while (!work.isEmpty()) {
            var entry = work.poll();
            var from = groups.get(entry.group);
            for (int r = from.firstRef(); r < from.firstRef() + from.refCount(); r++) {
                var ref = refs.get(r);
                var portal = portals.get(ref.portal());
                double dot = portal.dot(x, y, z);
                boolean near = Math.abs(dot) <= PLANE_EPSILON;
                boolean facing = ref.side() < 0 ? dot <= 0 : dot >= 0;
                if (!facing && !near) {
                    continue;
                }
                if (outside(portal, base) || outside(portal, entry.portalPlanes)) {
                    continue;
                }
                var narrowed =
                        near ? entry.portalPlanes : narrow(entry.portalPlanes, portal, x, y, z);
                enter(ref.group(), narrowed, visible, entries, work);
            }
        }
        return new Result(camera, visible);
    }

    private record Entry(int group, List<Plane> portalPlanes) {}

    private void enter(
            int group,
            List<Plane> portalPlanes,
            BitSet visible,
            int[] entries,
            ArrayDeque<Entry> work) {
        visible.set(group);
        if (entries[group] < MAX_ENTRIES_PER_GROUP) {
            entries[group]++;
            work.add(new Entry(group, portalPlanes));
        }
    }

    /** The smallest interior group whose box holds the point, or -1. */
    int cameraGroup(double x, double y, double z) {
        int best = -1;
        double volume = Double.POSITIVE_INFINITY;
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            if (group.interior() && group.contains(x, y, z) && group.volume() < volume) {
                best = g;
                volume = group.volume();
            }
        }
        return best;
    }

    private static boolean outside(Portal portal, List<Plane> planes) {
        var v = portal.vertices();
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

    /** The portal planes plus one plane through the camera and each portal edge. */
    private static List<Plane> narrow(
            List<Plane> planes, Portal portal, double x, double y, double z) {
        var v = portal.vertices();
        int n = v.length / 3;
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            cx += v[i * 3] / n;
            cy += v[i * 3 + 1] / n;
            cz += v[i * 3 + 2] / n;
        }
        var edges = new ArrayList<Plane>(n);
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
            double centre = a * cx + b * cy + c * cz + d;
            if (Math.abs(centre) <= 1e-9) {
                continue;
            }
            edges.add(centre > 0 ? new Plane(a, b, c, d) : new Plane(-a, -b, -c, -d));
        }
        var result = new ArrayList<Plane>(planes.size() + edges.size());
        result.addAll(planes);
        result.addAll(edges);
        int excess = result.size() - MAX_PORTAL_PLANES;
        return excess > 0
                ? List.copyOf(result.subList(excess, result.size()))
                : List.copyOf(result);
    }
}
