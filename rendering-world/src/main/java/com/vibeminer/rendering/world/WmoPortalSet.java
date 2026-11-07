package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.rendering.world.WmoPortalTraversal.Group;
import com.vibeminer.rendering.world.WmoPortalTraversal.Portal;
import com.vibeminer.rendering.world.WmoPortalTraversal.Ref;
import java.util.ArrayList;
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
     * Per-tile bounds on carried sets: at most one per building root (24) and one group per group a
     * tile reads (256); the portal, vertex and reference tables are sized so one large root fits
     * whole. A set over what is left of a tile is not carried, and its root draws unculled.
     */
    public static final int MAX_TILE_SETS = 24, MAX_TILE_GROUPS = 256;

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
        return new WmoPortalSet(groups, portals, refs);
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
