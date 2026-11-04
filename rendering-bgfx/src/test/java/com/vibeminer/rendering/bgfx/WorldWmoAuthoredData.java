package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/** Authored neutral presentation fixtures; no game source or authenticated capability. */
final class WorldWmoAuthoredData implements BasicWmoData.View {
    final BasicWmoData.Metadata meta;
    final float[] positions, normals, uv;
    final int[] indices;
    final List<BasicWmoData.Batch> batches = new ArrayList<>();
    final List<BasicWmoData.Source> sources = new ArrayList<>();
    final List<BasicWmoData.Image> images = new ArrayList<>();
    final int materials;

    WorldWmoAuthoredData(
            TerrainScene scene,
            float depth,
            boolean maximum,
            boolean reverse,
            boolean gradient,
            boolean cancelNormals) {
        float rawC = (float) (51200.0 / 3.0);
        var placement = new BasicWmoData.Placement(rawC, 0, rawC, 0, 0, 0, 1024, 12, 0, 0);
        var tile = scene.tiles().getFirst();
        meta =
                new BasicWmoData.Metadata(
                        7,
                        0,
                        tile.x(),
                        tile.y(),
                        0,
                        77,
                        5,
                        1,
                        "ab".repeat(32),
                        TerrainGeometryFingerprint.sha256(tile),
                        0xff000000L,
                        scene.origin(),
                        placement);
        int vertices = maximum ? 512 : 3, count = maximum ? 1536 : 3;
        positions = new float[vertices * 3];
        normals = new float[vertices * 3];
        uv = new float[vertices * 2];
        indices = new int[count];
        float[] y = {6, -6, 0}, z = {-6, -6, 6};
        for (int i = 0; i < vertices; i++) {
            positions[i * 3] = -depth;
            positions[i * 3 + 1] = y[i % 3];
            positions[i * 3 + 2] = z[i % 3];
            normals[i * 3 + 2] = 1;
            uv[i * 2] = gradient ? 1.25f : .5f;
            uv[i * 2 + 1] = gradient ? -.25f : .5f;
        }
        if (cancelNormals) {
            float[] ny = {3, -3, 0}, nz = {-2, -2, 4};
            for (int i = 0; i < 3; i++) {
                positions[i * 3 + 1] = ny[i] + .0244140625f;
                positions[i * 3 + 2] = nz[i] + .0244140625f;
            }
            normals[0] = 1;
            normals[2] = 0;
            normals[3] = -.5f;
            normals[4] = .8660254f;
            normals[5] = 0;
            normals[6] = -.5f;
            normals[7] = -.8660254f;
            normals[8] = 0;
        }
        for (int i = 0; i < count; i++) {
            indices[i] = reverse ? new int[] {0, 2, 1}[i % 3] : i % 3;
        }
        materials = maximum ? 6 : 1;
        int first = 0;
        for (int i = 0; i < materials; i++) {
            int span = maximum ? (i < 5 ? 255 : 261) : 3;
            batches.add(new BasicWmoData.Batch(first, span, i));
            first += span;
            int side = maximum ? 256 : 4;
            byte[] rgba = new byte[side * side * 4];
            for (int row = 0; row < side; row++) {
                for (int col = 0; col < side; col++) {
                    int at = (row * side + col) * 4;
                    rgba[at] = (byte) (gradient ? col * 64 : 255);
                    rgba[at + 1] = (byte) (gradient ? row * 64 : 0);
                    rgba[at + 2] = 0;
                    rgba[at + 3] = (byte) 255;
                }
            }
            images.add(new BasicWmoData.Image(10 + i, side, side, ByteBuffer.wrap(rgba)));
        }
        int[] limits = {1 << 20, 2 << 20, 8 << 20, 1024, 32768};
        for (int i = 0; i < 5; i++) {
            sources.add(new BasicWmoData.Source(1 + i, i, "cd".repeat(16), 1, null, limits[i]));
        }
        for (var image : images) {
            sources.add(new BasicWmoData.Source(image.id(), 5, "ef".repeat(16), 1, null, 65536));
        }
    }

    public BasicWmoData.Metadata metadata() {
        return meta;
    }

    public FloatBuffer positions() {
        return FloatBuffer.wrap(positions).asReadOnlyBuffer();
    }

    public FloatBuffer normals() {
        return FloatBuffer.wrap(normals).asReadOnlyBuffer();
    }

    public FloatBuffer uv() {
        return FloatBuffer.wrap(uv).asReadOnlyBuffer();
    }

    public IntBuffer indices() {
        return IntBuffer.wrap(indices).asReadOnlyBuffer();
    }

    public List<BasicWmoData.Batch> batches() {
        return batches;
    }

    public List<BasicWmoData.Source> sources() {
        return sources;
    }

    public int materialCount() {
        return materials;
    }

    public long materialWord(int material, int word) {
        return word == 3 ? 10 + material : 0;
    }

    public int imageCount() {
        return images.size();
    }

    public BasicWmoData.Image image(int i) {
        return images.get(i);
    }
}
