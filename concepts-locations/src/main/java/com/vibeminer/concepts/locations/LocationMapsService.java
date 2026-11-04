package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Reviewed build-local map relationships, phase-zero artwork and bounded explicit tile reads. */
@SuppressWarnings("try")
public final class LocationMapsService implements BuildLocationMaps {
    private static final int MAX_TILES = 256;
    private final BuildTables tables;
    private final BuildFiles files;
    private final BlpImages images;
    private final Map<String, MapTable> schemas = new HashMap<>();
    private final Map<String, List<Db2Types.Row>> rows = new HashMap<>();
    private final Map<Long, Db2Types.Row> mapRows = new HashMap<>();
    private final Map<Long, MapTypes.Artwork> artwork = new HashMap<>();
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("location-maps").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Void> closing;

    public LocationMapsService(BuildTables tables) {
        this(tables, null, null);
    }

    public LocationMapsService(BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this.tables = Objects.requireNonNull(tables);
        this.files = files;
        if (files != null
                && (!files.build().equals(tables.build())
                        || !files.locale().equals(tables.locale()))) {
            throw new IllegalArgumentException(
                    "Map tables/files must belong to the same build and locale");
        }
        images =
                files == null || generated == null
                        ? null
                        : new BlpImages(
                                generated,
                                new Blp2Types.Limits(2L << 20, 512, 512L * 512, 2L << 20, 1L << 20),
                                null);
    }

    @Override
    public CompletableFuture<MapNavigationSourceTypes.Result> navigation(Cancellation token) {
        return submit(token, request -> MapNavigationSource.read(tables, request));
    }

    @Override
    public CompletableFuture<MapTypes.Resolution> resolve(
            LocationTypes.Location location, Cancellation token) {
        Objects.requireNonNull(location);
        return submit(
                token,
                request -> {
                    var issues = new ArrayList<String>();
                    var result = new ArrayList<MapTypes.Candidate>();
                    try {
                        load("UiMap", 8192, request, issues);
                        var direct = new HashSet<Long>();
                        for (var coordinate : location.coordinates()) {
                            if (coordinate.map().kind() != LocationTypes.MapKind.UI_MAP) {
                                continue;
                            }
                            try {
                                var info = map(coordinate.map().id(), issues);
                                if (info == null) {
                                    continue;
                                }
                                double divisor =
                                        coordinate.units() == LocationTypes.Units.PERCENT ? 100 : 1;
                                result.add(
                                        new MapTypes.Candidate(
                                                info,
                                                coordinate.x() / divisor,
                                                coordinate.y() / divisor,
                                                null,
                                                MapTypes.Basis.RECORDED_UI_MAP,
                                                mapSources(info.id(), issues),
                                                null));
                                direct.add(info.id());
                            } catch (MapTable.Projection failure) {
                                MapTable.issue(issues, failure.getMessage());
                            }
                        }
                        try {
                            var assignments = load("UiMapAssignment", 16_384, request, issues);
                            var data = schemas.get("UiMapAssignment");
                            data.reference("AreaID", "AreaTable");
                            data.reference("UiMapID", "UiMap");
                            data.reference("MapID", "Map");
                            for (var coordinate : location.coordinates()) {
                                for (var assignment : assignments) {
                                    request.check();
                                    try {
                                        long uiMap = data.number(assignment, "UiMapID");
                                        if (direct.contains(uiMap)) {
                                            continue;
                                        }
                                        var info = map(uiMap, issues);
                                        if (info == null
                                                || schemas.get("UiMap")
                                                                .number(mapRow(uiMap), "System")
                                                        != 0) {
                                            continue;
                                        }
                                        if (coordinate.map().kind() == LocationTypes.MapKind.AREA
                                                && data.number(assignment, "AreaID")
                                                        == coordinate.map().id()) {
                                            var candidate =
                                                    area(
                                                            coordinate,
                                                            info,
                                                            data,
                                                            assignment,
                                                            issues);
                                            if (candidate != null) {
                                                result.add(candidate);
                                            }
                                        } else if (coordinate.map().kind()
                                                        == LocationTypes.MapKind.WORLD_MAP
                                                && data.number(assignment, "MapID")
                                                        == coordinate.map().id()) {
                                            var candidate =
                                                    world(
                                                            coordinate,
                                                            info,
                                                            data,
                                                            assignment,
                                                            issues);
                                            if (candidate != null) {
                                                result.add(candidate);
                                            }
                                        }
                                    } catch (MapTable.Projection failure) {
                                        MapTable.issue(issues, failure.getMessage());
                                    }
                                }
                            }
                        } catch (MapTable.Projection failure) {
                            MapTable.issue(issues, failure.getMessage());
                        }
                        result.sort(
                                Comparator.comparing(
                                                (MapTypes.Candidate c) ->
                                                        c.basis() != MapTypes.Basis.RECORDED_UI_MAP)
                                        .thenComparingLong(c -> c.map().id())
                                        .thenComparing(
                                                c ->
                                                        c.assignmentId() == null
                                                                ? -1L
                                                                : c.assignmentId()));
                        var counts = new HashMap<Long, Integer>();
                        for (var candidate : result) {
                            counts.merge(candidate.map().id(), 1, Integer::sum);
                        }
                        if (counts.values().stream().anyMatch(count -> count > 1)) {
                            MapTable.issue(
                                    issues,
                                    "Multiple assignments target the same UiMap; no preferred assignment is chosen.");
                        }
                        return new MapTypes.Resolution(
                                result.isEmpty() ? MapTypes.State.UNAVAILABLE : state(issues),
                                tables.build(),
                                tables.locale(),
                                location,
                                result,
                                issues,
                                result.isEmpty()
                                        ? "No reviewed selected-build map relationship resolves this location."
                                        : null);
                    } catch (MapTable.Projection failure) {
                        return new MapTypes.Resolution(
                                failure.state,
                                tables.build(),
                                tables.locale(),
                                location,
                                result,
                                issues,
                                failure.getMessage());
                    }
                });
    }

    private MapTypes.Candidate area(
            LocationTypes.Coordinate coordinate,
            MapTypes.MapInfo info,
            MapTable data,
            Db2Types.Row row,
            List<String> issues) {
        var sources = assignmentSources(data, row, issues);
        String unsupported = null;
        try {
            if (!unscoped(data, row)) {
                unsupported = "Area assignment requires unavailable WMO context.";
            } else if (number(data, row, "UiMin[0]") != 0
                    || number(data, row, "UiMin[1]") != 0
                    || number(data, row, "UiMax[0]") != 1
                    || number(data, row, "UiMax[1]") != 1) {
                unsupported =
                        "Area assignment has a partial UiMap rectangle; percent-frame transfer is not reviewed.";
            }
        } catch (MapTable.Projection failure) {
            unsupported =
                    "Area map association is readable, but coordinate-frame fields are unavailable.";
        }
        if (unsupported != null) {
            MapTable.issue(issues, unsupported);
            return new MapTypes.Candidate(
                    info,
                    null,
                    null,
                    row.id(),
                    MapTypes.Basis.AREA_ASSIGNMENT,
                    sources,
                    unsupported);
        }
        double divisor = coordinate.units() == LocationTypes.Units.PERCENT ? 100 : 1;
        return new MapTypes.Candidate(
                info,
                coordinate.x() / divisor,
                coordinate.y() / divisor,
                row.id(),
                MapTypes.Basis.AREA_ASSIGNMENT,
                sources,
                "Recorded area percent uses this full-extent UiMap; phase/character eligibility is unevaluated.");
    }

    private MapTypes.Candidate world(
            LocationTypes.Coordinate coordinate,
            MapTypes.MapInfo info,
            MapTable data,
            Db2Types.Row row,
            List<String> issues) {
        if (!unscoped(data, row)) {
            return null;
        }
        double minX = number(data, row, "Region[0]"), minY = number(data, row, "Region[1]");
        double maxX = number(data, row, "Region[3]"), maxY = number(data, row, "Region[4]");
        Double minZ = optionalFloat(data, row, "Region[2]", issues),
                maxZ = optionalFloat(data, row, "Region[5]", issues);
        double sizeX = maxX - minX, sizeY = maxY - minY;
        if (!(sizeX > 0) || !(sizeY > 0) || !Double.isFinite(sizeX) || !Double.isFinite(sizeY)) {
            MapTable.issue(issues, "Some map assignment regions are degenerate or unsupported.");
            return null;
        }
        if (coordinate.x() < minX
                || coordinate.x() > maxX
                || coordinate.y() < minY
                || coordinate.y() > maxY) {
            return null;
        }
        if (coordinate.z() != null
                && minZ != null
                && maxZ != null
                && maxZ > minZ
                && (coordinate.z() < minZ || coordinate.z() > maxZ)) {
            return null;
        }
        double relativeX = (coordinate.x() - minX) / sizeX,
                relativeY = (coordinate.y() - minY) / sizeY;
        double uiX =
                relativeY * number(data, row, "UiMin[0]")
                        + (1 - relativeY) * number(data, row, "UiMax[0]");
        double uiY =
                relativeX * number(data, row, "UiMin[1]")
                        + (1 - relativeX) * number(data, row, "UiMax[1]");
        if (!Double.isFinite(uiX)
                || !Double.isFinite(uiY)
                || uiX < 0
                || uiX > 1
                || uiY < 0
                || uiY > 1) {
            MapTable.issue(issues, "Some world projections lie outside normalized map bounds.");
            return null;
        }
        return new MapTypes.Candidate(
                info,
                uiX,
                uiY,
                row.id(),
                MapTypes.Basis.WORLD_ASSIGNMENT,
                assignmentSources(data, row, issues),
                "Projected through a selected-build unscoped assignment; no nearest-floor guess is made.");
    }

    private static Double optionalFloat(
            MapTable data, Db2Types.Row row, String field, List<String> issues) {
        try {
            return number(data, row, field);
        } catch (MapTable.Projection failure) {
            MapTable.issue(
                    issues,
                    "Some assignment height bounds are unreadable; no floor eligibility is inferred.");
            return null;
        }
    }

    private static boolean unscoped(MapTable data, Db2Types.Row row) {
        return data.number(row, "WMODoodadPlacementID") == 0 && data.number(row, "WMOGroupID") == 0;
    }

    private static double number(MapTable data, Db2Types.Row row, String field) {
        double value = data.decimal(row, field).doubleValue();
        if (!Double.isFinite(value)) {
            throw data.invalid("Non-finite coordinate " + field);
        }
        return value;
    }

    private static List<MapTypes.TableSource> assignmentSources(
            MapTable data, Db2Types.Row row, List<String> issues) {
        var result = new ArrayList<MapTypes.TableSource>();
        for (String field :
                List.of(
                        "AreaID",
                        "MapID",
                        "UiMapID",
                        "UiMin[0]",
                        "UiMin[1]",
                        "UiMax[0]",
                        "UiMax[1]",
                        "Region[0]",
                        "Region[1]",
                        "Region[2]",
                        "Region[3]",
                        "Region[4]",
                        "Region[5]",
                        "WMODoodadPlacementID",
                        "WMOGroupID")) {
            try {
                result.add(data.source(row, field));
            } catch (MapTable.Projection failure) {
                MapTable.issue(issues, failure.getMessage());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public CompletableFuture<MapTypes.Artwork> artwork(long uiMapId, Cancellation token) {
        validId(uiMapId);
        return submit(token, request -> art(uiMapId, request));
    }

    private MapTypes.Artwork art(long uiMapId, Cancellation token) {
        var cached = artwork.get(uiMapId);
        if (cached != null) {
            return cached;
        }
        var issues = new ArrayList<String>();
        var sources = new ArrayList<MapTypes.TableSource>();
        var links = new ArrayList<MapTypes.ArtLink>();
        MapTypes.MapInfo info = null;
        Long artId = null;
        MapTypes.Layer layer = null;
        try {
            load("UiMap", 8192, token, issues);
            info = map(uiMapId, issues);
            if (info == null) {
                return failedArt(
                        MapTypes.State.UNAVAILABLE,
                        info,
                        links,
                        sources,
                        issues,
                        "UiMap record is unavailable.",
                        artId,
                        layer);
            }
            sources.addAll(mapSources(uiMapId, issues));
            var relations = load("UiMapXMapArt", 16_384, token, issues);
            var relation = schemas.get("UiMapXMapArt");
            relation.reference("UiMapID", "UiMap");
            relation.reference("UiMapArtID", "UiMapArt");
            for (var row : relations) {
                token.check();
                try {
                    if (relation.number(row, "UiMapID") != uiMapId) {
                        continue;
                    }
                    long id = relation.number(row, "UiMapArtID"),
                            phase = relation.number(row, "PhaseID");
                    validId(id);
                    links.add(new MapTypes.ArtLink(row.id(), id, phase));
                    for (String field : List.of("UiMapID", "UiMapArtID", "PhaseID")) {
                        sources.add(relation.source(row, field));
                    }
                } catch (MapTable.Projection | IllegalArgumentException failure) {
                    MapTable.issue(issues, "Some map/art relationships are unreadable.");
                }
            }
            var base =
                    links.stream()
                            .filter(link -> link.phaseId() == 0)
                            .map(MapTypes.ArtLink::artId)
                            .distinct()
                            .toList();
            if (base.size() != 1) {
                return failedArt(
                        MapTypes.State.UNAVAILABLE,
                        info,
                        links,
                        sources,
                        issues,
                        base.isEmpty()
                                ? "No readable phase-zero map artwork is declared."
                                : "Multiple phase-zero art IDs are ambiguous.",
                        artId,
                        layer);
            }
            artId = base.getFirst();
            var artRows = load("UiMapArt", 16_384, token, issues);
            var artData = schemas.get("UiMapArt");
            Long selectedArt = artId;
            var artRow =
                    artRows.stream()
                            .filter(row -> row.id() == selectedArt)
                            .findFirst()
                            .orElse(null);
            if (artRow == null) {
                return failedArt(
                        MapTypes.State.UNAVAILABLE,
                        info,
                        links,
                        sources,
                        issues,
                        "Map art record is unavailable.",
                        artId,
                        layer);
            }
            long styleId = artData.number(artRow, "UiMapArtStyleID");
            sources.add(artData.source(artRow, "UiMapArtStyleID"));
            var styles = load("UiMapArtStyleLayer", 1024, token, issues);
            var style = schemas.get("UiMapArtStyleLayer");
            var found = new ArrayList<Db2Types.Row>();
            for (var row : styles) {
                try {
                    if (style.number(row, "UiMapArtStyleID") == styleId
                            && style.number(row, "LayerIndex") == 0) {
                        found.add(row);
                    }
                } catch (MapTable.Projection failure) {
                    MapTable.issue(issues, failure.getMessage());
                }
            }
            if (found.size() != 1) {
                return failedArt(
                        MapTypes.State.UNAVAILABLE,
                        info,
                        links,
                        sources,
                        issues,
                        "No unique readable base artwork layer is declared.",
                        artId,
                        layer);
            }
            var layerRow = found.getFirst();
            int width = dimension(style, layerRow, "LayerWidth", 8192),
                    height = dimension(style, layerRow, "LayerHeight", 8192);
            int tileWidth = dimension(style, layerRow, "TileWidth", 512),
                    tileHeight = dimension(style, layerRow, "TileHeight", 512);
            if ((long) width * height > 16_777_216) {
                throw style.invalid("Map layer exceeds pixel budget");
            }
            int columns = (width + tileWidth - 1) / tileWidth,
                    rowCount = (height + tileHeight - 1) / tileHeight;
            if ((long) columns * rowCount > MAX_TILES) {
                throw style.invalid("Map layer exceeds tile budget");
            }
            layer = new MapTypes.Layer(layerRow.id(), 0, width, height, tileWidth, tileHeight);
            for (String field :
                    List.of(
                            "UiMapArtStyleID",
                            "LayerIndex",
                            "LayerWidth",
                            "LayerHeight",
                            "TileWidth",
                            "TileHeight")) {
                sources.add(style.source(layerRow, field));
            }
            var tileRows = load("UiMapArtTile", 65_536, token, issues);
            var tileData = schemas.get("UiMapArtTile");
            tileData.reference("UiMapArtID", "UiMapArt");
            tileData.reference("FileDataID", "FileData");
            var cells = new HashMap<String, MapTypes.Tile>();
            var conflicts = new HashSet<String>();
            for (var row : tileRows) {
                token.check();
                try {
                    if (!tileData.number(row, "UiMapArtID").equals(artId)
                            || tileData.number(row, "LayerIndex") != 0) {
                        continue;
                    }
                    int r = Math.toIntExact(tileData.number(row, "RowIndex")),
                            c = Math.toIntExact(tileData.number(row, "ColIndex"));
                    long file = tileData.number(row, "FileDataID");
                    validId(file);
                    if (r < 0 || c < 0 || r >= rowCount || c >= columns) {
                        MapTable.issue(
                                issues, "Some art tiles lie outside the declared layer grid.");
                        continue;
                    }
                    String key = r + ":" + c;
                    var tile =
                            new MapTypes.Tile(
                                    row.id(),
                                    file,
                                    r,
                                    c,
                                    c * tileWidth,
                                    r * tileHeight,
                                    Math.min(tileWidth, width - c * tileWidth),
                                    Math.min(tileHeight, height - r * tileHeight));
                    for (String field :
                            List.of(
                                    "UiMapArtID",
                                    "LayerIndex",
                                    "RowIndex",
                                    "ColIndex",
                                    "FileDataID")) {
                        sources.add(tileData.source(row, field));
                    }
                    var previous = cells.get(key);
                    if (conflicts.contains(key)) {
                        continue;
                    }
                    if (previous != null) {
                        MapTable.issue(issues, "Duplicate art tile cells are present.");
                        if (previous.fileDataId() != file) {
                            cells.remove(key);
                            conflicts.add(key);
                        }
                    } else {
                        cells.put(key, tile);
                    }
                } catch (MapTable.Projection
                        | IllegalArgumentException
                        | ArithmeticException failure) {
                    MapTable.issue(issues, "Some art tiles are unreadable.");
                }
            }
            var tiles =
                    cells.values().stream()
                            .sorted(
                                    Comparator.comparingInt(MapTypes.Tile::row)
                                            .thenComparingInt(MapTypes.Tile::column))
                            .toList();
            if (tiles.size() != columns * rowCount) {
                MapTable.issue(
                        issues,
                        "Some grid cells have no unique readable map tile; other tiles remain usable.");
            }
            var result =
                    new MapTypes.Artwork(
                            state(issues),
                            tables.build(),
                            tables.locale(),
                            info,
                            artId,
                            layer,
                            links,
                            tiles,
                            sources,
                            issues,
                            "Phase-zero base artwork; current character phase and map visibility are unevaluated.");
            token.check();
            artwork.put(uiMapId, result);
            return result;
        } catch (MapTable.Projection failure) {
            return failedArt(
                    failure.state,
                    info,
                    links,
                    sources,
                    issues,
                    failure.getMessage(),
                    artId,
                    layer);
        }
    }

    private MapTypes.Artwork failedArt(
            MapTypes.State state,
            MapTypes.MapInfo info,
            List<MapTypes.ArtLink> links,
            List<MapTypes.TableSource> sources,
            List<String> issues,
            String message,
            Long knownArtId,
            MapTypes.Layer knownLayer) {
        return new MapTypes.Artwork(
                state,
                tables.build(),
                tables.locale(),
                info,
                knownArtId,
                knownLayer,
                links,
                List.of(),
                sources,
                issues,
                message);
    }

    private static int dimension(MapTable data, Db2Types.Row row, String field, int maximum) {
        long value = data.number(row, field);
        if (value < 1 || value > maximum) {
            throw data.invalid("Unsupported dimension " + field);
        }
        return (int) value;
    }

    @Override
    public CompletableFuture<MapTypes.TileImage> tile(
            long uiMapId, long tileRowId, Cancellation token) {
        validId(uiMapId);
        validId(tileRowId);
        return submit(
                token,
                request -> {
                    var art = art(uiMapId, request);
                    var tile =
                            art.tiles().stream()
                                    .filter(t -> t.rowId() == tileRowId)
                                    .findFirst()
                                    .orElse(null);
                    if (tile == null) {
                        return new MapTypes.TileImage(
                                MapTypes.State.UNAVAILABLE,
                                null,
                                null,
                                art.issues(),
                                "Tile is not in this readable phase-zero layer.");
                    }
                    if (images == null) {
                        return new MapTypes.TileImage(
                                MapTypes.State.UNAVAILABLE,
                                tile,
                                null,
                                List.of(),
                                "Map image files/cache are unavailable.");
                    }
                    var decoded =
                            images.decodeLargestWithin(
                                            files,
                                            FileTypes.FileRef.id(tile.fileDataId()),
                                            512,
                                            request)
                                    .join();
                    request.check();
                    if (decoded.state() != BlpImageTypes.State.READY || decoded.image() == null) {
                        return new MapTypes.TileImage(
                                MapTypes.State.UNAVAILABLE,
                                tile,
                                null,
                                List.of(),
                                "Map tile: " + decoded.state());
                    }
                    var image = decoded.image();
                    var layer = art.layer();
                    if ((image.width() != layer.tileWidth() && image.width() != tile.visibleWidth())
                            || (image.height() != layer.tileHeight()
                                    && image.height() != tile.visibleHeight())) {
                        return new MapTypes.TileImage(
                                MapTypes.State.UNSUPPORTED,
                                tile,
                                null,
                                List.of(),
                                "Decoded image dimensions do not match the declared tile grid.");
                    }
                    return new MapTypes.TileImage(
                            MapTypes.State.READY, tile, decoded, List.of(), null);
                });
    }

    private List<Db2Types.Row> load(
            String name, int bound, Cancellation token, List<String> issues) {
        var data = schemas.get(name);
        if (data == null) {
            data = MapTable.open(tables, name, token, issues);
            schemas.put(name, data);
        } else if (data.entry.state() == TableTypes.State.PARTIAL) {
            MapTable.issue(issues, name + " is partial; readable rows are retained.");
        }
        var found = rows.get(name);
        if (found == null) {
            found = data.rows(tables, null, 0, bound, token);
            token.check();
            rows.put(name, found);
            if (name.equals("UiMap")) {
                for (var row : found) {
                    mapRows.put(row.id(), row);
                }
            }
        }
        return found;
    }

    private Db2Types.Row mapRow(long id) {
        return mapRows.get(id);
    }

    private MapTypes.MapInfo map(long id, List<String> issues) {
        var row = mapRow(id);
        if (row == null) {
            MapTable.issue(issues, "Some referenced UiMap records are unavailable.");
            return null;
        }
        var data = schemas.get("UiMap");
        String name = null;
        try {
            name = data.text(row, "Name_lang");
        } catch (MapTable.Projection failure) {
            MapTable.issue(issues, "Some map names are unreadable; IDs are retained.");
        }
        if (name == null || name.isBlank()) {
            name = "Map #" + id;
            MapTable.issue(issues, "Some map names are unavailable; IDs are retained.");
        }
        Long parent = optionalInteger(data, row, "ParentUiMapID", issues);
        Long type = optionalInteger(data, row, "Type", issues);
        Long condition = optionalInteger(data, row, "VisibilityPlayerConditionID", issues);
        return new MapTypes.MapInfo(
                id,
                name,
                parent,
                type == null || type < 0 || type > 255 ? null : type.intValue(),
                condition);
    }

    private static Long optionalInteger(
            MapTable data, Db2Types.Row row, String field, List<String> issues) {
        try {
            return data.number(row, field);
        } catch (MapTable.Projection failure) {
            MapTable.issue(issues, failure.getMessage());
            return null;
        }
    }

    private List<MapTypes.TableSource> mapSources(long id, List<String> issues) {
        var row = mapRow(id);
        var data = schemas.get("UiMap");
        var result = new ArrayList<MapTypes.TableSource>();
        for (String field :
                List.of(
                        "Name_lang",
                        "ParentUiMapID",
                        "Type",
                        "System",
                        "VisibilityPlayerConditionID")) {
            try {
                result.add(data.source(row, field));
            } catch (MapTable.Projection failure) {
                MapTable.issue(issues, failure.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static MapTypes.State state(List<String> issues) {
        return issues.isEmpty() ? MapTypes.State.READY : MapTypes.State.PARTIAL;
    }

    private static void validId(long id) {
        if (id < 1 || id > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid map/tile identifier");
        }
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var inner = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Location-map handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var registration = lifetime.onCancel(request::cancel)) {
                                request.check();
                                inner.complete(action.apply(request));
                            } catch (Throwable failure) {
                                inner.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                inner.completeExceptionally(new IllegalStateException("Map queries busy", failure));
            }
        }
        var outer = new CompletableFuture<T>();
        inner.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        outer.complete(value);
                                    } else {
                                        outer.completeExceptionally(failure);
                                    }
                                }));
        return outer;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable failure = null;
        try {
            lifetime.cancel();
        } catch (Throwable error) {
            failure = error;
        }
        var cancellationFailure = failure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        if (cancellationFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancellationFailure);
                        }
                    } catch (Throwable error) {
                        closing.completeExceptionally(error);
                    }
                });
        return closing.copy();
    }
}
