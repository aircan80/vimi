package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.ArrayList;
import java.util.BitSet;

/**
 * Admits one reader-qualified Stand sequence for the compact preview surface.
 *
 * <p>Only bones that influence a preview vertex, and their ancestors, are checked. Those bones may
 * carry no parent-inheritance flags. A billboard bone whose inspected bits are exactly one
 * billboard flag (0x8 spherical, 0x40 cylindrical about Z, 0x20 about Y; not 0x10) is admitted as a
 * childless leaf without rotation keys whose vertices are rigidly bound to it: its card follows its
 * evaluated pivot, which its own translation keys move, and its own scale keys size the card. The
 * camera orients the card. Flags of bones that move no rendered vertex (attachments, effect
 * emitters) do not matter.
 *
 * <p>Public so Races previews admit their Stand through the same bone-flag profile as Mounts.
 */
public final class DisplayIdleAnimations {
    public static final String POLICY = "display-idle-stand-local-v1";
    private static final long INHERITANCE = 0x7, SPHERICAL = 0x8, CYLINDRICAL = 0x70;
    private static final long CYLINDRICAL_Y = 0x20, CYLINDRICAL_Z = 0x40;

    private DisplayIdleAnimations() {}

    /** Thrown for a readable source outside the admitted profile. */
    public static final class Unsupported extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Binds {@code idle} to the rest surface built from {@code skin}.
     *
     * @throws Unsupported for a readable source outside the admitted profile
     * @throws IllegalArgumentException when the inputs do not belong together
     */
    public static PreviewAnimation admit(
            M2Types.Model model,
            M2Types.RestSkin skin,
            M2IdleAnimation.Idle idle,
            PreviewMesh mesh,
            float[] normals,
            Cancellation token) {
        return admit(model, skin, idle, mesh, normals, false, token).animation();
    }

    /** A World admission: the animation and the rest-surface vertices the World leaves out. */
    public record WorldStand(PreviewAnimation animation, int[] leftOut) {}

    /**
     * Binds {@code idle} for the World, which draws no camera-facing parts. As World objects do
     * (#360), every bone that billboards (spherical or cylindrical, flags 0x78) and every
     * descendant of one is evaluated as an ordinary bone, and each vertex with a non-zero weight on
     * one of them is listed as left out. Parent-inheritance flags on the other bones that move
     * rendered vertices are still refused.
     *
     * @throws Unsupported for a readable source outside the World profile
     * @throws IllegalArgumentException when the inputs do not belong together
     */
    public static WorldStand admitWorld(
            M2Types.Model model,
            M2Types.RestSkin skin,
            M2IdleAnimation.Idle idle,
            PreviewMesh mesh,
            float[] normals,
            Cancellation token) {
        return admit(model, skin, idle, mesh, normals, true, token);
    }

    /**
     * Vertex bone indices are bound as direct rig indices. A skin section can instead route its
     * vertices through a palette: section-local indices into the model's bone lookup from the
     * section's {@code boneStart}. Binding is admitted only when both name the same bone for every
     * weighted influence of every section vertex, so a palette that differs is refused rather than
     * posed with the wrong bones.
     *
     * @throws Unsupported naming the first section whose palette differs
     */
    static void requireDirectPalettes(
            M2Types.Model model, M2Types.RestSkin skin, Cancellation token) {
        var lookup = model.boneLookup();
        var properties = skin.boneProperties();
        for (var section : skin.sections()) {
            token.check();
            int end = section.vertexStart() + section.vertexCount();
            for (int v = section.vertexStart(); v < end; v++) {
                var vertex = model.vertices().get(skin.vertexLookup().get(v));
                long local = properties.get(v);
                for (int k = 0; k < 4; k++) {
                    if ((vertex.boneWeights() >>> (k * 8) & 255) == 0) {
                        continue;
                    }
                    int bone = (int) (vertex.boneIndices() >>> (k * 8) & 255);
                    int slot = (int) (local >>> (k * 8) & 255);
                    long palette = (long) section.boneStart() + slot;
                    if (slot >= section.boneCount()
                            || palette >= lookup.size()
                            || lookup.get((int) palette) != bone) {
                        throw new Unsupported(
                                "Skin section "
                                        + section.id()
                                        + " names bones through a palette that differs from the"
                                        + " vertex bone indices; idle evaluation binds vertex"
                                        + " indices directly.");
                    }
                }
            }
        }
    }

    private static WorldStand admit(
            M2Types.Model model,
            M2Types.RestSkin skin,
            M2IdleAnimation.Idle idle,
            PreviewMesh mesh,
            float[] normals,
            boolean world,
            Cancellation token) {
        if (idle.model() != model || skin.model() != model) {
            throw new IllegalArgumentException("Idle facts belong to another parsed model");
        }
        var vertexLookup = skin.vertexLookup();
        if (vertexLookup.size() != mesh.vertexCount()) {
            throw new IllegalArgumentException("Idle influences differ from the preview surface");
        }
        if (!idle.animated()) {
            throw new Unsupported(
                    "The Stand sequence records no bone keys; the rest pose is shown.");
        }
        // The rig: the model's bones, or its external skeleton's bones.
        var rig = idle.rig();
        int boneCount = rig.size();
        int[] bones = new int[mesh.vertexCount() * 4], weights = new int[bones.length];
        var influencing = new BitSet(boneCount);
        for (int v = 0; v < vertexLookup.size(); v++) {
            if ((v & 2047) == 0) {
                token.check();
            }
            var vertex = model.vertices().get(vertexLookup.get(v));
            for (int k = 0; k < 4; k++) {
                int weight = (int) (vertex.boneWeights() >>> (k * 8) & 255);
                int bone = (int) (vertex.boneIndices() >>> (k * 8) & 255);
                if (weight == 0) {
                    bone = 0;
                } else if (bone >= boneCount) {
                    throw new IllegalArgumentException("A vertex names a bone outside the rig");
                } else {
                    influencing.set(bone);
                }
                bones[v * 4 + k] = bone;
                weights[v * 4 + k] = weight;
            }
        }
        requireDirectPalettes(model, skin, token);
        var chain = new BitSet(boneCount);
        for (int bone = influencing.nextSetBit(0);
                bone >= 0;
                bone = influencing.nextSetBit(bone + 1)) {
            int steps = 0;
            for (int at = bone; at >= 0 && !chain.get(at); at = rig.get(at).parent()) {
                if (++steps > boneCount) {
                    throw new IllegalArgumentException("Bone hierarchy contains a cycle");
                }
                chain.set(at);
            }
        }
        // Bones that billboard or descend from one (World profile only).
        var billboardTree = new BitSet(boneCount);
        if (world) {
            for (int index = 0; index < boneCount; index++) {
                int steps = 0;
                for (int at = index; at >= 0 && steps++ <= boneCount; at = rig.get(at).parent()) {
                    if ((rig.get(at).flags() & (SPHERICAL | CYLINDRICAL)) != 0) {
                        billboardTree.set(index);
                        break;
                    }
                }
            }
        }
        boolean[] parents = new boolean[boneCount];
        for (var bone : rig) {
            if (bone.parent() >= 0) {
                parents[bone.parent()] = true;
            }
        }
        var result = new ArrayList<PreviewAnimation.Bone>(boneCount);
        for (int index = 0; index < boneCount; index++) {
            var bone = rig.get(index);
            var tracks = idle.bones().get(index);
            long flags = bone.flags() & (INHERITANCE | SPHERICAL | CYLINDRICAL);
            boolean billboard = false;
            if (chain.get(index) && flags != 0 && !billboardTree.get(index)) {
                if (flags != SPHERICAL && flags != CYLINDRICAL_Z && flags != CYLINDRICAL_Y) {
                    throw new Unsupported(
                            "Bone "
                                    + index
                                    + " moves rendered vertices with billboard or"
                                    + " parent-inheritance flags 0x"
                                    + Long.toHexString(flags)
                                    + " that idle evaluation does not implement.");
                }
                if (parents[index]) {
                    throw new Unsupported(
                            "Billboard bone "
                                    + index
                                    + " has child bones, which would turn with the camera;"
                                    + " idle evaluation does not orient them.");
                }
                if (tracks.rotation() != null) {
                    throw new Unsupported(
                            "Billboard bone "
                                    + index
                                    + " has rotation keys, which turn its card in the screen"
                                    + " plane; idle evaluation does not evaluate them.");
                }
                billboard = true;
            }
            result.add(
                    new PreviewAnimation.Bone(
                            bone.parent(),
                            bone.pivot().x(),
                            bone.pivot().y(),
                            bone.pivot().z(),
                            track(tracks.translation()),
                            billboard ? null : track(tracks.rotation()),
                            track(tracks.scale()),
                            billboard));
        }
        int[] leftOut = new int[0];
        if (!billboardTree.isEmpty()) {
            var out = new ArrayList<Integer>();
            for (int v = 0; v < mesh.vertexCount(); v++) {
                for (int k = 0; k < 4; k++) {
                    if (weights[v * 4 + k] > 0 && billboardTree.get(bones[v * 4 + k])) {
                        out.add(v);
                        break;
                    }
                }
            }
            leftOut = out.stream().mapToInt(Integer::intValue).toArray();
        }
        try {
            return new WorldStand(
                    new PreviewAnimation(
                            "Stand",
                            idle.durationMillis(),
                            idle.globalSequenceMillis(),
                            result,
                            mesh,
                            normals,
                            bones,
                            weights),
                    leftOut);
        } catch (IllegalArgumentException invalid) {
            throw new Unsupported("Idle evaluation refused this sequence: " + invalid.getMessage());
        }
    }

    private static PreviewAnimation.Track track(M2IdleAnimation.Track track) {
        if (track == null) {
            return null;
        }
        return new PreviewAnimation.Track(
                track.interpolation() == M2IdleAnimation.Interpolation.STEP
                        ? PreviewAnimation.Interpolation.STEP
                        : PreviewAnimation.Interpolation.LINEAR,
                track.globalSequence(),
                track.components(),
                track.times(),
                track.values());
    }
}
