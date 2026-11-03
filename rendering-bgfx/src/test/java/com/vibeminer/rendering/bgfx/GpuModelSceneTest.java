package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GpuModelSceneTest {
    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(42, 0, 3), new PreviewMesh.Part(7, 0, 3)));
    }

    @Test
    void packsOriginalTopologyNormalsUvAndExplicitRgbaWithoutMixingPartIdsAndOrdinals() {
        var mesh = mesh();
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 0, 1, 0, .5f, 1},
                        new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1},
                        List.of(new MeshAppearance.Surface(1, 0, true, false, true)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0x80123456})));
        var scene = GpuModelScene.create(mesh, appearance);
        assertEquals(3, scene.vertexCount());
        assertEquals(3, scene.indexCount());
        assertEquals(48, GpuModelScene.VERTEX_BYTES);
        assertTrue(scene.hasNormals());
        var vertices = scene.vertexData();
        float[] values = new float[vertices.remaining()];
        vertices.get(values);
        assertArrayEquals(
                new float[] {
                    0, -1, -1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 1, -1, 0, 1, 0, 1, 0, 0, 0, 0, 0,
                    0, 0, 1, 0, 0, 1, .5f, 1, 0, 0, 0, 0
                },
                values);
        int[] indices = new int[3];
        scene.indexData().get(indices);
        assertArrayEquals(new int[] {0, 1, 2}, indices);
        assertFalse(scene.draws().getFirst().textured());
        var draw = scene.draws().getLast();
        assertEquals(1, draw.partOrdinal());
        assertEquals(7, draw.partId());
        assertTrue(draw.textured());
        assertTrue(draw.wrapU());
        assertFalse(draw.wrapV());
        assertTrue(draw.alphaKey());
        assertTrue(draw.visible(Set.of()));
        assertTrue(draw.visible(Set.of(7)));
        assertFalse(draw.visible(Set.of(1)));
        assertFalse(draw.visible(Set.of(Integer.MIN_VALUE)));
        byte[] rgba = new byte[4];
        scene.textures().getFirst().rgba8().get(rgba);
        assertArrayEquals(new byte[] {0x12, 0x34, 0x56, (byte) 0x80}, rgba);
        assertEquals(3L * 48 + 3 * 4 + 4, scene.uploadBytes());
    }

    @Test
    void materialQualificationUsesSectionOrdinalDespiteDuplicateRecordedPartIds() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 0, 3)));
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 0, 1, 0, .5f, 1},
                        List.of(new MeshAppearance.Surface(1, 0, false, false, true)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffffffff})));
        var scene = GpuModelScene.create(mesh, appearance);
        assertFalse(scene.draws().getFirst().visible(Set.of(0), true));
        assertTrue(scene.draws().getLast().visible(Set.of(0), true));
        assertTrue(scene.draws().getFirst().visible(Set.of(0), false));
        assertTrue(scene.draws().getLast().visible(Set.of(0), false));
        assertFalse(scene.draws().getLast().visible(Set.of(7), true));
    }

    @Test
    void rigidCardsPackSparsePivotsAndUseConservativeBoundsWithoutChangingRestVertices() {
        var mesh =
                new PreviewMesh(
                        new float[] {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        var material =
                new MeshAppearance.Material(
                        MeshAppearance.Combiner.DIFFUSE,
                        List.of(
                                new MeshAppearance.TextureStage(
                                        0, MeshAppearance.Coordinate.UV0, false, false)),
                        MeshAppearance.Lighting.UNLIT,
                        MeshAppearance.Blend.ADDITIVE);
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 0, 1, 0, 1, 1, 0, 1},
                        new float[0],
                        List.of(new MeshAppearance.Surface(0, material)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0x80123456})),
                        List.of(
                                new MeshAppearance.RigidBillboard(
                                        36,
                                        new MeshAppearance.Vec3(1, .5f, .5f),
                                        new int[] {0, 1, 2, 3})));
        var scene = GpuModelScene.create(mesh, appearance);
        assertEquals(material, scene.draws().getFirst().material());
        assertTrue(scene.draws().getFirst().additive(true));
        assertFalse(scene.draws().getFirst().additive(false));
        var packed = scene.vertexData();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * GpuModelScene.VERTEX_FLOATS;
            assertEquals(1f, packed.get(offset));
            assertEquals(1f, packed.get(offset + 8));
            assertEquals(.5f, packed.get(offset + 9));
            assertEquals(.5f, packed.get(offset + 10));
            assertEquals(1f, packed.get(offset + 11));
        }
        assertTrue(scene.minimum(0) < mesh.minimum(0));
        assertTrue(scene.maximum(0) > mesh.maximum(0));
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(appearance.minimum(axis), scene.minimum(axis));
            assertEquals(appearance.maximum(axis), scene.maximum(axis));
        }
        assertEquals(4L * 48 + 6 * 4 + 4, scene.uploadBytes());
    }

    @Test
    void storageIsImmutableAndBufferPositionsDoNotLeakBetweenUploadObservers() {
        var scene = GpuModelScene.create(mesh(), null);
        assertFalse(scene.hasNormals());
        assertTrue(scene.textures().isEmpty());
        var vertices = scene.vertexData();
        vertices.position(8);
        assertEquals(0, scene.vertexData().position());
        assertThrows(ReadOnlyBufferException.class, () -> scene.vertexData().put(0, 1));
        assertThrows(ReadOnlyBufferException.class, () -> scene.indexData().put(0, 2));
        assertThrows(UnsupportedOperationException.class, () -> scene.draws().clear());
        assertEquals(0, scene.vertexData().get(3));
        assertEquals(0, scene.vertexData().get(6));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuModelScene.create(
                                mesh(),
                                new MeshAppearance(
                                        mesh(),
                                        new float[] {0, 0, 1, 0, .5f, 1},
                                        List.of(),
                                        List.of())));
    }

    @Test
    void preservesMaximumUnsigned16IndexInExplicitIndex32UploadWithoutExpansion() {
        var positions = new float[PreviewMesh.MAX_VERTICES * 3];
        positions[positions.length - 1] = 1;
        var mesh =
                new PreviewMesh(
                        positions, new int[] {0, 1, 65535}, List.of(new PreviewMesh.Part(0, 0, 3)));
        var scene = GpuModelScene.create(mesh, null);
        assertEquals(65536, scene.vertexCount());
        assertEquals(65535, scene.indexData().get(2));
        assertEquals(65536L * 48 + 12, scene.uploadBytes());
    }
}
