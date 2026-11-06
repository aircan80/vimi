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
    private static <T> T unusable(
            Class<T> type, java.util.function.Function<String, Object> answer) {
        return type.cast(
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            Object value = answer.apply(method.getName());
                            if (value == null) {
                                throw new AssertionError("Unexpected " + method.getName());
                            }
                            return value;
                        }));
    }

    private static DisplayModelService failingSchema(Object answer) {
        var tables =
                unusable(
                        com.vibeminer.formats.tables.BuildTables.class,
                        name ->
                                switch (name) {
                                    case "schema" ->
                                            answer instanceof Throwable failure
                                                    ? java.util.concurrent.CompletableFuture
                                                            .failedFuture(failure)
                                                    : java.util.concurrent.CompletableFuture
                                                            .completedFuture(answer);
                                    case "closeAsync" ->
                                            java.util.concurrent.CompletableFuture.completedFuture(
                                                    null);
                                    default -> null;
                                });
        var files = unusable(com.vibeminer.files.BuildFiles.class, name -> null);
        return new DisplayModelService(tables, files, null);
    }

    private static RuntimeException busy() throws Exception {
        var constructor =
                com.vibeminer.formats.tables.TableWorkScheduler.BusyException.class
                        .getDeclaredConstructor(
                                com.vibeminer.formats.tables.TableTypes.Priority.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                com.vibeminer.formats.tables.TableTypes.Priority.INTERACTIVE);
    }

    @Test
    void tableFailuresAreRefusalsOnlyWhenPermanentAndBusyOrClosedIsTransient() throws Exception {
        // A table that is not available is a stated refusal: an unavailable preview with a reason.
        var refused =
                failingSchema(
                        new com.vibeminer.formats.tables.TableTypes.SchemaResult(null, null, null));
        var preview = refused.preview(5, Cancellation.NONE).get();
        assertEquals(DisplayModelTypes.State.UNAVAILABLE, preview.state());
        assertTrue(preview.message().contains("is unavailable"), preview.message());
        // A full table queue is transient: the component's busy refusal, never a cached result.
        var queued = failingSchema(busy());
        var busy = assertThrowsCause(() -> queued.preview(5, Cancellation.NONE).get());
        assertTrue(
                busy instanceof DisplayModelTypes.ServiceUnavailable unavailable
                        && !unavailable.closed(),
                String.valueOf(busy));
        // A closed table handle is the closed refusal.
        var handle = failingSchema(new IllegalStateException("Table handle closed"));
        var closed = assertThrowsCause(() -> handle.preview(5, Cancellation.NONE).get());
        assertTrue(
                closed instanceof DisplayModelTypes.ServiceUnavailable unavailable
                        && unavailable.closed(),
                String.valueOf(closed));
        // Anything else is a reader failure and propagates as it is, for the caller to log.
        var broken = failingSchema(new IllegalStateException("tables retired"));
        var failed = assertThrowsCause(() -> broken.preview(5, Cancellation.NONE).get());
        assertFalse(failed instanceof DisplayModelTypes.ServiceUnavailable, String.valueOf(failed));
        var reader =
                failed instanceof java.util.concurrent.CompletionException
                        ? failed.getCause()
                        : failed;
        assertTrue(
                reader instanceof IllegalStateException state
                        && state.getMessage().equals("tables retired"),
                String.valueOf(failed));
        // A closed preview component is the closed refusal too.
        refused.closeAsync().get();
        var component = assertThrowsCause(() -> refused.preview(5, Cancellation.NONE).get());
        assertTrue(
                component instanceof DisplayModelTypes.ServiceUnavailable unavailable
                        && unavailable.closed());
        for (var service : List.of(queued, handle, broken)) {
            service.closeAsync().get();
        }
    }

    private static Throwable assertThrowsCause(
            org.junit.jupiter.api.function.Executable executable) {
        var thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.ExecutionException.class, executable);
        return thrown.getCause();
    }

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

    @Test
    void legacy272DuplicateSectionReferenceAdmitsCutoutWhileModernFlagsStayStrict()
            throws Exception {
        byte[] modernBytes = golden("appearance-simple.m2");
        byte[] legacyBytes = modernBytes.clone();
        var header = java.nio.ByteBuffer.wrap(legacyBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(274, header.getInt(12));
        header.putInt(12, 272);
        var legacy = M2ModelReader.read(AssetSource.bytes(legacyBytes)).join();
        var modern = M2ModelReader.read(AssetSource.bytes(modernBytes)).join();
        var facts =
                M2Appearance.readModel(AssetSource.bytes(legacyBytes), legacy, Cancellation.NONE)
                        .join();
        var skin =
                M2RestDrawSkinReader.read(
                                AssetSource.bytes(golden("appearance-simple.skin")),
                                legacy,
                                null,
                                Cancellation.NONE)
                        .join();
        var original = skin.batches().getFirst();
        assertTrue(DisplayModelService.simpleDraw(legacy, section(original, 1, 1), facts));
        assertTrue(DisplayModelService.simpleDraw(legacy, section(original, 2, 2), facts));
        assertTrue(DisplayModelService.simpleDraw(legacy, section(original, 2, 0), facts));
        assertFalse(DisplayModelService.simpleDraw(legacy, section(original, 1, 2), facts));
        assertFalse(DisplayModelService.simpleDraw(modern, section(original, 1, 1), facts));
        assertFalse(DisplayModelService.simpleDraw(modern, section(original, 2, 2), facts));
    }

    private static M2Types.Batch section(M2Types.Batch batch, int section, int second) {
        return new M2Types.Batch(
                batch.flags(),
                batch.priority(),
                batch.shaderId(),
                section,
                second,
                batch.colorIndex(),
                batch.materialIndex(),
                batch.materialLayer(),
                batch.textureCount(),
                batch.textureComboIndex(),
                batch.textureCoordinateComboIndex(),
                batch.textureWeightComboIndex(),
                batch.textureTransformComboIndex());
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
