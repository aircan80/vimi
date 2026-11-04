package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Build-scoped lazy world access; all asset reads use the existing build-owned file store. */
public final class WorldService implements BuildWorld {
    public record MapInfo(long id, String name, String directory, Integer type, Long wdtId) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? "Map #" + id : name;
        }
    }

    public record Area(long id, String name, long parentId, long mapId, boolean hierarchyKnown) {
        public Area(long id, String name, long parentId, long mapId) {
            this(id, name, parentId, mapId, true);
        }

        @Override
        public String toString() {
            return name == null || name.isBlank() ? "Area #" + id : name;
        }
    }

    public record Catalog(
            List<MapInfo> maps,
            List<Area> areas,
            List<String> issues,
            WorldCurrentArea.Index areaIndex) {
        public Catalog(List<MapInfo> maps, List<Area> areas, List<String> issues) {
            this(maps, areas, issues, currentAreaIndex(maps, areas, issues));
        }

        public Catalog {
            if (maps.size() > 10000 || areas.size() > 100000 || issues.size() > 1024) {
                throw new IllegalArgumentException("World catalog bounds");
            }
            java.util.Objects.requireNonNull(areaIndex);
            maps = List.copyOf(maps);
            areas = List.copyOf(areas);
            issues = List.copyOf(issues);
        }
    }

    private static WorldCurrentArea.Index currentAreaIndex(
            List<MapInfo> maps, List<Area> areas, List<String> issues) {
        java.util.Objects.requireNonNull(maps);
        java.util.Objects.requireNonNull(areas);
        java.util.Objects.requireNonNull(issues);
        if (maps.size() > 10000 || areas.size() > 100000 || issues.size() > 1024) {
            throw new IllegalArgumentException("World catalog bounds");
        }
        return WorldCurrentArea.Index.of(areas);
    }

    public record Inventory(MapInfo map, WorldFormats.World world, String issue) {}

    public record LoadedTile(
            WorldFormats.Tile tile,
            WorldFormats.Terrain terrain,
            String issue,
            WorldFormats.AreaMetadata areas) {
        public LoadedTile(WorldFormats.Tile tile, WorldFormats.Terrain terrain, String issue) {
            this(tile, terrain, issue, null);
        }
    }

    /** Detached top-left ARGB artwork, bounded to one 256-pixel mip. */
    public record Minimap(int width, int height, java.nio.IntBuffer pixels, String issue) {
        public Minimap {
            if (pixels == null) {
                if (width != 0 || height != 0 || issue == null) {
                    throw new IllegalArgumentException("Missing minimap requires an issue");
                }
            } else {
                if (width < 1
                        || height < 1
                        || width > 256
                        || height > 256
                        || pixels.remaining() != width * height) {
                    throw new IllegalArgumentException("Minimap dimensions outside bounds");
                }
                int[] copy = new int[pixels.remaining()];
                pixels.duplicate().get(copy);
                pixels = java.nio.IntBuffer.wrap(copy).asReadOnlyBuffer();
            }
        }

        @Override
        public java.nio.IntBuffer pixels() {
            return pixels == null ? null : pixels.asReadOnlyBuffer();
        }

        public static Minimap unavailable(String issue) {
            return new Minimap(0, 0, null, java.util.Objects.requireNonNull(issue));
        }
    }

    private final WorldMinimaps minimaps;
    private final BuildTables tables;
    private final BuildFiles files;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().daemon().name("world-data").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Cancellation lifetime = new Cancellation();
    private CompletableFuture<Void> closing;
    private final WorldGeometryCache cache = new WorldGeometryCache();
    private Catalog catalog;
    private WorldAreaNavigation areaNavigation;
    private WorldPlacementSource placements;
    private final TerrainAtlasPool atlasPool;
    private volatile WorldTerrainTextureSource terrainTextures;
    private WorldPreparedTerrain preparedTerrain;
    private WorldTerrainStreaming streamingFacade;

    public WorldService(BuildTables tables, BuildFiles files) {
        this(tables, files, null);
    }

    public WorldService(BuildTables tables, BuildFiles files, TerrainAtlasPool atlasPool) {
        this.atlasPool = atlasPool;
        this.tables = java.util.Objects.requireNonNull(tables);
        this.files = files;
        this.minimaps = new WorldMinimaps(files);
    }

    private WorldGlobalScopes globalCore;
    private WorldGlobalScenes globalFacade;

    @Override
    public synchronized java.util.Optional<WorldGlobalScenes> globalScenes() {
        if (closing != null) {
            throw new IllegalStateException("World workspace closed");
        }
        if (files == null) {
            return java.util.Optional.empty();
        }
        if (globalFacade == null) {
            globalCore = new WorldGlobalScopes(tables, files);
            globalFacade = new WorldGlobalScenes(globalCore);
        }
        return java.util.Optional.of(globalFacade);
    }

    @Override
    public synchronized java.util.Optional<WorldTerrainStreaming> terrainStreaming() {
        if (closing != null) {
            throw new IllegalStateException("World workspace closed");
        }
        if (atlasPool == null || files == null) {
            return java.util.Optional.empty();
        }
        if (streamingFacade == null) {
            streamingFacade = WorldTerrainStreaming.owned(preparedTerrain());
        }
        return java.util.Optional.of(streamingFacade);
    }

    /** Internal source-worker factory; the public closed packet bridge is a separate checkpoint. */
    synchronized WorldPreparedTerrain preparedTerrain() {
        if (closing != null) {
            throw new IllegalStateException("World workspace closed");
        }
        if (atlasPool == null || files == null) {
            throw new UnsupportedOperationException("Configured terrain material source required");
        }
        if (preparedTerrain == null) {
            if (terrainTextures == null) {
                terrainTextures = new WorldTerrainTextureSource(files, atlasPool);
            }
            preparedTerrain =
                    new WorldPreparedTerrain(
                            this, new WorldStreamScopes(tables, files), terrainTextures);
        }
        return preparedTerrain;
    }

    public CompletableFuture<Catalog> catalog(Cancellation token) {
        return submit(
                token,
                cancel -> {
                    if (catalog != null) {
                        return catalog;
                    }
                    var maps = new ArrayList<MapInfo>();
                    var areas = new ArrayList<Area>();
                    var issues = new ArrayList<String>();
                    read(
                            "Map",
                            10000,
                            cancel,
                            issues,
                            (schema, row) -> {
                                String directory = text(schema, row, "Directory");
                                if (directory != null && !directory.matches("[A-Za-z0-9_]+")) {
                                    directory = null;
                                }
                                Long type = number(schema, row, "InstanceType"),
                                        wdt = number(schema, row, "WdtFileDataID");
                                maps.add(
                                        new MapInfo(
                                                row.id(),
                                                text(schema, row, "MapName_lang"),
                                                directory,
                                                type == null ? null : type.intValue(),
                                                wdt));
                            });
                    read(
                            "AreaTable",
                            100000,
                            cancel,
                            issues,
                            (schema, row) -> {
                                Long map = number(schema, row, "ContinentID"),
                                        parent = number(schema, row, "ParentAreaID");
                                if (map != null) {
                                    areas.add(
                                            new Area(
                                                    row.id(),
                                                    text(schema, row, "AreaName_lang"),
                                                    parent == null ? 0 : parent,
                                                    map,
                                                    parent != null));
                                }
                            });
                    catalog = new Catalog(maps, areas, issues);
                    return catalog;
                });
    }

    public CompletableFuture<Inventory> inventory(MapInfo map, Cancellation token) {
        return submit(
                token,
                cancel -> {
                    try {
                        var ref =
                                map.wdtId() != null && map.wdtId() > 0
                                        ? FileTypes.FileRef.id(map.wdtId())
                                        : map.directory() != null
                                                ? FileTypes.FileRef.forPath(
                                                        "world/maps/"
                                                                + map.directory()
                                                                + "/"
                                                                + map.directory()
                                                                + ".wdt")
                                                : null;
                        if (ref == null) {
                            return new Inventory(
                                    map, null, "No readable WDT reference or directory");
                        }
                        return new Inventory(
                                map,
                                WorldFormats.wdt(bytes(ref, cancel), cancel::isCanceled),
                                null);
                    } catch (java.util.concurrent.CancellationException e) {
                        throw e;
                    } catch (RuntimeException failure) {
                        cancel.check();
                        return new Inventory(
                                map, null, "Map terrain inventory unavailable: " + reason(failure));
                    }
                });
    }

    public CompletableFuture<LoadedTile> tile(
            MapInfo map, WorldFormats.Tile tile, Cancellation token) {
        return submit(
                token,
                cancel -> {
                    String key = WorldGeometryCache.key(map.id(), map.directory(), tile);
                    var saved = cache.legacyGet(key);
                    if (saved != null) {
                        return saved;
                    }
                    try {
                        var ref =
                                tile.rootId() > 0
                                        ? FileTypes.FileRef.id(tile.rootId())
                                        : map.directory() != null
                                                ? FileTypes.FileRef.forPath(
                                                        "world/maps/"
                                                                + map.directory()
                                                                + "/"
                                                                + map.directory()
                                                                + "_"
                                                                + tile.x()
                                                                + "_"
                                                                + tile.y()
                                                                + ".adt")
                                                : null;
                        if (ref == null) {
                            return new LoadedTile(tile, null, "No terrain file reference");
                        }
                        var parsed = WorldFormats.adtTile(bytes(ref, cancel), cancel::isCanceled);
                        var loaded = new LoadedTile(tile, parsed.terrain(), null, parsed.areas());
                        cache.legacyPut(key, loaded);
                        return loaded;
                    } catch (java.util.concurrent.CancellationException e) {
                        throw e;
                    } catch (RuntimeException failure) {
                        cancel.check();
                        return new LoadedTile(
                                tile, null, "Tile " + tile.key() + ": " + reason(failure));
                    }
                });
    }

    /** Private source factories call this only inside withRecordedTile on their source worker. */
    WorldGeometryCache.Prepared prepareGeometry(WorldStreamScopes.TileAdmission admission) {
        return cache.prepare(admission);
    }

    @Override
    public CompletableFuture<WorldAreaNavigation.Result> findArea(
            WorldAreaNavigation.Request request,
            Cancellation token,
            java.util.function.Consumer<WorldAreaNavigation.Progress> progress) {
        WorldAreaNavigation navigation;
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("World workspace closed"));
            }
            if (areaNavigation == null) {
                areaNavigation = new WorldAreaNavigation(files);
            }
            navigation = areaNavigation;
        }
        return navigation
                .scan(request, token, progress)
                .thenApply(
                        result -> {
                            synchronized (this) {
                                if (closing == null) {
                                    return result;
                                }
                            }
                            // The closing marker wins delivery even before off-caller lower
                            // cancellation runs.
                            return new WorldAreaNavigation.Result(
                                    WorldAreaNavigation.State.CANCELED,
                                    result.progress(),
                                    result.matches(),
                                    result.issues());
                        });
    }

    @Override
    public CompletableFuture<LoadedTile> areaTile(
            MapInfo map, WorldAreaNavigation.Match match, Cancellation token) {
        return submit(
                token,
                cancel -> {
                    try {
                        if (match.entryChunk() == null
                                || match.entryChunk().entryReference() == null) {
                            throw new IllegalStateException(
                                    "No rendered terrain entry for this match");
                        }
                        var source =
                                WorldFileBytes.read(
                                        files,
                                        WorldFileBytes.reference(map, match.tile()),
                                        cancel,
                                        true);
                        if (!source.contentKey().equals(match.contentKey())) {
                            throw new IllegalStateException(
                                    "Terrain source changed; find terrain again");
                        }
                        var parsed = WorldFormats.adtTile(source.bytes(), cancel::isCanceled);
                        var metadata = parsed.areas();
                        var entry =
                                metadata.chunks().stream()
                                        .filter(
                                                c ->
                                                        c.areaId() == match.entryChunk().areaId()
                                                                && c.entryReference() != null)
                                        .findFirst()
                                        .orElse(null);
                        long count =
                                metadata.chunks().stream()
                                        .filter(c -> c.areaId() == match.entryChunk().areaId())
                                        .count();
                        if (!match.entryChunk().equals(entry) || count != match.matchingChunks()) {
                            throw new IllegalStateException(
                                    "Terrain entry evidence changed; find terrain again");
                        }
                        cancel.check();
                        return new LoadedTile(match.tile(), parsed.terrain(), null, metadata);
                    } catch (java.util.concurrent.CancellationException canceled) {
                        throw canceled;
                    } catch (RuntimeException failure) {
                        cancel.check();
                        return new LoadedTile(match.tile(), null, reason(failure));
                    }
                });
    }

    private byte[] bytes(FileTypes.FileRef ref, Cancellation token) {
        return WorldFileBytes.read(files, ref, token, false).bytes();
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(TableTypes.SchemaResult schema, Db2Types.Row row);
    }

    private void read(
            String table, int bound, Cancellation token, List<String> issues, RowConsumer consume) {
        try {
            var ref = TableTypes.Ref.forName(table);
            var schema = tables.schema(ref, TableTypes.Priority.INTERACTIVE, token).join();
            long after = -1;
            String identity = null;
            int count = 0;
            do {
                token.check();
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                ref, after, 500, List.of(), identity),
                                        TableTypes.Priority.INTERACTIVE,
                                        token)
                                .join();
                for (var row : page.rows()) {
                    if (++count > bound) {
                        throw new IllegalStateException("Table exceeds world metadata bound");
                    }
                    consume.accept(schema, row.row());
                }
                if (page.table() != null
                        && page.table().state() != TableTypes.State.READY
                        && !issues.contains(table + ": " + page.table().state())) {
                    issues.add(table + ": " + page.table().state());
                }
                if (page.nextAfter() == null) {
                    break;
                }
                if (page.nextAfter() <= after) {
                    throw new IllegalStateException("Non-advancing world table cursor");
                }
                after = page.nextAfter();
                identity = page.identity();
            } while (true);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException failure) {
            token.check();
            issues.add(table + ": " + reason(failure));
        }
    }

    static int column(TableTypes.SchemaResult schema, String field, boolean integer) {
        if (schema.schema() == null
                || schema.columns() == null
                || schema.schema().columns().stream()
                        .noneMatch(
                                c ->
                                        c.name().equals(field)
                                                && (c.verifiedName()
                                                        || field.equals("WdtFileDataID")
                                                                && schema.table() != null
                                                                && schema.table()
                                                                        .name()
                                                                        .equals("Map")
                                                                && schema.schema()
                                                                        .layoutHashes()
                                                                        .contains("D43AFAC3")
                                                                && Integer.valueOf(32)
                                                                        .equals(c.bits())
                                                                && Boolean.FALSE.equals(
                                                                        c.unsigned())
                                                                && c.foreignKey() != null
                                                                && c.foreignKey()
                                                                        .table()
                                                                        .equals("FileData")
                                                                && c.foreignKey()
                                                                        .column()
                                                                        .equals("ID"))
                                                && c.arrayLength() == 1
                                                && (integer
                                                        ? c.kind()
                                                                == com.vibeminer.formats.definitions
                                                                        .TableSchema.ColumnKind.INT
                                                        : c.kind()
                                                                        == com.vibeminer.formats
                                                                                .definitions
                                                                                .TableSchema
                                                                                .ColumnKind.STRING
                                                                || c.kind()
                                                                        == com.vibeminer.formats
                                                                                .definitions
                                                                                .TableSchema
                                                                                .ColumnKind
                                                                                .LOCSTRING))) {
            return -1;
        }
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).name().equals(field)
                    && schema.columns().get(i).interpretation() == null) {
                return i;
            }
        }
        return -1;
    }

    private static String text(TableTypes.SchemaResult schema, Db2Types.Row row, String field) {
        int i = column(schema, field, false);
        if (i < 0
                || i >= row.values().size()
                || row.textIssues() != null
                        && row.textIssues().stream().anyMatch(issue -> issue.columnIndex() == i)) {
            return null;
        }
        return row.values().get(i);
    }

    static Long number(TableTypes.SchemaResult schema, Db2Types.Row row, String field) {
        int i = column(schema, field, true);
        if (i < 0 || i >= row.values().size()) {
            return null;
        }
        try {
            return Long.valueOf(row.values().get(i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String reason(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? "Source unavailable" : error.getMessage();
    }

    @SuppressWarnings("try")
    private synchronized <T> CompletableFuture<T> submit(
            Cancellation token, Function<Cancellation, T> action) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World workspace closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        var cancel = new Cancellation();
                        try (var caller = token.onCancel(cancel::cancel);
                                var owner = lifetime.onCancel(cancel::cancel)) {
                            cancel.check();
                            result.complete(action.apply(cancel));
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("World request queue is full", full));
        }
        return result.copy();
    }

    @Override
    public CompletableFuture<Minimap> minimap(WorldFormats.Tile tile, Cancellation token) {
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("World workspace closed"));
            }
        }
        return minimaps.image(
                java.util.Objects.requireNonNull(tile), java.util.Objects.requireNonNull(token));
    }

    @Override
    public synchronized CompletableFuture<WorldPlacementSource.Result> placements(
            MapInfo map, WorldFormats.Tile tile, Cancellation token) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World workspace closed"));
        }
        if (placements == null) {
            placements = new WorldPlacementSource(files);
        }
        return placements.read(map, tile, token);
    }

    @Override
    public synchronized CompletableFuture<WorldTerrainTextureSource.BatchResult> terrainTextures(
            MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World workspace closed"));
        }
        if (atlasPool == null || files == null) {
            return BuildWorld.super.terrainTextures(map, tiles, token, progress);
        }
        if (terrainTextures == null) {
            terrainTextures = new WorldTerrainTextureSource(files, atlasPool);
        }
        return terrainTextures.read(map, tiles, token, progress);
    }

    /** Native-only transition fence; no new source, IO, worker or source close is performed. */
    public CompletableFuture<Void> terrainActivityDrained() {
        var source = terrainTextures;
        return source == null ? CompletableFuture.completedFuture(null) : source.activityDrained();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> completion;
        WorldAreaNavigation navigation;
        WorldPlacementSource placementSource;
        WorldTerrainTextureSource textureSource;
        WorldPreparedTerrain preparedSource;
        WorldGlobalScopes globalSource;
        synchronized (this) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            completion = closing;
            cache.invalidate();
            navigation = areaNavigation;
            placementSource = placements;
            textureSource = terrainTextures;
            preparedSource = preparedTerrain;
            globalSource = globalCore;
            if (globalFacade != null) {
                globalFacade.invalidate();
            }
            if (preparedSource != null) {
                preparedSource.invalidate();
            }
            if (streamingFacade != null) {
                streamingFacade.invalidate();
            }
        }
        Thread.startVirtualThread(
                () -> {
                    Throwable failure = null;
                    worker.shutdown();
                    try {
                        lifetime.cancel();
                    } catch (RuntimeException | Error error) {
                        failure = error;
                    }
                    var drains = new ArrayList<CompletableFuture<Void>>();
                    if (globalSource != null) {
                        try {
                            globalSource.closeAsync();
                        } catch (RuntimeException | Error error) {
                            failure = appendCloseFailure(failure, error);
                        } finally {
                            drains.add(globalSource.whenClosed());
                        }
                    }
                    var closes =
                            List.<java.util.function.Supplier<CompletableFuture<Void>>>of(
                                    minimaps::closeAsync,
                                    () ->
                                            navigation == null
                                                    ? CompletableFuture.completedFuture(null)
                                                    : navigation.closeAsync(),
                                    () ->
                                            placementSource == null
                                                    ? CompletableFuture.completedFuture(null)
                                                    : placementSource.closeAsync(),
                                    () ->
                                            textureSource == null
                                                    ? CompletableFuture.completedFuture(null)
                                                    : textureSource.closeAsync(),
                                    () ->
                                            preparedSource == null
                                                    ? CompletableFuture.completedFuture(null)
                                                    : preparedSource.closeAsync(),
                                    cache::closeAsync);
                    for (var close : closes) {
                        try {
                            drains.add(close.get());
                        } catch (RuntimeException | Error error) {
                            failure = appendCloseFailure(failure, error);
                        }
                    }
                    boolean interrupted = false;
                    while (!worker.isTerminated()) {
                        try {
                            worker.awaitTermination(1, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            interrupted = true;
                            failure = appendCloseFailure(failure, error);
                        }
                    }
                    try {
                        CompletableFuture.allOf(drains.toArray(CompletableFuture[]::new)).join();
                    } catch (RuntimeException | Error error) {
                        failure = appendCloseFailure(failure, error);
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (failure == null) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
        return completion.copy();
    }

    private static Throwable appendCloseFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
