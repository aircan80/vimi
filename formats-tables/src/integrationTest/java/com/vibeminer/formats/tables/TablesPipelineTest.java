package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileSystemOptions;
import com.vibeminer.files.FilesScenario;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real disk object -> access -> Files -> DB2 -> durable generated table -> query pipeline. */
@Timeout(30)
final class TablesPipelineTest {
    @TempDir Path temporary;

    @Test
    void inventoryReopensWithoutEncodedSourceAndMissingGraphRepairsOnAccess() {
        pipeline(false);
    }

    @Test
    void unifiedGraphRestorationAttachesAllDependenciesBeforeAnyRowQuery() {
        pipeline(true);
    }

    private void pipeline(boolean unified) {
        byte[] db2 = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var document =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        BundledDefinitions provider =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Numbers", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", document)));
        String identity = null;
        for (int round = 0; round < 3; round++) {
            ObjectStore objects =
                    unified
                            ? ObjectStore.openLibrary(temporary.resolve("library")).join()
                            : ObjectStore.open(temporary.resolve("objects")).join();
            var catalog =
                    unified
                            ? com.vibeminer.store.LibraryCatalog.open(objects.databasePath()).join()
                            : null;
            String owner = round == 0 ? "build-a" : "build-b";
            var original =
                    unified
                            ? objects.forOwner(
                                    owner,
                                    com.vibeminer.store.LibraryCatalog.Scope.BUILD,
                                    "source",
                                    com.vibeminer.store.LibraryCatalog.Role.ORIGINAL)
                            : null;
            com.vibeminer.generated.GeneratedStore generated =
                    unified
                            ? new com.vibeminer.generated.PackGeneratedStore(
                                    objects,
                                    objects.forOwner(
                                            owner,
                                            com.vibeminer.store.LibraryCatalog.Scope.BUILD,
                                            "tables",
                                            com.vibeminer.store.LibraryCatalog.Role.DERIVED))
                            : DiskGeneratedStore.open(temporary.resolve("generated")).join();
            CachingObjectSource access =
                    unified
                            ? CachingObjectSource.localOnly(original, original)
                            : CachingObjectSource.localOnly(objects, objects);
            try {
                FilesScenario fixture =
                        new FilesScenario(false, FileSystemOptions.defaults(), ignored -> access)
                                .add(100, "dbfilesclient/numbers.db2", db2, false, List.of());
                var encoded = fixture.encoded.get(100L);
                ObjectKey key = new ObjectKey(ObjectKey.ENCODED, encoded.key().bytes());
                if (round != 1) {
                    if (unified) {
                        original.put(List.of(new PutObject(key, encoded.bytes()))).join();
                    } else {
                        objects.put(List.of(new PutObject(key, encoded.bytes()))).join();
                    }
                } else {
                    // No decoded-file cache exists: successful queries must use generated rows.
                    objects.delete(List.of(key)).join();
                    assertEquals(false, objects.has(key).join());
                }
                if (round == 2) {
                    // The saved assessment survives. Only the requested graph needs repair,
                    // using its encoded source locally, without a full inventory pass.
                    var manifest =
                            com.vibeminer.generated.GeneratedKey.hash(
                                    java.nio.ByteBuffer.allocate(21)
                                            .put(
                                                    "table"
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .US_ASCII))
                                            .put(
                                                    com.vibeminer.generated.GeneratedKey.parse(
                                                                    identity)
                                                            .bytes())
                                            .array());
                    generated.delete(List.of(manifest)).join();
                }
                Tables service =
                        new TablesService(
                                fixture.files,
                                new TableDefinitions(provider, null, null),
                                new GeneratedTables(generated),
                                new TablesOptions(
                                        (build, token) ->
                                                CompletableFuture.completedFuture("1.2.3.4")));
                var open = service.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join();
                assertNotNull(open.tables());
                try (var tables = open.tables()) {
                    var inventory = new PreparedTablesCache(generated);
                    if (round == 0) {
                        inventory
                                .save(
                                        "fixture-build-locale-policy",
                                        tables.prepareKnown(null, Cancellation.NONE).join(),
                                        Cancellation.NONE)
                                .join();
                    } else {
                        var restored =
                                inventory
                                        .restore(
                                                "fixture-build-locale-policy",
                                                null,
                                                Cancellation.NONE)
                                        .join();
                        assertNotNull(restored);
                        assertEquals(TableTypes.State.READY, restored.tables().getFirst().state());
                    }
                    var checked =
                            tables.check(
                                            TableTypes.Ref.id(100),
                                            TableTypes.Priority.BACKGROUND,
                                            Cancellation.NONE)
                                    .join();
                    assertEquals(
                            TableTypes.State.READY,
                            checked.state(),
                            checked.reason() == null ? null : checked.reason().message());
                    var schema =
                            tables.schema(
                                            TableTypes.Ref.id(100),
                                            TableTypes.Priority.INTERACTIVE,
                                            Cancellation.NONE)
                                    .join();
                    assertEquals("Value", schema.columns().get(1).name());
                    if (unified && round == 1) {
                        var first =
                                catalog.figures("build-a", com.vibeminer.store.Cancellation.NONE)
                                        .join()
                                        .derived();
                        var current =
                                catalog.figures(owner, com.vibeminer.store.Cancellation.NONE)
                                        .join()
                                        .derived();
                        assertEquals(
                                true,
                                first.storedObjects() >= 4,
                                "Inventory, node, manifest and segment are distinct payloads");
                        assertEquals(
                                first.storedObjects(),
                                current.storedObjects(),
                                "Opening cached schema claims all dependencies without row enumeration");
                        assertEquals(first.storedBytes(), current.sharedWithBuildBytes());
                        assertEquals(0, current.uniqueToBuildBytes());
                    }
                    var page =
                            tables.page(
                                            new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                            TableTypes.Priority.INTERACTIVE,
                                            Cancellation.NONE)
                                    .join();
                    assertEquals(TableTypes.PageState.OK, page.state(), page.message());
                    var artifacts =
                            tables.savedArtifacts(TableTypes.Ref.id(100), Cancellation.NONE).join();
                    assertEquals(
                            true,
                            artifacts.size() >= 3,
                            "Saved graph includes node, manifest and record segments");
                    assertEquals(
                            true,
                            artifacts.contains(
                                    com.vibeminer.generated.GeneratedKey.parse(page.identity())));

                    assertEquals(2, page.rows().size());
                    assertEquals(List.of("7", "9"), page.rows().getFirst().row().values());
                    assertEquals(List.of("8", "9"), page.rows().get(1).row().values());
                    assertEquals(true, page.rows().get(1).row().isCopy());
                    if (round == 0) {
                        identity = page.identity();
                        assertNotNull(identity);
                    } else {
                        assertEquals(identity, page.identity());
                    }
                    assertEquals(
                            TableTypes.RowOutcome.FOUND,
                            tables.get(
                                            TableTypes.Ref.id(100),
                                            8,
                                            TableTypes.Priority.INTERACTIVE,
                                            Cancellation.NONE)
                                    .join()
                                    .outcome());
                }
            } finally {
                access.closeAsync().join();
                if (generated instanceof DiskGeneratedStore disk) {
                    disk.closeAsync().join();
                }
                if (catalog != null) {
                    catalog.closeAsync().join();
                }
                objects.closeAsync().join();
            }
        }
    }
}
