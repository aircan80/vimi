package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldBookmarksTest {
    private static final WorldService.MapInfo AZEROTH =
            new WorldService.MapInfo(0, "Eastern Kingdoms", "Azeroth", 0, 775971L);
    private static final WorldService.MapInfo KALIMDOR =
            new WorldService.MapInfo(1, "Kalimdor", "Kalimdor", 0, 782779L);
    private static final WorldService.Inventory INVENTORY =
            new WorldService.Inventory(
                    AZEROTH,
                    new WorldFormats.World(
                            List.of(
                                    new WorldFormats.Tile(32, 48, 1, 0, 0),
                                    new WorldFormats.Tile(31, 49, 2, 0, 0)),
                            false),
                    null);

    private static WorldBookmarks.Bookmark bookmark(String name, double x, double y) {
        return new WorldBookmarks.Bookmark(
                name,
                0,
                "Azeroth",
                "Eastern Kingdoms",
                WorldBookmarks.tileIndex(x),
                WorldBookmarks.tileIndex(y),
                x,
                y,
                55.5,
                1.25,
                -.4,
                "1.60.1.70707");
    }

    @Test
    void tileIndexFollowsTheNativeAxisConventionAndBounds() {
        double size = WorldFormats.TILE_SIZE;
        assertEquals(32, WorldBookmarks.tileIndex(0));
        assertEquals(32, WorldBookmarks.tileIndex(-size + 1e-6));
        assertEquals(33, WorldBookmarks.tileIndex(-size));
        assertEquals(31, WorldBookmarks.tileIndex(1e-6));
        assertEquals(0, WorldBookmarks.tileIndex(32 * size));
        assertEquals(-1, WorldBookmarks.tileIndex(32 * size + 1e-3));
        assertEquals(63, WorldBookmarks.tileIndex(-31 * size));
        assertEquals(-1, WorldBookmarks.tileIndex(-32 * size));
        assertEquals(-1, WorldBookmarks.tileIndex(Double.NaN));
        // A tile's own maxima belong to it under the half-open camera ownership rule.
        double maxX = (32 - 48) * size;
        assertEquals(48, WorldBookmarks.tileIndex(maxX));
        assertTrue(WorldBookmarks.within(maxX, 0, maxX, 0));
        assertFalse(WorldBookmarks.within(maxX, 0, maxX - size, -1));
        assertFalse(WorldBookmarks.within(maxX, 0, maxX + 1e-3, -1));
    }

    @Test
    void bookmarksResolveOnlyAgainstTheSameMapIdentityAndRecordedTile() {
        double size = WorldFormats.TILE_SIZE;
        var saved = bookmark("Goldshire", (32 - 32.5) * size, (32 - 48.5) * size);
        var refusal = new WorldBookmarks.Refusal[1];
        assertEquals(AZEROTH, WorldBookmarks.map(saved, List.of(KALIMDOR, AZEROTH), refusal).get());
        assertEquals(WorldBookmarks.Refusal.NONE, refusal[0]);
        var resolved = WorldBookmarks.resolve(saved, AZEROTH, INVENTORY);
        assertTrue(resolved.usable());
        assertEquals(1, resolved.target().tile().rootId());
        assertEquals(saved.z(), resolved.target().z());
        assertTrue(resolved.target().hasZ());

        assertTrue(WorldBookmarks.map(saved, List.of(KALIMDOR), refusal).isEmpty());
        assertEquals(WorldBookmarks.Refusal.MAP_MISSING, refusal[0]);
        var renamedDirectory = new WorldService.MapInfo(0, "Eastern Kingdoms", "Other", 0, 1L);
        assertTrue(WorldBookmarks.map(saved, List.of(renamedDirectory), refusal).isEmpty());
        assertEquals(WorldBookmarks.Refusal.MAP_CHANGED, refusal[0]);
        var unrecordedDirectory = new WorldService.MapInfo(0, "Eastern Kingdoms", null, 0, 1L);
        assertTrue(WorldBookmarks.map(saved, List.of(unrecordedDirectory), refusal).isPresent());

        var unrecordedTile = bookmark("Sea", (32 - 10.5) * size, (32 - 10.5) * size);
        assertEquals(
                WorldBookmarks.Refusal.TILE_NOT_RECORDED,
                WorldBookmarks.resolve(unrecordedTile, AZEROTH, INVENTORY).refusal());
        assertEquals(
                WorldBookmarks.Refusal.NO_INVENTORY,
                WorldBookmarks.resolve(saved, AZEROTH, null).refusal());
        var otherInventory = new WorldService.Inventory(KALIMDOR, INVENTORY.world(), null);
        assertEquals(
                WorldBookmarks.Refusal.NO_INVENTORY,
                WorldBookmarks.resolve(saved, AZEROTH, otherInventory).refusal());
        assertEquals(
                WorldBookmarks.Refusal.MAP_MISSING,
                WorldBookmarks.resolve(saved, KALIMDOR, INVENTORY).refusal());
        for (var reason : WorldBookmarks.Refusal.values()) {
            assertFalse(new WorldBookmarks.Resolution(reason, null).explanation().isBlank());
        }
    }

    @Test
    void typedCoordinatesUseTheRecordedInventoryTile() {
        double size = WorldFormats.TILE_SIZE;
        var position = WorldBookmarks.parseCoordinates(" -10.5, " + (-16.25 * size) + " ").get();
        var resolved = WorldBookmarks.coordinates(AZEROTH, INVENTORY, position);
        assertTrue(resolved.usable());
        assertEquals(32, resolved.target().tile().x());
        assertEquals(48, resolved.target().tile().y());
        assertFalse(resolved.target().hasZ());
        var withZ =
                WorldBookmarks.coordinates(
                        AZEROTH, INVENTORY, new double[] {-10, -16.25 * size, 7});
        assertTrue(withZ.target().hasZ());
        assertEquals(7, withZ.target().z());
        assertEquals(
                WorldBookmarks.Refusal.TILE_NOT_RECORDED,
                WorldBookmarks.coordinates(AZEROTH, INVENTORY, new double[] {0, 0}).refusal());
        assertEquals(
                WorldBookmarks.Refusal.TILE_NOT_RECORDED,
                WorldBookmarks.coordinates(AZEROTH, INVENTORY, new double[] {99999, 0}).refusal());
    }

    @Test
    void coordinateParsingIsStrict() {
        assertArrayEquals(new double[] {1, -2.5}, WorldBookmarks.parseCoordinates("1 -2.5").get());
        assertArrayEquals(new double[] {1, 2, 3}, WorldBookmarks.parseCoordinates("1;2,3").get());
        for (String bad :
                new String[] {
                    null,
                    "",
                    "1",
                    "1 2 3 4",
                    "a b",
                    "NaN 1",
                    "1 Infinity",
                    "2000000 1",
                    "1".repeat(200) + " 2"
                }) {
            assertTrue(WorldBookmarks.parseCoordinates(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void encodingRoundTripsAndRejectsMalformedRecords() {
        var saved = bookmark("Name | with % and ünïcode", -100.125, -8000.5);
        String line = WorldBookmarks.encode(saved);
        assertFalse(line.substring(3).contains("ü"));
        assertEquals(saved, WorldBookmarks.decode(line));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldBookmarks.decode("v2" + line.substring(2)));
        assertThrows(IllegalArgumentException.class, () -> WorldBookmarks.decode(line + "|x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldBookmarks.decode(line.replace("|0|", "|zero|")));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldBookmarks.decode(line.replace("-100.125", "NaN")));
        assertThrows(IllegalArgumentException.class, () -> WorldBookmarks.decode(null));
    }

    @Test
    void bookmarkFieldsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> bookmark(" ", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bookmark("x".repeat(81), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bookmark("tab\there", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> bookmark("far", 2_000_000, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldBookmarks.Bookmark("a", -1, "", "", 0, 0, 0, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldBookmarks.Bookmark("a", 0, "", "", 64, 0, 0, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldBookmarks.Bookmark("a", 0, "", "", 0, 0, 0, 0, 0, 0, 2, ""));
        assertEquals("  trimmed ".strip(), bookmark("  trimmed ", 0, 0).name());
        assertEquals("renamed", bookmark("a", 0, 0).renamed("renamed").name());
    }

    @Test
    void searchMatchesNamesMapsAndExactIds() {
        var a = bookmark("Goldshire inn", 0, 0);
        var b =
                new WorldBookmarks.Bookmark(
                        "Ratchet", 1, "Kalimdor", "Kalimdor", 32, 32, 0, 0, 0, 0, 0, "");
        var all = List.of(a, b);
        assertEquals(all, WorldBookmarks.search(all, " "));
        assertEquals(List.of(a), WorldBookmarks.search(all, "GOLD"));
        assertEquals(List.of(b), WorldBookmarks.search(all, "kalim"));
        assertEquals(List.of(a), WorldBookmarks.search(all, "azeroth"));
        assertEquals(List.of(b), WorldBookmarks.search(all, "1"));
        assertEquals(List.of(), WorldBookmarks.search(all, "11"));
    }
}
