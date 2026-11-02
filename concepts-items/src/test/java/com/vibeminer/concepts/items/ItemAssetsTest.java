package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemAssetsTest {
    static FileTypes.BuildFile file(long id, byte[] bytes) {
        return new FileTypes.BuildFile(
                id,
                null,
                FileTypes.FilePathSource.NONE,
                (long) bytes.length,
                "a".repeat(32),
                FileTypes.FileFormat.UNKNOWN,
                FileTypes.FileFormatSource.NONE,
                FileTypes.FileStorage.STORED,
                false);
    }

    static byte[] icon() {
        var b = ByteBuffer.allocate(152).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put(new byte[] {3, 8, 0, 0})
                .putInt(1)
                .putInt(1)
                .putInt(148);
        b.putInt(84, 4);
        return b.array();
    }

    static MemoryFiles add(
            MemoryFiles files, long id, byte[] bytes, List<FileTypes.FileHole> holes) {
        return files.add(ItemsWorld.BUILD, "enUS", file(id, bytes), bytes, holes);
    }

    @Test
    void iconInspectionUsesBlpHeaderAndPreservesContentKeyWithoutReadingPixels() {
        var data =
                ItemsWorld.data(List.of(ItemsWorld.row(1, "1", "2", "7", "13", "123")), List.of());
        var files = add(new MemoryFiles(), 123, icon(), List.of());
        try (var h =
                new ItemsService(data, files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .items()) {
            var icon = h.get(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().detail().icon();
            assertEquals(ItemIconState.READY, icon.state());
            assertEquals(1, icon.header().width());
            assertEquals("a".repeat(32), icon.contentKey());
            assertEquals(1, icon.mipCount());
        }
    }

    @Test
    void missingOrProtectedIconRetainsTypedFailureAndKey() {
        var data =
                ItemsWorld.data(List.of(ItemsWorld.row(1, "1", "2", "7", "13", "123")), List.of());
        var bytes = icon();
        var files =
                add(
                        new MemoryFiles(),
                        123,
                        bytes,
                        List.of(new FileTypes.FileHole(0, 148, "SECRET")));
        try (var h =
                new ItemsService(data, files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .items()) {
            var icon = h.get(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().detail().icon();
            assertEquals(ItemIconState.PROTECTED, icon.state());
            assertEquals(List.of("SECRET"), icon.holeKeys());
        }
        try (var h = ItemsWorld.open(data)) {
            assertEquals(
                    ItemIconState.FILES_NOT_OPEN,
                    h.get(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().detail().icon().state());
        }
    }

    @Test
    void modelMetadataReferencesAreReadOnceAndFurtherDistinctFilesAreExplicitlyCapped()
            throws Exception {
        byte[] model;
        try (var in = getClass().getResourceAsStream("/golden/model-274.m2")) {
            model = in.readAllBytes();
        }
        var seed =
                ItemAppearancesTest.chain(
                        List.of(ItemsWorld.row(100, "100", "1", "0", "0", "200")));
        seed.add(
                ItemsWorld.table(
                        1,
                        "Item",
                        ItemsWorld.ITEM,
                        List.of(ItemsWorld.row(1, "1", "2", "7", "13", "0"))));
        seed.add(ItemsWorld.table(2, "ItemSparse", ItemsWorld.SPARSE, List.of()));
        seed.set(
                3,
                ItemsWorld.table(
                        13,
                        "ModelFileData",
                        ItemsWorld.columns("ID", "ModelResourcesID"),
                        List.of(
                                ItemsWorld.row(600, "600", "400"),
                                ItemsWorld.row(601, "601", "400"))));
        var files = add(new MemoryFiles(), 600, model, List.of());
        var source = new MemoryTables().add(ItemsWorld.BUILD, "enUS", seed);
        try (var h =
                new ItemsService(
                                source,
                                files,
                                new BlpImages(new MemoryGeneratedStore()),
                                new ItemsOptions(20000, 200000, 4000000, 16, 32, 1))
                        .forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN)
                        .join()
                        .items()) {
            var detail = h.get(1, ItemsWorld.PRIORITY, ItemsWorld.TOKEN).join().detail();
            assertEquals(ItemModelsState.CAPPED, detail.appearances().models().state());
            var refs = detail.appearances().models().models();
            assertEquals(2, refs.size());
            assertEquals(ItemModelRefState.READ, refs.getFirst().state());
            assertEquals(BuildModelTypes.ReadState.READ, refs.getFirst().read());
            assertTrue(refs.getFirst().textures().size() > 0);
            assertEquals(ItemModelRefState.NOT_READ, refs.get(1).state());
            assertEquals(ItemsService.MODEL_CAP_CODE, detail.appearances().models().code());
            assertEquals(2, detail.icons().size());
        }
    }
}
