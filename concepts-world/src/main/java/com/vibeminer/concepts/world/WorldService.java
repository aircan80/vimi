package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public record Area(long id, String name, long parentId, long mapId) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? "Area #" + id : name;
        }
    }

    public record Catalog(List<MapInfo> maps, List<Area> areas, List<String> issues) {
        public Catalog {
            maps = List.copyOf(maps);
            areas = List.copyOf(areas);
            issues = List.copyOf(issues);
        }
    }

    public record Inventory(MapInfo map, WorldFormats.World world, String issue) {}

    public record LoadedTile(WorldFormats.Tile tile, WorldFormats.Terrain terrain, String issue) {}

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
    private final Map<String, LoadedTile> cache = new LinkedHashMap<>(16, .75f, true);
    private Catalog catalog;
    private WorldAreaNavigation areaNavigation;

    public WorldService(BuildTables tables, BuildFiles files) {
        this.tables = java.util.Objects.requireNonNull(tables);
        this.files = files;
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
                                                    map));
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
                    String key =
                            map.id()
                                    + ":"
                                    + map.directory()
                                    + ":"
                                    + tile.key()
                                    + ":"
                                    + tile.rootId();
                    var saved = cache.get(key);
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
                        var loaded =
                                new LoadedTile(
                                        tile,
                                        WorldFormats.adt(bytes(ref, cancel), cancel::isCanceled),
                                        null);
                        cache.put(key, loaded);
                        while (cache.size() > 12) {
                            cache.remove(cache.keySet().iterator().next());
                        }
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
        return navigation.scan(request, token, progress);
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

    private static int column(TableTypes.SchemaResult schema, String field, boolean integer) {
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

    private static Long number(TableTypes.SchemaResult schema, Db2Types.Row row, String field) {
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

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        lifetime.cancel();
        worker.shutdown();
        var areasClosed =
                areaNavigation == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : areaNavigation.closeAsync();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        areasClosed.join();
                        cache.clear();
                        closing.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(e);
                    } catch (RuntimeException failure) {
                        closing.completeExceptionally(failure);
                    }
                });
        return closing.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
