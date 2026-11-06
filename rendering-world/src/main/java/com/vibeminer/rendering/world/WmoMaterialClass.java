package com.vibeminer.rendering.world;

/**
 * The one MOMT material mapping shared by the outdoor objects and the dungeon viewer (issues #367,
 * #375, #297), so both draw a material the same way. A material becomes a range class: a kind from
 * its blend mode (word 2), plus flags from its flags word (word 0).
 *
 * <ul>
 *   <li>Blend modes (EGxBlend): 0 opaque, 1 alpha key (cutout), 2 alpha blend, 3 additive. Higher
 *       modes (modulate and their variants) are not pinned and stay unsupported.
 *   <li>F_UNLIT (0x1): drawn at full intensity, without the light.
 *   <li>F_UNCULLED (0x4), documented as "two-sided": {@link WorldObjectPicking#TWO_SIDED}.
 *   <li>Every other bit (F_UNFOGGED, F_EXTLIGHT, F_SIDN, F_WINDOW, the clamp bits and the
 *       undocumented rest) names no formula to apply: it is ignored, and callers count it.
 * </ul>
 */
public final class WmoMaterialClass {
    /** MOMT flags bit 0x1, F_UNLIT. */
    public static final long F_UNLIT = 0x1;

    /** MOMT flags bit 0x4, F_UNCULLED: two-sided. */
    public static final long F_UNCULLED = 0x4;

    /** MOMT flags this mapping applies. */
    public static final long APPLIED = F_UNLIT | F_UNCULLED;

    /** Returned by {@link #of} for a blend mode that is not supported. */
    public static final int UNSUPPORTED = -1;

    private WmoMaterialClass() {}

    /**
     * The range class of a material from its flags (word 0) and blend mode (word 2), or {@link
     * #UNSUPPORTED} for a blend mode above additive.
     */
    public static int of(long flags, long blendMode) {
        if (blendMode < 0 || blendMode > WorldObjectPicking.ADDITIVE) {
            return UNSUPPORTED;
        }
        int rangeClass = (int) blendMode;
        if ((flags & F_UNLIT) != 0) {
            rangeClass |= WorldObjectPicking.UNLIT;
        }
        if ((flags & F_UNCULLED) != 0) {
            rangeClass |= WorldObjectPicking.TWO_SIDED;
        }
        return rangeClass;
    }

    /** Whether a flags word sets a bit this mapping does not apply. */
    public static boolean ignoresFlags(long flags) {
        return (flags & ~APPLIED) != 0;
    }
}
