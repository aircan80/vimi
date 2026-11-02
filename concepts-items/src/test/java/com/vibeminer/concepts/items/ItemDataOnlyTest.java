package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.files.FileTypes;
import com.vibeminer.files.Files;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ItemDataOnlyTest {
    @Test
    void dataOnlyRetainsModelIdsAndWarmupNeverLoadsAssets() {
        var seed =
                ItemAppearancesTest.chain(
                        List.of(ItemsWorld.row(100, "100", "1", "0", "0", "200")));
        seed.add(
                ItemsWorld.table(
                        1,
                        "Item",
                        ItemsWorld.ITEM,
                        List.of(ItemsWorld.row(1, "1", "2", "7", "13", "123"))));
        seed.add(ItemsWorld.table(2, "ItemSparse", ItemsWorld.SPARSE, List.of()));
        seed.add(
                ItemsWorld.table(
                        17,
                        "ItemDisplayInfoMaterialRes",
                        ItemsWorld.columns("ID", "ItemDisplayInfoID"),
                        List.of(ItemsWorld.row(9, "9", "300"))));
        var tables =
                new com.vibeminer.formats.tables.MemoryTables().add(ItemsWorld.BUILD, "enUS", seed);
        Files files =
                new Files() {
                    public java.util.concurrent.CompletableFuture<FileTypes.FilesOpenResult>
                            forBuild(
                                    com.vibeminer.builds.BuildTypes.BuildRef b,
                                    String l,
                                    com.vibeminer.access.Cancellation t) {
                        throw new AssertionError("Media requested");
                    }

                    public com.vibeminer.files.names.FileNames names() {
                        return com.vibeminer.files.names.FileNames.OFF;
                    }
                };
        try (var handle =
                new ItemsService(tables, files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .items()) {
            assertTrue(handle.warmup(ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().ready());
            var data = handle.getData(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().detail();
            assertEquals(600L, data.appearances().models().models().getFirst().fileDataId());
            assertEquals(
                    ItemModelRefState.NOT_READ,
                    data.appearances().models().models().getFirst().state());
            assertEquals(ItemIconState.NOT_REQUESTED, data.icon().state());
        }
    }

    @Test
    void dataOnlyRetainsFactsAndIconIdentityWithoutOpeningFiles() {
        var tables =
                ItemsWorld.data(List.of(ItemsWorld.row(1, "1", "2", "7", "13", "123")), List.of());
        Files files =
                new Files() {
                    public java.util.concurrent.CompletableFuture<FileTypes.FilesOpenResult>
                            forBuild(
                                    com.vibeminer.builds.BuildTypes.BuildRef b,
                                    String l,
                                    com.vibeminer.access.Cancellation t) {
                        throw new AssertionError("Asset I/O during data-only detail");
                    }

                    public com.vibeminer.files.names.FileNames names() {
                        return com.vibeminer.files.names.FileNames.OFF;
                    }
                };
        try (var handle =
                new ItemsService(tables, files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .items()) {
            var result = handle.getData(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join();
            assertEquals(ItemResultState.FOUND, result.state());
            assertEquals(123L, result.detail().icon().fileDataId());
            assertEquals(ItemIconState.NOT_REQUESTED, result.detail().icon().state());
            assertNotNull(result.detail().facts());
            var warm = handle.warmup(ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join();
            assertEquals(4, warm.entries().size());
            assertFalse(warm.ready());
        }
    }
}
