package com.vibeminer.rendering.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Bounded versioned geometry/UV/atlas derivative; byte content is validated before allocation. */
public final class MeshAppearanceCodec {
    public static final String REVISION = "mesh-appearance-v3-materials-bind-cards";
    public static final int MAX_BYTES = 40 << 20;
    private static final int MAX_GEOMETRY_BYTES =
            16
                    + PreviewMesh.MAX_VERTICES * 3 * 4
                    + PreviewMesh.MAX_INDICES * 4
                    + PreviewMesh.MAX_PARTS * 12;

    private MeshAppearanceCodec() {}

    public static byte[] encode(MeshAppearance value) {
        long geometrySize =
                16L
                        + value.mesh().positions.length * 4L
                        + value.mesh().indices.length * 4L
                        + value.mesh().parts().size() * 12L;
        long size = 28L + geometrySize + value.uv.length * 4L + value.normals.length * 4L;
        for (var surface : value.surfaces()) {
            size += 20L + surface.material().stages().size() * 12L;
        }
        for (var card : value.billboards()) {
            size += 20L + card.vertexIndices().length * 4L;
        }
        for (var texture : value.textures()) {
            size += 8L + texture.pixels.length * 4L;
        }
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("Appearance derivative exceeds bounds");
        }
        byte[] geometry = PreviewMeshCodec.encode(value.mesh());
        var output = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x56415033)
                .putInt(geometry.length)
                .putInt(value.uv.length)
                .putInt(value.surfaces().size())
                .putInt(value.textures().size())
                .putInt(value.normals.length)
                .putInt(value.billboards().size())
                .put(geometry);
        for (float v : value.uv) {
            output.putFloat(v);
        }
        for (float v : value.normals) {
            output.putFloat(v);
        }
        for (var surface : value.surfaces()) {
            var material = surface.material();
            output.putInt(surface.sectionIndex())
                    .putInt(material.combiner().code())
                    .putInt(material.lighting().code())
                    .putInt(material.blend().code())
                    .putInt(material.stages().size());
            for (var stage : material.stages()) {
                output.putInt(stage.textureIndex())
                        .putInt(stage.coordinate().code())
                        .putInt((stage.wrapU() ? 1 : 0) | (stage.wrapV() ? 2 : 0));
            }
        }
        for (var card : value.billboards()) {
            int[] members = card.vertexIndices();
            var pivot = card.modelPivot();
            output.putInt(card.boneId())
                    .putFloat(pivot.x())
                    .putFloat(pivot.y())
                    .putFloat(pivot.z())
                    .putInt(members.length);
            for (int member : members) {
                output.putInt(member);
            }
        }
        for (var texture : value.textures()) {
            output.putInt(texture.width()).putInt(texture.height());
            for (int color : texture.pixels) {
                output.putInt(color);
            }
        }
        return output.array();
    }

    public static MeshAppearance decode(byte[] bytes) {
        if (bytes.length < 24 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Appearance derivative exceeds bounds");
        }
        int magic = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (magic == 0x56415032) {
            return decodeLegacy(bytes);
        }
        if (magic != 0x56415033 || bytes.length < 28) {
            throw new IllegalArgumentException("Unknown appearance derivative");
        }
        try {
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            input.getInt();
            int geometry = input.getInt(),
                    uvCount = input.getInt(),
                    surfaceCount = input.getInt(),
                    textureCount = input.getInt(),
                    normalCount = input.getInt(),
                    groupCount = input.getInt();
            if (geometry < 16
                    || geometry > MAX_GEOMETRY_BYTES
                    || geometry > input.remaining()
                    || uvCount < 2
                    || uvCount > PreviewMesh.MAX_VERTICES * 2
                    || surfaceCount < 0
                    || surfaceCount > PreviewMesh.MAX_PARTS
                    || textureCount < 0
                    || textureCount > 16
                    || normalCount < 0
                    || normalCount > PreviewMesh.MAX_VERTICES * 3
                    || groupCount < 0
                    || groupCount > 128
                    || (long) geometry
                                    + uvCount * 4L
                                    + normalCount * 4L
                                    + surfaceCount * 32L
                                    + groupCount * 24L
                                    + textureCount * 8L
                            > input.remaining()) {
                throw new IllegalArgumentException("Invalid appearance derivative bounds");
            }
            byte[] raw = new byte[geometry];
            input.get(raw);
            var mesh = PreviewMeshCodec.decode(raw);
            if (uvCount != mesh.vertexCount() * 2
                    || normalCount != 0 && normalCount != mesh.vertexCount() * 3) {
                throw new IllegalArgumentException("Invalid appearance coordinate counts");
            }
            float[] uv = new float[uvCount], normals = new float[normalCount];
            for (int i = 0; i < uv.length; i++) {
                uv[i] = input.getFloat();
            }
            for (int i = 0; i < normals.length; i++) {
                normals[i] = input.getFloat();
            }
            var surfaces = new ArrayList<MeshAppearance.Surface>(surfaceCount);
            for (int i = 0; i < surfaceCount; i++) {
                int section = input.getInt();
                var combiner =
                        tag(
                                input.getInt(),
                                MeshAppearance.Combiner.values(),
                                MeshAppearance.Combiner::code);
                var lighting =
                        tag(
                                input.getInt(),
                                MeshAppearance.Lighting.values(),
                                MeshAppearance.Lighting::code);
                var blend =
                        tag(
                                input.getInt(),
                                MeshAppearance.Blend.values(),
                                MeshAppearance.Blend::code);
                int count = input.getInt();
                if (count < 1 || count > 2 || count * 12L > input.remaining()) {
                    throw new IllegalArgumentException("Invalid stage count");
                }
                var stages = new ArrayList<MeshAppearance.TextureStage>(count);
                for (int stage = 0; stage < count; stage++) {
                    int texture = input.getInt();
                    var coordinate =
                            tag(
                                    input.getInt(),
                                    MeshAppearance.Coordinate.values(),
                                    MeshAppearance.Coordinate::code);
                    int flags = input.getInt();
                    if ((flags & ~3) != 0) {
                        throw new IllegalArgumentException("Invalid texture stage flags");
                    }
                    stages.add(
                            new MeshAppearance.TextureStage(
                                    texture, coordinate, (flags & 1) != 0, (flags & 2) != 0));
                }
                surfaces.add(
                        new MeshAppearance.Surface(
                                section,
                                new MeshAppearance.Material(combiner, stages, lighting, blend)));
            }
            var cards = new ArrayList<MeshAppearance.RigidBillboard>(groupCount);
            for (int i = 0; i < groupCount; i++) {
                int bone = input.getInt();
                var pivot =
                        new MeshAppearance.Vec3(
                                input.getFloat(), input.getFloat(), input.getFloat());
                int count = input.getInt();
                if (count < 1 || count > 8 || count * 4L > input.remaining()) {
                    throw new IllegalArgumentException("Invalid billboard member count");
                }
                int[] members = new int[count];
                for (int j = 0; j < count; j++) {
                    members[j] = input.getInt();
                }
                cards.add(new MeshAppearance.RigidBillboard(bone, pivot, members));
            }
            var textures = readTextures(input, textureCount);
            if (input.hasRemaining()) {
                throw new IllegalArgumentException("Trailing appearance data");
            }
            return new MeshAppearance(mesh, uv, normals, surfaces, textures, cards);
        } catch (java.nio.BufferUnderflowException invalid) {
            throw new IllegalArgumentException("Truncated appearance derivative", invalid);
        }
    }

    private static <T> T tag(int code, T[] values, java.util.function.ToIntFunction<T> wireCode) {
        for (T value : values) {
            if (wireCode.applyAsInt(value) == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown appearance material code");
    }

    private static ArrayList<MeshAppearance.Texture> readTextures(ByteBuffer input, int count) {
        var textures = new ArrayList<MeshAppearance.Texture>(count);
        long total = 0;
        for (int i = 0; i < count; i++) {
            int width = input.getInt(), height = input.getInt();
            long pixels = (long) width * height;
            total += pixels;
            if (width < 1
                    || height < 1
                    || width > 2048
                    || height > 2048
                    || total > 8_388_608
                    || pixels > input.remaining() / 4) {
                throw new IllegalArgumentException("Invalid appearance image bounds");
            }
            int[] argb = new int[(int) pixels];
            for (int p = 0; p < argb.length; p++) {
                argb[p] = input.getInt();
            }
            textures.add(MeshAppearance.Texture.adopt(width, height, argb));
        }
        return textures;
    }

    private static MeshAppearance decodeLegacy(byte[] bytes) {
        if (bytes.length < 24 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Appearance derivative exceeds bounds");
        }
        try {
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (input.getInt() != 0x56415032) {
                throw new IllegalArgumentException("Unknown appearance derivative");
            }
            int geometry = input.getInt(),
                    uvCount = input.getInt(),
                    surfaceCount = input.getInt(),
                    textureCount = input.getInt(),
                    normalCount = input.getInt();
            if (geometry < 16
                    || geometry > MAX_GEOMETRY_BYTES
                    || geometry > input.remaining()
                    || uvCount < 2
                    || uvCount > PreviewMesh.MAX_VERTICES * 2
                    || surfaceCount < 0
                    || surfaceCount > PreviewMesh.MAX_PARTS
                    || textureCount < 0
                    || textureCount > 16
                    || normalCount < 0
                    || normalCount > PreviewMesh.MAX_VERTICES * 3) {
                throw new IllegalArgumentException("Invalid appearance derivative bounds");
            }
            byte[] raw = new byte[geometry];
            input.get(raw);
            var mesh = PreviewMeshCodec.decode(raw);
            if (uvCount != mesh.vertexCount() * 2
                    || input.remaining()
                            < uvCount * 4L
                                    + normalCount * 4L
                                    + surfaceCount * 12L
                                    + textureCount * 8L) {
                throw new IllegalArgumentException("Truncated appearance derivative");
            }
            float[] uv = new float[uvCount];
            for (int i = 0; i < uv.length; i++) {
                uv[i] = input.getFloat();
            }
            if (normalCount != 0 && normalCount != mesh.vertexCount() * 3) {
                throw new IllegalArgumentException("Invalid normal derivative count");
            }
            float[] normals = new float[normalCount];
            for (int i = 0; i < normals.length; i++) {
                normals[i] = input.getFloat();
            }
            var surfaces = new ArrayList<MeshAppearance.Surface>();
            for (int i = 0; i < surfaceCount; i++) {
                int section = input.getInt(), texture = input.getInt(), flags = input.getInt();
                if ((flags & ~7) != 0) {
                    throw new IllegalArgumentException("Invalid appearance flags");
                }
                surfaces.add(
                        new MeshAppearance.Surface(
                                section,
                                texture,
                                (flags & 1) != 0,
                                (flags & 2) != 0,
                                (flags & 4) != 0));
            }
            var textures = new ArrayList<MeshAppearance.Texture>();
            long total = 0;
            for (int i = 0; i < textureCount; i++) {
                int width = input.getInt(), height = input.getInt();
                long pixels = (long) width * height;
                total += pixels;
                if (width < 1
                        || height < 1
                        || width > 2048
                        || height > 2048
                        || total > 8_388_608
                        || pixels > input.remaining() / 4) {
                    throw new IllegalArgumentException("Invalid appearance image bounds");
                }
                int[] argb = new int[(int) pixels];
                for (int p = 0; p < argb.length; p++) {
                    argb[p] = input.getInt();
                }
                textures.add(MeshAppearance.Texture.adopt(width, height, argb));
            }
            if (input.hasRemaining()) {
                throw new IllegalArgumentException("Trailing appearance data");
            }
            return new MeshAppearance(mesh, uv, normals, surfaces, textures);
        } catch (java.nio.BufferUnderflowException invalid) {
            throw new IllegalArgumentException("Truncated appearance derivative", invalid);
        }
    }
}
