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

    /** Filename coordinates and all eight recorded MAID references; zero means absent. */
    public record Tile(
            int x,
            int y,
            long rootId,
            long mapTextureId,
            long minimapId,
            long obj0Id,
            long obj1Id,
            long tex0Id,
            long lodId,
            long mapTextureNId) {
        /** Existing terrain/minimap callers do not invent unrecorded companion references. */
        public Tile(int x, int y, long rootId, long mapTextureId, long minimapId) {
            this(x, y, rootId, mapTextureId, minimapId, 0, 0, 0, 0, 0);
        }

        public Tile {
            if (x < 0 || y < 0 || x >= 64 || y >= 64) {
                throw new IllegalArgumentException("Invalid tile coordinates");
            }
            // Preserve the existing five-argument constructor's ID behavior; newly exposed source
            // fields are unsigned MAID values, never signed sentinel IDs.
            for (long id : new long[] {obj0Id, obj1Id, tex0Id, lodId, mapTextureNId}) {
                if (id < 0 || id > 0xffff_ffffL) {
                    throw new IllegalArgumentException("Invalid unsigned companion file ID");
                }
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
                    ids = new long[4096][8];
                    for (int i = 0; i < 4096; i++) {
                        check(canceled);
                        for (int field = 0; field < 8; field++) {
                            ids[i][field] =
                                    Integer.toUnsignedLong(
                                            input.getInt(c.start + i * 32 + field * 4));
                        }
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
                                ids == null ? 0 : ids[i][5],
                                ids == null ? 0 : ids[i][7],
                                ids == null ? 0 : ids[i][1],
                                ids == null ? 0 : ids[i][2],
                                ids == null ? 0 : ids[i][3],
                                ids == null ? 0 : ids[i][4],
                                ids == null ? 0 : ids[i][6]));
            }
        }
        return new World(tiles, global);
    }

    /** A source-derived point; no collision or walkability claim. */
    public record AreaPoint(float x, float y, float z) {
        public AreaPoint {
            coordinate(x);
            coordinate(y);
            coordinate(z);
        }
    }

    /** Raw MCNK origin Z is a base altitude, not the entry height. */
    public record AreaChunk(
            int x,
            int y,
            long areaId,
            float originX,
            float originY,
            float originZ,
            AreaPoint entryReference) {
        public AreaChunk {
            if (x < 0
                    || x >= 16
                    || y < 0
                    || y >= 16
                    || areaId < 0
                    || areaId > 0xffff_ffffL
                    || !Float.isFinite(originX)
                    || !Float.isFinite(originY)
                    || !Float.isFinite(originZ)) {
                throw invalid("Invalid area chunk");
            }
        }
    }

    public record AreaMetadata(List<AreaChunk> chunks) {
        public AreaMetadata {
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty() || chunks.size() > 256) {
                throw invalid("Invalid area metadata bounds");
            }
            boolean[] seen = new boolean[256];
            for (var chunk : chunks) {
                int at = chunk.y() * 16 + chunk.x();
                if (seen[at]) {
                    throw invalid("Duplicate area chunk");
                }
                seen[at] = true;
            }
        }
    }

    public static Terrain adt(byte[] bytes, BooleanSupplier canceled) {
        return parseAdt(bytes, canceled, true).terrain();
    }

    /** Metadata shares the geometry profile and validates every source height without a mesh. */
    public static AreaMetadata adtAreas(byte[] bytes, BooleanSupplier canceled) {
        return parseAdt(bytes, canceled, false).areas();
    }

    /** Native source samples through the same checked walker, never inferred from a mesh. */
    public static TerrainGrid adtGrid(byte[] bytes, BooleanSupplier canceled) {
        return parseAdt(bytes, canceled, false, true).grid();
    }

    private record ParsedAdt(Terrain terrain, AreaMetadata areas, TerrainGrid grid) {}

    private static ParsedAdt parseAdt(byte[] bytes, BooleanSupplier canceled, boolean geometry) {
        return parseAdt(bytes, canceled, geometry, false);
    }

    private static ParsedAdt parseAdt(
            byte[] bytes, BooleanSupplier canceled, boolean geometry, boolean captureGrid) {
        var input = input(bytes);
        var grids = captureGrid ? new ArrayList<TerrainGrid.Chunk>() : null;
        var vertices = geometry ? new ArrayList<Float>() : null;
        var indices = geometry ? new ArrayList<Integer>() : null;
        var areas = new ArrayList<AreaChunk>();
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
                int height = -1, color = -1;
                boolean badColors = false;
                input.position(c.start + 128);
                while (input.position() < c.end) {
                    var sub = chunk(input, c.end);
                    if (sub.tag == 0x4d435654) {
                        if (sub.size != 145 * 4 || height != -1) {
                            throw invalid("ADT heights");
                        }
                        height = sub.start;
                    } else if (captureGrid && sub.tag == 0x4d434356) {
                        if (sub.size != 580 || color != -1 || badColors) {
                            badColors = true;
                            color = -1;
                        } else {
                            color = sub.start;
                        }
                    }
                    input.position(sub.end);
                }
                if (height < 0) {
                    throw invalid("Terrain chunk has no height data");
                }
                int base = geometry ? vertices.size() / 3 : 0;
                for (int row = 0, at = 0; row < 17; row++) {
                    for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++, at++) {
                        float vx = (float) (px - row * (TILE_SIZE / 256));
                        float vy =
                                (float)
                                        (py
                                                - col * (TILE_SIZE / 128)
                                                - (row % 2) * (TILE_SIZE / 256));
                        float vz = pz + input.getFloat(height + at * 4);
                        coordinate(vx);
                        coordinate(vy);
                        coordinate(vz);
                        if (geometry) {
                            vertices.add(vx);
                            vertices.add(vy);
                            vertices.add(vz);
                        }
                    }
                }
                AreaPoint entry = null;
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 8; col++) {
                        boolean hole =
                                (flags & 0x10000) != 0
                                        ? ((high >>> (row * 8 + col)) & 1) != 0
                                        : ((low >>> ((row / 2) * 4 + col / 2)) & 1) != 0;
                        if (hole) {
                            continue;
                        }
                        if (entry == null) {
                            entry =
                                    new AreaPoint(
                                            (float) (px - (2 * row + 1) * (TILE_SIZE / 256)),
                                            (float) (py - (col + .5) * (TILE_SIZE / 128)),
                                            pz + input.getFloat(height + (row * 17 + col + 9) * 4));
                        }
                        if (!geometry) {
                            continue;
                        }
                        int a = base + row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                        for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a}) {
                            indices.add(index);
                        }
                    }
                }
                if (captureGrid) {
                    float[] heights = new float[145];
                    for (int i = 0; i < heights.length; i++) {
                        heights[i] = input.getFloat(height + i * 4);
                    }
                    long holes = 0;
                    for (int row = 0; row < 8; row++) {
                        for (int col = 0; col < 8; col++) {
                            boolean hole =
                                    (flags & 0x10000) != 0
                                            ? ((high >>> (row * 8 + col)) & 1) != 0
                                            : ((low >>> ((row / 2) * 4 + col / 2)) & 1) != 0;
                            if (hole) {
                                holes |= 1L << (row * 8 + col);
                            }
                        }
                    }
                    byte[] colors =
                            color < 0
                                    ? new byte[0]
                                    : java.util.Arrays.copyOfRange(bytes, color, color + 580);
                    var state =
                            badColors
                                    ? TerrainGrid.ColorState.UNSUPPORTED
                                    : color < 0
                                            ? TerrainGrid.ColorState.ABSENT
                                            : TerrainGrid.ColorState.BGRA8;
                    grids.add(
                            new TerrainGrid.Chunk(
                                    chunks,
                                    x,
                                    y,
                                    Integer.toUnsignedLong(flags),
                                    px,
                                    py,
                                    pz,
                                    heights,
                                    holes,
                                    state,
                                    colors));
                }
                areas.add(
                        new AreaChunk(
                                x,
                                y,
                                Integer.toUnsignedLong(input.getInt(c.start + 52)),
                                px,
                                py,
                                pz,
                                entry));
                chunks++;
            }
            input.position(c.end);
        }
        if (!version || chunks == 0) {
            throw invalid("ADT has no readable terrain");
        }
        var metadata = new AreaMetadata(areas);
        if (!geometry) {
            return new ParsedAdt(null, metadata, captureGrid ? new TerrainGrid(grids) : null);
        }
        float[] positions = new float[vertices.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = vertices.get(i);
        }
        return new ParsedAdt(
                new Terrain(
                        positions, indices.stream().mapToInt(Integer::intValue).toArray(), chunks),
                metadata,
                captureGrid ? new TerrainGrid(grids) : null);
    }

    private static void coordinate(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw invalid("Invalid terrain coordinate");
        }
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
