package com.vimi.gui;

import com.vibeminer.concepts.world.WorldGlobalScenes;
import com.vibeminer.rendering.bgfx.WorldGlobalTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detached group, floor and entrance facts for one qualified global interior. Built off FX from
 * the recorded WMO facts; every entry is either recorded by a source or explicitly unknown.
 */
record GlobalInteriorOutline(List<Group> groups, String floors, String entrance) {
    /** No loaded source records an instance entrance; it is never derived from geometry. */
    static final String ENTRANCE_UNKNOWN =
            "Entrance: unknown · no loaded source records an entrance for this instance";

    /** Floors are never inferred from height; no qualified UiMap/WMO-group link is read yet. */
    static final String FLOORS_UNQUALIFIED =
            "Floors: not linked · no qualified UiMap/WMO-group floor source for this interior";

    /**
     * One recorded WMO group. {@code name} is the MOGN name at the recorded offset, or null when
     * unreadable. {@code bounds} is the recorded MOGP box in native space clipped to the drawn
     * scene bounds, or null when it cannot frame a camera.
     */
    record Group(int ordinal, long fileId, String name, long flags, float[] bounds) {
        Group {
            bounds = bounds == null ? null : bounds.clone();
        }

        @Override
        public float[] bounds() {
            return bounds == null ? null : bounds.clone();
        }

        String label() {
            String recorded =
                    name == null ? "name not recorded" : name.isEmpty() ? "unnamed" : name;
            return "Group " + ordinal + " · " + recorded;
        }

        String details() {
            return flagText(flags)
                    + " · file #"
                    + fileId
                    + (bounds == null ? " · bounds outside the camera range" : "");
        }

        /** Inspection camera at the centre of the recorded group box, looking along +x. */
        WorldGlobalTransport.Camera camera() {
            if (bounds == null) {
                throw new IllegalStateException("Group has no usable bounds");
            }
            return new WorldGlobalTransport.Camera(
                    (bounds[0] + (double) bounds[3]) * .5,
                    (bounds[1] + (double) bounds[4]) * .5,
                    (bounds[2] + (double) bounds[5]) * .5,
                    0,
                    0);
        }
    }

    GlobalInteriorOutline {
        groups = List.copyOf(groups);
        Objects.requireNonNull(floors);
        Objects.requireNonNull(entrance);
    }

    static GlobalInteriorOutline of(List<WorldGlobalScenes.Group> recorded, float[] sceneBounds) {
        Objects.requireNonNull(recorded);
        Objects.requireNonNull(sceneBounds);
        if (sceneBounds.length != 6) {
            throw new IllegalArgumentException("Scene bounds need six values");
        }
        var groups = new ArrayList<Group>(recorded.size());
        for (var group : recorded) {
            groups.add(
                    new Group(
                            group.ordinal(),
                            group.id(),
                            group.recordedName().orElse(null),
                            group.flags(),
                            clip(group.nativeBounds(), sceneBounds)));
        }
        return new GlobalInteriorOutline(groups, FLOORS_UNQUALIFIED, ENTRANCE_UNKNOWN);
    }

    static GlobalInteriorOutline empty() {
        return new GlobalInteriorOutline(List.of(), FLOORS_UNQUALIFIED, ENTRANCE_UNKNOWN);
    }

    /**
     * Intersection of the recorded group box and the drawn scene box. Qualification proved every
     * group vertex lies in both, so the intersection still contains the whole group mesh.
     */
    static float[] clip(float[] group, float[] scene) {
        if (group == null || group.length != 6 || scene.length != 6) {
            return null;
        }
        var result = new float[6];
        for (int axis = 0; axis < 3; axis++) {
            float low = Math.max(group[axis], scene[axis]);
            float high = Math.min(group[axis + 3], scene[axis + 3]);
            if (!Float.isFinite(low)
                    || !Float.isFinite(high)
                    || low > high
                    || Math.abs(low) > 4096
                    || Math.abs(high) > 4096) {
                return null;
            }
            result[axis] = low;
            result[axis + 3] = high;
        }
        return result;
    }

    private static final long[] FLAG_BITS = {0x1, 0x4, 0x8, 0x40, 0x200, 0x800, 0x1000, 0x2000};
    private static final String[] FLAG_NAMES = {
        "BSP", "vertex colors", "outdoor", "exterior lit", "lights", "doodads", "liquid", "indoor"
    };

    /** Raw recorded MOGP flags with the commonly documented bits named; others stay raw. */
    static String flagText(long flags) {
        var names = new ArrayList<String>();
        long rest = flags;
        for (int i = 0; i < FLAG_BITS.length; i++) {
            if ((flags & FLAG_BITS[i]) != 0) {
                names.add(FLAG_NAMES[i]);
                rest &= ~FLAG_BITS[i];
            }
        }
        if (rest != 0) {
            names.add("other 0x" + Long.toHexString(rest));
        }
        return "flags 0x"
                + Long.toHexString(flags)
                + (names.isEmpty() ? "" : " (" + String.join(", ", names) + ")");
    }
}
