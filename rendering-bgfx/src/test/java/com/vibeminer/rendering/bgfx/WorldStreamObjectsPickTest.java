package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.WorldObjectPicking;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

/** CPU pick geometry agrees with the hot pass's own model matrices for the same objects file. */
final class WorldStreamObjectsPickTest {
    @Test
    void pickedTrianglesSitWhereTheRendererPlacesThem() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
        builder.model();
        // A local triangle fan of two triangles in the plane z = 0.
        float[] vertices = {
            0, 0, 0, 0, 0, 1, 0, 0, //
            3, 0, 0, 0, 0, 1, 1, 0, //
            3, 2, 0, 0, 0, 1, 1, 1, //
            0, 2, 0, 0, 0, 1, 0, 1
        };
        builder.mesh(
                FloatBuffer.wrap(vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3}),
                new int[] {0, 6, 0});
        double cos = Math.cos(.7), sin = Math.sin(.7);
        builder.placement(0, 1234.5, -567.25, 89, 1.5, cos, sin);
        builder.placement(0, 1300, -500, 95, .75, -cos, sin);
        var exterior = builder.build();
        var tile = exterior.pickTile();
        assertEquals(2, tile.placements());
        var data = exterior.data();
        var origin = new TerrainScene.Origin(0, 0, 0);
        var camera = new TerrainCamera(1200, -620, 140, 0, 0);
        for (int p = 0; p < 2; p++) {
            var model = FloatBuffer.allocate(16);
            var normal = FloatBuffer.allocate(16);
            BgfxWorldStreamRenderer.objectMatrices(data.placements, p, origin, model, normal);
            for (int triangle = 0; triangle < 2; triangle++) {
                int[] corners = triangle == 0 ? new int[] {0, 1, 2} : new int[] {0, 2, 3};
                double cx = 0, cy = 0, cz = 0;
                for (int v : corners) {
                    double lx = vertices[v * 8], ly = vertices[v * 8 + 1], lz = vertices[v * 8 + 2];
                    // Column-major model matrix, exactly as the vertex shader applies it.
                    cx +=
                            (model.get(0) * lx
                                            + model.get(4) * ly
                                            + model.get(8) * lz
                                            + model.get(12))
                                    / 3;
                    cy +=
                            (model.get(1) * lx
                                            + model.get(5) * ly
                                            + model.get(9) * lz
                                            + model.get(13))
                                    / 3;
                    cz +=
                            (model.get(2) * lx
                                            + model.get(6) * ly
                                            + model.get(10) * lz
                                            + model.get(14))
                                    / 3;
                }
                var hit =
                        WorldObjectPicking.pick(
                                        tile,
                                        camera,
                                        cx - camera.x(),
                                        cy - camera.y(),
                                        cz - camera.z(),
                                        2200)
                                .orElseThrow();
                assertEquals(p, hit.placement(), "placement " + p);
                assertEquals(triangle, hit.triangle(), "triangle of placement " + p);
                assertEquals(cx, hit.x(), 1e-3);
                assertEquals(cy, hit.y(), 1e-3);
                assertEquals(cz, hit.z(), 1e-3);
            }
        }
        var corners = tile.corners(0).orElseThrow();
        assertTrue(corners.length == 24);
    }
}
