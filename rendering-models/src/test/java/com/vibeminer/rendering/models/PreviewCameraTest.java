package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PreviewCameraTest {
    @Test
    void sphereMapHasIndependentVertexAndInteriorOracles() {
        var p = new PreviewCamera.Eye(0, 0, -1);
        assertEquals(
                new PreviewCamera.TexCoord(.5, .5),
                PreviewCamera.environment(p, new PreviewCamera.Eye(0, 0, 1)));
        var tilted =
                PreviewCamera.environment(
                        p, new PreviewCamera.Eye(Math.sqrt(.5), 0, Math.sqrt(.5)));
        assertEquals(.853553390593, tilted.u(), 1e-11);
        assertEquals(.5, tilted.v(), 1e-12);
        var a =
                PreviewCamera.environment(
                        new PreviewCamera.Eye(-1, -1, -2), new PreviewCamera.Eye(0, 0, 1));
        var b =
                PreviewCamera.environment(
                        new PreviewCamera.Eye(1, -1, -4),
                        new PreviewCamera.Eye(Math.sqrt(.5), 0, Math.sqrt(.5)));
        var c =
                PreviewCamera.environment(
                        new PreviewCamera.Eye(0, 1, -3),
                        new PreviewCamera.Eye(0, Math.sqrt(.5), Math.sqrt(.5)));
        assertEquals(.538560735010, (6 * a.u() + 3 * b.u() + 4 * c.u()) / 13, 1e-11);
        assertEquals(.553381740323, (6 * a.v() + 3 * b.v() + 4 * c.v()) / 13, 1e-11);
        for (var normal :
                new PreviewCamera.Eye[] {
                    new PreviewCamera.Eye(1, 0, 0),
                    new PreviewCamera.Eye(0, 0, 0),
                    new PreviewCamera.Eye(Double.NaN, 0, 1)
                }) {
            assertEquals(new PreviewCamera.TexCoord(.5, .5), PreviewCamera.environment(p, normal));
        }
    }

    @Test
    void cardsPreserveModelPivotAndAllThreeOffsetsInSharedEyeBasis() {
        var camera =
                PreviewCamera.create(
                        new PreviewCamera.Bounds(-2, -3, -4, 2, 3, 4),
                        640,
                        480,
                        new StaticMeshRenderer.View(.7, -.3, 1, false, Set.of()),
                        true);
        var pivot = new MeshAppearance.Vec3(.3f, .4f, .5f);
        var center = camera.modelToEye(pivot.x(), pivot.y(), pivot.z());
        var card = camera.billboardToEye(pivot, pivot.x() + .002, pivot.y() + .7, pivot.z() - .2);
        assertEquals(center.x() + .7, card.x(), 1e-12);
        assertEquals(center.y() - .2, card.y(), 1e-12);
        assertEquals(center.z() - .002, card.z(), 1e-12);
        assertEquals(640 * .5, camera.screenX(new PreviewCamera.Eye(0, 0, -2)), 1e-12);
        assertEquals(480 * .53, camera.screenY(new PreviewCamera.Eye(0, 0, -2)), 1e-12);
        assertTrue(camera.modelToEye().isReadOnly());
        assertTrue(camera.projection().isReadOnly());
    }
}
