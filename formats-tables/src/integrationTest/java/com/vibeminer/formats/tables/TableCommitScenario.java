package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileSystemOptions;
import com.vibeminer.files.FilesScenario;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.GeneratedCommitGroup;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.PackGeneratedStore;
import com.vibeminer.generated.RecordFileOptions;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.PackLibraryOwner;
import com.vibeminer.store.StoreTypes;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.WriteProbes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * A real unified pack library (the production generated-table store) holding encoded DB2 sources
 * for a few native tables, plus helpers that classify each table's saved graph after a crash.
 */
final class TableCommitScenario implements AutoCloseable {
    static final List<Long> IDS = List.of(100L, 200L, 300L, 400L);
    static final String INVENTORY = "fixture-table-commit-inventory";
    private static final String OWNER = "build-a";

    /** One observed write point of a generated object. */
    record Event(String point, GeneratedKey key) {}

    /**
     * Store bounds. DEFAULT is production. The others give table 100 many rows and one record
     * segment per row, then shrink one bound so its single put is forced to split into several
     * durable commits, each at a different layer.
     */
    enum Config {
        DEFAULT(0, 0, 0, 0, 0, 0),
        /** The store's own pending batch is full (PendingWrites, 128 entries). */
        STORE_BATCH(300, 0, 0, 1L << 30, GeneratedTypes.MAX_BATCH, 0),
        /** The active pack is full and the store rolls over to a new one. */
        PACK_ROLLOVER(30, 2048, 0, 0, 0, 0),
        /** PackGeneratedStore puts the batch as several parts. */
        STORE_PART(30, 0, 256, 0, 0, 0),
        /** The commit group's byte bound. */
        GROUP_BYTES(30, 0, 0, 256, 0, 0),
        /** The commit group's object bound. */
        GROUP_OBJECTS(30, 0, 0, 0, 8, 0),
        /** The collected-bytes budget shared by all of the instance's groups. */
        BUDGET(30, 0, 0, 0, 0, 256);

        final int rows;
        final long packBytes, partBytes, groupBytes, collectedBytes;
        final int groupObjects;

        Config(
                int rows,
                long packBytes,
                long partBytes,
                long groupBytes,
                int groupObjects,
                long collectedBytes) {
            this.rows = rows;
            this.packBytes = packBytes;
            this.partBytes = partBytes;
            this.groupBytes = groupBytes;
            this.groupObjects = groupObjects;
            this.collectedBytes = collectedBytes;
        }

        GeneratedTables tables(GeneratedStore store) {
            if (this == DEFAULT) {
                return new GeneratedTables(store);
            }
            // One block and one segment per record, so a table writes one object per row.
            return new GeneratedTables(
                    store,
                    new RecordFileOptions(4 << 20, 16 << 20, 16 << 20, 1, 1, 9),
                    groupBytes > 0 ? groupBytes : GeneratedCommitGroup.MAX_BYTES,
                    groupObjects > 0 ? groupObjects : GeneratedCommitGroup.MAX_OBJECTS,
                    collectedBytes > 0 ? collectedBytes : GeneratedTables.COLLECTED_BYTES);
        }
    }

    /** The outcome of a clean cold preparation, which every recovered library must reach. */
    record Reference(
            List<TableTypes.Entry> ledger,
            Map<Long, String> identities,
            Map<Long, List<List<String>>> rows,
            Map<Long, Set<GeneratedKey>> objects,
            Map<Long, List<Event>> traces) {}

    final ObjectStore library;
    final LibraryCatalog catalog;
    final PackLibraryOwner original;
    final PackGeneratedStore generated;
    final CachingObjectSource access;
    final FilesScenario files;
    final Tables service;
    final GeneratedTables tableStore;
    final Config config;
    private BuildTables handle;

    private TableCommitScenario(Path folder, Config config) {
        this.config = config;
        library =
                config.packBytes > 0
                        ? ObjectStore.open(
                                        folder,
                                        new StoreTypes.Options(
                                                StoreTypes.Kind.LIBRARY,
                                                config.packBytes,
                                                java.time.Clock.systemUTC()))
                                .join()
                        : ObjectStore.openLibrary(folder).join();
        catalog = LibraryCatalog.open(library.databasePath()).join();
        original =
                library.forOwner(
                        OWNER, LibraryCatalog.Scope.BUILD, "source", LibraryCatalog.Role.ORIGINAL);
        var derived =
                library.forOwner(
                        OWNER, LibraryCatalog.Scope.BUILD, "tables", LibraryCatalog.Role.DERIVED);
        generated =
                config.partBytes > 0
                        ? new PackGeneratedStore(library, derived, config.partBytes)
                        : new PackGeneratedStore(library, derived);
        tableStore = config.tables(generated);
        access = CachingObjectSource.localOnly(original, original);
        files = new FilesScenario(false, FileSystemOptions.defaults(), ignored -> access);
        var inventory = new ArrayList<BundledDefinitions.DefinitionTable>();
        for (long id : IDS) {
            files.add(id, "dbfilesclient/t" + id + ".db2", db2(id, config), false, List.of());
            inventory.add(
                    new BundledDefinitions.DefinitionTable(
                            "11223344", "T" + id, Long.toString(id), true));
        }
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        service =
                new TablesService(
                        files.files,
                        new TableDefinitions(
                                BundledDefinitions.of(
                                        inventory,
                                        Map.of(
                                                "11223344",
                                                new BundledDefinitions.Definition(
                                                        "a".repeat(64), "fixture", definition))),
                                null,
                                null),
                        tableStore,
                        new TablesOptions(
                                        (build, token) ->
                                                CompletableFuture.completedFuture("1.2.3.4"))
                                .withLocalDefinitionsOnly());
    }

    static TableCommitScenario open(Path folder) {
        return open(folder, Config.DEFAULT);
    }

    static TableCommitScenario open(Path folder, Config config) {
        return new TableCommitScenario(folder.toAbsolutePath(), config);
    }

    /** Each table has its own content, so its own content key, node and segment. */
    static byte[] db2(long id, Config config) {
        return id == 100 && config.rows > 0
                ? rows(id, config.rows)
                : Db2Fixture.numeric("WDC5", 0, 32, id, new long[0], true);
    }

    /**
     * Db2Fixture.numeric's one-section WDC5 layout (an inline 32-bit Value and a non-inline ID
     * list) with {@code count} records: IDs 1..count, values from {@code first}.
     */
    static byte[] rows(long first, int count) {
        int header = 204, at = header - 68, start = header + 40 + 28;
        var b = ByteBuffer.allocate(start + 12 * count).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("WDC5".getBytes(StandardCharsets.US_ASCII)).putInt(5);
        b.putInt(at, count);
        b.putInt(at + 4, 1);
        b.putInt(at + 8, 8);
        b.putInt(at + 16, 0x11223344);
        b.putInt(at + 20, 0x01020304);
        b.putShort(at + 36, (short) 4);
        b.putInt(at + 40, 1);
        b.putInt(at + 52, 24);
        b.putInt(at + 64, 1);
        b.position(header);
        b.putLong(0).putInt(start).putInt(count).putInt(0).putInt(0).putInt(4 * count);
        b.putInt(0).putInt(0).putInt(0);
        b.putShort((short) 0).putShort((short) 0);
        b.putShort((short) 0).putShort((short) 32).putInt(0).putInt(0).putInt(0).putInt(0);
        b.putInt(0);
        b.position(start);
        for (int i = 0; i < count; i++) {
            b.putLong(first * 1000 + i);
        }
        for (int i = 0; i < count; i++) {
            b.putInt(i + 1);
        }
        return b.array();
    }

    static GeneratedKey manifest(GeneratedKey node) {
        return GeneratedKey.hash(
                ByteBuffer.allocate(21)
                        .put("table".getBytes(StandardCharsets.US_ASCII))
                        .put(node.bytes())
                        .array());
    }

    /** Stores the encoded sources; nothing generated exists yet. */
    TableCommitScenario seed() {
        for (long id : IDS) {
            var encoded = files.encoded.get(id);
            original.put(
                            List.of(
                                    new PutObject(
                                            new ObjectKey(ObjectKey.ENCODED, encoded.key().bytes()),
                                            encoded.bytes())))
                    .join();
        }
        return this;
    }

    BuildTables tables() {
        if (handle == null) {
            handle = service.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().tables();
        }
        return handle;
    }

    TableTypes.Entry check(long id, Cancellation token) {
        return tables().check(TableTypes.Ref.id(id), TableTypes.Priority.BACKGROUND, token).join();
    }

    /** Observes every write point of a generated object; other objects are ignored. */
    void probe(BiConsumer<String, GeneratedKey> probe) {
        WriteProbes.install(
                library,
                probe == null
                        ? null
                        : (point, key) -> {
                            if (key.type() == ObjectKey.GENERATED) {
                                probe.accept(point, new GeneratedKey(key.bytes()));
                            }
                        });
    }

    /** The previous complete state: two prepared tables and a saved inventory naming them. */
    void preparePrevious() {
        var entries = new ArrayList<TableTypes.Entry>();
        for (long id : List.of(300L, 400L)) {
            var entry = check(id, Cancellation.NONE);
            assertEquals(TableTypes.State.READY, entry.state());
            entries.add(entry);
        }
        var progress =
                new TableTypes.PreloadProgress(
                        TableTypes.PreloadStage.PREPARING, 0, 0, 0, 0, 0, 0, 2, 2, 0);
        new PreparedTablesCache(generated)
                .save(
                        INVENTORY,
                        new TableTypes.PreloadResult(
                                TableTypes.PreloadOutcome.COMPLETED,
                                progress,
                                entries,
                                com.vibeminer.files.FileTypes.FilesOpenState.OPEN,
                                "FIXTURE",
                                "Previous preparation"),
                        Cancellation.NONE)
                .join();
    }

    /**
     * A clean cold run: the generated objects each table's preparation writes, in write order, then
     * the full known-table ledger, identities and rows.
     */
    Reference reference() {
        var objects = new LinkedHashMap<Long, Set<GeneratedKey>>();
        var traces = new LinkedHashMap<Long, List<Event>>();
        for (long id : IDS) {
            var written = new LinkedHashSet<GeneratedKey>();
            var trace = java.util.Collections.synchronizedList(new ArrayList<Event>());
            probe(
                    (point, key) -> {
                        trace.add(new Event(point, key));
                        if (point.equals("ENTRY_STARTED")) {
                            written.add(key);
                        }
                    });
            assertEquals(TableTypes.State.READY, check(id, Cancellation.NONE).state());
            objects.put(id, Set.copyOf(written));
            traces.put(id, List.copyOf(trace));
        }
        probe(null);
        var ledger = tables().prepareKnown(null, Cancellation.NONE).join();
        assertEquals(TableTypes.PreloadOutcome.COMPLETED, ledger.outcome());
        var identities = new LinkedHashMap<Long, String>();
        var rows = new LinkedHashMap<Long, List<List<String>>>();
        for (long id : IDS) {
            var page = page(id);
            identities.put(id, page.identity());
            rows.put(id, values(page));
        }
        return new Reference(ledger.tables(), identities, rows, objects, traces);
    }

    TableTypes.Page page(long id) {
        var page =
                tables().page(
                                new TableTypes.PageRequest(TableTypes.Ref.id(id)),
                                TableTypes.Priority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertEquals(TableTypes.PageState.OK, page.state(), page.message());
        return page;
    }

    static List<List<String>> values(TableTypes.Page page) {
        return page.rows().stream().map(row -> row.row().values()).toList();
    }

    /**
     * Waits for the store write in progress. A canceled caller returns before the abandoned build
     * finishes its put; the store's single writer runs this no-op delete after that put.
     */
    void settle() {
        generated
                .delete(List.of(GeneratedKey.hash("settle".getBytes(StandardCharsets.US_ASCII))))
                .join();
    }

    /** Which of a table's objects the index exposes, by a local index lookup (no payload read). */
    Set<GeneratedKey> present(Set<GeneratedKey> keys) {
        var lengths =
                generated
                        .storedLengths(List.copyOf(keys), com.vibeminer.generated.Cancellation.NONE)
                        .join();
        var present = new LinkedHashSet<GeneratedKey>();
        var ordered = List.copyOf(keys);
        for (int i = 0; i < ordered.size(); i++) {
            if (lengths.get(i) >= 0) {
                present.add(ordered.get(i));
            }
        }
        return present;
    }

    /**
     * True when the table's graph is complete, false when its node is absent. Fails on any other
     * state: a node or manifest that points at a missing, partial or corrupt object.
     */
    boolean graph(Reference reference, long id) {
        var node = GeneratedKey.parse(reference.identities().get(id));
        var read = generated.read(node).join();
        if (read.state() == GeneratedTypes.ReadState.ABSENT) {
            var capture =
                    tables().capturePreparedLocal(TableTypes.Ref.id(id), Cancellation.NONE).join();
            assertEquals(CapturedRowsTypes.CaptureState.NOT_PREPARED, capture.state(), "" + id);
            return false;
        }
        assertEquals(GeneratedTypes.ReadState.PRESENT, read.state(), "node of " + id);
        var artifacts = tables().savedArtifacts(TableTypes.Ref.id(id), Cancellation.NONE).join();
        assertTrue(artifacts.contains(node), "inventory of " + id + " names its node");
        assertTrue(
                artifacts.contains(manifest(node)), "inventory of " + id + " names its manifest");
        assertTrue(
                artifacts.containsAll(reference.objects().get(id)),
                "inventory of " + id + " names every object its preparation writes");
        for (var key : artifacts) {
            assertEquals(
                    GeneratedTypes.ReadState.PRESENT,
                    generated.read(key).join().state(),
                    "object " + key + " of " + id);
        }
        var capture =
                tables().capturePreparedLocal(TableTypes.Ref.id(id), Cancellation.NONE).join();
        assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, capture.state(), capture.code());
        assertNotNull(capture.rows());
        capture.rows().close();
        return true;
    }

    /** Later passes classify the recovered library and regenerate whatever is missing. */
    void assertRegenerates(Reference reference, boolean inventorySaved) {
        var cache = new PreparedTablesCache(generated);
        if (inventorySaved) {
            var restored = cache.restore(INVENTORY, null, Cancellation.NONE).join();
            assertNotNull(restored, "the saved inventory survives");
            var verified = cache.restore(INVENTORY, tables(), null, Cancellation.NONE).join();
            assertNotNull(verified, "the previous tables still verify against their graphs");
            for (var entry : verified.tables()) {
                assertTrue(reference.ledger().contains(entry), "" + entry);
            }
        }
        var pass = tables().prepareKnown(null, Cancellation.NONE).join();
        assertEquals(TableTypes.PreloadOutcome.COMPLETED, pass.outcome());
        assertEquals(reference.ledger(), pass.tables());
        for (long id : IDS) {
            assertTrue(graph(reference, id), "" + id);
            var page = page(id);
            assertEquals(reference.identities().get(id), page.identity());
            assertEquals(reference.rows().get(id), values(page));
        }
        assertEquals(
                0,
                library.verify(true, com.vibeminer.store.Cancellation.NONE).join().corruptCount());
        assertEquals(0, tableStore.collectedBytes(), "no commit group keeps collected bytes");
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Runnable step :
                List.<Runnable>of(
                        () -> {
                            if (handle != null) {
                                try {
                                    handle.close();
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                        },
                        () -> access.closeAsync().join(),
                        () -> catalog.closeAsync().join(),
                        () -> library.closeAsync().join())) {
            try {
                step.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
