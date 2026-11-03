package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DisplayModelServiceTest {
    @Test
    void replacementTexturesUseDisplayVariationsWhileTypeZeroUsesExactTxid() {
        var variations = List.of(100L, 200L, 300L, 400L);
        assertEquals(
                100,
                DisplayModelService.textureId(new M2Types.Texture(0, 11, 0, "", 999L), variations));
        assertEquals(
                300,
                DisplayModelService.textureId(new M2Types.Texture(0, 13, 0, "", 999L), variations));
        assertEquals(
                999,
                DisplayModelService.textureId(new M2Types.Texture(0, 0, 0, "", 999L), variations));
        assertEquals(
                0,
                DisplayModelService.textureId(new M2Types.Texture(0, 1, 0, "", 999L), variations));
        assertEquals(
                0,
                DisplayModelService.textureId(
                        new M2Types.Texture(0, 13, 0, "", 999L), List.of(100L)));
    }

    @Test
    void savedPreviewValidatesEverySourceReceiptAndRequiresNoAssetRead() {
        var files =
                new AssetFilesFixture()
                        .add(123, "1".repeat(32), new byte[] {1})
                        .add(900, "2".repeat(32), new byte[] {2});
        var store = new MemoryGeneratedStore();
        var cache = new DisplayPreviewCache(store, files);
        var key = GeneratedKey.hash(new byte[] {1});
        var issues = new ArrayList<String>();
        cache.save(
                key,
                appearance(),
                Map.of(123L, "1".repeat(32), 900L, "2".repeat(32)),
                Cancellation.NONE,
                issues);
        assertTrue(issues.isEmpty());
        var restored = cache.restore(key, 123, "1".repeat(32), Cancellation.NONE);
        assertNotNull(restored);
        assertEquals(1, restored.mesh().triangleCount());
        assertEquals(0, files.opens.get());
        files.add(900, "3".repeat(32), new byte[] {3});
        assertNull(cache.restore(key, 123, "1".repeat(32), Cancellation.NONE));
        files.close();
    }

    @Test
    void corruptedOrWrongModelPreviewNeverPublishesCachedGeometry() {
        var files =
                new AssetFilesFixture()
                        .add(123, "1".repeat(32), new byte[] {1})
                        .add(900, "2".repeat(32), new byte[] {2});
        var store = new MemoryGeneratedStore();
        var cache = new DisplayPreviewCache(store, files);
        var key = GeneratedKey.hash(new byte[] {2});
        cache.save(
                key,
                appearance(),
                Map.of(123L, "1".repeat(32), 900L, "2".repeat(32)),
                Cancellation.NONE,
                new ArrayList<>());
        assertNull(cache.restore(key, 124, "1".repeat(32), Cancellation.NONE));
        store.damage(key);
        assertNull(cache.restore(key, 123, "1".repeat(32), Cancellation.NONE));
        files.close();
    }

    @Test
    void drawProfileAcceptsVerifiedSimpleOpacityAndRejectsUnsupportedShaders() throws Exception {
        var bytes = golden("appearance-simple.m2");
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        var skin =
                M2RestDrawSkinReader.read(
                                AssetSource.bytes(golden("appearance-simple.skin")),
                                model,
                                null,
                                Cancellation.NONE)
                        .join();
        var facts =
                M2Appearance.readModel(AssetSource.bytes(bytes), model, Cancellation.NONE).join();
        var batch = skin.batches().getFirst();
        assertTrue(DisplayModelService.simpleDraw(model, batch, facts));
        var unsupported =
                new M2Types.Batch(
                        batch.flags(),
                        batch.priority(),
                        0x7fff,
                        batch.sectionIndex(),
                        batch.flags2(),
                        batch.colorIndex(),
                        batch.materialIndex(),
                        batch.materialLayer(),
                        batch.textureCount(),
                        batch.textureComboIndex(),
                        batch.textureCoordinateComboIndex(),
                        batch.textureWeightComboIndex(),
                        batch.textureTransformComboIndex());
        assertFalse(DisplayModelService.simpleDraw(model, unsupported, facts));
        assertFalse(DisplayModelService.simpleDraw(model, batch, null));
    }

    private static byte[] golden(String name) throws Exception {
        try (var input = DisplayModelServiceTest.class.getResourceAsStream("/golden/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing authored fixture");
            }
            return input.readAllBytes();
        }
    }

    private static MeshAppearance appearance() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        return new MeshAppearance(mesh, new float[] {0, 0, 1, 0, 0, 1}, List.of(), List.of());
    }
}
