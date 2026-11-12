package com.vibeminer.concepts.world;

import java.util.function.IntUnaryOperator;

/**
 * The vertex light of an interior WMO group: its first MOCV as the client adjusts it at load
 * ({@code CMapObjGroup::FixColorVertexAlpha}), plus the building's MOHD ambient colour that its
 * shader adds back.
 *
 * <p>Sources: the wowdev.wiki WMO page (MOHD flags, MOCV, FixColorVertexAlpha), and the open-source
 * WebWowViewerCpp port of that function ({@code WmoGroupGeom::fixColorVertexAlpha}) and its WMO
 * shader, which lights an interior vertex with the ambient plus twice the adjusted colour ({@code
 * calcLight}: {@code precomputedLight = vColor.rgb * 2}, {@code adjAmbient = ambient +
 * precomputedLight}). Per channel, on 0..255 values:
 *
 * <ul>
 *   <li>MOHD flag 0x8: the colour is kept; the light is {@code 2 * c + ambient}.
 *   <li>Otherwise, vertices of the group's first {@code transBatchCount} batches: {@code f = (c -
 *       base) * (1 - a / 255) / 2}, floored at 0; others: {@code f = ((c * a >> 6) + c - base) /
 *       2}, clamped to 0..255. {@code base} is the ambient, or 0 with MOHD flag 0x2 (skip base
 *       colour). The light is {@code 2 * f + ambient}, the ambient added either way.
 * </ul>
 *
 * <p>So an interior vertex is never darker than the ambient, and its MOCV alpha brightens it.
 *
 * <p>A transition vertex whose colour is under the base is floored at 0. The open-source port
 * subtracts in unsigned bytes, so such a channel wraps around to a bright value there; wowdev
 * describes a plain subtraction. On build 70862 one transition vertex of 50,448 in the interiors
 * around Stormwind, Goldshire, Ironforge, Orgrimmar, Thunder Bluff and Shadowfang Keep has such a
 * channel (none of the 67 buildings there sets MOHD 0x2 without 0x8). {@code AttenTransVerts} (the
 * transition batches' fade toward a portal to an outdoor group) is not applied: its vertices are
 * lit as if no such portal were near, as the client lights them when none is.
 *
 * <p>The light can reach 3.0 (two full colours and a full ambient), but it reaches the shader as a
 * vertex colour of one byte per channel, the light itself, so it is kept to 1.0. On build 70862 the
 * interiors around Stormwind, Thunder Bluff and Shadowfang Keep have 2-5% of their vertices above
 * it. Not applied either, because each needs the WMO shader: the client's blend of transition
 * vertices toward the outdoor light by their alpha, and its interior light direction term.
 */
final class WmoInteriorLight {
    private WmoInteriorLight() {}

    /** MOHD flag 0x8: the colour adjustment keeps the MOCV colour (only its alpha changes). */
    static final int KEEP_COLOUR = 0x8;

    /**
     * MOHD flag 0x2 (skip base colour): the colour adjustment subtracts nothing instead of the
     * ambient; the shader still adds the ambient. MOHD 0x8 takes precedence.
     */
    static final int SKIP_BASE_COLOUR = 0x2;

    /**
     * Each vertex's light, packed as {@link WorldExteriorObjects#packColour} packs a colour. {@code
     * mocv} gives the unsigned bytes B, G, R, A per vertex, or is null for a group without one,
     * which is lit by the ambient alone. Vertices below {@code transVertices} belong to the
     * transition batches. {@code ambient} is MOHD ambColor as stored (B, G, R, A bytes of a
     * little-endian u32).
     */
    static int[] colours(
            IntUnaryOperator mocv, int count, int transVertices, long ambient, int flags) {
        int ab = (int) (ambient & 0xff), ag = (int) (ambient >> 8 & 0xff);
        int ar = (int) (ambient >> 16 & 0xff);
        var colours = new int[count];
        for (int v = 0; v < count; v++) {
            if (mocv == null) {
                colours[v] =
                        WorldExteriorObjects.packColour(light(0, ab), light(0, ag), light(0, ar));
                continue;
            }
            int b = mocv.applyAsInt(v * 4) & 0xff, g = mocv.applyAsInt(v * 4 + 1) & 0xff;
            int r = mocv.applyAsInt(v * 4 + 2) & 0xff, a = mocv.applyAsInt(v * 4 + 3) & 0xff;
            boolean trans = v < transVertices;
            colours[v] =
                    WorldExteriorObjects.packColour(
                            light(fixed(b, a, ab, trans, flags), ab),
                            light(fixed(g, a, ag, trans, flags), ag),
                            light(fixed(r, a, ar, trans, flags), ar));
        }
        return colours;
    }

    /** One channel after FixColorVertexAlpha, 0..255. */
    static int fixed(int c, int a, int ambient, boolean trans, int flags) {
        if ((flags & KEEP_COLOUR) != 0) {
            return c;
        }
        int base = (flags & SKIP_BASE_COLOUR) != 0 ? 0 : ambient;
        if (trans) {
            float less = c - base;
            return (int) Math.max(0f, (float) Math.floor((less - a / 255f * less) / 2f));
        }
        int sum = (c * a >> 6) + c - base;
        return (int) Math.min(255f, Math.max(sum / 2f, 0f));
    }

    /** The light {@code 2 * fixed + ambient}, kept to 255 (a light of 1.0). */
    static int light(int fixed, int ambient) {
        return Math.min(255, 2 * fixed + ambient);
    }
}
