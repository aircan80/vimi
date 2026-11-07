package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Authored structural file data; deliberately no game-source capability. */
final class GlobalWmoAuthoredData implements WorldGlobalSceneWriter.StructuralRead {
    private final float[] vertices;
    private final int[] indices;
    private final List<byte[]> images;
    private final WorldGlobalSceneWriter.Description description;
    private final List<WorldGlobalHashes.Batch> batches;
    private final WorldGlobalLayout.Counts counts;

    GlobalWmoAuthoredData(
            float[] vertices,
            int[] indices,
            List<byte[]> images,
            List<WorldGlobalHashes.Batch> batches) {
        this(vertices, indices, images, batches, new long[images.size()]);
    }

    /**
     * As above, with material i's MOMT flags and blend mode packed as {@code flags << 32 | blend}
     * in {@code materials[i]} (#297).
     */
    GlobalWmoAuthoredData(
            float[] vertices,
            int[] indices,
            List<byte[]> images,
            List<WorldGlobalHashes.Batch> batches,
            long[] materialWords) {
        this.vertices = vertices;
        this.indices = indices;
        this.images = images;
        this.batches = List.copyOf(batches);
        int pixels = images.size() * 4;
        counts =
                new WorldGlobalLayout.Counts(
                        images.size(),
                        1,
                        batches.size(),
                        images.size(),
                        vertices.length / 5,
                        indices.length,
                        indices.length / 3,
                        pixels);
        var raw = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(-1);
        raw.position(32);
        for (float value : new float[] {-1, -1, -1, 1, 1, 1}) {
            raw.putFloat(value);
        }
        raw.putShort((short) 8).putShort((short) 0).putShort((short) 0).putShort((short) 1024);
        var sources = new ArrayList<WorldGlobalHashes.Source>();
        sources.add(source(1, WorldGlobalHashes.Role.WDT, 0));
        sources.add(source(2, WorldGlobalHashes.Role.ROOT, 0));
        sources.add(source(3, WorldGlobalHashes.Role.GROUP, 0));
        var materials = new ArrayList<WorldGlobalHashes.Material>();
        var descriptors = new ArrayList<WorldGlobalHashes.Image>();
        for (int i = 0; i < images.size(); i++) {
            long[] words = new long[16];
            words[0] = materialWords[i] >>> 32;
            words[2] = materialWords[i] & 0xffff_ffffL;
            words[3] = 4 + i;
            words[4] = 0xff000000L;
            words[8] = 10;
            materials.add(new WorldGlobalHashes.Material(words));
            byte[] texels = images.get(i), opaque = texels.clone();
            int min = 255, max = 0, nonopaque = 0;
            for (int at = 3; at < texels.length; at += 4) {
                int alpha = texels[at] & 255;
                min = Math.min(min, alpha);
                max = Math.max(max, alpha);
                nonopaque += alpha == 255 ? 0 : 1;
                opaque[at] = (byte) 255;
            }
            descriptors.add(
                    new WorldGlobalHashes.Image(
                            4 + i,
                            2,
                            2,
                            1,
                            nonopaque == 0 ? 0 : 8,
                            0,
                            1,
                            min,
                            max,
                            nonopaque,
                            hash(texels),
                            hash(opaque)));
            sources.add(source(4 + i, WorldGlobalHashes.Role.IMAGE, i));
        }
        description =
                new WorldGlobalSceneWriter.Description(
                        249,
                        "7".repeat(64),
                        WorldGlobalHashes.buildLocale(
                                "wowf", "a".repeat(32), "b".repeat(32), "us", "enUS"),
                        WorldGlobalHashes.RawModf.copyOf(raw.array(), 2, 0xffff_ffffL),
                        sources,
                        materials,
                        descriptors);
    }

    private static WorldGlobalHashes.Source source(
            long id, WorldGlobalHashes.Role role, int ordinal) {
        return new WorldGlobalHashes.Source(
                id, role, ordinal, 64, 64L, "a".repeat(32), "1".repeat(64));
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] solid(int r, int g, int b) {
        return solid(r, g, b, 255);
    }

    static byte[] solid(int r, int g, int b, int a) {
        var result = new byte[16];
        for (int i = 0; i < 4; i++) {
            result[4 * i] = (byte) r;
            result[4 * i + 1] = (byte) g;
            result[4 * i + 2] = (byte) b;
            result[4 * i + 3] = (byte) a;
        }
        return result;
    }

    public WorldGlobalLayout.Counts counts() {
        return counts;
    }

    public WorldGlobalSceneWriter.Description description() {
        return description;
    }

    public List<WorldGlobalHashes.Group> groups() {
        return List.of(
                new WorldGlobalHashes.Group(
                        0, vertices.length / 5, 0, indices.length, 0, batches.size(), 0xa09));
    }

    public List<WorldGlobalHashes.Batch> batches() {
        return batches;
    }

    public float vertex(int v, int c) {
        return vertices[v * 5 + c];
    }

    public int index(int i) {
        return indices[i];
    }

    public int imageBytes(int i) {
        return images.get(i).length;
    }

    public int imageByte(int i, int at) {
        return images.get(i)[at] & 255;
    }

    static WorldGlobalSceneWriter.StructuralRead maximum() {
        var maximum = new WorldGlobalLayout.Counts(24, 8, 64, 18, 32768, 98304, 32768, 1179648);
        var sources = new java.util.ArrayList<WorldGlobalHashes.Source>();
        sources.add(
                new WorldGlobalHashes.Source(
                        1,
                        WorldGlobalHashes.Role.WDT,
                        0,
                        128,
                        128L,
                        "a".repeat(32),
                        "1".repeat(64)));
        sources.add(
                new WorldGlobalHashes.Source(
                        2,
                        WorldGlobalHashes.Role.ROOT,
                        0,
                        64,
                        null,
                        "b".repeat(32),
                        "2".repeat(64)));
        for (int n = 0; n < 8; n++) {
            long id = 3 + n;
            sources.add(
                    new WorldGlobalHashes.Source(
                            id,
                            WorldGlobalHashes.Role.GROUP,
                            n,
                            256,
                            256L,
                            String.format("%032x", id),
                            String.format("%064x", id)));
        }
        byte[] oneImage = new byte[262144];
        for (int n = 0; n < oneImage.length; n += 4) {
            oneImage[n] = (byte) 255;
            oneImage[n + 3] = (byte) 255;
        }
        String opaque = hash(oneImage);
        var images = new java.util.ArrayList<WorldGlobalHashes.Image>();
        for (int n = 0; n < 18; n++) {
            long id = 100 + n;
            sources.add(
                    new WorldGlobalHashes.Source(
                            id,
                            WorldGlobalHashes.Role.IMAGE,
                            n,
                            44876,
                            44876L,
                            String.format("%032x", id),
                            String.format("%064x", id)));
            images.add(
                    new WorldGlobalHashes.Image(
                            id, 256, 256, 1, 0, 0, 9, 255, 255, 0, opaque, opaque));
        }
        var materials = new java.util.ArrayList<WorldGlobalHashes.Material>();
        for (int n = 0; n < 24; n++) {
            long[] words = new long[16];
            words[3] = 100 + n % 18;
            materials.add(new WorldGlobalHashes.Material(words));
        }
        var original =
                new GlobalWmoAuthoredData(
                        new float[] {1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 1, 0, 0},
                        new int[] {0, 1, 2},
                        List.of(solid(255, 0, 0)),
                        List.of(new WorldGlobalHashes.Batch(0, 0, 0, 3)));
        var data =
                new WorldGlobalSceneWriter.Description(
                        249,
                        original.description().scope(),
                        original.description().buildLocale(),
                        original.description().modf(),
                        sources,
                        materials,
                        images);
        var groups = new java.util.ArrayList<WorldGlobalHashes.Group>();
        var batches = new java.util.ArrayList<WorldGlobalHashes.Batch>();
        for (int n = 0; n < 8; n++) {
            groups.add(
                    new WorldGlobalHashes.Group(n * 4096, 4096, n * 12288, 12288, n * 8, 8, 0xa09));
            for (int k = 0; k < 8; k++) {
                batches.add(new WorldGlobalHashes.Batch(n, 0, n * 12288 + k * 1536, 1536));
            }
        }
        WorldGlobalSceneWriter.StructuralRead procedural =
                new WorldGlobalSceneWriter.StructuralRead() {
                    public WorldGlobalLayout.Counts counts() {
                        return maximum;
                    }

                    public WorldGlobalSceneWriter.Description description() {
                        return data;
                    }

                    public List<WorldGlobalHashes.Group> groups() {
                        return groups;
                    }

                    public List<WorldGlobalHashes.Batch> batches() {
                        return batches;
                    }

                    public float vertex(int vertex, int component) {
                        return component == 0
                                ? (vertex % 100) / 100f
                                : component == 3 ? (vertex % 33 - 16) / 2f : 0;
                    }

                    public int index(int ordinal) {
                        return ordinal / 12288 * 4096 + ordinal % 12288 % 4096;
                    }

                    public int imageBytes(int image) {
                        return 262144;
                    }

                    public int imageByte(int image, int offset) {
                        return offset % 4 == 0 || offset % 4 == 3 ? 255 : 0;
                    }
                };
        return procedural;
    }
}
