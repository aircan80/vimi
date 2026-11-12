package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.AuthoredPacketDestination;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.world.LiquidSurface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Uses the actual native Map preparation and actual checked WDT/ADT/BLP source factory. */
public final class WorldStreamSourceBridgeTest {
    private WorldStreamSourceBridgeTest() {}
    static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    public static void main(String[] args) throws Exception {
        successfulMove(false, false, false);
        successfulMove(true, false, true);
        successfulMove(false, true, false);
        successfulMove(true, false, false);
        untakenResidentCloseLeavesMovedPacketOwned();
        rejectLateView(2);
        rejectLateView(3);
        fatalCleanupAfterMove();
        destinationStoppingCancelsActualSourceAdmission();
        cleanSourceRetirementMapsButProviderFailureStaysFatal();
        stickySourceCleanupHealthIsFatalBeforeFileMove();
        exteriorsAreOnByDefault();
        pickSourcesFollowCopiedPlacements();
        terrainProducerNeverWaitsForObjects();
        objectsStatusShowsDrawnAndOmittedCounts();
        portalSetsAreCopiedWhileTheyFit();
        tiesFollowTheirBuildingsCopy();
        waterCopyMergesFullFlatChunksOnly();
        waterCopyCapsWholeLayersAtThePacketBound();
        waterCopyKeepsAChunksDepthGradient();
        waterCopyCoarsensDepthBeforeCapping();
        waterCopyFoldsOnTheNeSwDiagonal();
        waterCopyChecksEveryCellCorner();
        waterCopyKeepsExactDepthAtExactlyTheBound();
        swimSurfaceKeepsTheHighestLayersAndNeverThrows();
    }

    /** The authored water with each vertex raised to its layer's index, or NaN in one layer. */
    private static WorldStreamSourceBridge.WaterCopy.Cells raised(AuthoredWater water, int broken) {
        return new WorldStreamSourceBridge.WaterCopy.Cells() {
            @Override public int vertices() { return water.vertices(); }
            @Override public int indices() { return water.indices(); }
            @Override public int layers() { return water.layers(); }
            @Override public int width(int layer) { return water.width(layer); }
            @Override public int height(int layer) { return water.height(layer); }
            @Override public long mask(int layer) { return water.mask(layer); }
            @Override public boolean planar(int layer) { return water.planar(layer); }
            @Override public int colour(int layer) { return water.colour(layer); }
            @Override public boolean ocean(int layer) { return water.ocean(layer); }
            @Override public float north(int vertex) { return water.north(vertex); }
            @Override public float west(int vertex) { return water.west(vertex); }
            @Override public float up(int vertex) {
                int layer = water.locate(vertex)[0];
                return layer == broken ? Float.NaN : layer;
            }
            @Override public int depth(int vertex) { return water.depth(vertex); }
        };
    }

    /**
     * The swimming surface is built apart from the drawn copy: past its cell bound it keeps the
     * highest layers and says so, a layer with a bad coordinate is skipped, and a drawn copy that
     * fails keeps the surface instead of taking it down too.
     */
    private static void swimSurfaceKeepsTheHighestLayersAndNeverThrows() {
        int layers = LiquidSurface.MAX_CELLS / 64 + 6;
        var shape = new int[layers][];
        var masks = new long[layers];
        var planar = new boolean[layers];
        for (int l = 0; l < layers; l++) {
            shape[l] = new int[] {8, 8};
            masks[l] = -1L;
        }
        var cells = raised(new AuthoredWater(shape, masks, planar), -1);
        var swim = WorldStreamSourceBridge.WaterCopy.swim(cells, Cancellation.NONE);
        check(swim.tile().cells() == LiquidSurface.MAX_CELLS
                        && swim.note().equals("swimming surface keeps the highest 1024 of 1030 layers;"
                                + " 6 past 65536 cells"),
                "Too many layers keep the highest: " + swim.tile().cells() + " " + swim.note());
        check(LiquidSurface.of(java.util.List.of(swim.tile())).at(.5, .5).height() == layers - 1,
                "The highest layer answers");
        var copy = WorldStreamSourceBridge.WaterCopy.of(cells, Cancellation.NONE);
        check(copy.capped() && copy.water() != null && copy.surface().cells() == LiquidSurface.MAX_CELLS
                        && copy.surfaceNote().equals(swim.note()),
                "The drawn copy and the surface are both capped, neither throws");
        var broken = WorldStreamSourceBridge.WaterCopy.swim(
                raised(new AuthoredWater(new int[][] {{8, 8}, {8, 8}}, new long[] {-1L, -1L},
                        new boolean[] {true, true}), 0), Cancellation.NONE);
        check(broken.tile().cells() == 64
                        && broken.note().equals("swimming surface keeps the highest 1 of 2 layers;"
                                + " 1 with unusable coordinates"),
                "A layer with a NaN corner is skipped: " + broken.note());
        // A depth byte the packet cannot carry fails the drawn copy; swimming stays.
        var deep = new AuthoredWater(new int[][] {{8, 8}}, new long[] {-1L}, new boolean[] {true},
                (l, r, c) -> 300);
        var kept = WorldStreamSourceBridge.WaterCopy.of(deep, Cancellation.NONE);
        check(kept.water() == null && kept.surface().cells() == 64
                        && kept.surfaceNote().startsWith("drawn water failed (Water depth byte)"),
                "A failed drawn copy keeps the swimming surface: " + kept.surfaceNote());
    }

    /**
     * A merged quad draws as two triangles split on the NE--SW diagonal, so a chunk whose depth
     * is a ridge along that diagonal (0 at NW and SE, 80 at NE and SW) is exactly one quad,
     * while a ridge along NW--SE, which the quad cannot draw, keeps cells.
     */
    private static void waterCopyFoldsOnTheNeSwDiagonal() {
        int[][] chunk = {{8, 8}};
        long[] full = {-1L};
        boolean[] flat = {true};
        var ridge = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(chunk, full, flat,
                        (l, r, c) -> 10 * Math.min(r + c, 16 - r - c)),
                Cancellation.NONE);
        check(ridge.water().cells() == 1, "A NE-SW ridge is one quad: " + ridge.water().cells());
        int[] depths = {0, 80, 80, 0};
        for (int c = 0; c < 4; c++) {
            check((ridge.water().depths()[c] & 255) == depths[c], "Ridge corner " + c);
        }
        int across = copyCells(new AuthoredWater(chunk, full, flat,
                (l, r, c) -> 80 - 10 * Math.min(r + c, 16 - r - c)));
        check(across == 1, "Its mirror (80 at NW and SE, 0 at NE and SW) is still one quad");
        int fold = copyCells(new AuthoredWater(chunk, full, flat, (l, r, c) -> 10 * Math.abs(r - c)));
        check(fold > 1, "A ridge along NW-SE keeps cells: " + fold);
    }

    /**
     * Every corner of every cell is checked, not only its NW corner: a depth that differs only
     * on the chunk's south edge (grid row 8, which is no cell's NW corner) keeps cells.
     */
    private static void waterCopyChecksEveryCellCorner() {
        int[][] chunk = {{8, 8}};
        long[] full = {-1L};
        boolean[] flat = {true};
        int south = copyCells(new AuthoredWater(chunk, full, flat,
                (l, r, c) -> r == 8 && c == 4 ? 150 : 100));
        check(south > 1, "A south-edge depth keeps cells: " + south);
        int east = copyCells(new AuthoredWater(chunk, full, flat,
                (l, r, c) -> c == 8 && r == 4 ? 150 : 100));
        check(east > 1, "An east-edge depth keeps cells: " + east);
    }

    /** 128 noisy flat chunks are exactly 8192 quads: all fit with exact depth, nothing capped. */
    private static void waterCopyKeepsExactDepthAtExactlyTheBound() {
        int layers = 128;
        var shape = new int[layers][];
        var masks = new long[layers];
        var planar = new boolean[layers];
        for (int l = 0; l < layers; l++) {
            shape[l] = new int[] {8, 8};
            masks[l] = -1L;
            planar[l] = true;
        }
        var copy = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(shape, masks, planar, (l, r, c) -> 100 + 2 * ((r + c) % 2)),
                Cancellation.NONE);
        check(!copy.capped() && copy.finest() == 1 && copy.layers() == layers
                        && copy.water().cells() == WorldStreamTileWriter.Water.MAX_CELLS,
                "Exactly the bound keeps every cell: " + copy.finest() + " "
                        + copy.water().cells());
    }

    /**
     * Authored source water: each layer is (width, height, mask, planar); every cell emits NW, NE,
     * SW, SE with north = row (+1 for the south corners), west = column (+1 for the east ones)
     * and up = layer + corner / 10 when the layer is not planar. Depth is a function of the layer
     * and the vertex's grid row and column; by default 10 + row + column, an even slope.
     */
    private record AuthoredWater(int[][] shape, long[] masks, boolean[] planar, DepthField field)
            implements WorldStreamSourceBridge.WaterCopy.Cells {
        interface DepthField {
            int depth(int layer, int row, int column);
        }

        AuthoredWater(int[][] shape, long[] masks, boolean[] planar) {
            this(shape, masks, planar, (layer, row, column) -> 10 + row + column);
        }

        int cells(int layer) { return Long.bitCount(masks[layer]); }
        int total() {
            int n = 0;
            for (int l = 0; l < masks.length; l++) n += cells(l);
            return n;
        }
        /** Layer, cell ordinal (Y-major among set bits), corner of a vertex. */
        int[] locate(int vertex) {
            int cell = vertex / 4;
            for (int l = 0; l < masks.length; l++) {
                if (cell < cells(l)) {
                    int seen = -1;
                    for (int bit = 0; bit < 64; bit++) {
                        if ((masks[l] & (1L << bit)) != 0 && ++seen == cell)
                            return new int[] {l, bit, vertex % 4};
                    }
                }
                cell -= cells(l);
            }
            throw new IndexOutOfBoundsException(vertex);
        }
        @Override public int vertices() { return total() * 4; }
        @Override public int indices() { return total() * 6; }
        @Override public int layers() { return masks.length; }
        @Override public int width(int layer) { return shape[layer][0]; }
        @Override public int height(int layer) { return shape[layer][1]; }
        @Override public long mask(int layer) { return masks[layer]; }
        @Override public boolean planar(int layer) { return planar[layer]; }
        /** Layer 2 is a magma layer with its own flat colour; the rest are water. */
        @Override public int colour(int layer) { return layer == 2 ? 0xD06800 : -1; }
        /** Layer 0 is ocean; the others are river or lake water. */
        @Override public boolean ocean(int layer) { return layer == 0; }
        /** Layer 2 is LiquidType 3, magma; the rest have no LiquidType. */
        @Override public int liquidType(int layer) { return layer == 2 ? 3 : 0; }
        @Override public float north(int vertex) {
            var at = locate(vertex);
            return at[1] / width(at[0]) + at[2] / 2;
        }
        @Override public float west(int vertex) {
            var at = locate(vertex);
            return at[1] % width(at[0]) + at[2] % 2;
        }
        @Override public float up(int vertex) {
            var at = locate(vertex);
            return planar[at[0]] ? 5 : 5 + at[2] / 10f;
        }
        @Override public int depth(int vertex) {
            return field.depth(locate(vertex)[0], (int) north(vertex), (int) west(vertex));
        }
    }

    /**
     * Open sea is whole flat chunks: one quad each draws the same plane as 64 cells. A sloped
     * full chunk or a partial one keeps every cell.
     */
    private static void waterCopyMergesFullFlatChunksOnly() {
        var source = new AuthoredWater(new int[][] {{8, 8}, {8, 8}, {4, 2}},
                new long[] {-1L, -1L, 0b1011}, new boolean[] {true, false, true});
        var copy = WorldStreamSourceBridge.WaterCopy.of(source, Cancellation.NONE);
        var water = copy.water();
        check(water.cells() == 1 + 64 + 3 && copy.sourceCells() == 64 + 64 + 3
                        && copy.layers() == 3 && !copy.capped(),
                "One quad for the flat full chunk, every cell otherwise: " + water.cells());
        var p = water.positions();
        // The merged quad spans the chunk: NW (0,0), NE (0,8), SW (8,0), SE (8,8), and keeps the
        // corner depths of its even slope.
        float[] corners = {0, 0, 0, 8, 8, 0, 8, 8};
        int[] depths = {10, 18, 18, 26};
        for (int c = 0; c < 4; c++) {
            check(p[c * 3] == corners[c * 2] && p[c * 3 + 1] == corners[c * 2 + 1]
                            && p[c * 3 + 2] == 5 && water.depths()[c] == depths[c],
                    "Merged corner " + c + " comes from the chunk's corner cell");
        }
        // The sloped chunk's first cell keeps its own corner heights.
        check(p[4 * 3 + 2] == 5f && p[5 * 3 + 2] == 5.1f && p[7 * 3 + 2] == 5.3f,
                "Sloped cells keep their heights");
        // Water layers take the water colour; the magma layer's three quads its own (#171).
        var colours = water.colours();
        for (int v = 0; v < water.cells() * 4; v++) {
            int rgb = (colours[v * 3] & 255) << 16 | (colours[v * 3 + 1] & 255) << 8
                    | colours[v * 3 + 2] & 255;
            int expected = v >= (1 + 64) * 4 ? 0xD06800 : WorldStreamTileWriter.Water.WATER_RGB;
            check(rgb == expected, "Vertex " + v + " colour " + Integer.toHexString(rgb));
            // The merged ocean quad is OCEAN, the sloped chunk RIVER, the magma layer FLAT.
            byte kind = water.kinds()[v];
            byte expectedKind = v < 4 ? WorldStreamTileWriter.Water.OCEAN
                    : v >= (1 + 64) * 4 ? WorldStreamTileWriter.Water.FLAT
                    : WorldStreamTileWriter.Water.RIVER;
            check(kind == expectedKind, "Vertex " + v + " kind " + kind);
        }
        // Swimming keeps every source cell, merged or not, and what liquid each layer is.
        check(copy.surface().cells() == 64 + 64 + 3,
                "Every source cell reaches the liquid surface: " + copy.surface().cells());
        check(WorldStreamSourceBridge.WaterCopy.kind(source, 0) == LiquidSurface.Kind.OCEAN
                        && WorldStreamSourceBridge.WaterCopy.kind(source, 1) == LiquidSurface.Kind.WATER
                        && WorldStreamSourceBridge.WaterCopy.kind(source, 2) == LiquidSurface.Kind.MAGMA,
                "Ocean, water and magma layers keep their kind");
        var indices = water.indices();
        for (int q = 0; q < water.cells(); q++) {
            int b = q * 4;
            check(indices[q * 6] == b && indices[q * 6 + 1] == b + 2 && indices[q * 6 + 2] == b + 1
                            && indices[q * 6 + 3] == b + 1 && indices[q * 6 + 4] == b + 2
                            && indices[q * 6 + 5] == b + 3,
                    "Every quad keeps the NE-SW diagonal");
        }
    }

    /** Layers are copied whole in source order; the first one past 8192 quads stops the copy. */
    private static void waterCopyCapsWholeLayersAtThePacketBound() {
        int layers = 129;
        var shape = new int[layers][];
        var masks = new long[layers];
        var planar = new boolean[layers];
        for (int l = 0; l < layers; l++) {
            shape[l] = new int[] {8, 8};
            masks[l] = -1L;
        }
        var copy = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(shape, masks, planar), Cancellation.NONE);
        check(copy.capped() && copy.layers() == 128 && copy.water().cells() == 8192,
                "128 sloped full chunks fill the bound and the 129th is left out: "
                        + copy.layers() + " " + copy.water().cells());
        check(copy.surface().cells() == 129 * 64,
                "A layer left out of the packet still reaches the liquid surface");
        planar[0] = planar[1] = true;
        var merged = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(shape, masks, planar), Cancellation.NONE);
        check(!merged.capped() && merged.layers() == 129 && merged.water().cells() == 2 + 127 * 64,
                "Merged flat chunks make room for every layer");
    }

    /**
     * Depth shades water (#171), so a flat full chunk's cells merge only where one quad draws
     * every source depth within a byte: equal depths, an even slope, or one-byte noise. A coastal
     * chunk with a shallow strip inside it keeps the cells around the strip and their depths.
     */
    private static void waterCopyKeepsAChunksDepthGradient() {
        int[][] chunk = {{8, 8}};
        long[] full = {-1L};
        boolean[] flat = {true};
        check(copyCells(new AuthoredWater(chunk, full, flat, (l, r, c) -> 200)) == 1,
                "Equal depths merge");
        check(copyCells(new AuthoredWater(chunk, full, flat, (l, r, c) -> 255 - 30 * c)) == 1,
                "An even shallow-to-deep slope merges: the quad draws the same depths");
        check(copyCells(new AuthoredWater(chunk, full, flat, (l, r, c) -> 100 + (r + c) % 2)) == 1,
                "One-byte noise merges");
        check(copyCells(new AuthoredWater(chunk, full, flat, (l, r, c) -> 100 + 2 * ((r + c) % 2)))
                        == 64,
                "Two-byte noise keeps every cell");
        // Deep everywhere except a shallow strip on grid column 4. Every 4 x 4 block touches it,
        // so they split; the 2 x 2 blocks on cell columns 0-1 and 6-7 are flat and merge (4 + 4),
        // and cell columns 2..5 beside the strip keep their cells (32).
        var coast = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(chunk, full, flat, (l, r, c) -> c == 4 ? 20 : 255),
                Cancellation.NONE);
        var water = coast.water();
        check(!coast.capped() && coast.finest() == 1 && water.cells() == 8 + 32,
                "The coastal chunk keeps the cells around its strip: " + water.cells());
        // Every copied vertex keeps its own source depth: 20 on column 4, 255 elsewhere.
        var p = water.positions();
        for (int v = 0; v < water.cells() * 4; v++) {
            int expected = p[v * 3 + 1] == 4 ? 20 : 255;
            check((water.depths()[v] & 255) == expected,
                    "Vertex " + v + " at column " + p[v * 3 + 1] + " depth "
                            + (water.depths()[v] & 255));
        }
        check(covered(water) == 64, "The quads cover the whole chunk once");
        // A wavy (planar = false) chunk never merges, whatever its depths.
        check(copyCells(new AuthoredWater(chunk, full, new boolean[] {false}, (l, r, c) -> 200))
                        == 64,
                "A sloped surface keeps every cell");
    }

    /**
     * A tile whose depths would pass 8192 quads merges blocks regardless of depth at the finest
     * size that fits, rather than leaving layers out; only when whole chunks do not fit are
     * layers capped.
     */
    private static void waterCopyCoarsensDepthBeforeCapping() {
        int layers = 256;
        var shape = new int[layers][];
        var masks = new long[layers];
        var planar = new boolean[layers];
        for (int l = 0; l < layers; l++) {
            shape[l] = new int[] {8, 8};
            masks[l] = -1L;
            planar[l] = true;
        }
        // Two-byte noise everywhere: 64 cells per chunk would be 16,384 quads.
        AuthoredWater.DepthField noise = (l, r, c) -> 100 + 2 * ((r + c) % 2);
        var copy = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(shape, masks, planar, noise), Cancellation.NONE);
        check(!copy.capped() && copy.layers() == layers && copy.finest() == 2
                        && copy.water().cells() == layers * 16,
                "2 x 2 blocks fit every layer: " + copy.finest() + " " + copy.water().cells());
        // With 200 layers, half of them a smooth slope that stays whole, the noisy rest keep
        // every cell: 100 + 6,400 quads fit.
        int some = 200;
        AuthoredWater.DepthField half = (l, r, c) -> l % 2 == 0 ? 10 + r + c : 100 + 2 * ((r + c) % 2);
        var mixed = WorldStreamSourceBridge.WaterCopy.of(
                new AuthoredWater(java.util.Arrays.copyOf(shape, some),
                        java.util.Arrays.copyOf(masks, some),
                        java.util.Arrays.copyOf(planar, some), half),
                Cancellation.NONE);
        check(!mixed.capped() && mixed.finest() == 1 && mixed.water().cells() == 100 + 100 * 64,
                "Exact depth while it fits: " + mixed.water().cells());
    }

    /** Cells covered by the copy's quads, from their corner positions. */
    private static int covered(WorldStreamTileWriter.Water water) {
        var p = water.positions();
        int area = 0;
        for (int q = 0; q < water.cells(); q++) {
            float north = p[q * 12], west = p[q * 12 + 1];
            float south = p[q * 12 + 9], east = p[q * 12 + 10];
            area += (int) ((south - north) * (east - west));
        }
        return area;
    }

    private static int copyCells(AuthoredWater source) {
        return WorldStreamSourceBridge.WaterCopy.of(source, Cancellation.NONE).water().cells();
    }

    /**
     * Issue #383 step 3a: a model's portal set is copied with it while the tile's portal tables
     * have room, and its meshes then name their groups; past that the model is still copied, without
     * its set.
     */
    private static void portalSetsAreCopiedWhileTheyFit() {
        var set = new com.vibeminer.rendering.world.WmoPortalSet(
                java.util.List.of(new com.vibeminer.rendering.world.WmoPortalTraversal.Group(
                        0x2000, 0, 0, 0, 1, 1, 1, 0, 0)),
                java.util.List.of(), java.util.List.of());
        var builder = com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Exterior.builder();
        check(!WorldStreamSourceBridge.startModel(builder, null), "A model without a set carries none");
        for (int m = 0; m < com.vibeminer.rendering.world.WmoPortalSet.MAX_TILE_SETS; m++) {
            check(WorldStreamSourceBridge.startModel(builder, set), "A set is carried while it fits");
        }
        check(!WorldStreamSourceBridge.startModel(builder, set),
                "Past the tile's portal tables the model is copied without its set");
        check(!builder.portalsFit(set), "The tile's portal tables are full");
    }

    /**
     * A set doodad tied to an interior group names its building's index in the file; a tie to a
     * building the copy capped, or one placed out of order, is dropped and the doodad draws untied.
     */
    private static void tiesFollowTheirBuildingsCopy() {
        var room = new com.vibeminer.rendering.world.WmoPortalSet(
                java.util.List.of(new com.vibeminer.rendering.world.WmoPortalTraversal.Group(
                        0x2000, 0, 0, 0, 1, 1, 1, 0, 0)),
                java.util.List.of(), java.util.List.of());
        var tied = new WorldStreamSourceBridge.Placed(false, 0, 300, 5, 6, 1,
                new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, placed(false, 0, 13, 300).source(), 0, 0);
        var orphan = new WorldStreamSourceBridge.Placed(false, 0, 400, 5, 6, 1,
                new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, placed(false, 0, 14, 400).source(), 1, 0);
        var ahead = new WorldStreamSourceBridge.Placed(false, 0, 500, 5, 6, 1,
                new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, placed(false, 0, 15, 500).source(), 5, 0);
        // Building 11 of model 0 (with the room's set), building 12 over the draw budget.
        var set = new WorldStreamSourceBridge.ObjectSet((builder, token) -> {
            builder.image(1, 1, java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3, (byte) 255}));
            for (int m = 0; m < 2; m++) {
                boolean portals = WorldStreamSourceBridge.startModel(builder, m == 0 ? room : null);
                builder.mesh(java.nio.FloatBuffer.wrap(new float[] {
                        0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1}),
                        java.nio.IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0}, new int[1],
                        java.nio.IntBuffer.allocate(0), portals ? 0 : -1);
            }
        }, new int[] {1, com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Exterior.MAX_DRAWS},
                java.util.List.of(placed(true, 0, 11, 100), placed(true, 1, 12, 200), tied, orphan, ahead), 2);
        var copy = WorldStreamSourceBridge.finish(set, false, "b", WorldStreamSourceBridge.ExteriorSummary.NONE,
                TILE, new Cancellation(), null);
        check(java.util.Arrays.equals(tieTable(copy, "placementParents"), new int[] {-1, 0, -1, -1}),
                "The tie names building 11's copy, and the others are dropped: "
                        + java.util.Arrays.toString(tieTable(copy, "placementParents")));
        check(java.util.Arrays.equals(tieTable(copy, "placementGroups"), new int[] {-1, 0, -1, -1}),
                "Only the kept tie names its group");
    }

    /** One per-placement tie table of a copy's objects file data. */
    private static int[] tieTable(WorldStreamSourceBridge.ExteriorCopy copy, String name) {
        try {
            var data = copy.exterior().getClass().getDeclaredField("data");
            data.setAccessible(true);
            var value = data.get(copy.exterior());
            var table = value.getClass().getDeclaredField(name);
            table.setAccessible(true);
            return (int[]) table.get(value);
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }

    private static void objectsStatusShowsDrawnAndOmittedCounts() {
        var none = WorldStreamSourceBridge.ExteriorSummary.NONE;
        check(WorldStreamSourceController.objectsDetail(
                        new WorldStreamSourceBridge.ExteriorSummary(3, 0, 0, 0, 0, 0, 0), 3)
                        .equals("; 3 BASIC exterior buildings on 3"),
                "Buildings only, nothing omitted");
        var a = new WorldStreamSourceBridge.ExteriorSummary(6, 1, 9, 4, 2, 3, 1);
        var b = new WorldStreamSourceBridge.ExteriorSummary(2, 0, 1, 0, 0, 0, 0);
        var sum = none.plus(a).plus(b);
        check(sum.equals(new WorldStreamSourceBridge.ExteriorSummary(8, 1, 10, 4, 2, 3, 1)),
                "Per-tile summaries add up: " + sum);
        check(WorldStreamSourceController.objectsDetail(sum, 2).equals(
                        "; 8 BASIC exterior buildings and 10 doodads on 2"
                                + " (not drawn: 1 building, 4 doodads (2 capped), 3 surfaces (1 unreadable))"),
                "Omissions are listed: " + WorldStreamSourceController.objectsDetail(sum, 2));
        var capped = b.capped(1, 1);
        check(capped.buildings() == 1 && capped.buildingsOmitted() == 1
                        && capped.doodads() == 0 && capped.doodadsOmitted() == 1
                        && capped.doodadsCapped() == 1,
                "The bridge's own draw-budget cap moves records to omitted");
        var legacy = new WorldStreamSourceBridge.ExteriorCopy(null, 5, false, "");
        check(legacy.summary().buildings() == 5, "A placements-only copy counts buildings");
    }

    private static void exteriorsAreOnByDefault() {
        var standard = com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD;
        var high = com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH;
        check(WorldStreamGpuOwner.imageDetail(null) == standard, "Object textures default to Standard");
        check(WorldStreamGpuOwner.imageDetail("HIGH") == high, "high (any case) selects High");
        check(WorldStreamGpuOwner.imageDetail(" high ") == high, "Surrounding whitespace is ignored");
        check(WorldStreamGpuOwner.imageDetail("ultra") == standard, "Anything else stays Standard");
        // Budgets are mip-chain texels (#265): the chain of a twice-as-large texture is double
        // within one texel, the 1 x 1 level.
        check(Math.abs(high.totalPixels() - 2 * standard.totalPixels()) <= 3, "High doubles every pool");
        // #280: dungeon (InstanceType 1) and raid (2) maps draw instance interiors; #383: every
        // other map draws interiors read after every exterior.
        var instance = com.vibeminer.concepts.world.WorldExteriorObjects.Interiors.INSTANCE;
        var openWorld = com.vibeminer.concepts.world.WorldExteriorObjects.Interiors.OPEN_WORLD;
        check(WorldView.interiorMode(new com.vibeminer.concepts.world.WorldService.MapInfo(
                36, "Dungeon", "D", 1, 1L)) == instance, "Dungeon maps draw instance interiors");
        check(WorldView.interiorMode(new com.vibeminer.concepts.world.WorldService.MapInfo(
                249, "Raid", "R", 2, 1L)) == instance, "Raid maps draw instance interiors");
        check(WorldView.interiorMode(new com.vibeminer.concepts.world.WorldService.MapInfo(
                0, "Continent", "C", 0, 1L)) == openWorld, "Open-world maps draw interiors after exteriors");
        check(WorldView.interiorMode(new com.vibeminer.concepts.world.WorldService.MapInfo(
                30, "Battleground", "B", 3, 1L)) == openWorld, "Battlegrounds draw interiors after exteriors");
        check(WorldView.interiorMode(new com.vibeminer.concepts.world.WorldService.MapInfo(
                7, "Unknown", "U", null, 1L)) == openWorld, "An unreadable instance type is open-world");
        check(WorldStreamGpuOwner.exteriorsEnabled(null), "Exteriors are on without the property");
        check(WorldStreamGpuOwner.exteriorsEnabled("true"), "true keeps exteriors on");
        check(WorldStreamGpuOwner.exteriorsEnabled(""), "An empty value keeps exteriors on");
        check(!WorldStreamGpuOwner.exteriorsEnabled("false"), "false turns exteriors off");
        check(GpuPreviewService.forwardedProperties(name -> null).isEmpty(),
                "Unset helper switches are not forwarded");
        check(GpuPreviewService.forwardedProperties(
                        name -> name.equals("vimi.world.terrainLighting") ? "false" : null)
                .equals(java.util.List.of("-Dvimi.world.terrainLighting=false")),
                "The terrain lighting switch reaches the World helper");
        check(!WorldStreamGpuOwner.vertexColoursEnabled(null), "WMO vertex colours are off without the property");
        check(!WorldStreamGpuOwner.vertexColoursEnabled("yes"), "Only true turns WMO vertex colours on");
        check(WorldStreamGpuOwner.vertexColoursEnabled("TRUE"), "true turns WMO vertex colours on");
        check(GpuPreviewService.forwardedProperties(false).isEmpty(), "Off forwards nothing to the helper");
        check(GpuPreviewService.forwardedProperties(true).equals(java.util.List.of("-Dvimi.world.wmoVertexColours=true")),
                "On is forwarded to the helper");
        check(!WorldStreamGpuOwner.exteriorsEnabled("FALSE"), "The opt-out ignores letter case");
        check(WorldStreamGpuOwner.exteriorsEnabled("no"), "Only false opts out");
        check(!WorldStreamGpuOwner.creaturesEnabled(null), "Creature models are off without the property");
        check(!WorldStreamGpuOwner.creaturesEnabled("yes"), "Only true opts in to creature models");
        check(WorldStreamGpuOwner.creaturesEnabled("TRUE"), "true (any case) opts in to creature models");
        if (System.getProperty("vimi.world.creatures") == null) {
            check(!WorldStreamGpuOwner.CREATURES, "The default GUI session draws no creature models");
        }
        var highSpec = WorldStreamGpuOwner.spec("0".repeat(64),
                com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH);
        check(highSpec.objectTextures() == com.vibeminer.rendering.bgfx.WorldStreamTransport.ObjectTextures.HIGH,
                "HIGH object textures select the HIGH objects budget");
        check(WorldStreamGpuOwner.spec("0".repeat(64),
                        com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD).objectTextures()
                        == com.vibeminer.rendering.bgfx.WorldStreamTransport.ObjectTextures.STANDARD,
                "STANDARD object textures keep the STANDARD budget");
        var spec = WorldStreamGpuOwner.spec("0".repeat(64));
        check(spec.exteriorObjects() == WorldStreamGpuOwner.EXTERIORS,
                "The GUI session profile follows the property");
        if (System.getProperty("vimi.world.exteriors") == null) {
            check(WorldStreamGpuOwner.EXTERIORS && spec.exteriorObjects(),
                    "The default GUI session draws exterior buildings");
        }
    }

    /**
     * The terrain producer never prepares or names exterior objects, even under the exterior
     * profile: objects are delivered late by the controller, so a tile's terrain packet never waits
     * for them. The standalone preparer degrades an empty tile to "no objects" and propagates only
     * cancellation.
     */
    @SuppressWarnings("try")
    private static void terrainProducerNeverWaitsForObjects() throws Exception {
        var fixture = new Fixture();
        var root = Files.createTempDirectory("bridge-exterior-");
        var path = root.resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(
                    fixture.source, null, scope, TILE, () -> true)) {
                var destination = AuthoredPacketDestination.controlled(
                        path, scope.scopeSha256(), true).destination();
                try (var packet = producer.prepare(destination, () -> false);
                        var resident = producer.takePrepared()) {
                    check(Files.exists(path), "Exterior-profile terrain packet produced at once");
                    try (var names = Files.list(root)) {
                        check(names.noneMatch(name -> name.toString().endsWith(".wso")),
                                "The terrain packet carries no objects file");
                    }
                    check(resident.exteriorSummary().buildings() == 0 && !resident.exteriorFailed(),
                            "No buildings until late objects attach");
                    var summary = new WorldStreamSourceBridge.ExteriorSummary(6, 1, 9, 4, 2, 3, 1);
                    resident.exteriorAttached(summary);
                    check(resident.exteriorSummary().equals(summary), "Late attach is recorded");
                    resident.exteriorFailedLate();
                    check(resident.exteriorFailed(), "Late failure is recorded");
                }
                check(!Files.exists(path), "Transport retirement deletes the packet");
            }
            var empty = WorldStreamSourceBridge.prepareExterior(
                    fixture.source, scope, TILE, new Cancellation());
            check(empty.exterior() == null && !empty.failed() && empty.placements() == 0,
                    "A tile without placed objects prepares to no objects, not a failure");
            // Creatures ride in the same late objects: a tile without buildings still carries them.
            var creatures = new WorldTileCreatures(token -> WorldTileCreaturesTest.index(java.util.List.of(
                    WorldSpawnOverlayTest.spawn(1, 1, (32 - TILE.x() - .5) * WorldFormats.TILE_SIZE,
                            (32 - TILE.y() - .5) * WorldFormats.TILE_SIZE, 5.0))), () -> "r1",
                    (npc, token) -> WorldTileCreaturesTest.qualified(7, null),
                    (display, token) -> WorldTileCreaturesTest.preview(display, 0L, 0L,
                            WorldTileCreaturesTest.appearance(true)));
            var withCreatures = WorldStreamSourceBridge.prepareExterior(fixture.source, scope, TILE,
                    new Cancellation(), (builder, tile, token) -> creatures.append(builder, 0, tile, token));
            check(withCreatures.exterior() != null && withCreatures.exterior().placements() == 1
                            && withCreatures.placements() == 0 && !withCreatures.failed()
                            && withCreatures.creatures().drawn() == 1,
                    "Creature placements are delivered without buildings and are not counted as buildings");
            var picks = withCreatures.picks();
            check(picks != null && picks.tileX() == TILE.x() && picks.tileY() == TILE.y()
                            && picks.geometry().placements() == 1
                            && picks.sources().getFirst() instanceof WorldObjectPicks.Creature creature
                            && creature.spawn().npcId() == 1,
                    "The delivered copy carries pick geometry and the drawn creature's spawn record");
            check(empty.picks() == null, "No objects, nothing to pick");
            var broken = WorldStreamSourceBridge.prepareExterior(fixture.source, scope, TILE,
                    new Cancellation(), (builder, tile, token) -> {
                        builder.model();
                        throw new IllegalStateException("authored creature failure");
                    });
            check(broken.exterior() == null && !broken.failed()
                            && broken.creatures().note().contains("IllegalStateException"),
                    "A creature failure is isolated to creatures and drops its partial model");
            var canceled = new Cancellation();
            canceled.cancel();
            try {
                WorldStreamSourceBridge.prepareExterior(fixture.source, scope, TILE, canceled);
                throw new AssertionError("Canceled object preparation must not complete");
            } catch (CancellationException expected) {
                // Only cancellation propagates.
            }
            check(fixture.pool.stats().leaseClaims() == 0, "No material lease leaked");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(root);
        }
    }

    /** A synthetic building, doodad or broken placement of model {@code model} at x. */
    private static WorldStreamSourceBridge.Placed placed(boolean building, int model, long uniqueId, double x) {
        var kind = building ? com.vibeminer.concepts.world.WorldExteriorObjects.Kind.BUILDING
                : com.vibeminer.concepts.world.WorldExteriorObjects.Kind.DOODAD;
        var source = new WorldObjectPicks.Exterior(kind, (int) uniqueId, uniqueId, 1000 + uniqueId, 0, 0, 0, 1,
                java.util.List.of(2000L), java.util.List.of(0), 0, new WorldObjectPicks.Key(model, x, 5, 6, 1));
        return new WorldStreamSourceBridge.Placed(building, model, x, 5, 6, 1,
                new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, source);
    }

    /** Two one-range models; model 1 declares the whole draw budget, so its placement is capped. */
    private static WorldStreamSourceBridge.ObjectSet objects(WorldStreamSourceBridge.Placed... placements) {
        return new WorldStreamSourceBridge.ObjectSet((builder, token) -> {
            builder.image(1, 1, java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3, (byte) 255}));
            for (int m = 0; m < 2; m++) {
                builder.model();
                builder.mesh(java.nio.FloatBuffer.wrap(new float[] {
                        0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1}),
                        java.nio.IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0});
            }
        }, new int[] {1, com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Exterior.MAX_DRAWS},
                java.util.List.of(placements), 2);
    }

    /** Recorded unique IDs (or NPC IDs for creatures, negated) of a copy's pick sources. */
    private static java.util.List<Long> ids(WorldStreamSourceBridge.ExteriorCopy copy) {
        var picks = copy.picks();
        check(picks != null && picks.geometry().placements() == copy.exterior().placements(),
                "One pick placement per copied placement");
        return picks.sources().stream().map(source -> switch (source) {
            case WorldObjectPicks.Exterior e -> e.uniqueId();
            case WorldObjectPicks.Creature c -> -c.spawn().npcId();
            case WorldObjectPicks.Unknown u -> 0L;
        }).toList();
    }

    /**
     * Pick sources stay aligned with the copied placements in every bridge path: buildings with
     * creatures, a placement dropped by the bridge's draw cap, the rebuild after a creature failure
     * and buildings failing partway. Alignment is checked by the uploaded keys, not only counts.
     */
    private static void pickSourcesFollowCopiedPlacements() {
        var creatures = new WorldTileCreatures(token -> WorldTileCreaturesTest.index(java.util.List.of(
                WorldSpawnOverlayTest.spawn(1, 1, (32 - TILE.x() - .5) * WorldFormats.TILE_SIZE,
                        (32 - TILE.y() - .5) * WorldFormats.TILE_SIZE, 5.0))), () -> "r1",
                (npc, token) -> WorldTileCreaturesTest.qualified(7, null),
                (display, token) -> WorldTileCreaturesTest.preview(display, 0L, 0L,
                        WorldTileCreaturesTest.appearance(true)));
        WorldStreamSourceBridge.CreatureAppender appender = (builder, tile, token) ->
                creatures.append(builder, 0, tile, token);
        // Building 11, building 12 over the draw budget (dropped), doodad 13.
        var set = objects(placed(true, 0, 11, 100), placed(true, 1, 12, 200), placed(false, 0, 13, 300));
        var none = WorldStreamSourceBridge.ExteriorSummary.NONE;

        var alone = WorldStreamSourceBridge.finish(set, false, "b", none, TILE, new Cancellation(), null);
        check(ids(alone).equals(java.util.List.of(11L, 13L)) && alone.summary().buildingsOmitted() == 1,
                "The capped building leaves no source behind: " + ids(alone));

        var mixed = WorldStreamSourceBridge.finish(set, false, "b", none, TILE, new Cancellation(), appender);
        check(ids(mixed).equals(java.util.List.of(11L, 13L, -1L)),
                "Buildings and doodads, then creatures, in file order: " + ids(mixed));

        var rebuilt = WorldStreamSourceBridge.finish(set, false, "b", none, TILE, new Cancellation(),
                (builder, tile, token) -> {
                    creatures.append(builder, 0, tile, token);
                    throw new IllegalStateException("authored creature failure after appending");
                });
        check(ids(rebuilt).equals(java.util.List.of(11L, 13L)) && rebuilt.creatures().placed().isEmpty(),
                "The buildings-only rebuild records its own sources once: " + ids(rebuilt));

        // Placement 2 names a model without a draw cost: the copy fails after recording building 11.
        var broken = objects(placed(true, 0, 11, 100), placed(true, 5, 14, 400));
        var partial = WorldStreamSourceBridge.finish(broken, false, "b", none, TILE, new Cancellation(), appender);
        check(partial.failed() && ids(partial).equals(java.util.List.of(-1L)),
                "A building copy failing partway leaves only the creatures' sources: " + ids(partial));
    }

    private static void successfulMove(boolean height, boolean partial, boolean rejectedBinding) throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-source-").resolve("tile-1.wst");
        try {
            if (rejectedBinding) {
                // Actual root coordinates no longer match the atlas header grid.
                var root = fixture.files.sources.get(2L).clone();
                java.nio.ByteBuffer.wrap(root).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(12 + 8 + 104, 1000);
                fixture.files.sources.put(2L, root);
            } else if (height) {
                fixture.files.sources.remove(3L);
            }
            if (partial) {
                fixture.files.sources.remove(11L);
            }
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(
                    fixture.source, scope, TILE, () -> true)) {
                var packet = producer.prepare(
                        AuthoredPacketDestination.create(path, scope.scopeSha256()), () -> false);
                try (packet; var resident = producer.takePrepared()) {
                    check(Files.exists(path), "Moved packet remains owned by transport");
                    check(resident.facts().outcome() == (height
                            ? WorldTerrainStreaming.Outcome.HEIGHT_FALLBACK
                            : WorldTerrainStreaming.Outcome.TEXTURED), "Actual source outcome");
                    if (rejectedBinding) {
                        check(resident.facts().fallback() == WorldTerrainStreaming.Fallback.QUALIFIED_BINDING_REJECTED,
                                "Actual qualified atlas rejected by geometry binding");
                    }
                    resident.withPayload(borrow -> {
                        check(borrow.geometry().vertexCount() > 0, "Checked source geometry");
                        check((borrow.texture() == null) == height, "Checked material binding");
                        if (partial) {
                            check(borrow.material().compositionCacheSha256() == null,
                                    "PARTIAL keeps genuine nullable composition cache identity");
                        }
                    });
                    fixture.world.closeAsync().get(5, TimeUnit.SECONDS);
                    check(Files.exists(path), "Source close cannot delete moved child input");
                    check(!resident.isOpen(), "Source close retires new source borrows");
                }
                check(!Files.exists(path), "Transport retirement deletes packet");
            }
            check(fixture.pool.stats().leaseClaims() == 0, "No material lease leaked");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    @SuppressWarnings("try")
    private static void untakenResidentCloseLeavesMovedPacketOwned() throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-untaken-").resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE, () -> true);
            try (var packet = producer.prepare(
                    AuthoredPacketDestination.create(path, scope.scopeSha256()), () -> false)) {
                producer.close();
                check(fixture.pool.stats().leaseClaims() == 0, "Untaken base retired exactly once");
                check(Files.exists(path), "Untaken source retirement cannot delete moved packet");
                try {
                    producer.takePrepared();
                    throw new AssertionError("Retired producer cannot transfer a source holder");
                } catch (IllegalStateException expected) {
                    // No holder can be minted from a retired producer.
                }
                producer.close();
            } finally {
                producer.close();
            }
            check(!Files.exists(path), "File ownership stays with returned packet");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void rejectLateView(int rejectAt) throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-stale-").resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            try (var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE,
                    () -> checks.incrementAndGet() < rejectAt)) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                            () -> false);
                    throw new AssertionError("Stale view must not hand off a packet");
                } catch (CancellationException expected) {
                    check(!Files.exists(path), "Unadvertised packet retired on late view change");
                }
            }
            check(fixture.pool.stats().leaseClaims() == 0, "Late-view base retired");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void stickySourceCleanupHealthIsFatalBeforeFileMove() throws Exception {
        stickySourceCleanupHealthIsFatalAt(2);
        stickySourceCleanupHealthIsFatalAt(3);
    }

    private static void stickySourceCleanupHealthIsFatalAt(int checkpoint) throws Exception {
        var fixture=new Fixture();
        var path=Files.createTempDirectory("bridge-health-").resolve("tile-1.wst");
        var health=new IllegalStateException("Authored sticky cleanup failure");
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var scope=fixture.scope();
            try(var producer=new WorldStreamSourceBridge.Producer(fixture.source,scope,TILE,()->{
                if(checks.incrementAndGet()==checkpoint) {
                    try {
                        var get=WorldService.class.getDeclaredMethod("preparedTerrain");
                        get.setAccessible(true);
                        var prepared=get.invoke(fixture.world);
                        var field=prepared.getClass().getDeclaredField("scopes");
                        field.setAccessible(true);
                        var engine=field.get(prepared);
                        var failure=engine.getClass().getDeclaredField("cleanupFailure");
                        failure.setAccessible(true);
                        failure.set(engine,health); // Negative fault only, never source positivity.
                    } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                }
                return true;
            })) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path,scope.scopeSha256()),()->false);
                    throw new AssertionError("Unknown source cleanup fault cannot become typed retirement");
                } catch(IllegalStateException expected) {
                    check(expected.getCause()==health,"Actual source health guard preserves fatal cleanup cause");
                    check(producer.sourceRefusal()==null,"False isOpen is never origin evidence for cleanup failure");
                    check(!Files.exists(path),"Unadvertised input retires on fatal source health failure");
                }
            }
            check(fixture.pool.stats().leaseClaims()==0,"Source base retires despite sticky source fault");
        } finally {
            try { fixture.world.closeAsync().get(5,TimeUnit.SECONDS); }
            catch(java.util.concurrent.ExecutionException expected) { /* Sticky source cleanup stays fatal. */ }
            fixture.pool.closeAsync().get(5,TimeUnit.SECONDS);
            fixture.tables.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void cleanSourceRetirementMapsButProviderFailureStaysFatal() throws Exception {
        for (boolean retired : new boolean[] {true, false}) {
            var providerFailure = new IllegalStateException("Authored unknown provider failure");
            var fail = new java.util.concurrent.atomic.AtomicBoolean();
            var fixture = new Fixture(new AuthoredTerrainSource.Files() {
                @Override
                com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                    if (fail.get()) { throw providerFailure; }
                    return super.stat(id, token);
                }
            });
            var path = Files.createTempDirectory("bridge-refusal-").resolve("tile-1.wst");
            try {
                var scope = fixture.scope();
                if (retired) { fixture.world.closeAsync().get(5, TimeUnit.SECONDS); }
                else { fail.set(true); }
                try (var producer = new WorldStreamSourceBridge.Producer(
                        fixture.source, scope, TILE, () -> true)) {
                    try {
                        producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                                () -> false);
                        throw new AssertionError("Expected source refusal/failure");
                    } catch (com.vibeminer.rendering.bgfx.WorldStreamTransport.PreparationRefusal expected) {
                        check(retired && expected.reason() == com.vibeminer.rendering.bgfx.WorldStreamTransport.Refusal.SOURCE_INVALIDATED,
                                "Only actual source-origin retirement maps to transport refusal");
                        check(producer.sourceRefusal() != null
                                        && producer.sourceRefusal().reason()
                                                == WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED,
                                "Closed bridge retains only actual clean source-origin metadata");
                    } catch (IllegalStateException expected) {
                        check(!retired && expected == providerFailure,
                                "Unknown provider failure stays fatal, never a refusal");
                        check(producer.sourceRefusal() == null,
                                "Unknown failure cannot mint source-origin metadata");
                    }
                }
            } finally {
                fail.set(false);
                fixture.close();
                Files.deleteIfExists(path);
                Files.deleteIfExists(path.getParent());
            }
        }
    }

    @SuppressWarnings("try")
    private static void destinationStoppingCancelsActualSourceAdmission() throws Exception {
        var entered = new java.util.concurrent.CompletableFuture<Void>();
        var canceled = new java.util.concurrent.CompletableFuture<Void>();
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var fixture = new Fixture(new AuthoredTerrainSource.Files() {
            @Override
            com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                if (id == 2) {
                    try (var registration = token.onCancel(() -> canceled.complete(null))) {
                        entered.complete(null);
                        release.join();
                        token.check();
                    }
                }
                return super.stat(id, token);
            }
        });
        var path = Files.createTempDirectory("bridge-cancel-").resolve("tile-1.wst");
        var scope = fixture.scope();
        var control = AuthoredPacketDestination.controlled(path, scope.scopeSha256());
        var cancellationWorker = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { control.runCancellation(); }
            catch (InterruptedException error) { throw new java.util.concurrent.CompletionException(error); }
        });
        try (var producer = new WorldStreamSourceBridge.Producer(
                fixture.source, scope, TILE, () -> true)) {
            var work = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { producer.prepare(control.destination(), () -> false); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            entered.get(5, TimeUnit.SECONDS);
            control.stop();
            canceled.get(5, TimeUnit.SECONDS);
            check(!work.isDone(), "Actual lower source work must drain before producer retires");
            release.complete(null);
            try {
                work.get(5, TimeUnit.SECONDS);
                throw new AssertionError("Stopped destination cannot hand off packet");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(!Files.exists(path), "Canceled source never advertises a file");
            }
            check(fixture.pool.stats().leaseClaims() == 0, "Canceled source has no atlas owner");
        } finally {
            release.complete(null);
            control.finish();
            cancellationWorker.get(5, TimeUnit.SECONDS);
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void fatalCleanupAfterMove() throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-fatal-").resolve("tile-1.wst");
        var original = new IllegalStateException("Authored post-MOVE failure");
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE,
                    () -> {
                        if (checks.incrementAndGet() == 3) {
                            try {
                                Files.delete(path);
                                Files.createDirectory(path);
                                Files.write(path.resolve("held"), new byte[] {1});
                            } catch (java.io.IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                            throw original;
                        }
                        return true;
                    })) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                            () -> false);
                    throw new AssertionError("Cleanup failure must be fatal");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().equals("Source packet cleanup failed"),
                            "Cleanup errors cannot become expected cancellation/refusal");
                    check(expected.getCause().getSuppressed().length == 1
                                    && expected.getCause().getSuppressed()[0] == original,
                            "Primary failure retained alongside cleanup failure");
                }
            }
            check(fixture.pool.stats().leaseClaims() == 0,
                    "Failed moved-file cleanup cannot skip independent base cleanup");
        } finally {
            fixture.close();
            Files.deleteIfExists(path.resolve("held"));
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void check(boolean valid, String detail) {
        if (!valid) {
            throw new AssertionError(detail);
        }
    }

    @SuppressWarnings("try")
    static final class Fixture implements AutoCloseable {
        final AuthoredTerrainSource.Files files;
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final AuthoredNativeMap tables;
        final WorldService world;
        final WorldTerrainStreaming source;

        Fixture() { this(new AuthoredTerrainSource.Files()); }

        Fixture(AuthoredTerrainSource.Files files) {
            this.files = files;
            tables = new AuthoredNativeMap(files.files());
            world = new WorldService(tables.tables(), files.files(), pool);
            source = world.terrainStreaming().orElseThrow();
        }

        WorldTerrainStreaming.Scope scope() {
            return source.createScope(new WorldService.MapInfo(7, "Authored", null, 0, 1L),
                    Cancellation.NONE);
        }

        public void close() throws Exception {
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }
}
