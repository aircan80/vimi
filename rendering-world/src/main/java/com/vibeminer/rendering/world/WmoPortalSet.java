package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.rendering.world.WmoPortalTraversal.Group;
import com.vibeminer.rendering.world.WmoPortalTraversal.Portal;
import com.vibeminer.rendering.world.WmoPortalTraversal.Ref;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * One WMO root's retained portals (MOPV, MOPT, MOPR) and its LOD0 group headers, in group order, as
 * the World objects carry them to the renderer (#383 step 3a). Values come from the files' 32-bit
 * floats, so carrying them as floats is exact. Validated as {@link WmoPortalTraversal#of} validates
 * them, plus finite values within {@link #MAX_VALUE}, ordered boxes, unsigned 32-bit group flags
 * and signed 16-bit sides.
 */
public record WmoPortalSet(List<Group> groups, List<Portal> portals, List<Ref> refs) {
    /**
     * Per-tile bounds on carried sets: at most one per building root (24), and one group per group
     * a tile reads (256) plus every group of one root drawn by group (512, the most groups a root
     * may have), whose set holds all of its groups although the tile reads only its own; the
     * portal, vertex and reference tables are sized so one large root fits whole. A set over what
     * is left of a tile is not carried, and its root draws unculled (a root drawn by group draws no
     * interior instead).
     */
    public static final int MAX_TILE_SETS = 24, MAX_TILE_GROUPS = 256 + 512;

    public static final int MAX_TILE_PORTALS = 2048, MAX_TILE_VERTICES = 8192;
    public static final int MAX_TILE_REFS = 4096;

    /**
     * Largest magnitude of a box, plane or vertex value, the objects file's own bound: a set
     * outside it is refused here, where only its root's set is dropped.
     */
    public static final int MAX_VALUE = 1_000_000;

    public WmoPortalSet {
        groups = List.copyOf(groups);
        portals = List.copyOf(portals);
        refs = List.copyOf(refs);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("A portal set needs its groups");
        }
        for (var g : groups) {
            if (g.flags() < 0
                    || g.flags() > 0xffff_ffffL
                    || !finite(g.minX(), g.minY(), g.minZ(), g.maxX(), g.maxY(), g.maxZ())
                    || g.minX() > g.maxX()
                    || g.minY() > g.maxY()
                    || g.minZ() > g.maxZ()) {
                throw new IllegalArgumentException("Portal set group");
            }
        }
        for (var p : portals) {
            if (!finite(p.vertices()) || !finite(p.nx(), p.ny(), p.nz(), p.distance())) {
                throw new IllegalArgumentException("Portal set portal");
            }
        }
        for (var r : refs) {
            if (r.side() < Short.MIN_VALUE || r.side() > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Portal set side");
            }
        }
        // Ranges and references; the traversal is rebuilt where it is used.
        WmoPortalTraversal.of(groups, portals, refs);
    }

    /** Portal vertices over all portals. */
    public int vertexCount() {
        int count = 0;
        for (var p : portals) {
            count += p.vertices().length / 3;
        }
        return count;
    }

    public WmoPortalTraversal traversal() {
        return WmoPortalTraversal.of(groups, portals, refs);
    }

    /**
     * The set of one root's retained portals and its groups' headers, in group order. Refused when
     * the portals were not retained, or as the constructor: this is where each MOGP portal range is
     * checked against MOPR.
     */
    public static WmoPortalSet of(
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
        var refs = new ArrayList<Ref>(recorded.refs().size());
        for (var r : recorded.refs()) {
            refs.add(new Ref(r.portal(), r.group(), r.side()));
        }
        return new WmoPortalSet(groups, portals(recorded), refs);
    }

    private static List<Portal> portals(WmoFormats.Portals recorded) {
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
        return portals;
    }

    /**
     * The set of one root's retained portals with every LOD0 group described by its root alone:
     * flags and box from MOGI, and its MOPR range from the references themselves ({@link
     * #refRanges}), so no group file is read. This is the set of a root drawn by group, whose tile
     * reads only the groups it owns. On real data MOGI flags and boxes equal the MOGP ones, and the
     * recovered ranges equal the MOGP ranges except where a portal is referenced from one side only
     * (see {@link #refRanges}). Refused when the portals or the MOGI bounds were not retained, when
     * the ranges cannot be recovered, or as the constructor.
     */
    public static WmoPortalSet ofGroupInfo(WmoFormats.Root root) {
        var recorded = root.portals();
        if (!recorded.retained() || !root.groupInfoBoundsRetained()) {
            throw new IllegalArgumentException("Portals or group info not retained");
        }
        int count = (int) root.header().groupCount();
        var refs = new ArrayList<Ref>(recorded.refs().size());
        for (var r : recorded.refs()) {
            refs.add(new Ref(r.portal(), r.group(), r.side()));
        }
        int[] ranges = refRanges(refs, count);
        if (ranges == null) {
            throw new IllegalArgumentException("Portal references not recoverable per group");
        }
        var groups = new ArrayList<Group>(count);
        for (int g = 0; g < count; g++) {
            var lower = root.groupInfoLower(g);
            var upper = root.groupInfoUpper(g);
            groups.add(
                    new Group(
                            root.groupInfoFlags(g),
                            lower.x(),
                            lower.y(),
                            lower.z(),
                            upper.x(),
                            upper.y(),
                            upper.z(),
                            ranges[g * 2],
                            ranges[g * 2 + 1]));
        }
        return new WmoPortalSet(groups, portals(recorded), refs);
    }

    /**
     * Each group's MOPR range ({@code first, count} per group), recovered from the references
     * alone. A reference names the group it leads to, not the group it leads from; but a portal
     * between two groups is referenced once from each side, so each of its two references leads
     * from the group the other one names. MOPR lists each group's references together, so a group
     * owns the span from its first such reference to its last. A reference whose portal is not
     * referenced exactly twice (Stormwind has ten from one group to another that never refers back)
     * cannot be placed that way: inside a group's span it is that group's, as MOGP says; between
     * two groups' spans it is given to both, so the walk may follow it from the wrong group (and
     * draw more), never miss it. Null when the spans of two groups overlap, or when references
     * exist but none is paired.
     */
    public static int[] refRanges(List<Ref> refs, int groupCount) {
        int n = refs.size();
        int[] from = new int[n];
        Arrays.fill(from, -1);
        var byPortal = new HashMap<Integer, int[]>();
        for (int i = 0; i < n; i++) {
            int[] seen = byPortal.computeIfAbsent(refs.get(i).portal(), k -> new int[] {-1, -1, 0});
            if (seen[2] < 2) {
                seen[seen[2]] = i;
            }
            seen[2]++;
        }
        for (int[] seen : byPortal.values()) {
            if (seen[2] == 2) {
                from[seen[0]] = refs.get(seen[1]).group();
                from[seen[1]] = refs.get(seen[0]).group();
            }
        }
        int[] first = new int[groupCount], last = new int[groupCount];
        Arrays.fill(first, -1);
        Arrays.fill(last, -1);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int g = from[i];
            if (g < 0) {
                continue;
            }
            if (g >= groupCount) {
                return null;
            }
            any = true;
            if (first[g] < 0) {
                first[g] = i;
            }
            last[g] = i;
        }
        if (n > 0 && !any) {
            return null;
        }
        // No paired reference of another group may lie inside a group's span: walking the
        // references in order, a group whose span has ended never appears again.
        var ended = new boolean[groupCount];
        int current = -1;
        for (int i = 0; i < n; i++) {
            int g = from[i];
            if (g < 0 || g == current) {
                continue;
            }
            if (ended[g]) {
                return null;
            }
            if (current >= 0) {
                ended[current] = true;
            }
            current = g;
        }
        // Each run of unplaced references outside every span goes to the spans on both sides.
        for (int i = 0; i < n; ) {
            if (from[i] >= 0) {
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < n && from[end + 1] < 0) {
                end++;
            }
            int before = i > 0 ? from[i - 1] : -1, after = end + 1 < n ? from[end + 1] : -1;
            if (before < 0 || before != after) {
                if (before >= 0) {
                    last[before] = end;
                }
                if (after >= 0) {
                    first[after] = i;
                }
            }
            i = end + 1;
        }
        int[] ranges = new int[groupCount * 2];
        for (int g = 0; g < groupCount; g++) {
            if (first[g] >= 0) {
                ranges[g * 2] = first[g];
                ranges[g * 2 + 1] = last[g] - first[g] + 1;
            }
        }
        return ranges;
    }

    /** Finite and within {@link #MAX_VALUE}. */
    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value) || Math.abs(value) > MAX_VALUE) {
                return false;
            }
        }
        return true;
    }
}
