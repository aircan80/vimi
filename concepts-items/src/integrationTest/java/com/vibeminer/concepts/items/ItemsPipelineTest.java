package com.vibeminer.concepts.items;

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
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import com.vibeminer.formats.tables.TablesOptions;
import com.vibeminer.formats.tables.TablesService;
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
final class ItemsPipelineTest {
    @TempDir Path temporary;

    @Test
    void nativeItemDetailAndListReopenWithoutEncodedSourceOrRedecoding() {
        byte[] db2 = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var document =
                Db2Fixture.definition("int ID\nint ClassID", "$id,noninline$ID<u32>\nClassID<32>");
        BundledDefinitions provider =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Item", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", document)));
        String identity = null;
        for (int round = 0; round < 2; round++) {
            ObjectStore objects = ObjectStore.open(temporary.resolve("objects")).join();
            DiskGeneratedStore generated =
                    DiskGeneratedStore.open(temporary.resolve("generated")).join();
            CachingObjectSource access = CachingObjectSource.localOnly(objects, objects);
            try {
                FilesScenario fixture =
                        new FilesScenario(false, FileSystemOptions.defaults(), ignored -> access)
                                .add(100, "dbfilesclient/item.db2", db2, false, List.of());
                var encoded = fixture.encoded.get(100L);
                ObjectKey key = new ObjectKey(ObjectKey.ENCODED, encoded.key().bytes());
                if (round == 0) {
                    objects.put(List.of(new PutObject(key, encoded.bytes()))).join();
                } else {
                    // No decoded-file cache exists: successful queries must use generated rows.
                    objects.delete(List.of(key)).join();
                    assertEquals(false, objects.has(key).join());
                }
                Tables service =
                        new TablesService(
                                fixture.files,
                                new TableDefinitions(provider),
                                new GeneratedTables(generated),
                                new TablesOptions(
                                        (build, token) ->
                                                CompletableFuture.completedFuture("1.2.3.4")));
                var items = new ItemsService(service, fixture.files, new BlpImages(generated));
                var open = items.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join();
                assertNotNull(open.items());
                try (var handle = open.items()) {
                    var page =
                            handle.list(
                                            new ItemQuery(),
                                            TableTypes.Priority.INTERACTIVE,
                                            Cancellation.NONE)
                                    .join();
                    assertEquals(ItemsPageState.OK, page.state(), page.message());
                    assertEquals(
                            List.of(7L, 8L), page.entries().stream().map(ItemSummary::id).toList());
                    assertEquals(
                            ItemNameState.SPARSE_UNKNOWN, page.entries().getFirst().name().state());
                    assertEquals(true, page.entries().get(1).isCopy());
                    var detail =
                            handle.get(7, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                                    .join();
                    assertEquals(ItemResultState.FOUND, detail.state(), detail.message());
                    assertEquals(ItemIconState.FIELD_UNAVAILABLE, detail.detail().icon().state());
                    assertEquals("9", detail.detail().summary().values().getFirst().value());
                    assertEquals(48, detail.detail().facts().facts().size());
                    if (round == 0) {
                        identity = page.item().contentKey();
                    } else {
                        assertEquals(identity, page.item().contentKey());
                    }
                }
            } finally {
                access.closeAsync().join();
                generated.closeAsync().join();
                objects.closeAsync().join();
            }
        }
    }
}
