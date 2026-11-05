package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldCurrentAreaTest {
    private static WorldService.LoadedTile tile(WorldFormats.AreaChunk... chunks) {
        return new WorldService.LoadedTile(
                new WorldFormats.Tile(7, 19, 1, 0, 0),
                null,
                null,
                new WorldFormats.AreaMetadata(List.of(chunks)));
    }

    private static WorldFormats.AreaChunk chunk(int x, int y, long id, float maxX, float maxY) {
        return new WorldFormats.AreaChunk(x, y, id, maxX, maxY, 500, null);
    }

    private static WorldCurrentArea.Index index(WorldService.Area... areas) {
        return WorldCurrentArea.Index.of(List.of(areas));
    }

    private static WorldService.Area area(long id, String name, long parent) {
        return new WorldService.Area(id, name, parent, 0);
    }

    @Test
    void asymmetricNativeAxesAndHalfOpenBoundarySelectExactRecordedChunk() {
        float boundary = (float) (1000 - 16 * (WorldFormats.TILE_SIZE / 256));
        var tiles =
                List.of(
                        tile(
                                chunk(7, 3, 10, 1000, 2000),
                                chunk(7, 4, 11, boundary, 2000),
                                chunk(
                                        8,
                                        3,
                                        12,
                                        1000,
                                        (float) (2000 - 8 * (WorldFormats.TILE_SIZE / 128)))));
        var index =
                index(
                        area(1, "Elwynn Forest", 0),
                        area(9, "Northshire Valley", 1),
                        area(10, "Northshire Abbey", 9),
                        area(11, "Goldshire", 1),
                        area(12, "Eastvale", 1));
        assertEquals(
                "Terrain area: Elwynn Forest · Northshire Valley · Northshire Abbey",
                WorldCurrentArea.locate(0, 990, 1990, tiles, index).label());
        assertEquals(11, WorldCurrentArea.locate(0, boundary, 1990, tiles, index).areaId());
        assertEquals(
                10,
                WorldCurrentArea.locate(0, Math.nextUp((double) boundary), 1990, tiles, index)
                        .areaId());
        assertEquals(12, WorldCurrentArea.locate(0, 990, 1950, tiles, index).areaId());
        assertEquals(
                WorldCurrentArea.Reason.NO_COVERAGE,
                WorldCurrentArea.locate(0, 5000, 5000, tiles, index).reason());
    }

    @Test
    void retainedMetadataLookupMatchesLoadedTileLookup() {
        var index = index(area(1, "Elwynn Forest", 0), area(10, "Goldshire", 1));
        var tile = tile(chunk(0, 0, 10, 1000, 2000));
        assertEquals(
                WorldCurrentArea.locate(0, 990, 1990, List.of(tile), index),
                WorldCurrentArea.locateIn(0, 990, 1990, List.of(tile.areas()), index));
        var missing = new java.util.ArrayList<WorldFormats.AreaMetadata>();
        missing.add(null);
        assertEquals(
                WorldCurrentArea.Reason.NO_LOADED_METADATA,
                WorldCurrentArea.locateIn(0, 990, 1990, missing, index).reason());
        assertEquals(
                WorldCurrentArea.Reason.NO_COVERAGE,
                WorldCurrentArea.locateIn(0, 5000, 5000, List.of(tile.areas()), index).reason());
    }

    @Test
    void hierarchyCyclesUnknownParentsAndMapMismatchNeverInventARootZone() {
        var tiles = List.of(tile(chunk(0, 0, 10, 1000, 2000)));
        assertEquals(
                WorldCurrentArea.Reason.CYCLE,
                WorldCurrentArea.locate(
                                0, 990, 1990, tiles, index(area(10, "a", 11), area(11, "b", 10)))
                        .reason());
        assertEquals(
                WorldCurrentArea.Reason.UNKNOWN_PARENT,
                WorldCurrentArea.locate(0, 990, 1990, tiles, index(area(10, "a", 11))).reason());
        assertEquals(
                WorldCurrentArea.Reason.UNKNOWN_PARENT,
                WorldCurrentArea.locate(
                                0,
                                990,
                                1990,
                                tiles,
                                index(new WorldService.Area(10, "a", 0, 0, false)))
                        .reason());
        assertEquals(
                WorldCurrentArea.Reason.MAP_MISMATCH,
                WorldCurrentArea.locate(
                                0, 990, 1990, tiles, index(new WorldService.Area(10, "a", 0, 1)))
                        .reason());
        assertEquals(
                WorldCurrentArea.Reason.DUPLICATE_ID,
                WorldCurrentArea.locate(
                                0, 990, 1990, tiles, index(area(10, "a", 0), area(10, "b", 0)))
                        .reason());
        assertEquals(
                "Terrain area: Area #10",
                WorldCurrentArea.locate(0, 990, 1990, tiles, index(area(10, null, 0))).label());
    }

    @Test
    void sourceGapsAndOverlapsAreUnavailableNotNearestOrHeightGuesses() {
        var index = index(area(10, "a", 0), area(11, "b", 0));
        var overlap = List.of(tile(chunk(0, 0, 10, 1000, 2000), chunk(1, 0, 11, 999, 2000)));
        assertEquals(
                WorldCurrentArea.State.AMBIGUOUS,
                WorldCurrentArea.locate(0, 990, 1990, overlap, index).state());
        var noMetadata =
                List.of(
                        new WorldService.LoadedTile(
                                new WorldFormats.Tile(0, 0, 1, 0, 0), null, "unavailable"));
        assertEquals(
                WorldCurrentArea.Reason.NO_LOADED_METADATA,
                WorldCurrentArea.locate(0, 990, 1990, noMetadata, index).reason());
        // The query deliberately has no altitude parameter: source originZ cannot change terrain
        // area.
        var low = tile(new WorldFormats.AreaChunk(0, 0, 10, 1000, 2000, -500, null));
        var high = tile(new WorldFormats.AreaChunk(0, 0, 10, 1000, 2000, 50000, null));
        assertEquals(
                WorldCurrentArea.locate(0, 990, 1990, List.of(low), index),
                WorldCurrentArea.locate(0, 990, 1990, List.of(high), index));
    }

    @Test
    void detachedRecordedIdUsesTheSameHierarchyAndKeepsUnknownSourceExplicit() {
        var hierarchy = index(area(1, "Elwynn Forest", 0), area(10, "Goldshire", 1));
        assertEquals(
                WorldCurrentArea.locate(
                        0, 990, 1990, List.of(tile(chunk(0, 0, 10, 1000, 2000))), hierarchy),
                WorldCurrentArea.resolve(0, 10, hierarchy));
        assertEquals(
                WorldCurrentArea.Reason.UNKNOWN_AREA,
                WorldCurrentArea.resolve(0, 0, hierarchy).reason());
        assertEquals(
                WorldCurrentArea.Reason.UNKNOWN_AREA,
                WorldCurrentArea.resolve(0, 999, hierarchy).reason());
        assertEquals(
                WorldCurrentArea.Reason.MAP_MISMATCH,
                WorldCurrentArea.resolve(1, 10, hierarchy).reason());
        assertThrows(
                IllegalArgumentException.class, () -> WorldCurrentArea.resolve(-1, 10, hierarchy));
        assertThrows(
                IllegalArgumentException.class, () -> WorldCurrentArea.resolve(0, -1, hierarchy));
    }

    @Test
    void oversizedCatalogIsRejectedBeforeTraversalOrCopies() {
        var oversized =
                new AbstractList<WorldService.Area>() {
                    public int size() {
                        return 100001;
                    }

                    public WorldService.Area get(int i) {
                        throw new AssertionError("Oversized catalog traversed");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> WorldCurrentArea.Index.of(oversized));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldService.Catalog(List.of(), oversized, List.of()));
        var deep = new java.util.ArrayList<WorldService.Area>();
        for (int i = 1; i <= 65; i++) {
            deep.add(area(i, "level" + i, i == 65 ? 0 : i + 1));
        }
        assertEquals(
                WorldCurrentArea.Reason.DEPTH_LIMIT,
                WorldCurrentArea.locate(
                                0,
                                990,
                                1990,
                                List.of(tile(chunk(0, 0, 1, 1000, 2000))),
                                WorldCurrentArea.Index.of(deep))
                        .reason());
        assertTrue(new WorldService.Area(1, "a", 0, 0).hierarchyKnown());
    }
}
