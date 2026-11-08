package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldServiceTest {
    @Test
    void partialMetadataStaysColdAndReviewedWdtNameAdmissionIsNarrow() {
        var opens = new AtomicInteger();
        try (var service = new WorldService(tables("D43AFAC3"), files(opens, false))) {
            var catalog = service.catalog(Cancellation.NONE).join();
            assertEquals(1, catalog.maps().size());
            assertEquals("Authored", catalog.maps().getFirst().name());
            assertEquals(51, catalog.maps().getFirst().wdtId());
            assertEquals(0, opens.get());
            assertTrue(catalog.issues().stream().anyMatch(s -> s.contains("PARTIAL")));
        }
        try (var service = new WorldService(tables("OTHER"), files(opens, false))) {
            assertNull(service.catalog(Cancellation.NONE).join().maps().getFirst().wdtId());
        }
    }

    @Test
    void readsBoundedTileStreamsCachesTerrainAndDoesNotCloseBorrowedHandles() {
        var opens = new AtomicInteger();
        var service = new WorldService(tables("D43AFAC3"), files(opens, false));
        var map = service.catalog(Cancellation.NONE).join().maps().getFirst();
        var location = new WorldFormats.Tile(1, 2, 81, 0, 0);
        var a = service.tile(map, location, Cancellation.NONE).join();
        var b = service.tile(map, location, Cancellation.NONE).join();
        assertEquals(1, opens.get());
        assertTrue(a.terrain() != null && b.terrain() != null);
        assertEquals(1, a.terrain().chunks());
        service.closeAsync().join();
        assertTrue(service.catalog(Cancellation.NONE).isCompletedExceptionally());
    }

    @Test
    void missingFilesDoNotHideReadableMapMetadata() {
        try (var service = new WorldService(tables("D43AFAC3"), null)) {
            var map = service.catalog(Cancellation.NONE).join().maps().getFirst();
            assertEquals("Authored", map.name());
            assertNull(service.inventory(map, Cancellation.NONE).join().world());
        }
    }

    @Test
    void protectedTileDoesNotFabricateTerrain() {
        var opens = new AtomicInteger();
        try (var service = new WorldService(tables("D43AFAC3"), files(opens, true))) {
            var map = service.catalog(Cancellation.NONE).join().maps().getFirst();
            var result =
                    service.tile(map, new WorldFormats.Tile(1, 2, 81, 0, 0), Cancellation.NONE)
                            .join();
            assertNull(result.terrain());
            assertTrue(result.issue().contains("protected"));
        }
    }

    @Test
    void mapLayoutWithoutWdtFieldResolvesTheCatalogWdtThroughTheDirectoryPath() {
        var opens = new AtomicInteger();
        var paths = new java.util.ArrayList<String>();
        try (var service =
                new WorldService(tables("B47C6F05", false), files(opens, false, paths::add))) {
            var map = service.catalog(Cancellation.NONE).join().maps().getFirst();
            assertEquals(81, map.wdtId());
            assertEquals(List.of("world/maps/Authored/Authored.wdt"), paths);
            assertEquals(0, opens.get(), "Resolving the name never opens the WDT");
        }
        paths.clear();
        // An unreviewed layout without a WDT field keeps no WDT ID and resolves nothing.
        try (var service =
                new WorldService(tables("OTHER", false), files(opens, false, paths::add))) {
            assertNull(service.catalog(Cancellation.NONE).join().maps().getFirst().wdtId());
            assertTrue(paths.isEmpty());
        }
        // A layout with a WDT field never uses the directory, even when that field is unusable.
        try (var service =
                new WorldService(tables("B47C6F05", true), files(opens, false, paths::add))) {
            assertNull(service.catalog(Cancellation.NONE).join().maps().getFirst().wdtId());
            assertTrue(paths.isEmpty());
        }
    }

    @Test
    void catalogNeverBuildsADirectoryWdtPathFromAnUnsafeDirectory() {
        var opens = new AtomicInteger();
        var paths = new java.util.ArrayList<String>();
        for (String unsafe : List.of("", "..", "a/b", "..\\Azeroth", "a".repeat(65))) {
            try (var service =
                    new WorldService(
                            tables("B47C6F05", false, unsafe), files(opens, false, paths::add))) {
                var map = service.catalog(Cancellation.NONE).join().maps().getFirst();
                assertNull(map.wdtId(), unsafe);
            }
        }
        assertTrue(paths.isEmpty(), paths.toString());
        try (var service =
                new WorldService(
                        tables("B47C6F05", false, "a".repeat(64)),
                        files(opens, false, paths::add))) {
            assertEquals(81, service.catalog(Cancellation.NONE).join().maps().getFirst().wdtId());
            assertEquals(
                    List.of("world/maps/" + "a".repeat(64) + "/" + "a".repeat(64) + ".wdt"), paths);
        }
    }

    private static BuildTables tables(String layout) {
        return tables(layout, true);
    }

    private static BuildTables tables(String layout, boolean wdtField) {
        return tables(layout, wdtField, "Authored");
    }

    private static BuildTables tables(String layout, boolean wdtField, String directory) {
        var names =
                wdtField
                        ? List.of("Directory", "MapName_lang", "InstanceType", "WdtFileDataID")
                        : List.of("Directory", "MapName_lang", "InstanceType");
        var columns =
                names.stream()
                        .map(
                                n ->
                                        new Db2Types.Column(
                                                n,
                                                n.contains("lang") || n.equals("Directory")
                                                        ? "string"
                                                        : "integer",
                                                false,
                                                null,
                                                null))
                        .toList();
        var fields =
                names.stream()
                        .map(
                                n -> {
                                    boolean text = n.contains("lang") || n.equals("Directory"),
                                            wdt = n.equals("WdtFileDataID");
                                    return new TableSchema.SchemaColumn(
                                            n,
                                            text
                                                    ? TableSchema.ColumnKind.STRING
                                                    : TableSchema.ColumnKind.INT,
                                            text ? null : 32,
                                            text ? null : false,
                                            1,
                                            wdt
                                                    ? new TableSchema.ForeignKey("FileData", "ID")
                                                    : null,
                                            TableSchema.KeyStorage.NONE,
                                            TableSchema.KeyStorage.NONE,
                                            !wdt,
                                            WowLocale.EN_US,
                                            null,
                                            null);
                                })
                        .toList();
        var schema =
                new TableSchema(
                        fields,
                        List.of(layout),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.EN_US,
                        null);
        var entry =
                new TableTypes.Entry(
                        1,
                        "Map",
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(2, 1, 1, 1, "WDC5", layout),
                        null);
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("schema")) {
                                return CompletableFuture.completedFuture(
                                        new TableTypes.SchemaResult(entry, columns, schema));
                            }
                            if (method.getName().equals("page")) {
                                var request = (TableTypes.PageRequest) args[0];
                                boolean map = request.table().name().equals("Map");
                                return CompletableFuture.completedFuture(
                                        new TableTypes.Page(
                                                entry,
                                                TableTypes.PageState.OK,
                                                map
                                                        ? List.of(
                                                                new TableTypes.Row(
                                                                        0,
                                                                        new Db2Types.Row(
                                                                                0,
                                                                                false,
                                                                                null,
                                                                                List.of(
                                                                                                directory,
                                                                                                "Authored",
                                                                                                "0",
                                                                                                "51")
                                                                                        .subList(
                                                                                                0,
                                                                                                names
                                                                                                        .size()),
                                                                                List.of())))
                                                        : List.of(),
                                                null,
                                                "test",
                                                1,
                                                null,
                                                null));
                            }
                            throw new AssertionError(
                                    "Borrowed tables remain open: " + method.getName());
                        });
    }

    private static BuildFiles files(AtomicInteger opens, boolean protectedFile) {
        return files(opens, protectedFile, path -> {});
    }

    private static BuildFiles files(
            AtomicInteger opens,
            boolean protectedFile,
            java.util.function.Consumer<String> pathLookups) {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) -> {
                            var data = adt();
                            if (method.getName().equals("stat")
                                    || method.getName().equals("statContent")) {
                                var ref = (FileTypes.FileRef) args[0];
                                if (ref.path() != null) {
                                    pathLookups.accept(ref.path());
                                }
                                return CompletableFuture.completedFuture(
                                        new FileTypes.FileStat(
                                                FileTypes.FileLookupState.FOUND,
                                                new FileTypes.BuildFile(
                                                        81,
                                                        null,
                                                        FileTypes.FilePathSource.NONE,
                                                        (long) data.length,
                                                        "key",
                                                        FileTypes.FileFormat.ADT_ROOT,
                                                        FileTypes.FileFormatSource.MAGIC,
                                                        FileTypes.FileStorage.STORED,
                                                        false)));
                            }
                            if (method.getName().equals("openRead")) {
                                opens.incrementAndGet();
                                var stream =
                                        new FileReadStream() {
                                            int position;
                                            boolean closed;

                                            public long length() {
                                                return data.length;
                                            }

                                            public long position() {
                                                return position;
                                            }

                                            public void seek(long p) {
                                                position = (int) p;
                                            }

                                            public List<FileTypes.FileHole> holes() {
                                                return protectedFile
                                                        ? List.of(
                                                                new FileTypes.FileHole(1, 1, "key"))
                                                        : List.of();
                                            }

                                            public CompletableFuture<Integer> read(
                                                    ByteBuffer target, Cancellation t) {
                                                t.check();
                                                assertFalse(closed);
                                                int n =
                                                        Math.min(
                                                                target.remaining(),
                                                                data.length - position);
                                                target.put(data, position, n);
                                                position += n;
                                                return CompletableFuture.completedFuture(n);
                                            }

                                            public void close() {
                                                closed = true;
                                            }
                                        };
                                return CompletableFuture.completedFuture(
                                        new FileTypes.FileOpen(
                                                FileTypes.FileReadState.COMPLETE,
                                                stream,
                                                null,
                                                null,
                                                null));
                            }
                            throw new AssertionError(
                                    "Borrowed files remain open: " + method.getName());
                        });
    }

    private static byte[] adt() {
        var b = ByteBuffer.allocate(12 + 8 + 128 + 8 + 145 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4d564552).putInt(4).putInt(18).putInt(0x4d434e4b).putInt(128 + 8 + 145 * 4);
        b.position(20 + 128);
        b.putInt(0x4d435654).putInt(145 * 4);
        return b.array();
    }
}
