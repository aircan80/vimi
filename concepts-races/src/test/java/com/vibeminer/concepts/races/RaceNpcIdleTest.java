package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RaceNpcIdleTest {
    private static final int HELMET_PART = RaceNpcAttachments.FIRST_PART + 11 * 100;

    /** A Stand whose only bone lifts every vertex by 1 on z. */
    private static RaceMediaTypes.Idle stand(PreviewMesh rest) {
        var lift =
                new PreviewAnimation.Track(
                        PreviewAnimation.Interpolation.LINEAR,
                        -1,
                        3,
                        new int[] {0},
                        new float[] {0, 0, 1});
        var bone = new PreviewAnimation.Bone(-1, 0, 0, 0, lift, null, null, false);
        int vertices = rest.positions().length / 3;
        int[] bones = new int[vertices * 4], weights = new int[vertices * 4];
        for (int v = 0; v < vertices; v++) {
            weights[v * 4] = 255;
        }
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(bone),
                        rest,
                        new float[vertices * 3],
                        bones,
                        weights);
        return new RaceMediaTypes.Idle(
                RaceTypes.State.READY,
                new RaceMediaTypes.Variant(1, 0, 49, 149, 1490),
                animation,
                List.of(),
                List.of(),
                "Stand");
    }

    private static MeshAppearance appearance(PreviewMesh mesh) {
        int vertices = mesh.positions().length / 3;
        return new MeshAppearance(
                mesh, new float[vertices * 2], new float[vertices * 3], List.of(), List.of());
    }

    @Test
    void attachedItemsFollowTheirAttachmentPointsBone() {
        var rest =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        var npc =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 1, 0, 2, 0, 1, 2},
                        new int[] {0, 1, 2, 3, 4, 5},
                        List.of(
                                new PreviewMesh.Part(0, 0, 3),
                                new PreviewMesh.Part(HELMET_PART, 3, 3)));
        var stand = stand(rest);
        var fitted =
                RaceNpcIdle.fit(stand, appearance(npc), attachment -> attachment == 11 ? 0 : null);
        assertEquals(RaceTypes.State.READY, fitted.state());
        assertTrue(fitted.animation().matches(appearance(npc)));
        var posed = fitted.animation().pose(npc, 0).mesh().positions();
        assertEquals(3, posed[3 * 3 + 2], 1e-5); // the helmet vertex lifted with the head bone
        assertTrue(
                fitted.facts().contains("Attached items follow their attachment points' bones."));
        // The race surface alone needs no fitting.
        assertSame(stand, RaceNpcIdle.fit(stand, appearance(rest), attachment -> null));
        // An attachment point the model lacks: no idle, with the reason.
        var missing = RaceNpcIdle.fit(stand, appearance(npc), attachment -> null);
        assertEquals(RaceTypes.State.UNSUPPORTED, missing.state());
        assertNull(missing.animation());
        assertEquals(
                "Stand: an attached item's attachment point is not on the model.",
                missing.message());
    }
}
