package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Admits one reader-qualified Stand sequence for the compact preview surface.
 *
 * <p>Only bones that influence a preview vertex, and their ancestors, are checked. Those bones may
 * carry no parent-inheritance or cylindrical-billboard flags. A spherical billboard bone (flags
 * exactly 0x8 within the inspected bits) is admitted only as a childless leaf without own Stand
 * keys whose vertices are rigidly bound to it; its card follows its evaluated pivot. Flags of bones
 * that move no rendered vertex (attachments, effect emitters) do not matter.
 *
 * <p>Public so Races previews admit their Stand through the same bone-flag profile as Mounts.
 */
public final class DisplayIdleAnimations {
    public static final String POLICY = "display-idle-stand-local-v1";
    private static final long INHERITANCE = 0x7, SPHERICAL = 0x8, CYLINDRICAL = 0x70;

    private DisplayIdleAnimations() {}

    /** Thrown for a readable source outside the admitted profile. */
    public static final class Unsupported extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Binds {@code idle} to the rest surface built from {@code vertexLookup}.
     *
     * @throws Unsupported for a readable source outside the admitted profile
     * @throws IllegalArgumentException when the inputs do not belong together
     */
    public static PreviewAnimation admit(
            M2Types.Model model,
            List<Integer> vertexLookup,
            M2IdleAnimation.Idle idle,
            PreviewMesh mesh,
            float[] normals,
            Cancellation token) {
        if (idle.model() != model) {
            throw new IllegalArgumentException("Idle facts belong to another parsed model");
        }
        if (vertexLookup.size() != mesh.vertexCount()) {
            throw new IllegalArgumentException("Idle influences differ from the preview surface");
        }
        if (!idle.animated()) {
            throw new Unsupported(
                    "The Stand sequence records no bone keys; the rest pose is shown.");
        }
        int boneCount = model.bones().size();
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
                    throw new IllegalArgumentException("A vertex names a bone outside the model");
                } else {
                    influencing.set(bone);
                }
                bones[v * 4 + k] = bone;
                weights[v * 4 + k] = weight;
            }
        }
        var chain = new BitSet(boneCount);
        for (int bone = influencing.nextSetBit(0);
                bone >= 0;
                bone = influencing.nextSetBit(bone + 1)) {
            int steps = 0;
            for (int at = bone; at >= 0 && !chain.get(at); at = model.bones().get(at).parent()) {
                if (++steps > boneCount) {
                    throw new IllegalArgumentException("Bone hierarchy contains a cycle");
                }
                chain.set(at);
            }
        }
        boolean[] parents = new boolean[boneCount];
        for (var bone : model.bones()) {
            if (bone.parent() >= 0) {
                parents[bone.parent()] = true;
            }
        }
        var result = new ArrayList<PreviewAnimation.Bone>(boneCount);
        for (int index = 0; index < boneCount; index++) {
            var bone = model.bones().get(index);
            var tracks = idle.bones().get(index);
            long flags = bone.flags() & (INHERITANCE | SPHERICAL | CYLINDRICAL);
            boolean billboard = false;
            if (chain.get(index) && flags != 0) {
                if (flags != SPHERICAL) {
                    throw new Unsupported(
                            "Bone "
                                    + index
                                    + " moves rendered vertices with billboard or"
                                    + " parent-inheritance flags 0x"
                                    + Long.toHexString(flags)
                                    + " that idle evaluation does not implement.");
                }
                if (parents[index] || tracks.animated()) {
                    throw new Unsupported(
                            "Billboard bone "
                                    + index
                                    + " is not a keyless leaf; its card orientation is not"
                                    + " evaluated.");
                }
                billboard = true;
            }
            result.add(
                    new PreviewAnimation.Bone(
                            bone.parent(),
                            bone.pivot().x(),
                            bone.pivot().y(),
                            bone.pivot().z(),
                            billboard ? null : track(tracks.translation()),
                            billboard ? null : track(tracks.rotation()),
                            billboard ? null : track(tracks.scale()),
                            billboard));
        }
        try {
            return new PreviewAnimation(
                    "Stand",
                    idle.durationMillis(),
                    idle.globalSequenceMillis(),
                    result,
                    mesh,
                    normals,
                    bones,
                    weights);
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
