package com.vibeminer.rendering.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Bounded versioned geometry/UV/atlas derivative; byte content is validated before allocation. */
public final class MeshAppearanceCodec {
    public static final String REVISION = "mesh-appearance-v2";
    public static final int MAX_BYTES = 40 << 20;

    private MeshAppearanceCodec() {}

    public static byte[] encode(MeshAppearance value) {
        // The API checkpoint must not silently flatten richer materials into the legacy wire type.
        if (!value.billboards().isEmpty()
                || value.surfaces().stream()
                        .anyMatch(
                                surface ->
                                        surface.material().combiner()
                                                        != MeshAppearance.Combiner.DIFFUSE
                                                || surface.material().lighting()
                                                        != MeshAppearance.Lighting.LIT
                                                || surface.material().blend()
                                                        == MeshAppearance.Blend.ADDITIVE)) {
            throw new IllegalArgumentException("Rich appearance requires the new material codec");
        }
        byte[] geometry = PreviewMeshCodec.encode(value.mesh());
        float[] uv = value.uv();
        float[] normals = value.normals();
        long size =
                24L
                        + geometry.length
                        + uv.length * 4L
                        + normals.length * 4L
                        + value.surfaces().size() * 12L;
        for (var texture : value.textures()) {
            size += 8L + texture.width() * texture.height() * 4L;
        }
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("Appearance derivative exceeds bounds");
        }
        var output = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x56415032)
                .putInt(geometry.length)
                .putInt(uv.length)
                .putInt(value.surfaces().size())
                .putInt(value.textures().size())
                .putInt(normals.length)
                .put(geometry);
        for (float v : uv) {
            output.putFloat(v);
        }
        for (float normal : normals) {
            output.putFloat(normal);
        }
        for (var surface : value.surfaces()) {
            output.putInt(surface.sectionIndex())
                    .putInt(surface.textureIndex())
                    .putInt(
                            (surface.wrapU() ? 1 : 0)
                                    | (surface.wrapV() ? 2 : 0)
                                    | (surface.alphaKey() ? 4 : 0));
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
                textures.add(new MeshAppearance.Texture(width, height, argb));
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
