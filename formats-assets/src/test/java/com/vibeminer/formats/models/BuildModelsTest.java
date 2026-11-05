package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadException;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.BuildModelTypes.ReadState;
import com.vibeminer.formats.models.BuildModelTypes.Reference;
import com.vibeminer.formats.models.BuildModelTypes.ReferenceKind;
import com.vibeminer.formats.models.BuildModelTypes.ReferenceState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class BuildModelsTest {
    private static final String CONTENT = "1".repeat(32), KEY = "ABCDEF0123456789";

    private static AssetFilesFixture fixture() {
        return new AssetFilesFixture()
                .add(123, CONTENT, M2ReadersTest.golden("model-274.m2"))
                .add(900, "2".repeat(32), M2ReadersTest.golden("skin-274.skin"))
                .add(700, "3".repeat(32), new byte[] {1});
    }

    private static byte[] appendChunk(byte[] model, String id, long... ids) {
        ByteBuffer b =
                ByteBuffer.allocate(model.length + 8 + ids.length * 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.put(model)
                .put(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(ids.length * 4);
        for (long value : ids) {
            b.putInt((int) value);
        }
        return b.array();
    }

    @Test
    void modelAndDeclaredMainSkinReadThroughFilesWithoutFetchingReferences() {
        AssetFilesFixture files = fixture();
        BuildModels models = new BuildModels(files);
        var read = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.READ, read.state());
        assertEquals(1, read.mainSkinCount());
        assertEquals(1, read.lodSkinCount());
        assertEquals(
                List.of(
                        ReferenceKind.SKIN,
                        ReferenceKind.LOD_SKIN,
                        ReferenceKind.TEXTURE,
                        ReferenceKind.TEXTURE),
                read.references().stream().map(Reference::kind).toList());
        assertEquals(ReferenceState.FOUND, read.references().getFirst().state());
        assertEquals(ReferenceState.UNKNOWN_FILE, read.references().get(1).state());
        assertEquals(ReferenceState.RESOLVED_ELSEWHERE, read.references().get(3).state());
        assertEquals(1, files.opens.get());
        var skin = models.readSkin(read, 0, Cancellation.NONE).join();
        assertEquals(ReadState.READ, skin.state());
        assertEquals(900, skin.fileDataId());
        assertEquals(4, skin.skin().vertexLookup().size());
        var geometry = models.readGeometrySkin(read, 0, Cancellation.NONE).join();
        assertEquals(ReadState.READ, geometry.state());
        assertEquals(
                M2Types.GeometryEvaluation.NOT_EVALUATED, geometry.skin().materialEvaluation());
        assertEquals(files.opens.get(), files.streamCloses.get());
        assertEquals(
                ReadState.NOT_DECLARED, models.readSkin(read, 1, Cancellation.NONE).join().state());
        assertEquals(
                ReadState.NOT_DECLARED,
                models.readSkin(read, -1, Cancellation.NONE).join().state());
    }

    @Test
    void legacyMainProfilesNeverGuessSkinFilename() {
        AssetFilesFixture files =
                new AssetFilesFixture().add(123, CONTENT, M2ReadersTest.golden("model-264.m2"));
        BuildModels models = new BuildModels(files);
        var read = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.READ, read.state());
        assertEquals(ReferenceState.NOT_DECLARED, read.references().getFirst().state());
        assertEquals(
                ReadState.NOT_DECLARED, models.readSkin(read, 0, Cancellation.NONE).join().state());
        assertEquals(1, files.opens.get());
    }

    @Test
    void partialAndProtectedModelsReturnKeysBeforeReadingAnyBytes() {
        for (boolean all : List.of(false, true)) {
            byte[] bytes = M2ReadersTest.golden("model-274.m2");
            BuildFile file =
                    new BuildFile(
                            123,
                            null,
                            FilePathSource.NONE,
                            (long) bytes.length,
                            CONTENT,
                            FileFormat.M2_CHUNKED,
                            FileFormatSource.MAGIC,
                            FileStorage.STORED,
                            true);
            AssetFilesFixture files =
                    new AssetFilesFixture()
                            .add(
                                    file,
                                    bytes,
                                    List.of(new FileHole(0, all ? bytes.length : 8, KEY)),
                                    List.of(KEY));
            var read = new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join();
            assertEquals(all ? ReadState.PROTECTED : ReadState.PARTIAL, read.state());
            assertEquals(List.of(KEY), read.keyNames());
            assertTrue(read.references().isEmpty());
            assertNull(read.model());
            assertEquals(0, files.reads.get());
            assertEquals(1, files.streamCloses.get());
        }
    }

    @Test
    void unknownSizeLimitParserRefusalAndDatedReadFailureRemainDistinct() {
        AssetFilesFixture files = fixture();
        BuildModels models = new BuildModels(files);
        assertEquals(
                ReadState.UNKNOWN_FILE,
                models.readModel(FileRef.id(999), Cancellation.NONE).join().state());
        BuildFile huge =
                new BuildFile(
                        123,
                        null,
                        FilePathSource.NONE,
                        (64L << 20) + 1,
                        CONTENT,
                        FileFormat.M2,
                        FileFormatSource.MAGIC,
                        FileStorage.STORED,
                        false);
        files.add(huge, new byte[4], List.of(), List.of());
        var large = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.REJECTED, large.state());
        assertEquals("MODEL_LIMIT", large.code());
        assertTrue(large.message().contains("67,108,865"));
        assertEquals(0, files.opens.get());
        files.add(123, CONTENT, new byte[4]);
        assertEquals(
                ReadState.REJECTED,
                models.readModel(FileRef.id(123), Cancellation.NONE).join().state());
        files.add(123, CONTENT, M2ReadersTest.golden("model-274.m2"));
        files.failRead(
                123,
                new FileReadException(
                        FileReadState.MISSING, "CDN_NOT_FOUND", "404", Instant.EPOCH));
        var failed = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.MISSING, failed.state());
        assertEquals("CDN_NOT_FOUND", failed.code());
        assertEquals(Instant.EPOCH, failed.observedAt());
        assertNull(failed.model());
    }

    @Test
    void referenceStatFailuresDoNotFailModelAndOnlyNonzeroReferencesAreChecked() {
        AssetFilesFixture files = fixture();
        files.failStat(700, new IllegalArgumentException("scripted failure"));
        var result = new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.READ, result.state());
        var failed = result.references().get(2);
        assertEquals(ReferenceState.CHECK_FAILED, failed.state());
        assertEquals("scripted failure", failed.message());
        assertEquals(4, files.stats.get());
    }

    @Test
    void extraPhysicsParticleReferencesKeepZeroAndExactOrdinalSemantics() {
        byte[] bytes = M2ReadersTest.golden("model-274.m2");
        bytes = appendChunk(bytes, "PFID", 0);
        bytes = appendChunk(bytes, "RPID", 77, 0);
        bytes = appendChunk(bytes, "GPID", 88);
        AssetFilesFixture files = fixture().add(123, CONTENT, bytes);
        var result = new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.READ, result.state());
        List<Reference> extras = result.references().subList(4, 8);
        assertEquals(
                List.of(
                        ReferenceKind.PHYSICS_FILE,
                        ReferenceKind.RECURSIVE_PARTICLE_MODEL,
                        ReferenceKind.RECURSIVE_PARTICLE_MODEL,
                        ReferenceKind.GEOMETRY_PARTICLE_MODEL),
                extras.stream().map(Reference::kind).toList());
        assertEquals(ReferenceState.NO_EXTERNAL_FILE, extras.getFirst().state());
        assertEquals(ReferenceState.UNKNOWN_FILE, extras.get(1).state());
        assertEquals(1, extras.get(2).ordinal());
        assertEquals(ReferenceState.NO_EXTERNAL_FILE, extras.get(2).state());
        byte[] broken = appendChunk(M2ReadersTest.golden("model-274.m2"), "PFID", 1, 2);
        files.add(123, CONTENT, broken);
        assertEquals(
                "MODEL_INVALID",
                new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join().code());
    }

    @Test
    void referenceStatsNeverExceedEightAndOutputRetainsDeclarationOrder() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch firstEight = new CountDownLatch(8);
        List<CompletableFuture<FileStat>> pending = new ArrayList<>();
        boolean[] release = {false};
        AssetFilesFixture files =
                new AssetFilesFixture() {
                    @Override
                    public CompletableFuture<FileStat> stat(FileRef ref, Cancellation token) {
                        if (ref.fileDataId() != null && ref.fileDataId() >= 1000) {
                            synchronized (pending) {
                                if (release[0]) {
                                    return CompletableFuture.completedFuture(
                                            new FileStat(FileLookupState.UNKNOWN_FILE, null));
                                }
                                int current = active.incrementAndGet();
                                peak.accumulateAndGet(current, Math::max);
                                firstEight.countDown();
                                CompletableFuture<FileStat> future = new CompletableFuture<>();
                                pending.add(future);
                                return future.whenComplete((v, e) -> active.decrementAndGet());
                            }
                        }
                        return super.stat(ref, token);
                    }
                };
        long[] ids = new long[32];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 1000 + i;
        }
        files.add(123, CONTENT, appendChunk(M2ReadersTest.golden("model-274.m2"), "RPID", ids));
        var future = new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE);
        assertTrue(firstEight.await(5, TimeUnit.SECONDS));
        assertEquals(8, peak.get());
        synchronized (pending) {
            release[0] = true;
            for (var waiting : pending) {
                waiting.complete(new FileStat(FileLookupState.UNKNOWN_FILE, null));
            }
        }
        var result = future.join();
        assertEquals(ReadState.READ, result.state());
        assertEquals(0, active.get());
        assertEquals(
                32,
                result.references().stream()
                        .filter(r -> r.kind() == ReferenceKind.RECURSIVE_PARTICLE_MODEL)
                        .count());
        for (int i = 0; i < 32; i++) {
            assertEquals(1000 + i, result.references().get(4 + i).fileDataId());
        }
        assertEquals(1, files.opens.get());
    }

    @Test
    void encryptedButReadableSourcesPreserveEveryChunkKeyOnModelAndSkin() {
        AssetFilesFixture files = fixture();
        byte[] bytes = M2ReadersTest.golden("model-274.m2");
        BuildFile file =
                new BuildFile(
                        123,
                        null,
                        FilePathSource.NONE,
                        (long) bytes.length,
                        CONTENT,
                        FileFormat.M2,
                        FileFormatSource.MAGIC,
                        FileStorage.STORED,
                        true);
        files.add(file, bytes, List.of(), List.of(KEY));
        var result = new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(List.of(KEY), result.chunkKeys());
        assertTrue(result.keyNames().isEmpty());
    }

    @Test
    void legacyTextureNamesResolveOnlyThroughFileStatPathAuthority() {
        byte[] original = M2ReadersTest.golden("model-264.m2");
        byte[] name = "textures/authored.blp\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] model = java.util.Arrays.copyOf(original, original.length + name.length);
        System.arraycopy(name, 0, model, original.length, name.length);
        int textures = M2ReadersTest.get(model, 84);
        M2ReadersTest.put(model, textures + 8, name.length);
        M2ReadersTest.put(model, textures + 12, original.length);
        for (FilePathSource source : List.of(FilePathSource.ROOT_HASH, FilePathSource.LISTFILE)) {
            AssetFilesFixture files = new AssetFilesFixture().add(123, CONTENT, model);
            BuildFile texture =
                    new BuildFile(
                            444,
                            "textures/authored.blp",
                            source,
                            1L,
                            "4".repeat(32),
                            FileFormat.BLP,
                            FileFormatSource.MAGIC,
                            FileStorage.STORED,
                            false);
            files.add(texture, new byte[] {1}, List.of(), List.of());
            var result =
                    new BuildModels(files).readModel(FileRef.id(123), Cancellation.NONE).join();
            Reference ref = result.references().get(1);
            assertEquals(ReferenceState.FOUND, ref.state());
            assertEquals(444L, ref.fileDataId());
            assertEquals(
                    source == FilePathSource.ROOT_HASH
                            ? BuildModelTypes.ReferenceResolution.ROOT_NAME
                            : BuildModelTypes.ReferenceResolution.LISTFILE_NAME,
                    ref.resolution());
        }
        AssetFilesFixture unknown = new AssetFilesFixture().add(123, CONTENT, model);
        var result = new BuildModels(unknown).readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReferenceState.UNRESOLVED_BY_NAME, result.references().get(1).state());
    }

    @Test
    void disposedReferenceHandleIsNotDowngradedToCheckFailed() {
        AssetFilesFixture files = fixture();
        files.failStat(700, new IllegalStateException("Files handle closed"));
        Throwable failure =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        new BuildModels(files)
                                                .readModel(FileRef.id(123), Cancellation.NONE)
                                                .join()));
        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals("Files handle closed", failure.getMessage());
    }

    @Test
    void cancellationIsNotDowngradedToReferenceFailure() {
        AssetFilesFixture files = fixture();
        files.failStat(700, new java.util.concurrent.CancellationException());
        Throwable error =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        new BuildModels(files)
                                                .readModel(FileRef.id(123), Cancellation.NONE)
                                                .join()));
        assertInstanceOf(java.util.concurrent.CancellationException.class, error);
    }

    /** Version 1, one BIDA entry and one identity BOMT matrix. */
    private static byte[] boneFile(int bone) {
        var exact = ByteBuffer.allocate(4 + 8 + 2 + 8 + 64).order(ByteOrder.LITTLE_ENDIAN);
        exact.putInt(1);
        exact.put("BIDA".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(2);
        exact.putShort((short) bone);
        exact.put("BOMT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(64);
        for (int i = 0; i < 16; i++) {
            exact.putFloat(i % 5 == 0 ? 1 : 0);
        }
        return exact.array();
    }

    @Test
    void declaredBoneFilesAndStandFactsReadThroughFiles() {
        byte[] model =
                appendChunk(M2IdleAnimationTest.authored(274, a -> {}), "BFID", 701, 702, 703);
        byte[] outside = boneFile(9);
        AssetFilesFixture files =
                new AssetFilesFixture()
                        .add(123, CONTENT, model)
                        .add(701, "4".repeat(32), boneFile(1))
                        .add(702, "5".repeat(32), outside);
        BuildModels models = new BuildModels(files);
        var read = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        assertEquals(ReadState.READ, read.state());
        var valid = models.readBoneFile(read, 701, Cancellation.NONE).join();
        assertEquals(ReadState.READ, valid.state());
        assertEquals(1, valid.bones().count());
        var invalid = models.readBoneFile(read, 702, Cancellation.NONE).join();
        assertEquals(ReadState.REJECTED, invalid.state());
        assertEquals("MODEL_INVALID", invalid.code());
        assertNull(invalid.bones());
        var missing = models.readBoneFile(read, 703, Cancellation.NONE).join();
        assertEquals(ReadState.UNKNOWN_FILE, missing.state());
        assertThrows(
                IllegalArgumentException.class,
                () -> models.readBoneFile(read, 704, Cancellation.NONE));
        var stand = models.readStandFacts(read, Cancellation.NONE).join();
        assertEquals(ReadState.READ, stand.state());
        assertNull(stand.stand().refusal());
        assertEquals(1000, stand.stand().durationMillis());
        assertEquals(files.opens.get(), files.streamCloses.get());
    }

    @Test
    void oversizedBoneFilesAreRefusedBeforeOpening() {
        byte[] model = appendChunk(M2IdleAnimationTest.authored(274, a -> {}), "BFID", 701);
        AssetFilesFixture files =
                new AssetFilesFixture()
                        .add(123, CONTENT, model)
                        .add(701, "4".repeat(32), new byte[M2BoneFile.MAX_BYTES + 1]);
        BuildModels models = new BuildModels(files);
        var read = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        int opens = files.opens.get();
        var refused = models.readBoneFile(read, 701, Cancellation.NONE).join();
        assertEquals(ReadState.REJECTED, refused.state());
        assertEquals("MODEL_LIMIT", refused.code());
        assertEquals(opens, files.opens.get());
    }

    @Test
    void idleReadsReturnTracksOnlyForModelResidentBones() {
        byte[] local = M2IdleAnimationTest.authored(274, a -> {});
        AssetFilesFixture files =
                new AssetFilesFixture()
                        .add(123, CONTENT, local)
                        .add(124, "5".repeat(32), appendChunk(local, "BFID", 701));
        BuildModels models = new BuildModels(files);
        var read = models.readModel(FileRef.id(123), Cancellation.NONE).join();
        var idle = models.readIdle(read, Cancellation.NONE).join();
        assertEquals(ReadState.READ, idle.state());
        assertEquals(1000, idle.idle().durationMillis());
        assertEquals(read.model(), idle.idle().model());
        var external = models.readModel(FileRef.id(124), Cancellation.NONE).join();
        var refused = models.readIdle(external, Cancellation.NONE).join();
        assertEquals(ReadState.REJECTED, refused.state());
        assertEquals("MODEL_UNSUPPORTED", refused.code());
        assertNull(refused.idle());
        assertEquals(files.opens.get(), files.streamCloses.get());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        models.readIdle(
                                new BuildModels(new AssetFilesFixture())
                                        .readModel(FileRef.id(9), Cancellation.NONE)
                                        .join(),
                                Cancellation.NONE));
    }
}
