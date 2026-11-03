package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded WDT18/ADT18 terrain metadata and geometry, retaining source positions and holes. */
public final class WorldFormats {
    public static final String REVISION = "world-terrain-v1";
    public static final double TILE_SIZE = 1600.0 / 3;
    public static final int MAX_FILE_BYTES = 32 << 20;

    private WorldFormats() {}

    public record Tile(int x, int y, long rootId, long mapTextureId, long minimapId) {
        public Tile {
            if (x < 0 || y < 0 || x >= 64 || y >= 64) {
                throw new IllegalArgumentException("Invalid tile coordinates");
            }
        }

        public String key() {
            return x + "," + y;
        }
    }

    public record World(List<Tile> tiles, boolean globalModel) {
        public World {
            tiles = List.copyOf(tiles);
        }
    }

    public static final class Terrain {
        private final float[] vertices;
        private final int[] triangles;
        private final int chunks;

        public Terrain(float[] vertices, int[] triangles, int chunks) {
            if (vertices.length == 0
                    || vertices.length % 3 != 0
                    || vertices.length > 256 * 145 * 3
                    || triangles.length % 3 != 0
                    || triangles.length > 256 * 8 * 8 * 12
                    || chunks < 1
                    || chunks > 256) {
                throw new IllegalArgumentException("Invalid terrain bounds");
            }
            for (float v : vertices) {
                if (!Float.isFinite(v) || Math.abs(v) > 1_000_000) {
                    throw new IllegalArgumentException("Invalid terrain coordinate");
                }
            }
            for (int i : triangles) {
                if (i < 0 || i >= vertices.length / 3) {
                    throw new IllegalArgumentException("Invalid terrain index");
                }
            }
            this.vertices = vertices.clone();
            this.triangles = triangles.clone();
            this.chunks = chunks;
        }

        public float[] vertices() {
            return vertices.clone();
        }

        public int[] triangles() {
            return triangles.clone();
        }

        public int chunks() {
            return chunks;
        }
    }

    public static World wdt(byte[] bytes, BooleanSupplier canceled) {
        var input = input(bytes);
        int[] main = null;
        long[][] ids = null;
        boolean version = false, global = false;
        while (input.hasRemaining()) {
            check(canceled);
            var c = chunk(input, input.limit());
            switch (c.tag) {
                case 0x4d564552 -> {
                    if (c.size != 4 || input.getInt(c.start) != 18 || version) {
                        throw invalid("WDT version");
                    }
                    version = true;
                }
                case 0x4d504844 -> {
                    if (c.size < 4) {
                        throw invalid("WDT flags");
                    }
                    global = (input.getInt(c.start) & 1) != 0;
                }
                case 0x4d41494e -> {
                    if (c.size != 4096 * 8 || main != null) {
                        throw invalid("WDT MAIN size");
                    }
                    main = new int[4096];
                    for (int i = 0; i < 4096; i++) {
                        main[i] = input.getInt(c.start + i * 8);
                    }
                }
                case 0x4d414944 -> {
                    if (c.size != 4096 * 32 || ids != null) {
                        throw invalid("WDT MAID size");
                    }
                    ids = new long[4096][3];
                    for (int i = 0; i < 4096; i++) {
                        ids[i][0] = Integer.toUnsignedLong(input.getInt(c.start + i * 32));
                        ids[i][1] = Integer.toUnsignedLong(input.getInt(c.start + i * 32 + 20));
                        ids[i][2] = Integer.toUnsignedLong(input.getInt(c.start + i * 32 + 28));
                    }
                }
                default -> {}
            }
            input.position(c.end);
        }
        if (!version || main == null) {
            throw invalid("WDT requires version and tile inventory");
        }
        var tiles = new ArrayList<Tile>();
        for (int i = 0; i < 4096; i++) {
            if ((main[i] & 1) != 0) {
                tiles.add(
                        new Tile(
                                i / 64,
                                i % 64,
                                ids == null ? 0 : ids[i][0],
                                ids == null ? 0 : ids[i][1],
                                ids == null ? 0 : ids[i][2]));
            }
        }
        return new World(tiles, global);
    }

    public static Terrain adt(byte[] bytes, BooleanSupplier canceled) {
        var input = input(bytes);
        var vertices = new ArrayList<Float>();
        var indices = new ArrayList<Integer>();
        boolean[] seen = new boolean[256];
        int chunks = 0;
        boolean version = false;
        while (input.hasRemaining()) {
            check(canceled);
            var c = chunk(input, input.limit());
            if (c.tag == 0x4d564552) {
                if (c.size != 4 || input.getInt(c.start) != 18 || version) {
                    throw invalid("ADT version");
                }
                version = true;
            } else if (c.tag == 0x4d434e4b) {
                if (c.size < 128 || chunks == 256) {
                    throw invalid("ADT MCNK header");
                }
                int flags = input.getInt(c.start),
                        x = input.getInt(c.start + 4),
                        y = input.getInt(c.start + 8);
                if (x < 0 || x >= 16 || y < 0 || y >= 16 || seen[y * 16 + x]) {
                    throw invalid("Duplicate or invalid chunk index");
                }
                seen[y * 16 + x] = true;
                long high = input.getLong(c.start + 20);
                int low = Short.toUnsignedInt(input.getShort(c.start + 60));
                float px = input.getFloat(c.start + 104),
                        py = input.getFloat(c.start + 108),
                        pz = input.getFloat(c.start + 112);
                int height = -1;
                input.position(c.start + 128);
                while (input.position() < c.end) {
                    var sub = chunk(input, c.end);
                    if (sub.tag == 0x4d435654) {
                        if (sub.size != 145 * 4 || height != -1) {
                            throw invalid("ADT heights");
                        }
                        height = sub.start;
                    }
                    input.position(sub.end);
                }
                if (height < 0) {
                    throw invalid("Terrain chunk has no height data");
                }
                int base = vertices.size() / 3;
                for (int row = 0, at = 0; row < 17; row++) {
                    for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++, at++) {
                        vertices.add((float) (px - row * (TILE_SIZE / 256)));
                        vertices.add(
                                (float)
                                        (py
                                                - col * (TILE_SIZE / 128)
                                                - (row % 2) * (TILE_SIZE / 256)));
                        vertices.add(pz + input.getFloat(height + at * 4));
                    }
                }
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 8; col++) {
                        boolean hole =
                                (flags & 0x10000) != 0
                                        ? ((high >>> (row * 8 + col)) & 1) != 0
                                        : ((low >>> ((row / 2) * 4 + col / 2)) & 1) != 0;
                        if (hole) {
                            continue;
                        }
                        int a = base + row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                        for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a}) {
                            indices.add(index);
                        }
                    }
                }
                chunks++;
            }
            input.position(c.end);
        }
        if (!version || chunks == 0) {
            throw invalid("ADT has no readable terrain");
        }
        float[] positions = new float[vertices.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = vertices.get(i);
        }
        return new Terrain(
                positions, indices.stream().mapToInt(Integer::intValue).toArray(), chunks);
    }

    private record Chunk(int tag, int start, int size, int end) {}

    private static Chunk chunk(ByteBuffer input, int end) {
        if (end - input.position() < 8) {
            throw invalid("Truncated chunk header");
        }
        int tag = input.getInt(), size = input.getInt(), start = input.position();
        if (size < 0 || size > end - start) {
            throw invalid("Chunk outside file");
        }
        return new Chunk(tag, start, size, start + size);
    }

    private static ByteBuffer input(byte[] bytes) {
        if (bytes.length < 12 || bytes.length > MAX_FILE_BYTES) {
            throw invalid("World file exceeds bounds");
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
