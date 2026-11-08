package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.BlpImageTypes.Cache;
import com.vibeminer.formats.images.BlpImageTypes.State;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class BlpImagesTest {
    private static final String CONTENT = "1".repeat(32), KEY = "ABCDEF0123456789";

    private static byte[] bgra() {
        return Blp2DecoderTest.build(3, 8, 0, 2, 2, new byte[16], new byte[] {1, 2, 3, 4});
    }

    private static BuildFile listed(byte[] bytes) {
        return new BuildFile(
                1,
                null,
                FilePathSource.NONE,
                (long) bytes.length,
                CONTENT,
                FileFormat.BLP,
                FileFormatSource.MAGIC,
                FileStorage.STORED,
                false);
    }

    @Test
    void inspectReadsOnlyHeaderAndPreservesRawUnsupportedProfileFacts() {
        byte[] b = bgra();
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, b);
        var images = new BlpImages(new MemoryGeneratedStore());
        var result = images.inspect(files, FileRef.id(1), Cancellation.NONE).join();
        assertEquals(State.READY, result.state());
        assertEquals(148, files.bytesRead.get() + 148 * files.ranges.get());
        assertEquals(0, files.opens.get());
        assertEquals(2, result.header().declaredMips());
        b[9] = 2;
        files.add(1, CONTENT, b);
        result = images.inspect(files, FileRef.id(1), Cancellation.NONE).join();
        assertEquals(State.UNSUPPORTED, result.state());
        assertEquals(2, result.header().alphaDepth());
        assertTrue(result.message().contains("alpha depth 2"));
    }

    @Test
    void sharedCacheHitNeedsOnlyStatAndNoOpenOrFileRange() {
        byte[] b = bgra();
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, b);
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        BlpImages images = new BlpImages(store);
        var first = images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join();
        assertEquals(State.READY, first.state());
        assertEquals(Cache.STORED, first.cache());
        int opens = files.opens.get(), reads = files.reads.get();
        files.failOpen(
                1,
                new FileOpen(
                        FileReadState.NEEDS_NETWORK, null, "OFFLINE", "offline", Instant.EPOCH));
        var second = images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join();
        assertEquals(Cache.HIT, second.cache());
        assertEquals(opens, files.opens.get());
        assertEquals(reads, files.reads.get());
        assertArrayEquals(first.image().rgba(), second.image().rgba());
        assertEquals(1, store.puts());
    }

    @Test
    void largestWithinReadsHeaderBeforeCacheAndNeverStepsPastProtectedSelectedLevel() {
        byte[] b = bgra();
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, b);
        BlpImages images = new BlpImages(new MemoryGeneratedStore());
        assertEquals(
                State.READY,
                images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join().state());
        files.add(listed(b), b, List.of(new FileHole(0, 148, KEY)), List.of(KEY));
        assertEquals(
                State.PROTECTED,
                images.decodeLargestWithin(files, FileRef.id(1), 2, Cancellation.NONE)
                        .join()
                        .state());
        MemoryGeneratedStore fresh = new MemoryGeneratedStore();
        images = new BlpImages(fresh);
        files.add(listed(b), b, List.of(new FileHole(148, 16, KEY)), List.of(KEY));
        assertEquals(
                State.PROTECTED,
                images.decodeLargestWithin(files, FileRef.id(1), 2, Cancellation.NONE)
                        .join()
                        .state());
        assertEquals(
                State.READY,
                images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join().state());
    }

    @Test
    void clearMipDecodesWithOtherMipHoleAndAllNeededKeysAreReported() {
        byte[] b = bgra();
        AssetFilesFixture files =
                new AssetFilesFixture()
                        .add(
                                listed(b),
                                b,
                                List.of(
                                        new FileHole(148, 8, "0000000000000001"),
                                        new FileHole(156, 8, KEY)),
                                List.of(KEY, "0000000000000001"));
        BlpImages images = new BlpImages(new MemoryGeneratedStore());
        var failed = images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join();
        assertEquals(State.PROTECTED, failed.state());
        assertEquals(List.of("0000000000000001", KEY), failed.holeKeys());
        var clear = images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join();
        assertEquals(State.READY, clear.state());
        assertEquals(1, clear.image().mipLevel());
        assertEquals(files.opens.get(), files.streamCloses.get());
    }

    @Test
    void encryptedChunksUseBuildAndFingerprintScopedCacheIncludingKnownKeys() {
        byte[] b = bgra();
        AssetFilesFixture first =
                new AssetFilesFixture().add(listed(b), b, List.of(), List.of(KEY));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        BlpImages unknown = new BlpImages(store);
        assertEquals(
                Cache.STORED,
                unknown.decode(first, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        String identity = unknown.identityText(CONTENT, 0);
        GeneratedKey shared = GeneratedKey.hash(identity.getBytes(StandardCharsets.UTF_8));
        assertFalse(store.has(shared).join());
        String scoped =
                BlpImages.scopedIdentityText(identity, first.build(), List.of(KEY), name -> null);
        assertTrue(store.has(GeneratedKey.hash(scoped.getBytes(StandardCharsets.UTF_8))).join());
        BlpImages known = new BlpImages(store, new Limits(), build -> name -> "fingerprint1");
        assertEquals(
                Cache.STORED,
                known.decode(first, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        assertEquals(
                Cache.HIT, known.decode(first, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        AssetFilesFixture other =
                new AssetFilesFixture(
                                new BuildRef("wow", "3".repeat(32), "2".repeat(32), "us"), "enUS")
                        .add(listed(b), b, List.of(), List.of(KEY));
        assertEquals(
                Cache.STORED,
                known.decode(other, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        assertEquals(3, store.keys().size());
    }

    @Test
    void malformedAndDamagedPayloadsAreDeletedThenDecodedAgain() {
        byte[] b = bgra();
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, b);
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        BlpImages images = new BlpImages(store);
        GeneratedKey key =
                GeneratedKey.hash(images.identityText(CONTENT, 0).getBytes(StandardCharsets.UTF_8));
        store.put(List.of(new GeneratedTypes.Put(key, new byte[] {1, 2, 3}))).join();
        assertEquals(
                Cache.STORED,
                images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        store.damage(key);
        assertEquals(
                Cache.STORED,
                images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join().cache());
        assertEquals(2, files.opens.get());
    }

    @Test
    void shortContainerAndInvalidSelectedLengthRefuseBeforeReadingProtectedPixels() {
        byte[] shortBytes = new byte[100];
        AssetFilesFixture files =
                new AssetFilesFixture()
                        .add(
                                listed(shortBytes),
                                shortBytes,
                                List.of(new FileHole(0, 100, KEY)),
                                List.of(KEY));
        BlpImages images = new BlpImages(new MemoryGeneratedStore());
        assertEquals(
                State.INVALID,
                images.inspect(files, FileRef.id(1), Cancellation.NONE).join().state());
        assertEquals(0, files.ranges.get());
        byte[] bad = bgra();
        java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(84, 15);
        files.add(listed(bad), bad, List.of(new FileHole(148, 16, KEY)), List.of(KEY));
        assertEquals(
                State.INVALID,
                images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join().state());
    }

    @Test
    void fileFailuresKeepTypedReasonAndObservation() {
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, bgra());
        files.failOpen(
                1,
                new FileOpen(
                        FileReadState.MISSING, null, "CDN_NOT_FOUND", "not found", Instant.EPOCH));
        var images = new BlpImages(new MemoryGeneratedStore());
        var result = images.decode(files, FileRef.id(1), 0, Cancellation.NONE).join();
        assertEquals(State.FILE_FAILED, result.state());
        assertEquals(FileReadState.MISSING, result.fileState());
        assertEquals(Instant.EPOCH, result.observedAt());
        assertEquals("CDN_NOT_FOUND", result.code());
        assertEquals(
                FileReadState.UNKNOWN_FILE,
                images.decode(files, FileRef.id(2), 0, Cancellation.NONE).join().fileState());
    }

    @Test
    void midStreamTypedFileFailureKeepsItsStateCodeAndTime() {
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, bgra());
        files.failRead(
                1,
                new com.vibeminer.files.FileReadException(
                        FileReadState.UNAVAILABLE, "CDN_TIMEOUT", "timed out", Instant.EPOCH));
        var result =
                new BlpImages(new MemoryGeneratedStore())
                        .decode(files, FileRef.id(1), 0, Cancellation.NONE)
                        .join();
        assertEquals(State.FILE_FAILED, result.state());
        assertEquals(FileReadState.UNAVAILABLE, result.fileState());
        assertEquals("CDN_TIMEOUT", result.code());
        assertEquals(Instant.EPOCH, result.observedAt());
        assertEquals(1, files.streamCloses.get());
    }

    @Test
    void failedStoreWriteReturnsImageWhileReadFailurePropagates() {
        AssetFilesFixture files = new AssetFilesFixture().add(1, CONTENT, bgra());
        StoreView store = new StoreView();
        store.failWrite = true;
        var result = new BlpImages(store).decode(files, FileRef.id(1), 0, Cancellation.NONE).join();
        assertEquals(State.READY, result.state());
        assertEquals(Cache.STORE_FAILED, result.cache());
        store.failRead = true;
        Throwable error =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        new BlpImages(store)
                                                .decode(files, FileRef.id(1), 0, Cancellation.NONE)
                                                .join()));
        assertInstanceOf(GeneratedStoreException.class, error);
    }

    @Test
    void payloadCodecRejectsAllMalformedFramingAndKeepsOriginalRgbaOwned() {
        var image = Blp2Decoder.decode(com.vibeminer.formats.AssetSource.bytes(bgra())).join();
        byte[] payload = BlpImagePayload.write(image);
        assertEquals(44 + 29 * 2 + 16, payload.length);
        assertArrayEquals(image.rgba(), BlpImagePayload.read(payload, 0, new Limits()).rgba());
        for (int i = 0; i < payload.length; i++) {
            assertNull(BlpImagePayload.read(java.util.Arrays.copyOf(payload, i), 0, new Limits()));
        }
        for (int at : new int[] {0, 4, 8, 20, 24, 36, 40, 59, 68, 98}) {
            byte[] corrupt = payload.clone();
            corrupt[at] = (byte) 255;
            assertNull(BlpImagePayload.read(corrupt, 0, new Limits()));
        }
    }

    @Test
    void scopedFingerprintValidationAndCanonicalIdentityAreStable() {
        BlpImages images = new BlpImages(new MemoryGeneratedStore());
        assertEquals(64, images.limitsHash().length());
        String identity = images.identityText(CONTENT, 3);
        assertTrue(
                identity.startsWith(
                        "vibeminer-generated-image v1\n"
                                + CONTENT
                                + "\nblp2-rgba8-v3\nmip=3\nlimits="));
        BuildRef build = new AssetFilesFixture().build();
        assertEquals(
                identity
                        + "\nencoding-in=wow "
                        + build.buildConfig()
                        + " "
                        + build.cdnConfig()
                        + "\nkey 0000000000000001=?\nkey "
                        + KEY
                        + "=good",
                BlpImages.scopedIdentityText(
                        identity,
                        build,
                        List.of(KEY, KEY, "0000000000000001"),
                        name -> name.equals(KEY) ? "good" : null));
        for (String bad : List.of("?", "has space", "", "x".repeat(129))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BlpImages.scopedIdentityText(identity, build, List.of(KEY), name -> bad));
        }
    }

    @Test
    void originalCsharpPayloadRemainsExactAndDecoderRevisionInvalidatesLegacyIdentity()
            throws Exception {
        byte[] source;
        byte[] expected;
        String identity;
        try (var input = getClass().getResourceAsStream("/golden/source.blp")) {
            source = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (var input = getClass().getResourceAsStream("/golden/cache.vmim")) {
            expected = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (var input = getClass().getResourceAsStream("/golden/image-identity.txt")) {
            identity =
                    new String(
                            java.util.Objects.requireNonNull(input).readAllBytes(),
                            StandardCharsets.UTF_8);
        }
        var image = Blp2Decoder.decode(com.vibeminer.formats.AssetSource.bytes(source)).join();
        assertArrayEquals(expected, BlpImagePayload.write(image));
        assertArrayEquals(image.rgba(), BlpImagePayload.read(expected, 0, new Limits()).rgba());
        String legacy = expectedIdentity(identity);
        assertTrue(legacy.contains("\nblp2-rgba8-v2\n"));
        String current = new BlpImages(new MemoryGeneratedStore()).identityText(CONTENT, 0);
        assertEquals(legacy.replace("\nblp2-rgba8-v2\n", "\nblp2-rgba8-v3\n"), current);
        assertNotEquals(
                GeneratedKey.hash(legacy.getBytes(StandardCharsets.UTF_8)),
                GeneratedKey.hash(current.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void canonicalIdentityFixtureAcceptsWindowsTextCheckoutWithoutChangingIdentityBytes() {
        String canonical = new BlpImages(new MemoryGeneratedStore()).identityText(CONTENT, 0);
        String windowsFixture = canonical.replace("\n", "\r\n");
        assertEquals(canonical, expectedIdentity(windowsFixture));
        assertFalse(canonical.contains("\r"));
    }

    private static String expectedIdentity(String fixture) {
        return fixture.replace("\r\n", "\n");
    }

    private static final class StoreView implements GeneratedStore {
        final MemoryGeneratedStore inner = new MemoryGeneratedStore();
        boolean failRead, failWrite;

        public CompletableFuture<Boolean> has(
                GeneratedKey k, com.vibeminer.generated.Cancellation t) {
            return inner.has(k, t);
        }

        public CompletableFuture<GeneratedTypes.Read> read(
                GeneratedKey k, com.vibeminer.generated.Cancellation t) {
            return failRead
                    ? CompletableFuture.failedFuture(
                            new GeneratedStoreException("SCRIPTED", "read failed"))
                    : inner.read(k, t);
        }

        public CompletableFuture<GeneratedTypes.Read> readRange(
                GeneratedKey k, long at, int n, com.vibeminer.generated.Cancellation t) {
            return inner.readRange(k, at, n, t);
        }

        public CompletableFuture<List<GeneratedTypes.PutOutcome>> put(
                List<GeneratedTypes.Put> b, com.vibeminer.generated.Cancellation t) {
            return failWrite
                    ? CompletableFuture.failedFuture(
                            new GeneratedStoreException("SCRIPTED", "write failed"))
                    : inner.put(b, t);
        }

        public CompletableFuture<Void> delete(
                List<GeneratedKey> k, com.vibeminer.generated.Cancellation t) {
            return inner.delete(k, t);
        }

        public CompletableFuture<GeneratedTypes.CompactReport> compact(
                double f, int n, com.vibeminer.generated.Cancellation t) {
            return inner.compact(f, n, t);
        }
    }
}
