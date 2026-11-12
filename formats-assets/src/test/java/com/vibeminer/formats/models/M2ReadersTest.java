package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class M2ReadersTest {
    static byte[] golden(String name) {
        java.io.InputStream resource = M2ReadersTest.class.getResourceAsStream("/golden/" + name);
        try {
            if (resource == null) {
                resource =
                        new java.util.zip.GZIPInputStream(
                                java.util.Objects.requireNonNull(
                                        M2ReadersTest.class.getResourceAsStream(
                                                "/golden/" + name + ".gz")));
            }
            try (var input = resource) {
                return input.readAllBytes();
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static Model model(int version) {
        return M2ModelReader.read(AssetSource.bytes(golden("model-" + version + ".m2"))).join();
    }

    static int get(byte[] b, int at) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }

    static void put(byte[] b, int at, int v) {
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(at, v);
    }

    static void word(byte[] b, int at, int v) {
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(at, (short) v);
    }

    static void failure(String code, Runnable action) {
        Throwable error = AssetTasks.unwrap(assertThrows(RuntimeException.class, action::run));
        assertInstanceOf(ModelFormatException.class, error);
        assertEquals(code, ((ModelFormatException) error).code());
    }

    @ParameterizedTest
    @ValueSource(ints = {264, 272, 274})
    void csharpGoldenNativeProfileHasExactTwoLevelIndicesAndSeparateBounds(int version) {
        Model model = model(version);
        var skin =
                M2SkinReader.read(AssetSource.bytes(golden("skin-" + version + ".skin")), model)
                        .join();
        var geometry = M2StaticGeometry.create(model, skin, Cancellation.NONE);
        assertEquals(List.of(2, 0, 1), geometry.indices());
        assertEquals(new Vec3(0, 3, 0), geometry.vertices().get(2).position());
        assertEquals(0x04030201L, geometry.vertices().get(2).boneWeights());
        assertEquals(new Vec3(0, 0, 0), geometry.bounds().minimum());
        assertEquals(new Vec3(2, 3, 0), geometry.bounds().maximum());
        assertEquals(new Vec3(50, 60, 70), model.computedBounds().maximum());
        assertEquals(0x03020100L, skin.boneProperties().getFirst());
        assertEquals(version, model.version());
        assertEquals(version != 264, model.isChunked());
        assertEquals("authored", model.name());
        if (version != 264) {
            assertEquals(40, model.baseOffset());
            assertEquals(4472, model.retainedBytes());
            assertEquals(List.of(900L), model.skinFileDataIds());
            assertEquals(List.of(901L), model.lodSkinFileDataIds());
            assertEquals(700L, model.textures().getFirst().fileDataId());
            assertEquals(0L, model.textures().get(1).fileDataId());
            assertEquals(
                    List.of("SFID", "TXID", "MD21"),
                    model.chunks().stream().map(M2Types.Chunk::id).toList());
        }
    }

    @Test
    void nativeOpaqueAdmissionNeedsExactPositiveTxid() {
        var m = model(274);
        var s = M2SkinReader.read(AssetSource.bytes(golden("skin-274.skin")), m).join();
        var draw = M2StaticMaterials.admitOpaque(m, s, Cancellation.NONE).draws().getFirst();
        assertEquals(700L, draw.fileDataId());
        assertTrue(draw.wrapU());
        assertTrue(draw.wrapV());
        var legacy = model(264);
        var legacySkin =
                M2SkinReader.read(AssetSource.bytes(golden("skin-264.skin")), legacy).join();
        failure(
                "MODEL_MATERIAL_UNSUPPORTED",
                () -> M2StaticMaterials.admitOpaque(legacy, legacySkin, Cancellation.NONE));
    }

    @Test
    void referenceIdentityOfParsedModelCannotBeReplaced() {
        var first = model(274);
        var second = model(274);
        var skin = M2SkinReader.read(AssetSource.bytes(golden("skin-274.skin")), first).join();
        failure("MODEL_INVALID", () -> M2StaticGeometry.create(second, skin, Cancellation.NONE));
        failure(
                "MODEL_INVALID",
                () -> M2StaticMaterials.admitOpaque(second, skin, Cancellation.NONE));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 263, 264, 271, 273, 275})
    void unsupportedVersionContainerPairsStayUnsupported(int version) {
        byte[] b = golden("model-274.m2");
        put(b, 44, version);
        failure("MODEL_UNSUPPORTED", () -> M2ModelReader.read(AssetSource.bytes(b)).join());
    }

    @Test
    void structureCountsOffsetsAndNonfiniteVerticesRefuseBeforeProducingModel() {
        byte[] base = golden("model-274.m2");
        for (int[] mutation :
                new int[][] {
                    {60, -1, 304}, {60, 4, -1}, {60, 4, 4}, {80, 33, 304}, {112, 257, 304}
                }) {
            byte[] b = base.clone();
            put(b, 40 + mutation[0], mutation[1]);
            put(b, 44 + mutation[0], mutation[2]);
            failure(
                    mutation[1] == -1 || mutation[1] == 33 || mutation[1] == 257
                            ? "MODEL_LIMIT"
                            : "MODEL_INVALID",
                    () -> M2ModelReader.read(AssetSource.bytes(b)).join());
        }
        byte[] nan = base.clone();
        put(nan, 40 + get(nan, 104), 0x7fc00000);
        failure("MODEL_INVALID", () -> M2ModelReader.read(AssetSource.bytes(nan)).join());
    }

    @Test
    void emptyCollisionBoxOnlyAllowedWithNoMeshDeclarations() {
        byte[] b = golden("model-274.m2");
        for (int i = 0; i < 3; i++) {
            put(b, 40 + 188 + i * 4, Float.floatToIntBits(1e7f));
            put(b, 40 + 200 + i * 4, Float.floatToIntBits(-1e7f));
        }
        assertEquals(4, M2ModelReader.read(AssetSource.bytes(b)).join().vertices().size());
        put(b, 40 + 216, 3);
        put(b, 40 + 220, get(b, 40 + 64));
        failure("MODEL_INVALID", () -> M2ModelReader.read(AssetSource.bytes(b)).join());
    }

    @Test
    void duplicateChunksAndTruncatedFramesRefuse() {
        byte[] b = golden("model-274.m2");
        byte[] duplicate = Arrays.copyOf(b, b.length + 16);
        System.arraycopy(b, 0, duplicate, b.length, 16);
        failure("MODEL_INVALID", () -> M2ModelReader.read(AssetSource.bytes(duplicate)).join());
        for (int length : new int[] {0, 3, 7, 39, 100, 343}) {
            assertThrows(
                    RuntimeException.class,
                    () -> M2ModelReader.read(AssetSource.bytes(Arrays.copyOf(b, length))).join());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void geometryOnlyPreservesRawZeroAndSentinelMaximumAndDoesNotReadMaterialRecords(int version) {
        var m = model(version);
        for (int raw : new int[] {0, 0xcdcdcdcd}) {
            byte[] s = golden("skin-" + version + ".skin");
            put(s, 44, raw);
            int batch = get(s, 40);
            AssetSource bytes = AssetSource.bytes(s);
            AssetSource guarded =
                    new AssetSource() {
                        public long length() {
                            return s.length;
                        }

                        public CompletableFuture<byte[]> readAt(
                                long at, int count, Cancellation token) {
                            assertTrue(
                                    at + count <= batch,
                                    "geometry reader must not read batch records");
                            return bytes.readAt(at, count, token);
                        }
                    };
            var skin = M2GeometrySkinReader.read(guarded, m, null, Cancellation.NONE).join();
            assertEquals(Integer.toUnsignedLong(raw), skin.maximumBones());
            assertEquals(1, skin.batchDeclaration().count());
            assertEquals(M2Types.GeometryEvaluation.NOT_EVALUATED, skin.materialEvaluation());
            assertEquals(
                    List.of(2, 0, 1),
                    M2StaticGeometry.create(m, skin, Cancellation.NONE).indices());
        }
    }

    @Test
    void geometryOnlyProfileRefusesLegacyAndExternalSkeleton() {
        var legacy = model(264);
        assertNotNull(M2GeometrySkinReader.profileRefusal(legacy));
        byte[] b = golden("model-274.m2");
        byte[] with = Arrays.copyOf(b, b.length + 12);
        System.arraycopy(
                new byte[] {'S', 'K', 'I', 'D', 4, 0, 0, 0, 1, 0, 0, 0}, 0, with, b.length, 12);
        assertTrue(
                M2GeometrySkinReader.profileRefusal(
                                M2ModelReader.read(AssetSource.bytes(with)).join())
                        .contains("SKID 1"));
    }

    @Test
    void restSurfaceAdmitsExternalRigWithoutClaimingRigEvaluationAndChecksTopology() {
        byte[] original = golden("model-274.m2");
        byte[] external = Arrays.copyOf(original, original.length + 12);
        System.arraycopy(
                new byte[] {'B', 'F', 'I', 'D', 4, 0, 0, 0, 1, 0, 0, 0},
                0,
                external,
                original.length,
                12);
        var model = M2ModelReader.read(AssetSource.bytes(external)).join();
        assertNotNull(M2GeometrySkinReader.profileRefusal(model));
        byte[] skin = golden("skin-274.skin");
        var rest =
                M2RestSkinReader.read(AssetSource.bytes(skin), model, null, Cancellation.NONE)
                        .join();
        assertEquals(List.of(0, 1, 2), rest.triangleLookup());
        assertEquals(List.of(2, 0, 1, 3), rest.vertexLookup());
        assertEquals(1, rest.sections().size());
        word(skin, get(skin, 16), 4);
        failure(
                "MODEL_INVALID",
                () ->
                        M2RestSkinReader.read(
                                        AssetSource.bytes(skin), model, null, Cancellation.NONE)
                                .join());
    }

    @Test
    void restDrawDeclarationsCheckMaterialsWhileStrictRigAdmissionRemainsSeparate() {
        byte[] original = golden("model-274.m2");
        byte[] external = Arrays.copyOf(original, original.length + 12);
        System.arraycopy(
                new byte[] {'B', 'F', 'I', 'D', 4, 0, 0, 0, 1, 0, 0, 0},
                0,
                external,
                original.length,
                12);
        var model = M2ModelReader.read(AssetSource.bytes(external)).join();
        assertNotNull(M2GeometrySkinReader.profileRefusal(model));
        byte[] skin = golden("skin-274.skin");
        var raw =
                M2RestDrawSkinReader.read(AssetSource.bytes(skin), model, null, Cancellation.NONE)
                        .join();
        assertEquals(1, raw.batches().size());
        assertEquals(1, raw.surface().sections().size());
        word(skin, get(skin, 40) + 16, 65535);
        failure(
                "MODEL_INVALID",
                () ->
                        M2RestDrawSkinReader.read(
                                        AssetSource.bytes(skin), model, null, Cancellation.NONE)
                                .join());
    }

    @Test
    void skinTwoLevelLookupSectionRangesAndRawMaximumChecked() {
        var model = model(274);
        byte[] original = golden("skin-274.skin");
        for (int[] mutation :
                new int[][] {
                    {get(original, 8), 4}, {get(original, 16), 4}, {get(original, 32) + 6, 1}
                }) {
            byte[] b = original.clone();
            word(b, mutation[0], mutation[1]);
            failure("MODEL_INVALID", () -> M2SkinReader.read(AssetSource.bytes(b), model).join());
        }
        byte[] b = original.clone();
        put(b, 44, 257);
        failure("MODEL_LIMIT", () -> M2SkinReader.read(AssetSource.bytes(b), model).join());
    }

    @Test
    void completeSourceRequiredAndReadsStayBounded() {
        byte[] bytes = golden("model-274.m2");
        AssetSource inner = AssetSource.bytes(bytes);
        AssetSource protectedSource =
                new AssetSource() {
                    public long length() {
                        return bytes.length;
                    }

                    public boolean protectedRanges() {
                        return true;
                    }

                    public CompletableFuture<byte[]> readAt(
                            long at, int count, Cancellation token) {
                        throw new AssertionError();
                    }
                };
        failure("MODEL_UNSUPPORTED", () -> M2ModelReader.read(protectedSource).join());
        AssetSource bounded =
                new AssetSource() {
                    public long length() {
                        return inner.length();
                    }

                    public CompletableFuture<byte[]> readAt(
                            long at, int count, Cancellation token) {
                        assertTrue(count <= 65536);
                        return inner.readAt(at, count, token);
                    }
                };
        assertEquals(4, M2ModelReader.read(bounded).join().vertices().size());
        Cancellation token = new Cancellation();
        token.cancel();
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () -> M2ModelReader.read(inner, new Limits(), token).join())));
    }

    @Test
    void allCombinerNamesKeepUnsupportedSelectionsExplicit() {
        assertEquals(M2Combiners.Combiner.OPAQUE, M2Combiners.admit(1, 0).profile().combiner());
        assertEquals(M2Combiners.Combiner.MOD, M2Combiners.admit(1, 0x10).profile().combiner());
        assertEquals(
                M2Combiners.Combiner.OPAQUE_MOD2X_NA_ALPHA,
                M2Combiners.admit(2, 0x8000).profile().combiner());
        assertNull(M2Combiners.admit(1, 1).profile());
        assertNull(M2Combiners.admit(1, 0x8024).profile());
        assertEquals("Combiners_Opaque_Alpha", M2Combiners.name(2, 0x8017).pixelShader());
    }

    /**
     * #104: a UV-transform lookup entry past the model's declared texture transforms names no
     * transform; the skin reads and records it (shipped drakes on 70862 hold a 0 with no tracks).
     */
    @Test
    void aTransformLookupEntryPastTheDeclaredTracksIsRecordedNotRefused() {
        byte[] bytes = golden("model-274.m2");
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // MD21 data begins at 40 in the golden; its transform lookup (header 0x98) has one entry.
        int md = 40;
        assertEquals(1, buffer.getInt(md + 0x98));
        int entry = md + buffer.getInt(md + 0x9c);
        assertEquals(0xffff, buffer.getShort(entry) & 0xffff);
        buffer.putShort(entry, (short) 0);
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        assertEquals(List.of(0), model.textureTransforms());
        var skin = M2SkinReader.read(AssetSource.bytes(golden("skin-274.skin")), model).join();
        var batch = skin.batches().getFirst();
        assertEquals(0, model.textureTransforms().get(batch.textureTransformComboIndex()));
    }
}
