package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.PreviewAnimation.Bone;
import com.vibeminer.rendering.models.PreviewAnimation.Interpolation;
import com.vibeminer.rendering.models.PreviewAnimation.Track;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewAnimationTest {
    private static final float S = (float) Math.sqrt(.5);

    private static PreviewMesh mesh(float... positions) {
        int vertices = positions.length / 3;
        int[] indices = new int[vertices * 3];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i % vertices;
        }
        return new PreviewMesh(
                positions, indices, List.of(new PreviewMesh.Part(0, 0, indices.length)));
    }

    private static Track constant(int components, float... value) {
        return new Track(Interpolation.LINEAR, -1, components, new int[] {0}, value);
    }

    private static int[] rigid(int... bones) {
        int[] out = new int[bones.length * 4];
        for (int v = 0; v < bones.length; v++) {
            out[v * 4] = bones[v];
        }
        return out;
    }

    private static int[] full(int vertices) {
        int[] out = new int[vertices * 4];
        for (int v = 0; v < vertices; v++) {
            out[v * 4] = 255;
        }
        return out;
    }

    private static void near(float[] expected, float[] actual, int vertex) {
        for (int c = 0; c < 3; c++) {
            assertEquals(
                    expected[c], actual[vertex * 3 + c], 1e-5, "vertex " + vertex + " axis " + c);
        }
    }

    @Test
    void childBonesComposeUnderEvaluatedParentsAroundTheirPivots() {
        var root = new Bone(-1, 0, 0, 0, null, constant(4, 0, 0, S, S), null, false);
        var child = new Bone(0, 1, 0, 0, constant(3, 0, 0, 1), null, null, false);
        var rest = mesh(2, 0, 0, 1, 1, 0, 2, 0, 0);
        int[] bones = {1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0};
        int[] weights = {255, 0, 0, 0, 255, 0, 0, 0, 128, 127, 0, 0};
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, child),
                        rest,
                        new float[0],
                        bones,
                        weights);
        var posed = animation.pose(rest, 0).mesh().positions();
        near(new float[] {0, 2, 1}, posed, 0);
        near(new float[] {-1, 1, 0}, posed, 1);
        near(new float[] {0, 2, 127 / 255f}, posed, 2);
    }

    @Test
    void rigsBeyondAnEightBitIndexEvaluateOnlyBonesThatMoveVertices() {
        // 300 bones: a translating root, bone 299 under it moving vertex 1, and 298 bones that
        // move nothing (one of them keyed, and pivoted away from its rest place if evaluated).
        var bones = new java.util.ArrayList<Bone>();
        bones.add(new Bone(-1, 0, 0, 0, constant(3, 0, 0, 1), null, null, false));
        for (int b = 1; b < 300; b++) {
            bones.add(
                    new Bone(
                            0,
                            b,
                            0,
                            0,
                            b == 7 ? constant(3, 5, 5, 5) : b == 299 ? constant(3, 0, 1, 0) : null,
                            null,
                            null,
                            false));
        }
        var rest = mesh(0, 0, 0, 1, 0, 0, 2, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        bones,
                        rest,
                        new float[0],
                        rigid(0, 299, 0),
                        full(3));
        assertEquals(300, animation.boneCount());
        var pose = animation.pose(rest, 0);
        near(new float[] {0, 0, 1}, pose.mesh().positions(), 0);
        near(new float[] {1, 1, 1}, pose.mesh().positions(), 1);
        // Bone 299 is evaluated under its root; bone 7 moves no vertex and keeps its rest place.
        assertEquals(new MeshAppearance.Vec3(299, 1, 1), round(pose.pivots().get(299)));
        assertEquals(new MeshAppearance.Vec3(7, 0, 0), round(pose.pivots().get(7)));
        var tooMany = new java.util.ArrayList<>(bones);
        while (tooMany.size() <= PreviewAnimation.MAX_BONES) {
            tooMany.add(new Bone(-1, 0, 0, 0, null, null, null, false));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                tooMany,
                                rest,
                                new float[0],
                                rigid(0, 299, 0),
                                full(3)));
    }

    @Test
    void parentsMayFollowChildrenInStorageOrder() {
        var child = new Bone(1, 1, 0, 0, constant(3, 0, 0, 1), null, null, false);
        var root = new Bone(-1, 0, 0, 0, null, constant(4, 0, 0, S, S), null, false);
        var rest = mesh(2, 0, 0, 1, 1, 0, 0, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(child, root),
                        rest,
                        new float[0],
                        rigid(0, 1, 1),
                        full(3));
        near(new float[] {0, 2, 1}, animation.pose(rest, 0).mesh().positions(), 0);
    }

    @Test
    void linearStepAndHeldKeysFollowSequenceTime() {
        var rest = mesh(0, 0, 0, 0, 1, 0, 1, 0, 0);
        int[] times = {0, 1000};
        float[] values = {0, 0, 0, 10, 0, 0};
        for (var interpolation : Interpolation.values()) {
            var bone =
                    new Bone(
                            -1,
                            0,
                            0,
                            0,
                            new Track(interpolation, -1, 3, times, values),
                            null,
                            null,
                            false);
            var animation =
                    new PreviewAnimation(
                            "Stand",
                            2000,
                            new long[0],
                            List.of(bone),
                            rest,
                            new float[0],
                            rigid(0, 0, 0),
                            full(3));
            float expected = interpolation == Interpolation.LINEAR ? 2.5f : 0;
            near(new float[] {expected, 0, 0}, animation.pose(rest, 250).mesh().positions(), 0);
            near(new float[] {10, 0, 0}, animation.pose(rest, 1500).mesh().positions(), 0);
            // Playback loops on the sequence duration.
            near(new float[] {expected, 0, 0}, animation.pose(rest, 2250).mesh().positions(), 0);
        }
    }

    @Test
    void rotationKeysUseShortestSphericalInterpolationAndRotateNormals() {
        var rest = mesh(1, 0, 0, 0, 1, 0, 0, 0, 1);
        // The second key is the negated 90 degree quaternion: the same rotation, shortest path.
        var rotation =
                new Track(
                        Interpolation.LINEAR,
                        -1,
                        4,
                        new int[] {0, 1000},
                        new float[] {0, 0, 0, 1, 0, 0, -S, -S});
        var bone = new Bone(-1, 0, 0, 0, null, rotation, null, false);
        float[] normals = {2, 0, 0, 0, 1, 0, 0, 0, 1};
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(bone),
                        rest,
                        normals,
                        rigid(0, 0, 0),
                        full(3));
        var pose = animation.pose(rest, 500);
        float c = (float) Math.cos(Math.PI / 8), s = (float) Math.sin(Math.PI / 8);
        // 45 degrees, applied to (1,0,0).
        near(new float[] {c * c - s * s, 2 * c * s, 0}, pose.mesh().positions(), 0);
        // Normal length is preserved while its direction rotates.
        near(new float[] {2 * (c * c - s * s), 4 * c * s, 0}, pose.normals(), 0);
    }

    @Test
    void globalSequenceTracksUseTheirOwnClock() {
        var rest = mesh(0, 0, 0, 0, 1, 0, 1, 0, 0);
        var bone =
                new Bone(
                        -1,
                        0,
                        0,
                        0,
                        new Track(
                                Interpolation.LINEAR,
                                0,
                                3,
                                new int[] {0, 500},
                                new float[] {0, 0, 0, 5, 0, 0}),
                        null,
                        null,
                        false);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[] {500},
                        List.of(bone),
                        rest,
                        new float[0],
                        rigid(0, 0, 0),
                        full(3));
        near(new float[] {2.5f, 0, 0}, animation.pose(rest, 750).mesh().positions(), 0);
        // A global clock may be longer than the sequence bound; it is sampled modulo its length.
        var faceClock =
                new Bone(
                        -1,
                        0,
                        0,
                        0,
                        new Track(
                                Interpolation.LINEAR,
                                0,
                                3,
                                new int[] {0, 1_079_000},
                                new float[] {0, 0, 0, 1_079, 0, 0}),
                        null,
                        null,
                        false);
        var longClock =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[] {1_079_000},
                        List.of(faceClock),
                        rest,
                        new float[0],
                        rigid(0, 0, 0),
                        full(3));
        near(
                new float[] {500, 0, 0},
                longClock.pose(rest, 1_079_000L + 500_000).mesh().positions(),
                0);
        // A zero-length global clock never advances: it holds the first key at every time.
        var stillClock =
                new Bone(
                        -1,
                        0,
                        0,
                        0,
                        new Track(
                                Interpolation.LINEAR,
                                0,
                                3,
                                new int[] {3333, 6667},
                                new float[] {7, 0, 0, 9, 0, 0}),
                        null,
                        null,
                        false);
        var still =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[] {0},
                        List.of(stillClock),
                        rest,
                        new float[0],
                        rigid(0, 0, 0),
                        full(3));
        for (long millis : new long[] {0, 500, 3333, 5000, 6667}) {
            near(new float[] {7, 0, 0}, still.pose(rest, millis).mesh().positions(), 0);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[] {PreviewAnimation.MAX_GLOBAL_MILLIS + 1},
                                List.of(bone),
                                rest,
                                new float[0],
                                rigid(0, 0, 0),
                                full(3)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                List.of(bone),
                                rest,
                                new float[0],
                                rigid(0, 0, 0),
                                full(3)));
    }

    @Test
    void billboardMembersKeepRestOffsetsAroundTheEvaluatedPivot() {
        var root =
                new Bone(-1, 0, 0, 0, constant(3, 0, 0, 3), constant(4, 0, 0, S, S), null, false);
        var card = new Bone(0, 1, 0, 1, null, null, null, true);
        var rest = mesh(1.5f, 0, 1, 1, .5f, 1, 2, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, card),
                        rest,
                        new float[0],
                        rigid(1, 1, 0),
                        full(3));
        var pose = animation.pose(rest, 0);
        // Pivot (1,0,1) rotates to (0,1,1) and lifts by 3; offsets are not rotated.
        assertEquals(new MeshAppearance.Vec3(0, 1, 4), round(pose.pivots().get(1)));
        near(new float[] {.5f, 1, 4}, pose.mesh().positions(), 0);
        near(new float[] {0, 1.5f, 4}, pose.mesh().positions(), 1);
        near(new float[] {0, 2, 3}, pose.mesh().positions(), 2);
    }

    @Test
    void billboardTranslationMovesThePivotAndScaleSizesTheCard() {
        var root =
                new Bone(-1, 0, 0, 0, constant(3, 0, 0, 3), constant(4, 0, 0, S, S), null, false);
        var card = new Bone(0, 1, 0, 1, constant(3, 1, 0, 0), null, constant(3, 2, 2, 2), true);
        var rest = mesh(1.5f, 0, 1, 1, .5f, 1, 2, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, card),
                        rest,
                        new float[0],
                        rigid(1, 1, 0),
                        full(3));
        var pose = animation.pose(rest, 0);
        // Pivot (1,0,1) + translation (1,0,0) rotates to (0,2,1) and lifts by 3; applying the
        // translation after the parent would give (1,1,4).
        assertEquals(new MeshAppearance.Vec3(0, 2, 4), round(pose.pivots().get(1)));
        // Card offsets double around the pivot; they are neither rotated nor translated.
        near(new float[] {1, 2, 4}, pose.mesh().positions(), 0);
        near(new float[] {0, 3, 4}, pose.mesh().positions(), 1);
        near(new float[] {0, 2, 3}, pose.mesh().positions(), 2);
    }

    @Test
    void posedCardsKeepTheirKind() {
        var root = new Bone(-1, 0, 0, 0, constant(3, 0, 0, 3), null, null, false);
        var card = new Bone(0, 1, 0, 1, null, null, null, true);
        var rest =
                new PreviewMesh(
                        new float[] {1, -.5f, .5f, 1, .5f, .5f, 1, .5f, 1.5f, 1, -.5f, 1.5f},
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        var additive =
                new MeshAppearance.Material(
                        MeshAppearance.Combiner.DIFFUSE,
                        List.of(
                                new MeshAppearance.TextureStage(
                                        0, MeshAppearance.Coordinate.UV0, false, false)),
                        MeshAppearance.Lighting.UNLIT,
                        MeshAppearance.Blend.ADDITIVE);
        var appearance =
                new MeshAppearance(
                        rest,
                        new float[8],
                        new float[0],
                        List.of(new MeshAppearance.Surface(0, additive)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})),
                        List.of(
                                new MeshAppearance.RigidBillboard(
                                        1,
                                        new MeshAppearance.Vec3(1, 0, 1),
                                        new int[] {0, 1, 2, 3},
                                        MeshAppearance.CardKind.CYLINDRICAL_Z)));
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, card),
                        rest,
                        new float[0],
                        rigid(1, 1, 1, 1),
                        full(4));
        var posed = animation.pose(appearance, 0).billboards().getFirst();
        assertEquals(MeshAppearance.CardKind.CYLINDRICAL_Z, posed.kind());
        assertEquals(new MeshAppearance.Vec3(1, 0, 4), round(posed.modelPivot()));
    }

    /** The camera looking from +Y (toward = +Y, up = +Z) and from +X (toward = +X). */
    private static final PreviewAnimation.View
            FROM_Y = PreviewAnimation.View.orbit(-Math.PI / 2, 0),
            FROM_X = PreviewAnimation.View.orbit(0, 0);

    @Test
    void orbitViewsMatchThePreviewCamera() {
        for (double yaw : new double[] {-2, -0.3, 0, 1.1, 3}) {
            for (double pitch : new double[] {-1.1, 0, 0.7}) {
                var view = PreviewAnimation.View.orbit(yaw, pitch);
                var camera =
                        PreviewCamera.create(
                                new PreviewCamera.Bounds(-1, -1, -1, 1, 1, 1),
                                100,
                                100,
                                new StaticMeshRenderer.View(
                                        yaw, pitch, 1, false, java.util.Set.of()),
                                false);
                var toward = camera.normalToEye(view.towardX(), view.towardY(), view.towardZ());
                var up = camera.normalToEye(view.upX(), view.upY(), view.upZ());
                assertEquals(1, toward.z(), 1e-9, "toward faces the eye");
                assertEquals(1, up.y(), 1e-9, "up is screen up");
            }
        }
        near(
                new float[] {0, 1, 0},
                new float[] {
                    (float) FROM_Y.towardX(), (float) FROM_Y.towardY(), (float) FROM_Y.towardZ()
                },
                0);
    }

    /**
     * A translating root, camera-facing bone 1 under it (pivot (0, 0, 1)) and ordinary bone 2 under
     * bone 1 (pivot (1, 0, 1)), as on a rig whose billboard bone carries a child chain.
     */
    private static List<Bone> facingChain(PreviewAnimation.Facing facing) {
        return List.of(
                new Bone(-1, 0, 0, 0, constant(3, 0, 0, 2), null, null, false),
                new Bone(0, 0, 0, 1, null, null, null, false, facing),
                new Bone(1, 1, 0, 1, null, null, null, false));
    }

    @Test
    void childrenOfACameraFacingBoneTurnWithItTowardTheCamera() {
        var rest = mesh(1, 0, 1, 2, 0, 1, 0, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        facingChain(PreviewAnimation.Facing.SPHERICAL),
                        rest,
                        new float[0],
                        rigid(2, 1, 0),
                        full(3));
        assertTrue(animation.viewDependent());
        assertThrows(IllegalArgumentException.class, () -> animation.pose(rest, 0));
        // Seen from +X the rest offsets already face the camera; the root lifts everything by 2.
        var fromX = animation.pose(rest, 0, FROM_X).mesh().positions();
        near(new float[] {1, 0, 3}, fromX, 0);
        near(new float[] {2, 0, 3}, fromX, 1);
        // Seen from +Y the facing bone turns its +X offsets toward +Y; its child follows.
        var fromY = animation.pose(rest, 0, FROM_Y).mesh().positions();
        near(new float[] {0, 1, 3}, fromY, 0);
        near(new float[] {0, 2, 3}, fromY, 1);
        near(new float[] {0, 0, 2}, fromY, 2);
        // Pivots: the facing bone stays on its parent; the child's pivot swings with the camera.
        var pivots = animation.pose(rest, 0, FROM_Y).pivots();
        assertEquals(new MeshAppearance.Vec3(0, 0, 3), round(pivots.get(1)));
        assertEquals(new MeshAppearance.Vec3(0, 1, 3), round(pivots.get(2)));
    }

    @Test
    void partlyWeightedVerticesBlendTheCameraFacingMatrix() {
        var rest = mesh(1, 0, 1, 1, 0, 1, 1, 0, 1);
        int[] bones = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        int[] weights = {128, 127, 0, 0, 128, 127, 0, 0, 128, 127, 0, 0};
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        facingChain(PreviewAnimation.Facing.SPHERICAL),
                        rest,
                        new float[0],
                        bones,
                        weights);
        var posed = animation.pose(rest, 0, FROM_Y).mesh().positions();
        float a = 128 / 255f, b = 127 / 255f;
        // Bone 1 puts it at (0, 1, 3), the root at (1, 0, 3); the weights blend the two.
        near(new float[] {b, a, 3}, posed, 0);
    }

    @Test
    void cylindricalFacingTurnsOnlyAboutItsAxis() {
        var rest = mesh(1, 0, 1, 1, 0, 2, 0, 0, 0);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        facingChain(PreviewAnimation.Facing.CYLINDRICAL_Z),
                        rest,
                        new float[0],
                        rigid(1, 1, 0),
                        full(3));
        // From +Y and 60 degrees up, the +X offset turns to +Y but stays level, and Z stays Z.
        var high = PreviewAnimation.View.orbit(-Math.PI / 2, Math.PI / 3);
        var posed = animation.pose(rest, 0, high).mesh().positions();
        near(new float[] {0, 1, 3}, posed, 0);
        near(new float[] {0, 1, 4}, posed, 1);
    }

    @Test
    void cardsOverCameraFacingVerticesAreDroppedAndOtherAnimationsIgnoreTheView() {
        var root = new Bone(-1, 0, 0, 0, constant(3, 0, 0, 3), null, null, false);
        var facing =
                new Bone(0, 1, 0, 1, null, null, null, false, PreviewAnimation.Facing.SPHERICAL);
        var rest =
                new PreviewMesh(
                        new float[] {1, -.5f, .5f, 1, .5f, .5f, 1, .5f, 1.5f, 1, -.5f, 1.5f},
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        var appearance =
                new MeshAppearance(
                        rest,
                        new float[8],
                        new float[0],
                        List.of(
                                new MeshAppearance.Surface(
                                        0,
                                        new MeshAppearance.Material(
                                                MeshAppearance.Combiner.DIFFUSE,
                                                List.of(
                                                        new MeshAppearance.TextureStage(
                                                                0,
                                                                MeshAppearance.Coordinate.UV0,
                                                                false,
                                                                false)),
                                                MeshAppearance.Lighting.UNLIT,
                                                MeshAppearance.Blend.ADDITIVE))),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})),
                        List.of(
                                new MeshAppearance.RigidBillboard(
                                        1,
                                        new MeshAppearance.Vec3(1, 0, 1),
                                        new int[] {0, 1, 2, 3})));
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, facing),
                        rest,
                        new float[0],
                        rigid(1, 1, 1, 1),
                        full(4));
        var posed = animation.pose(appearance, 0, FROM_Y);
        assertTrue(posed.billboards().isEmpty(), "the posed vertices already face the camera");
        // An animation without camera-facing bones poses the same with or without a view.
        var plain =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(root, new Bone(0, 1, 0, 1, null, null, null, true)),
                        rest,
                        new float[0],
                        rigid(1, 1, 1, 1),
                        full(4));
        assertFalse(plain.viewDependent());
        assertArrayEquals(
                plain.pose(rest, 0).mesh().positions(),
                plain.pose(rest, 0, FROM_Y).mesh().positions());
        assertEquals(1, plain.pose(appearance, 0, FROM_Y).billboards().size());
    }

    private static MeshAppearance.Vec3 round(MeshAppearance.Vec3 v) {
        return new MeshAppearance.Vec3(
                Math.round(v.x() * 1e4f) / 1e4f,
                Math.round(v.y() * 1e4f) / 1e4f,
                Math.round(v.z() * 1e4f) / 1e4f);
    }

    @Test
    void framingBoundsStayFixedAcrossPoses() {
        var rest = mesh(1, 0, 0, 0, 1, 0, 0, 0, 1);
        var rotation =
                new Track(
                        Interpolation.LINEAR,
                        -1,
                        4,
                        new int[] {0, 500, 1000},
                        new float[] {0, 0, 0, 1, 0, 0, S, S, 0, 0, 1, 0});
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(new Bone(-1, 0, 0, 0, null, rotation, null, false)),
                        rest,
                        new float[0],
                        rigid(0, 0, 0),
                        full(3));
        var early = animation.pose(rest, 100).mesh();
        var late = animation.pose(rest, 700).mesh();
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(early.minimum(axis), late.minimum(axis));
            assertEquals(early.maximum(axis), late.maximum(axis));
            assertTrue(early.minimum(axis) <= rest.minimum(axis));
        }
        assertArrayEquals(rest.indices(), late.indices());
        assertEquals(rest.parts(), late.parts());
    }

    @Test
    void foreignSurfacesAndInvalidSkeletonsAreRefused() {
        var rest = mesh(1, 0, 0, 0, 1, 0, 0, 0, 1);
        var bone = new Bone(-1, 0, 0, 0, null, constant(4, 0, 0, 0, 1), null, false);
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(bone),
                        rest,
                        new float[0],
                        rigid(0, 0, 0),
                        full(3));
        var other = mesh(1, 0, 0, 0, 1, 0, 0, 0, 2);
        assertTrue(animation.matches(rest));
        assertFalse(animation.matches(other));
        assertThrows(IllegalArgumentException.class, () -> animation.pose(other, 0));

        int[] underweight = full(3);
        underweight[0] = 254;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                List.of(bone),
                                rest,
                                new float[0],
                                rigid(0, 0, 0),
                                underweight));
        var a = new Bone(1, 0, 0, 0, null, null, null, false);
        var b = new Bone(0, 0, 0, 0, null, null, null, false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                List.of(a, b),
                                rest,
                                new float[0],
                                rigid(0, 0, 0),
                                full(3)));
        var billboard = new Bone(-1, 0, 0, 0, null, null, null, true);
        var underBillboard = new Bone(0, 0, 0, 0, null, null, null, false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                List.of(billboard, underBillboard),
                                rest,
                                new float[0],
                                rigid(1, 1, 1),
                                full(3)));
        int[] shared = {0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0};
        int[] split = {128, 127, 0, 0, 128, 127, 0, 0, 128, 127, 0, 0};
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                1000,
                                new long[0],
                                List.of(bone, new Bone(0, 0, 0, 0, null, null, null, true)),
                                rest,
                                new float[0],
                                shared,
                                split));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Bone(-1, 0, 0, 0, null, constant(4, 0, 0, 0, 1), null, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Track(Interpolation.LINEAR, -1, 3, new int[] {5, 1}, new float[6]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Track(Interpolation.LINEAR, -1, 4, new int[] {0}, new float[4]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewAnimation(
                                "Stand",
                                0,
                                new long[0],
                                List.of(bone),
                                rest,
                                new float[0],
                                rigid(0, 0, 0),
                                full(3)));
    }
}
