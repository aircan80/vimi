package com.vibeminer.concepts.world;

import com.vibeminer.formats.world.WorldFormats;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Saved explorer cameras and validated travel targets. A target is only usable after its recorded
 * map identity and terrain tile are found again in the current build; coordinates must then lie
 * inside that tile's loaded native bounds. Nothing here infers entrances, zone centers or floors.
 */
public final class WorldBookmarks {
    public static final int MAX_BOOKMARKS = 256, MAX_NAME = 80, MAX_TEXT = 256;
    public static final double MAX_COORDINATE = 1_000_000;
    private static final String VERSION = "v1";

    private WorldBookmarks() {}

    /** A camera saved with the map identity it was recorded against. */
    public record Bookmark(
            String name,
            long mapId,
            String mapDirectory,
            String mapName,
            int tileX,
            int tileY,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            String savedBuild) {
        public Bookmark {
            name = text(name, MAX_NAME, false);
            mapDirectory = text(mapDirectory, MAX_TEXT, true);
            mapName = text(mapName, MAX_TEXT, true);
            savedBuild = text(savedBuild, MAX_TEXT, true);
            if (mapId < 0 || mapId > 0xffff_ffffL) {
                throw new IllegalArgumentException("Invalid bookmark map");
            }
            tile(tileX);
            tile(tileY);
            for (double value : new double[] {x, y, z, yaw, pitch}) {
                if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) {
                    throw new IllegalArgumentException("Invalid bookmark coordinate");
                }
            }
            if (Math.abs(pitch) > 1.5) {
                throw new IllegalArgumentException("Invalid bookmark pitch");
            }
        }

        public Bookmark renamed(String next) {
            return new Bookmark(
                    next,
                    mapId,
                    mapDirectory,
                    mapName,
                    tileX,
                    tileY,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    savedBuild);
        }

        @Override
        public String toString() {
            String map = mapName.isEmpty() ? "Map #" + mapId : mapName;
            return name + " · " + map + " · tile " + tileX + "," + tileY;
        }
    }

    public enum Refusal {
        NONE,
        MAP_MISSING,
        MAP_CHANGED,
        NO_INVENTORY,
        TILE_NOT_RECORDED,
        OUTSIDE_TILE
    }

    /** A recorded map and inventory tile to open; position applies once the tile is verified. */
    public record Target(
            WorldService.MapInfo map,
            WorldFormats.Tile tile,
            double x,
            double y,
            double z,
            boolean hasZ,
            double yaw,
            double pitch) {
        public Target {
            Objects.requireNonNull(map);
            Objects.requireNonNull(tile);
        }
    }

    public record Resolution(Refusal refusal, Target target) {
        public boolean usable() {
            return refusal == Refusal.NONE;
        }

        public String explanation() {
            return switch (refusal) {
                case NONE -> "Recorded map and terrain tile found";
                case MAP_MISSING -> "This build has no map with the saved ID";
                case MAP_CHANGED -> "The saved map ID now records a different map directory";
                case NO_INVENTORY -> "Terrain inventory for this map is unavailable";
                case TILE_NOT_RECORDED ->
                        "The map's terrain inventory has no tile at that position";
                case OUTSIDE_TILE -> "Position lies outside the verified terrain tile";
            };
        }
    }

    /** Filename tile index on one native axis; the same convention the streaming lookup uses. */
    public static int tileIndex(double nativeCoordinate) {
        if (!Double.isFinite(nativeCoordinate)) {
            return -1;
        }
        double index = Math.floor(32 - nativeCoordinate / WorldFormats.TILE_SIZE);
        return index < 0 || index > 63 ? -1 : (int) index;
    }

    /** Map identity: same ID and, when both are recorded, the same directory. */
    public static Optional<WorldService.MapInfo> map(
            Bookmark bookmark, List<WorldService.MapInfo> maps, Refusal[] refusal) {
        Objects.requireNonNull(bookmark);
        Objects.requireNonNull(maps);
        for (var map : maps) {
            if (map.id() != bookmark.mapId()) {
                continue;
            }
            String directory = map.directory() == null ? "" : map.directory();
            if (!bookmark.mapDirectory().isEmpty()
                    && !directory.isEmpty()
                    && !directory.equalsIgnoreCase(bookmark.mapDirectory())) {
                refusal[0] = Refusal.MAP_CHANGED;
                return Optional.empty();
            }
            refusal[0] = Refusal.NONE;
            return Optional.of(map);
        }
        refusal[0] = Refusal.MAP_MISSING;
        return Optional.empty();
    }

    /** Resolves a bookmark against the current map record and its terrain inventory. */
    public static Resolution resolve(
            Bookmark bookmark, WorldService.MapInfo map, WorldService.Inventory inventory) {
        Objects.requireNonNull(bookmark);
        Objects.requireNonNull(map);
        if (map.id() != bookmark.mapId()) {
            return new Resolution(Refusal.MAP_MISSING, null);
        }
        return resolve(
                map,
                inventory,
                bookmark.tileX(),
                bookmark.tileY(),
                bookmark.x(),
                bookmark.y(),
                bookmark.z(),
                true,
                bookmark.yaw(),
                bookmark.pitch());
    }

    /** Resolves typed native coordinates on the selected map. */
    public static Resolution coordinates(
            WorldService.MapInfo map, WorldService.Inventory inventory, double[] position) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(position);
        int tx = tileIndex(position[0]), ty = tileIndex(position[1]);
        if (tx < 0 || ty < 0) {
            return new Resolution(Refusal.TILE_NOT_RECORDED, null);
        }
        return resolve(
                map,
                inventory,
                tx,
                ty,
                position[0],
                position[1],
                position.length > 2 ? position[2] : 0,
                position.length > 2,
                Math.PI,
                -.3);
    }

    private static Resolution resolve(
            WorldService.MapInfo map,
            WorldService.Inventory inventory,
            int tx,
            int ty,
            double x,
            double y,
            double z,
            boolean hasZ,
            double yaw,
            double pitch) {
        if (inventory == null
                || inventory.world() == null
                || inventory.map() == null
                || inventory.map().id() != map.id()) {
            return new Resolution(Refusal.NO_INVENTORY, null);
        }
        for (var tile : inventory.world().tiles()) {
            if (tile.x() == tx && tile.y() == ty) {
                return new Resolution(
                        Refusal.NONE, new Target(map, tile, x, y, z, hasZ, yaw, pitch));
            }
        }
        return new Resolution(Refusal.TILE_NOT_RECORDED, null);
    }

    /**
     * Verification after the tile loaded: the point must lie in the half-open native rectangle
     * below the loaded tile's maxima, the same ownership rule as the camera tile lookup.
     */
    public static boolean within(double tileMaxX, double tileMaxY, double x, double y) {
        double size = WorldFormats.TILE_SIZE;
        return x > tileMaxX - size && x <= tileMaxX && y > tileMaxY - size && y <= tileMaxY;
    }

    /** Parses "x y" or "x, y, z" native coordinates; anything else is empty. */
    public static Optional<double[]> parseCoordinates(String input) {
        if (input == null || input.length() > 128) {
            return Optional.empty();
        }
        String[] parts = input.trim().split("[\\s,;]+");
        if (parts.length < 2 || parts.length > 3) {
            return Optional.empty();
        }
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException invalid) {
                return Optional.empty();
            }
            if (!Double.isFinite(values[i]) || Math.abs(values[i]) > MAX_COORDINATE) {
                return Optional.empty();
            }
        }
        return Optional.of(values);
    }

    /** Case-insensitive search over bookmark, map name, directory and numeric map ID. */
    public static List<Bookmark> search(List<Bookmark> bookmarks, String query) {
        Objects.requireNonNull(bookmarks);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.copyOf(bookmarks);
        }
        var result = new ArrayList<Bookmark>();
        for (var bookmark : bookmarks) {
            if (bookmark.name().toLowerCase(Locale.ROOT).contains(needle)
                    || bookmark.mapName().toLowerCase(Locale.ROOT).contains(needle)
                    || bookmark.mapDirectory().toLowerCase(Locale.ROOT).contains(needle)
                    || Long.toString(bookmark.mapId()).equals(needle)) {
                result.add(bookmark);
            }
        }
        return List.copyOf(result);
    }

    /** Versioned single-line encoding; every text field is percent-encoded. */
    public static String encode(Bookmark bookmark) {
        Objects.requireNonNull(bookmark);
        return String.join(
                "|",
                VERSION,
                escape(bookmark.name()),
                Long.toString(bookmark.mapId()),
                escape(bookmark.mapDirectory()),
                escape(bookmark.mapName()),
                Integer.toString(bookmark.tileX()),
                Integer.toString(bookmark.tileY()),
                Double.toString(bookmark.x()),
                Double.toString(bookmark.y()),
                Double.toString(bookmark.z()),
                Double.toString(bookmark.yaw()),
                Double.toString(bookmark.pitch()),
                escape(bookmark.savedBuild()));
    }

    /** Decodes {@link #encode}; malformed or unknown versions are rejected, never repaired. */
    public static Bookmark decode(String line) {
        if (line == null || line.length() > 4096) {
            throw new IllegalArgumentException("Invalid bookmark record");
        }
        String[] f = line.split("\\|", -1);
        if (f.length != 13 || !VERSION.equals(f[0])) {
            throw new IllegalArgumentException("Unsupported bookmark record");
        }
        try {
            return new Bookmark(
                    unescape(f[1]),
                    Long.parseLong(f[2]),
                    unescape(f[3]),
                    unescape(f[4]),
                    Integer.parseInt(f[5]),
                    Integer.parseInt(f[6]),
                    Double.parseDouble(f[7]),
                    Double.parseDouble(f[8]),
                    Double.parseDouble(f[9]),
                    Double.parseDouble(f[10]),
                    Double.parseDouble(f[11]),
                    unescape(f[12]));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid bookmark record", invalid);
        }
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void tile(int index) {
        if (index < 0 || index > 63) {
            throw new IllegalArgumentException("Invalid bookmark tile");
        }
    }

    private static String text(String value, int limit, boolean allowEmpty) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() && !allowEmpty || trimmed.length() > limit) {
            throw new IllegalArgumentException("Invalid bookmark text");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new IllegalArgumentException("Invalid bookmark text");
            }
        }
        return trimmed;
    }
}
