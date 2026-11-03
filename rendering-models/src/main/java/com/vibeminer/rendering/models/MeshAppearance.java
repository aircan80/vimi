package com.vibeminer.rendering.models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded diffuse texture bindings for a static surface; no animation or full shader claim. */
public final class MeshAppearance {
    public record Surface(
            int sectionIndex, int textureIndex, boolean wrapU, boolean wrapV, boolean alphaKey) {}

    public static final class Texture {
        private final int width, height;
        final int[] pixels;

        public Texture(int width, int height, int[] argb) {
            if (width < 1
                    || height < 1
                    || width > 2048
                    || height > 2048
                    || (long) width * height != argb.length) {
                throw new IllegalArgumentException("Preview texture exceeds bounds");
            }
            this.width = width;
            this.height = height;
            pixels = argb.clone();
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int[] argb() {
            return pixels.clone();
        }
    }

    private final PreviewMesh mesh;
    final float[] uv;
    final float[] normals;
    final Map<Integer, Surface> surfaces;
    final List<Texture> textures;

    public MeshAppearance(
            PreviewMesh mesh, float[] uv, List<Surface> surfaces, List<Texture> textures) {
        this(mesh, uv, new float[0], surfaces, textures);
    }

    public MeshAppearance(
            PreviewMesh mesh,
            float[] uv,
            float[] normals,
            List<Surface> surfaces,
            List<Texture> textures) {
        if (uv.length != mesh.vertexCount() * 2
                || surfaces.size() > mesh.parts().size()
                || textures.size() > 16) {
            throw new IllegalArgumentException("Invalid static texture binding bounds");
        }
        if (normals.length != 0 && normals.length != mesh.vertexCount() * 3) {
            throw new IllegalArgumentException("Invalid static normal bounds");
        }
        for (float coordinate : normals) {
            if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 1_000_000) {
                throw new IllegalArgumentException("Invalid static normal");
            }
        }
        long pixels = 0;
        for (var texture : textures) {
            pixels += texture.pixels.length;
        }
        if (pixels > 8_388_608) {
            throw new IllegalArgumentException("Preview texture memory exceeds bounds");
        }
        for (float coordinate : uv) {
            if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 1_000_000) {
                throw new IllegalArgumentException("Invalid texture coordinate");
            }
        }
        var mapped = new HashMap<Integer, Surface>();
        for (var surface : surfaces) {
            if (surface.sectionIndex() < 0
                    || surface.sectionIndex() >= mesh.parts().size()
                    || surface.textureIndex() < 0
                    || surface.textureIndex() >= textures.size()
                    || mapped.putIfAbsent(surface.sectionIndex(), surface) != null) {
                throw new IllegalArgumentException("Invalid or ambiguous static texture binding");
            }
        }
        this.mesh = mesh;
        this.uv = uv.clone();
        this.normals = normals.clone();
        this.surfaces = Map.copyOf(mapped);
        this.textures = List.copyOf(textures);
    }

    public PreviewMesh mesh() {
        return mesh;
    }

    public float[] uv() {
        return uv.clone();
    }

    public float[] normals() {
        return normals.clone();
    }

    public List<Surface> surfaces() {
        return surfaces.values().stream()
                .sorted(java.util.Comparator.comparingInt(Surface::sectionIndex))
                .toList();
    }

    public List<Texture> textures() {
        return textures;
    }
}
