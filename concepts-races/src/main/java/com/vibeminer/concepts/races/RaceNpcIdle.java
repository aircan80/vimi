package com.vibeminer.concepts.races;

import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.MeshAppearance;
import java.util.List;

/**
 * Fits a race model's Stand idle to a humanoid NPC's composed appearance. The appearance begins
 * with the race model's rest surface (character textures change no vertex); attached items follow
 * after it, each part naming its attachment point ({@link RaceNpcAttachments#attachmentOf}), and
 * move rigidly with that point's bone.
 */
final class RaceNpcIdle {
    private RaceNpcIdle() {}

    static RaceMediaTypes.Idle fit(
            RaceMediaTypes.Idle stand, MeshAppearance appearance, M2Types.Model character) {
        return fit(stand, appearance, attachment -> bone(character, attachment));
    }

    /** As above, with the bone of each attachment point (null when the model has none). */
    static RaceMediaTypes.Idle fit(
            RaceMediaTypes.Idle stand,
            MeshAppearance appearance,
            java.util.function.IntFunction<Integer> boneOfAttachment) {
        var animation = stand.animation();
        if (animation.matches(appearance)) {
            return stand;
        }
        int base = animation.vertexCount();
        int vertices = appearance.mesh().positions().length / 3;
        if (vertices <= base) {
            return unsupported(stand, "the NPC surface does not begin with the race model's");
        }
        int[] bones = new int[vertices - base];
        java.util.Arrays.fill(bones, -1);
        int[] indices = appearance.mesh().indices();
        for (var part : appearance.mesh().parts()) {
            int attachment = RaceNpcAttachments.attachmentOf(part.id());
            if (attachment < 0) {
                continue;
            }
            Integer bone = boneOfAttachment.apply(attachment);
            if (bone == null) {
                return unsupported(
                        stand, "an attached item's attachment point is not on the model");
            }
            for (int i = part.firstIndex(); i < part.firstIndex() + part.indexCount(); i++) {
                int vertex = indices[i] - base;
                if (vertex < 0) {
                    return unsupported(stand, "an attached item shares the character's vertices");
                }
                bones[vertex] = bone;
            }
        }
        for (int i = 0; i < bones.length; i++) {
            if (bones[i] < 0) {
                // Item vertices no drawn part uses: they follow the first item's bone unseen.
                bones[i] = 0;
            }
        }
        var fitted = animation.withRigidVertices(appearance.mesh(), appearance.normals(), bones);
        if (fitted == null) {
            return unsupported(stand, "the NPC surface does not begin with the race model's");
        }
        return new RaceMediaTypes.Idle(
                stand.state(),
                stand.variant(),
                fitted,
                stand.reasons(),
                append(stand.facts(), "Attached items follow their attachment points' bones."),
                stand.message());
    }

    private static Integer bone(M2Types.Model character, int attachment) {
        if (character == null) {
            return null;
        }
        for (var point : character.attachments()) {
            if (point.id() == attachment) {
                return point.bone();
            }
        }
        return null;
    }

    private static RaceMediaTypes.Idle unsupported(RaceMediaTypes.Idle stand, String reason) {
        return new RaceMediaTypes.Idle(
                RaceTypes.State.UNSUPPORTED,
                stand.variant(),
                null,
                List.of("Stand: " + reason + "."),
                stand.facts(),
                "Stand: " + reason + ".");
    }

    private static List<String> append(List<String> facts, String fact) {
        var out = new java.util.ArrayList<>(facts);
        out.add(fact);
        return List.copyOf(out);
    }
}
