package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RichCodecTest {
    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1},
                new int[] {0, 1, 2, 0, 2, 3},
                List.of(new PreviewMesh.Part(0, 0, 6)));
    }

    private static MeshAppearance card() {
        return new MeshAppearance(
                mesh(),
                new float[] {0, 1, 1, 1, 1, 0, 0, 0},
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
                                                        true,
                                                        false)),
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.ADDITIVE))),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {0x8064503c})),
                List.of(
                        new MeshAppearance.RigidBillboard(
                                17, new MeshAppearance.Vec3(0, 0, 0), new int[] {0, 1, 2, 3})));
    }

    @Test
    void richSparseRoundtripRetainsMaterialAndDefensiveMembershipAndDerivedBounds() {
        var original = card();
        byte[] encoded = MeshAppearanceCodec.encode(original);
        var copy = MeshAppearanceCodec.decode(encoded);
        assertEquals(original.surfaces(), copy.surfaces());
        assertEquals(original.billboards(), copy.billboards());
        assertEquals(original.minimum(0), copy.minimum(0));
        assertEquals(original.maximum(2), copy.maximum(2));
        copy.billboards().getFirst().vertexIndices()[0] = 99;
        assertArrayEquals(encoded, MeshAppearanceCodec.encode(copy));
        assertEquals(0x8064503c, copy.textures().getFirst().argb()[0]);
    }

    @Test
    void authoredLegacyV2BytesDecodeThroughOneStageAdapter() {
        byte[] geometry = PreviewMeshCodec.encode(mesh());
        var bytes =
                ByteBuffer.allocate(24 + geometry.length + 8 * 4 + 12 + 12)
                        .order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x56415032)
                .putInt(geometry.length)
                .putInt(8)
                .putInt(1)
                .putInt(1)
                .putInt(0)
                .put(geometry);
        for (int i = 0; i < 8; i++) {
            bytes.putFloat(0);
        }
        bytes.putInt(0).putInt(0).putInt(5).putInt(1).putInt(1).putInt(0x80123456);
        var value = MeshAppearanceCodec.decode(bytes.array());
        assertEquals(
                List.of(new MeshAppearance.Surface(0, 0, true, false, true)), value.surfaces());
        assertTrue(value.billboards().isEmpty());
        assertEquals(
                value.surfaces(),
                MeshAppearanceCodec.decode(MeshAppearanceCodec.encode(value)).surfaces());
    }

    @Test
    void codecRejectsUnknownCodesOversizedCountsTruncationAndTrailingBytes() {
        byte[] valid = MeshAppearanceCodec.encode(card());
        int geometry = ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
        int surface = 28 + geometry + 8 * 4;
        for (int[] mutation :
                new int[][] {
                    {0, 0x56415034},
                    {8, Integer.MAX_VALUE},
                    {12, 257},
                    {16, 17},
                    {20, Integer.MAX_VALUE},
                    {24, 129},
                    {surface + 4, 99},
                    {surface + 8, 99},
                    {surface + 12, 99},
                    {surface + 16, 3},
                    {surface + 20, 16},
                    {surface + 24, 99},
                    {surface + 28, 4}
                }) {
            byte[] bad = valid.clone();
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(mutation[0], mutation[1]);
            assertThrows(IllegalArgumentException.class, () -> MeshAppearanceCodec.decode(bad));
        }
        for (int length : new int[] {0, 23, 27, valid.length - 1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MeshAppearanceCodec.decode(Arrays.copyOf(valid, length)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshAppearanceCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshAppearanceCodec.decode(new byte[MeshAppearanceCodec.MAX_BYTES + 1]));
    }

    @Test
    void maximumSparseGroupAndStageFramingStillFitsTheDocumentedAggregateEnvelope() {
        long geometry = 16L + 65536 * 3 * 4L + 1500000 * 4L + 256 * 12L;
        long allUpperBounds =
                28
                        + geometry
                        + 65536 * 2 * 4L
                        + 65536 * 3 * 4L
                        + 256 * (20 + 2 * 12L)
                        + 128 * (20 + 8 * 4L)
                        + 16 * 8L
                        + 8388608 * 4L;
        assertTrue(
                allUpperBounds < MeshAppearanceCodec.MAX_BYTES,
                "All current field maxima fit: no fictitious valid-per-field over-envelope fixture exists");
    }

    @Test
    void sparseGroupsRejectDuplicateIdentityMembershipAndCrossGroupTriangles() {
        var value = card();
        var group = value.billboards().getFirst();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance(
                                value.mesh(),
                                value.uv(),
                                value.normals(),
                                value.surfaces(),
                                value.textures(),
                                List.of(group, group)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance.RigidBillboard(
                                1, new MeshAppearance.Vec3(0, 0, 0), new int[] {1, 1}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance(
                                value.mesh(),
                                value.uv(),
                                value.normals(),
                                value.surfaces(),
                                value.textures(),
                                List.of(
                                        new MeshAppearance.RigidBillboard(
                                                1,
                                                new MeshAppearance.Vec3(0, 0, 0),
                                                new int[] {0, 1}),
                                        new MeshAppearance.RigidBillboard(
                                                2,
                                                new MeshAppearance.Vec3(0, 0, 0),
                                                new int[] {2, 3}))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance(
                                value.mesh(),
                                value.uv(),
                                value.normals(),
                                List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                                value.textures(),
                                List.of(group)));
    }
}
