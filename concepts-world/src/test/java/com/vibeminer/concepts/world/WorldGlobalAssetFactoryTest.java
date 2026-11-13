package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
@SuppressWarnings("try")
class WorldGlobalAssetFactoryTest {
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Comparison", null, 0, 999L);
    private static final WorldGlobalScopes.Selection SELECT =
            new WorldGlobalScopes.Selection(7, 0, 0xffff_ffffL);

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float v : values) {
            b.putFloat(v);
        }
        return b.array();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var bytes : parts) {
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] chunk(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(tag).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] version(int v) {
        return chunk("MVER", buffer(4).putInt(v).array());
    }

    private static byte[] wdt() {
        var mphd = buffer(32).putInt(0x241);
        var placement = buffer(64).putInt(123).putInt(-1);
        placement.putShort(56, (short) 8).putShort(62, (short) 1024);
        return join(
                version(18),
                chunk("MPHD", mphd.array()),
                chunk("MAIN", new byte[32768]),
                chunk("MAID", new byte[131072]),
                chunk("MODF", placement.array()));
    }

    private static byte[] root(long[] groups, long... images) {
        return root(new byte[0], groups, images);
    }

    /** Recorded MOGN (when non-empty) precedes GFID, as in client roots. */
    private static byte[] root(byte[] names, long[] groups, long... images) {
        var h = buffer(64).putInt(images.length).putInt(groups.length);
        h.position(28);
        h.putInt(0xff123456)
                .putInt(77)
                .put(floats(-1, -1, -1, 2, 2, 2))
                .putShort((short) 5)
                .putShort((short) 0);
        var m = buffer(images.length * 64);
        for (int i = 0; i < images.length; i++) {
            m.putInt(i * 64 + 12, (int) images[i])
                    .putInt(i * 64 + 16, 0xff000000)
                    .putInt(i * 64 + 32, 10);
        }
        var g = buffer(groups.length * 4);
        for (long id : groups) {
            g.putInt((int) id);
        }
        return join(
                version(17),
                chunk("MOHD", h.array()),
                chunk("MOMT", m.array()),
                names.length == 0 ? new byte[0] : chunk("MOGN", names),
                chunk("GFID", g.array()));
    }

    private static byte[] namedGroup(int nameOffset, int descriptionOffset) {
        var bytes = group(false, false);
        // MOGP payload starts after MVER (12 bytes) and the MOGP chunk header (8 bytes).
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(20, nameOffset)
                .putInt(24, descriptionOffset);
        return bytes;
    }

    private static byte[] group(boolean overlap, boolean extraUv) {
        var h = buffer(68);
        h.putInt(8, 0x0a09);
        h.position(12);
        h.put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) (overlap ? 2 : 1));
        var batch = buffer(overlap ? 48 : 24);
        for (int i = 0; i < (overlap ? 2 : 1); i++) {
            batch.putInt(i * 24 + 12, 0)
                    .putShort(i * 24 + 16, (short) 3)
                    .putShort(i * 24 + 20, (short) 2);
        }
        byte[] uv = chunk("MOTV", floats(0, 0, 1, 0, 0, 1));
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 2, 0)),
                                chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                uv,
                                chunk(
                                        "MOVI",
                                        buffer(12)
                                                .putShort((short) 0)
                                                .putShort((short) 1)
                                                .putShort((short) 2)
                                                .putShort((short) 2)
                                                .putShort((short) 1)
                                                .putShort((short) 0)
                                                .array()),
                                chunk("MOPY", new byte[4]),
                                chunk("MOBA", batch.array()),
                                extraUv ? uv : new byte[0])));
    }

    private static byte[] image(boolean bc3, boolean transparentBc1) {
        int block = bc3 ? 16 : 8;
        var b = buffer(148 + 3 * block);
        b.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) (bc3 ? 8 : transparentBc1 ? 1 : 0))
                .put((byte) (bc3 ? 7 : 0))
                .put((byte) 1)
                .putInt(4)
                .putInt(4);
        for (int i = 0; i < 3; i++) {
            int at = 148 + i * block;
            b.putInt(20 + i * 4, at).putInt(84 + i * 4, block);
            int color = bc3 ? at + 8 : at;
            b.putShort(color, (short) (transparentBc1 ? 0 : 0xf800))
                    .putShort(color + 2, (short) (transparentBc1 ? 0xf800 : 0));
            b.putInt(color + 4, transparentBc1 ? -1 : 0);
            if (bc3) {
                b.put(at, (byte) 0).put(at + 1, (byte) 217).put(at + 2, (byte) 8);
            }
        }
        return b.array();
    }

    private static class Files extends WorldTerrainTextureSourceTest.Files {
        Files() {
            sources.clear();
            sources.put(1L, wdt());
            sources.put(123L, root(new long[] {201}, 301));
            sources.put(201L, group(false, false));
            sources.put(301L, image(true, false));
        }

        @Override
        FileReadStream open(long id, Cancellation token) {
            var base = super.open(id, token);
            return new FileReadStream() {
                public long length() {
                    return base.length();
                }

                public long position() {
                    return base.position();
                }

                public void seek(long p) {
                    base.seek(p);
                }

                public List<FileTypes.FileHole> holes() {
                    return base.holes();
                }

                public CompletableFuture<Integer> read(ByteBuffer target, Cancellation token) {
                    token.check();
                    return base.position() == base.length()
                            ? CompletableFuture.completedFuture(-1)
                            : base.read(target, token);
                }

                public void close() {
                    base.close();
                }
            };
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Files files;
        final WorldStreamScopesTest.Tables tables;
        final WorldGlobalScopes scopes;
        final WorldGlobalAssetFactory factory;
        final WorldGlobalScopes.GlobalScope scope;

        Fixture() {
            this(new Files());
        }

        Fixture(Files files) {
            this.files = files;
            tables = new WorldStreamScopesTest.Tables(files);
            scopes = new WorldGlobalScopes(tables.handle(), files.files());
            scope = scopes.create(MAP, Cancellation.NONE);
            scopes.activate(scope);
            factory = new WorldGlobalAssetFactory(scopes);
        }

        WorldGlobalAssetFactory.Manifest inspect() {
            var result = new AtomicReference<WorldGlobalAssetFactory.Manifest>();
            try (var ignored =
                    factory.inspectSources(
                            scope,
                            SELECT,
                            Cancellation.NONE,
                            b -> {
                                result.set(b.manifest());
                                return (AutoCloseable) () -> {};
                            })) {
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return result.get();
        }

        public void close() throws Exception {
            try {
                scopes.closeAsync().get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
            }
            tables.close();
        }
    }

    @Test
    void actualNativeChainHasOnlyDetachedManifestAndPreservesUnbatchedAndOriginalAlphaFacts()
            throws Exception {
        try (var f = new Fixture()) {
            var saved = new AtomicReference<WorldGlobalAssetFactory.Borrow>();
            var closed = new AtomicInteger();
            try (var owner =
                    f.factory.inspectSources(
                            f.scope,
                            SELECT,
                            Cancellation.NONE,
                            b -> {
                                saved.set(b);
                                var m = b.manifest();
                                assertEquals(4, m.consumedBindings());
                                assertEquals(3, m.vertices());
                                assertEquals(2, m.rawTriangles());
                                assertEquals(1, m.drawnTriangles());
                                assertEquals(1, m.batches());
                                assertEquals(16, m.pixels());
                                assertEquals(List.of(201L), m.root().groups());
                                var group = m.groups().getFirst();
                                assertNull(group.name(), "no MOGN block: name stays unknown");
                                assertFalse(m.root().groupNames().present());
                                // Recorded MOGP box (-1..2) in the mesh's mirrored native space.
                                assertArrayEquals(
                                        new float[] {-2, -2, -1, 1, 1, 2},
                                        WorldGlobalAssetFactory.nativeBounds(group.header()));
                                var im = m.images().getFirst();
                                assertEquals(Blp2Types.Encoding.BC3, im.encoding());
                                assertEquals(0, im.alphaMin());
                                assertEquals(217, im.alphaMax());
                                assertEquals(16, im.nonopaque());
                                assertNotEquals(im.sourceRgbaSha256(), im.opaqueRgbaSha256());
                                byte[] expected = new byte[64];
                                for (int p = 0; p < 16; p++) {
                                    expected[p * 4] = (byte) 255;
                                    expected[p * 4 + 3] = (byte) 255;
                                }
                                try {
                                    assertEquals(
                                            HexFormat.of()
                                                    .formatHex(
                                                            MessageDigest.getInstance("SHA-256")
                                                                    .digest(expected)),
                                            im.opaqueRgbaSha256());
                                } catch (java.security.NoSuchAlgorithmException e) {
                                    throw new AssertionError(e);
                                }
                                return (AutoCloseable) closed::incrementAndGet;
                            })) {
                assertEquals(0, closed.get());
            }
            assertEquals(1, closed.get());
            assertThrows(IllegalStateException.class, () -> saved.get().manifest());
            assertEquals(java.util.Set.of(1L, 123L, 201L, 301L), f.files.opens.keySet());
            assertEquals(f.files.totalOpens(), f.files.closed.get());
            for (var field : WorldGlobalAssetFactory.Manifest.class.getDeclaredFields()) {
                assertFalse(field.getType().isArray());
                assertFalse(
                        field.getType()
                                .getSimpleName()
                                .matches(
                                        "AssetInput|AssetAdmission|BuildFiles|WorldGlobalScopes|Image"));
            }
        }
    }

    @Test
    void materialAndCrossRoleAncestryFailuresNeverIssueUnauthorizedDescendantIo() throws Exception {
        try (var f = new Fixture()) {
            ByteBuffer.wrap(f.files.sources.get(123L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(96, 1); // MOMT shader: recorded, drawn as unlit opaque diffuse
            assertEquals(1L, f.inspect().root().materials().getFirst().rawWords().get(1));
        }
        for (int variant = 1; variant < 4; variant++) {
            try (var f = new Fixture()) {
                switch (variant) {
                    case 1 -> f.files.sources.put(123L, root(new long[] {201, 201}, 301));
                    case 2 -> f.files.sources.put(123L, root(new long[] {201}, 201));
                    case 3 -> f.files.sources.put(123L, root(new long[] {201}, 1));
                    default -> throw new AssertionError();
                }
                assertThrows(RuntimeException.class, f::inspect);
                assertEquals(java.util.Set.of(1L, 123L), f.files.opens.keySet());
            }
        }
    }

    @Test
    void recordedGroupNameIsReadAtTheNameOffsetAndQualifiedRootStillQualifies() throws Exception {
        byte[] names = {0, 0, 'H', 'a', 'l', 'l', 0, 'D', 'e', 's', 'c', 0};
        try (var f = new Fixture()) {
            f.files.sources.put(123L, root(names, new long[] {201}, 301));
            f.files.sources.put(201L, namedGroup(2, 7));
            var m = f.inspect();
            assertTrue(m.root().groupNames().present());
            assertEquals("Hall", m.groups().getFirst().name(), "name offset, not description");
        }
        try (var f = new Fixture()) {
            f.files.sources.put(123L, root(names, new long[] {201}, 301));
            f.files.sources.put(201L, namedGroup(names.length + 4, 7));
            assertNull(f.inspect().groups().getFirst().name(), "offset outside block");
        }
    }

    @Test
    void bc1DrawsOpaqueWhetherOrNotItCarriesAlpha() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(301L, image(false, false));
            var im = f.inspect().images().getFirst();
            assertEquals(Blp2Types.Encoding.BC1, im.encoding());
            assertEquals(0, im.nonopaque());
            assertEquals(im.sourceRgbaSha256(), im.opaqueRgbaSha256());
        }
        try (var f = new Fixture()) {
            // One-bit alpha is recorded, then dropped: the profile draws every texel opaque.
            f.files.sources.put(301L, image(false, true));
            var im = f.inspect().images().getFirst();
            assertEquals(Blp2Types.Encoding.BC1, im.encoding());
            assertTrue(im.nonopaque() > 0);
            assertNotEquals(im.sourceRgbaSha256(), im.opaqueRgbaSha256());
        }
    }

    @Test
    void duplicateUvAndOverlappingBatchesCannotProduceManifest() throws Exception {
        for (boolean uv : List.of(false, true)) {
            try (var f = new Fixture()) {
                f.files.sources.put(201L, group(!uv, uv));
                assertThrows(RuntimeException.class, f::inspect);
                assertEquals(0, f.files.opens.getOrDefault(301L, new AtomicInteger()).get());
            }
        }
    }

    @Test
    void changingEachFinalConsumedBindingClosesUnreturnedResourceAndInvalidatesOnlyItsScope()
            throws Exception {
        for (long id : new long[] {1, 123, 201, 301}) {
            try (var f = new Fixture()) {
                var closed = new AtomicInteger();
                var failure =
                        assertThrows(
                                WorldTerrainStreaming.Refusal.class,
                                () ->
                                        f.factory.inspectSources(
                                                f.scope,
                                                SELECT,
                                                Cancellation.NONE,
                                                b -> {
                                                    f.files.changedId = id;
                                                    f.files.changeAfterRead = true;
                                                    return (AutoCloseable) closed::incrementAndGet;
                                                }));
                assertEquals(
                        id == 1 ? "GLOBAL_WDT_BINDING" : "GLOBAL_ASSET_BINDING", failure.site());
                assertEquals(1, closed.get());
                assertFalse(f.scopes.isKnown(f.scope));
            }
        }
    }

    @Test
    void manifestBorrowIsSameThreadAndCloseDuringWriterPreventsResourceEscape() throws Exception {
        try (var f = new Fixture()) {
            var closed = new AtomicInteger();
            var actual = new AtomicReference<CompletableFuture<Void>>();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.factory.inspectSources(
                                    f.scope,
                                    SELECT,
                                    Cancellation.NONE,
                                    b -> {
                                        CompletableFuture.runAsync(
                                                        () ->
                                                                assertThrows(
                                                                        IllegalStateException.class,
                                                                        b::manifest))
                                                .join();
                                        actual.set(f.scopes.closeAsync());
                                        assertFalse(actual.get().isDone());
                                        return (AutoCloseable) closed::incrementAndGet;
                                    }));
            assertEquals(1, closed.get());
            actual.get().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void absentDeclaredLengthsRemainAbsentAndOversizedStreamsAreRejectedBeforeRead()
            throws Exception {
        var reads = new AtomicInteger();
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                var original = super.stat(id, token);
                                if (id == 1 || original.file() == null) {
                                    return original;
                                }
                                var v = original.file();
                                return new FileTypes.FileStat(
                                        original.state(),
                                        new FileTypes.BuildFile(
                                                v.fileDataId(),
                                                v.path(),
                                                v.pathSource(),
                                                null,
                                                v.contentKey(),
                                                v.format(),
                                                v.formatSource(),
                                                v.storage(),
                                                v.encrypted()));
                            }
                        })) {
            var m = f.inspect();
            for (var r : m.receipts()) {
                org.junit.jupiter.api.Assertions.assertNull(r.declared());
                assertEquals(f.files.sources.get(r.id()).length, r.length());
            }
        }
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                var original = super.stat(id, token);
                                if (id != 123) {
                                    return original;
                                }
                                var v = original.file();
                                return new FileTypes.FileStat(
                                        original.state(),
                                        new FileTypes.BuildFile(
                                                v.fileDataId(),
                                                v.path(),
                                                v.pathSource(),
                                                null,
                                                v.contentKey(),
                                                v.format(),
                                                v.formatSource(),
                                                v.storage(),
                                                v.encrypted()));
                            }

                            @Override
                            FileReadStream open(long id, Cancellation token) {
                                var original = super.open(id, token);
                                if (id != 123) {
                                    return original;
                                }
                                return new FileReadStream() {
                                    public long length() {
                                        return GlobalInteriorLimits.ROOT_BYTES + 1L;
                                    }

                                    public long position() {
                                        return 0;
                                    }

                                    public void seek(long p) {
                                        throw new AssertionError();
                                    }

                                    public List<FileTypes.FileHole> holes() {
                                        return List.of();
                                    }

                                    public CompletableFuture<Integer> read(
                                            ByteBuffer target, Cancellation c) {
                                        reads.incrementAndGet();
                                        throw new AssertionError("Oversized stream read");
                                    }

                                    public void close() {
                                        original.close();
                                    }
                                };
                            }
                        })) {
            assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
            assertEquals(0, reads.get());
            assertEquals(f.files.totalOpens(), f.files.closed.get());
        }
    }

    @Test
    void heldActualRawReadKeepsClosePendingUntilRealReadAndStreamDrain() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new CompletableFuture<Integer>();
        var observed = new AtomicReference<RuntimeException>();
        var originalStop = new java.util.concurrent.CancellationException();
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileReadStream open(long id, Cancellation token) {
                                var original = super.open(id, token);
                                if (id != 201) {
                                    return original;
                                }
                                return new FileReadStream() {
                                    public long length() {
                                        return original.length();
                                    }

                                    public long position() {
                                        return original.position();
                                    }

                                    public void seek(long p) {
                                        original.seek(p);
                                    }

                                    public List<FileTypes.FileHole> holes() {
                                        return original.holes();
                                    }

                                    public CompletableFuture<Integer> read(
                                            ByteBuffer target, Cancellation c) {
                                        entered.countDown();
                                        return release;
                                    }

                                    public void close() {
                                        original.close();
                                    }
                                };
                            }
                        })) {
            var result = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    f.inspect();
                                    result.completeExceptionally(
                                            new AssertionError("Canceled work escaped"));
                                } catch (RuntimeException expected) {
                                    observed.set(expected);
                                    result.complete(null);
                                } catch (Throwable failure) {
                                    result.completeExceptionally(failure);
                                }
                            });
            try {
                org.junit.jupiter.api.Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
                var closed = f.scopes.closeAsync();
                assertFalse(closed.isDone());
                assertFalse(result.isDone());
                release.completeExceptionally(originalStop);
                result.get(3, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertSame(originalStop, observed.get());
                assertEquals(
                        java.util.concurrent.CancellationException.class,
                        observed.get().getClass());
                org.junit.jupiter.api.Assertions.assertNull(observed.get().getCause());
                assertEquals(0, observed.get().getSuppressed().length);
                closed.get(3, TimeUnit.SECONDS);
                assertEquals(f.files.totalOpens(), f.files.closed.get());
                assertEquals(0, f.files.opens.getOrDefault(301L, new AtomicInteger()).get());
            } finally {
                release.completeExceptionally(new java.util.concurrent.CancellationException());
            }
        }
    }

    @Test
    void missingStrictEofAndWriterLocalFailureCannotTransferUnreturnedResource() throws Exception {
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileReadStream open(long id, Cancellation token) {
                                var original = super.open(id, token);
                                if (id != 301) {
                                    return original;
                                }
                                return new FileReadStream() {
                                    public long length() {
                                        return original.length();
                                    }

                                    public long position() {
                                        return original.position();
                                    }

                                    public void seek(long p) {
                                        original.seek(p);
                                    }

                                    public List<FileTypes.FileHole> holes() {
                                        return original.holes();
                                    }

                                    public CompletableFuture<Integer> read(
                                            ByteBuffer target, Cancellation c) {
                                        return position() == length()
                                                ? CompletableFuture.completedFuture(0)
                                                : original.read(target, c);
                                    }

                                    public void close() {
                                        original.close();
                                    }
                                };
                            }
                        })) {
            var failure = assertThrows(IllegalStateException.class, f::inspect);
            assertEquals("Asset source EOF differs", failure.getMessage());
            assertEquals(f.files.totalOpens(), f.files.closed.get());
        }
        try (var f = new Fixture()) {
            var localClosed = new AtomicInteger();
            var original = new AssertionError("Writer failed before ownership return");
            var actual =
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    f.factory.inspectSources(
                                            f.scope,
                                            SELECT,
                                            Cancellation.NONE,
                                            b -> {
                                                // The fixed writer owns any resource that it
                                                // creates but never returns.
                                                try (AutoCloseable candidate =
                                                        localClosed::incrementAndGet) {
                                                    throw original;
                                                } catch (Exception impossible) {
                                                    throw new AssertionError(impossible);
                                                }
                                            }));
            org.junit.jupiter.api.Assertions.assertSame(original, actual);
            assertEquals(1, localClosed.get());
            assertEquals(f.files.totalOpens(), f.files.closed.get());
        }
    }

    @Test
    void lastDerivedFinalStatCannotChangeMapOrWdtAfterFinalAuthorityCheck() throws Exception {
        for (boolean map : List.of(false, true)) {
            var writerDone = new java.util.concurrent.atomic.AtomicBoolean();
            var tables = new AtomicReference<WorldStreamScopesTest.Tables>();
            var files =
                    new Files() {
                        @Override
                        FileTypes.FileStat stat(long id, Cancellation token) {
                            var result = super.stat(id, token);
                            if (writerDone.get() && id == 301) {
                                if (map) {
                                    tables.get().identity = "changed-final-map-receipt";
                                } else {
                                    changedId = 1;
                                    changeAfterRead = true;
                                }
                            }
                            return result;
                        }
                    };
            try (var f = new Fixture(files)) {
                tables.set(f.tables);
                var closed = new AtomicInteger();
                assertThrows(
                        WorldTerrainStreaming.Refusal.class,
                        () -> {
                            try (var result =
                                    f.factory.inspectSources(
                                            f.scope,
                                            SELECT,
                                            Cancellation.NONE,
                                            b -> {
                                                writerDone.set(true);
                                                return (AutoCloseable) closed::incrementAndGet;
                                            })) {}
                        });
                assertEquals(1, closed.get());
                assertFalse(f.scopes.isKnown(f.scope));
            }
        }
    }

    @Test
    void changingProviderLengthCannotWidenAnyValidatedRawAllocation() throws Exception {
        for (long selected : new long[] {1, 123, 201, 301}) {
            int cap = selected == 1 || selected == 201 ? 1 << 20 : 64 << 10;
            try (var f =
                    new Fixture(
                            new Files() {
                                @Override
                                FileTypes.FileStat stat(long id, Cancellation token) {
                                    var original = super.stat(id, token);
                                    if (id != selected) {
                                        return original;
                                    }
                                    var v = original.file();
                                    return new FileTypes.FileStat(
                                            original.state(),
                                            new FileTypes.BuildFile(
                                                    v.fileDataId(),
                                                    v.path(),
                                                    v.pathSource(),
                                                    null,
                                                    v.contentKey(),
                                                    v.format(),
                                                    v.formatSource(),
                                                    v.storage(),
                                                    v.encrypted()));
                                }

                                @Override
                                FileReadStream open(long id, Cancellation token) {
                                    var original = super.open(id, token);
                                    if (id != selected) {
                                        return original;
                                    }
                                    var calls = new AtomicInteger();
                                    return new FileReadStream() {
                                        public long length() {
                                            return calls.incrementAndGet() <= 2
                                                    ? sources.get(id).length
                                                    : cap + 1;
                                        }

                                        public long position() {
                                            return original.position();
                                        }

                                        public void seek(long p) {
                                            original.seek(p);
                                        }

                                        public List<FileTypes.FileHole> holes() {
                                            return original.holes();
                                        }

                                        public CompletableFuture<Integer> read(
                                                ByteBuffer target, Cancellation c) {
                                            org.junit.jupiter.api.Assertions.assertTrue(
                                                    target.capacity() <= cap,
                                                    "Changing provider getter widened validated allocation");
                                            return original.read(target, c);
                                        }

                                        public void close() {
                                            original.close();
                                        }
                                    };
                                }
                            })) {
                assertEquals(4, f.inspect().consumedBindings());
                assertEquals(f.files.totalOpens(), f.files.closed.get());
            }
        }
    }

    @Test
    void shortLimitAndLyingReadCountAreFatalAndDrainActualStream() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            int mode = variant;
            try (var f =
                    new Fixture(
                            new Files() {
                                @Override
                                FileReadStream open(long id, Cancellation token) {
                                    var original = super.open(id, token);
                                    if (id != 301) {
                                        return original;
                                    }
                                    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
                                    return new FileReadStream() {
                                        public long length() {
                                            return original.length();
                                        }

                                        public long position() {
                                            return original.position();
                                        }

                                        public void seek(long p) {
                                            original.seek(p);
                                        }

                                        public List<FileTypes.FileHole> holes() {
                                            return original.holes();
                                        }

                                        public CompletableFuture<Integer> read(
                                                ByteBuffer target, Cancellation c) {
                                            if (!first.compareAndSet(true, false)) {
                                                if (mode == 2) {
                                                    target.put((byte) 0);
                                                }
                                                if (mode == 3) {
                                                    target.limit(0);
                                                }
                                                return CompletableFuture.completedFuture(-1);
                                            }
                                            if (mode == 1) {
                                                target.limit(target.limit() - 1);
                                            }
                                            return original.read(target, c)
                                                    .thenApply(n -> mode == 0 ? n + 1 : n);
                                        }

                                        public void close() {
                                            original.close();
                                        }
                                    };
                                }
                            })) {
                var failure = assertThrows(IllegalStateException.class, f::inspect);
                assertFalse(failure instanceof WorldTerrainStreaming.Refusal);
                assertEquals(f.files.totalOpens(), f.files.closed.get());
            }
        }
    }

    @Test
    void declarationReaderAlsoRejectsLyingCountsBeforeParsingAndDrains() throws Exception {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean();
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileReadStream open(long id, Cancellation token) {
                                var original = super.open(id, token);
                                if (id != 1 || !enabled.get()) {
                                    return original;
                                }
                                return new FileReadStream() {
                                    public long length() {
                                        return original.length();
                                    }

                                    public long position() {
                                        return original.position();
                                    }

                                    public void seek(long p) {
                                        original.seek(p);
                                    }

                                    public List<FileTypes.FileHole> holes() {
                                        return original.holes();
                                    }

                                    public CompletableFuture<Integer> read(
                                            ByteBuffer target, Cancellation c) {
                                        return original.read(target, c).thenApply(n -> n + 1);
                                    }

                                    public void close() {
                                        original.close();
                                    }
                                };
                            }
                        })) {
            enabled.set(true);
            var failure = assertThrows(IllegalStateException.class, f::inspect);
            assertFalse(failure instanceof WorldTerrainStreaming.Refusal);
            assertEquals(f.files.totalOpens(), f.files.closed.get());
            assertEquals(0, f.files.opens.getOrDefault(123L, new AtomicInteger()).get());
        }
    }

    @Test
    void ownedPayloadPreservesExactTransformUvWindingAndExpiresScalarBorrow() throws Exception {
        try (var f = new Fixture()) {
            ByteBuffer.wrap(f.files.sources.get(201L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(184, -0f);
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                var saved = new AtomicReference<WorldGlobalAssetFactory.AssetBorrow>();
                asset.withBorrow(
                        b -> {
                            saved.set(b);
                            var busy =
                                    assertThrows(
                                            WorldTerrainStreaming.Refusal.class,
                                            () -> asset.withBorrow(nested -> nested.vertices()));
                            assertEquals("GLOBAL_ASSET_CAPACITY", busy.site());
                            assertEquals(3, b.vertices());
                            assertEquals(6, b.indices());
                            assertEquals(0, Float.floatToRawIntBits(b.vertex(0, 0)));
                            assertEquals(0x80000000, Float.floatToRawIntBits(b.vertex(0, 3)));
                            assertEquals(-1, b.vertex(1, 0));
                            assertEquals(-2, b.vertex(2, 1));
                            float ax = b.vertex(1, 0) - b.vertex(0, 0),
                                    ay = b.vertex(1, 1) - b.vertex(0, 1);
                            float bx = b.vertex(2, 0) - b.vertex(0, 0),
                                    by = b.vertex(2, 1) - b.vertex(0, 1);
                            assertEquals(2, ax * by - ay * bx);
                            assertEquals(
                                    List.of(new WorldGlobalAssetFactory.DrawSpan(0, 0, 3, 0)),
                                    b.spans());
                            for (int i = 0; i < 16; i++) {
                                assertEquals(255, b.imageByte(0, i * 4));
                                assertTrue(
                                        java.util.Set.of(0, 217)
                                                .contains(b.imageByte(0, i * 4 + 3)));
                            }
                            CompletableFuture.runAsync(
                                            () ->
                                                    assertThrows(
                                                            IllegalStateException.class,
                                                            () -> b.vertex(0, 0)))
                                    .join();
                            return null;
                        });
                assertThrows(IllegalStateException.class, () -> saved.get().vertex(0, 0));
                assertEquals(1, f.scopes.payloadCredits());
            }
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    @Test
    void twoSourceWideCreditsRefuseThirdBeforeIoAcrossDifferentFactories() throws Exception {
        try (var f = new Fixture()) {
            var other = new WorldGlobalAssetFactory(f.scopes);
            try (var a = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE);
                    var b = other.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                int opens = f.files.totalOpens(), captures = f.tables.captures.get();
                var refused =
                        assertThrows(
                                WorldTerrainStreaming.Refusal.class,
                                () -> other.createOwned(f.scope, SELECT, Cancellation.NONE));
                assertEquals("GLOBAL_ASSET_CAPACITY", refused.site());
                assertEquals(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER, refused.reason());
                assertEquals(opens, f.files.totalOpens());
                assertEquals(captures, f.tables.captures.get());
                assertEquals(2, f.scopes.payloadCredits());
                a.close();
                try (var next = other.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                    assertEquals(2, f.scopes.payloadCredits());
                }
            }
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    @Test
    void payloadBorrowRequiresStillKnownScopeButDetachedFactsRemainHistorical() throws Exception {
        try (var f = new Fixture();
                var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
            var next = f.scopes.create(MAP, Cancellation.NONE);
            f.scopes.activate(next);
            assertThrows(
                    WorldTerrainStreaming.Refusal.class, () -> asset.withBorrow(b -> b.vertices()));
            assertEquals(3, asset.facts().vertices());
            assertEquals(1, f.scopes.payloadCredits());
        }
    }

    @Test
    void wholeNumberUvsFarOutsideTheTextureDrawBitForBit() throws Exception {
        // Molten Core on 1.60.1.70862: two groups record vertices at exactly (-8192, 8192). Under
        // repeat addressing that is the texel at (0, 0), and the triangles to them span many
        // repeats, as in the client; the scene file keeps the recorded floats unchanged.
        try (var f = new Fixture()) {
            ByteBuffer.wrap(f.files.sources.get(201L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(184, -8192f)
                    .putFloat(188, 8192f);
            assertEquals(3, f.inspect().vertices());
            assertEquals(0, f.inspect().skippedGroups());
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                asset.withBorrow(
                        b -> {
                            assertEquals(-8192f, b.vertex(0, 3));
                            assertEquals(8192f, b.vertex(0, 4));
                            return null;
                        });
            }
        }
    }

    @Test
    void outOfRangePositionsOrUvsSkipTheGroupAndAnEmptyInteriorIsRefused() throws Exception {
        try (var f = new Fixture()) {
            // Tiled texture coordinates far past 16 are ordinary and draw.
            ByteBuffer.wrap(f.files.sources.get(201L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(184, 17);
            assertEquals(3, f.inspect().vertices());
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                assertEquals(3, asset.facts().vertices());
            }
            assertEquals(0, f.scopes.payloadCredits());
        }
        for (int bad = 0; bad < 3; bad++) {
            try (var f = new Fixture()) {
                var g = ByteBuffer.wrap(f.files.sources.get(201L)).order(ByteOrder.LITTLE_ENDIAN);
                switch (bad) {
                    case 0 -> g.putFloat(96, GlobalInteriorLimits.POSITION + 1);
                    case 1 -> g.putFloat(184, GlobalInteriorLimits.UV * 2);
                    default -> g.putFloat(184, Float.NaN);
                }
                // The only group is skipped, so nothing is left to draw: a bounded refusal.
                var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
                assertEquals("GLOBAL_ASSET_PROFILE", refused.site());
                assertEquals(
                        java.util.Optional.of("none of the interior's WMO groups can be read"),
                        refused.detail());
                assertThrows(
                        WorldTerrainStreaming.Refusal.class,
                        () -> f.factory.createOwned(f.scope, SELECT, Cancellation.NONE));
                assertEquals(0, f.scopes.payloadCredits());
            }
        }
    }

    /** BC1 BLP2 of side {@code side} with a full mip chain, or only its top level. */
    private static byte[] squareImage(int side, boolean mips) {
        var payload = new ByteArrayOutputStream();
        var header = buffer(148);
        header.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) (mips ? 1 : 0))
                .putInt(side)
                .putInt(side);
        int levels = mips ? 32 - Integer.numberOfLeadingZeros(side) : 1;
        for (int mip = 0, s = side; mip < levels; mip++, s >>= 1) {
            int bytes = ((s + 3) / 4) * ((s + 3) / 4) * 8;
            header.putInt(20 + mip * 4, 148 + payload.size()).putInt(84 + mip * 4, bytes);
            for (int at = 0; at < bytes; at += 8) {
                payload.writeBytes(
                        buffer(8).putShort((short) 0x07e0).putShort((short) 0).putInt(0).array());
            }
        }
        return join(header.array(), payload.toByteArray());
    }

    @Test
    void imagesLargerThan256AreDrawnFromTheirFirstSmallEnoughMip() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(301L, squareImage(1024, true));
            var image = f.inspect().images().getFirst();
            assertEquals(256, image.width());
            assertEquals(256, image.height());
            assertEquals(9, image.mipCount(), "256 down to 1 remain below the drawn level");
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                asset.withBorrow(
                        b -> {
                            assertEquals(256 * 256 * 4, b.imageBytes(0));
                            assertEquals(255, b.imageByte(0, 1), "green from the drawn mip");
                            return null;
                        });
            }
        }
        try (var f = new Fixture()) {
            f.files.sources.put(301L, squareImage(512, false));
            var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
            assertEquals("GLOBAL_ASSET_PROFILE", refused.site());
            assertEquals(
                    java.util.Optional.of("a texture larger than 256 pixels has no smaller mip"),
                    refused.detail());
        }
    }

    private static final String ENCRYPTED_GROUP =
            "a WMO group file is encrypted and its decryption key is not available";

    private static FileTypes.FileOpen failed(FileTypes.FileReadState state, String code) {
        return new FileTypes.FileOpen(state, null, code, null, null);
    }

    private static void assertPending(Fixture f) {
        var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
        assertEquals("GLOBAL_ASSET_PENDING", refused.site());
        assertEquals(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER, refused.reason());
        assertTrue(refused.detail().orElseThrow().contains("need the network"));
        assertThrows(
                WorldTerrainStreaming.Refusal.class,
                () -> f.factory.createOwned(f.scope, SELECT, Cancellation.NONE));
        assertEquals(0, f.scopes.payloadCredits());
    }

    @Test
    void missingAndEncryptedGroupsAreSkippedWithTheirReasons() throws Exception {
        try (var f = new Fixture()) {
            // 998 is not in the build at all: a permanent absence.
            f.files.sources.put(123L, root(new long[] {201, 998}, 301));
            var m = f.inspect();
            assertEquals(1, m.groups().size());
            assertEquals(java.util.Map.of("the group file is not readable", 1), m.skippedReasons());
        }
        for (int variant = 0; variant < 3; variant++) {
            try (var f = new Fixture()) {
                // Holes, PARTIAL and PROTECTED all mean an unknown decryption key: retrying never
                // helps, so the group is skipped, not the interior refused.
                f.files.sources.put(202L, f.files.sources.get(201L));
                f.files.sources.put(123L, root(new long[] {201, 202}, 301));
                switch (variant) {
                    case 0 -> f.files.protectedId = 202;
                    case 1 ->
                            f.files.failedOpens.put(
                                    202L, failed(FileTypes.FileReadState.PARTIAL, null));
                    default ->
                            f.files.failedOpens.put(
                                    202L, failed(FileTypes.FileReadState.PROTECTED, null));
                }
                var m = f.inspect();
                assertEquals(1, m.groups().size(), "variant " + variant);
                assertEquals(java.util.Map.of(ENCRYPTED_GROUP, 1), m.skippedReasons());
            }
        }
        try (var f = new Fixture()) {
            // An encrypted root or image cannot be skipped: the interior is refused, not retried.
            f.files.failedOpens.put(301L, failed(FileTypes.FileReadState.PROTECTED, null));
            var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
            assertEquals("SOURCE_INPUT", refused.site());
            assertEquals(
                    java.util.Optional.of(
                            "a WMO image file is encrypted and its decryption key is not"
                                    + " available"),
                    refused.detail());
        }
    }

    @Test
    void networkReadsOfAGroupRootOrImageRefuseTheInteriorForARetry() throws Exception {
        try (var f = new Fixture()) {
            f.files.failedOpens.put(201L, failed(FileTypes.FileReadState.NEEDS_NETWORK, null));
            assertPending(f);
        }
        try (var f = new Fixture()) {
            f.files.failedOpens.put(123L, failed(FileTypes.FileReadState.NEEDS_NETWORK, null));
            assertPending(f);
        }
        try (var f = new Fixture()) {
            f.files.failedOpens.put(
                    301L, failed(FileTypes.FileReadState.UNAVAILABLE, "CDN_OFFLINE"));
            assertPending(f);
        }
        try (var f = new Fixture()) {
            f.files.failedOpens.put(201L, failed(FileTypes.FileReadState.STORE_FAILED, null));
            assertPending(f);
        }
        try (var f = new Fixture()) {
            // Any other unavailable state is a missing group: skipped.
            f.files.sources.put(202L, f.files.sources.get(201L));
            f.files.sources.put(123L, root(new long[] {201, 202}, 301));
            f.files.failedOpens.put(202L, failed(FileTypes.FileReadState.UNAVAILABLE, "OTHER"));
            assertEquals(
                    java.util.Map.of("the group file is not readable", 1),
                    f.inspect().skippedReasons());
        }
        for (var state : FileTypes.FileReadState.values()) {
            var expected =
                    switch (state) {
                        case NEEDS_NETWORK, STORE_FAILED -> WorldGlobalScopes.ReadOutcome.NETWORK;
                        case PARTIAL, PROTECTED, WRONG_KEY ->
                                WorldGlobalScopes.ReadOutcome.ENCRYPTED;
                        default -> WorldGlobalScopes.ReadOutcome.UNAVAILABLE;
                    };
            assertEquals(expected, WorldGlobalScopes.classify(state, null), state.name());
        }
        for (String code : new String[] {"CDN_OFFLINE", "OBJECT_ACCESS_NO_CDN_CONTEXT"}) {
            assertEquals(
                    WorldGlobalScopes.ReadOutcome.NETWORK,
                    WorldGlobalScopes.classify(FileTypes.FileReadState.UNAVAILABLE, code));
        }
    }

    @Test
    void anUntexturedBlendedBatchIsLeftOutWithoutSkippingItsGroup() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(302L, f.files.sources.get(301L));
            byte[] root = withBlend(root(new long[] {201}, 301, 302), 1, 3);
            // Material 1 names no texture (word 3 is 0).
            ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(92 + 64 + 12, 0);
            f.files.sources.put(123L, root);
            f.files.sources.put(201L, twoBatchGroup(0, 1));
            var m = f.inspect();
            assertEquals(1, m.groups().size());
            assertEquals(1, m.groups().getFirst().batches());
            assertEquals(1, m.skippedBlendedBatches());
            assertEquals(0, m.skippedGroups());
        }
    }

    @Test
    void runningTotalsOverTheProfileRefuseTheInteriorInsteadOfSkipping() throws Exception {
        try (var f = new Fixture()) {
            // 33 full groups of 16,384 vertices pass 512Ki only at the last one.
            int count = GlobalInteriorLimits.VERTICES / 16384 + 1;
            long[] ids = new long[count];
            byte[] large = largeGroup();
            for (int i = 0; i < count; i++) {
                ids[i] = 1000 + i;
                f.files.sources.put(ids[i], large);
            }
            f.files.sources.put(123L, root(ids, 301));
            var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::inspect);
            assertEquals("GLOBAL_ASSET_PROFILE", refused.site());
            assertEquals(
                    java.util.Optional.of(
                            "the interior has more than "
                                    + GlobalInteriorLimits.VERTICES
                                    + " vertices"),
                    refused.detail());
        }
    }

    /** One group of two triangles drawn by two batches: [0,3) and [3,6). */
    private static byte[] twoBatchGroup(int firstMaterial, int secondMaterial) {
        var h = buffer(68);
        h.putInt(8, 0x0a09);
        h.position(12);
        h.put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) 2);
        var batch = buffer(48);
        for (int i = 0; i < 2; i++) {
            batch.putInt(i * 24 + 12, i * 3)
                    .putShort(i * 24 + 16, (short) 3)
                    .putShort(i * 24 + 20, (short) 2)
                    .put(i * 24 + 23, (byte) (i == 0 ? firstMaterial : secondMaterial));
        }
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 2, 0)),
                                chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                chunk("MOTV", floats(0, 0, 1, 0, 0, 1)),
                                chunk(
                                        "MOVI",
                                        buffer(12)
                                                .putShort((short) 0)
                                                .putShort((short) 1)
                                                .putShort((short) 2)
                                                .putShort((short) 2)
                                                .putShort((short) 1)
                                                .putShort((short) 0)
                                                .array()),
                                chunk("MOPY", new byte[4]),
                                chunk("MOBA", batch.array()))));
    }

    /** The root fixture with material {@code material}'s MOMT blend word set. */
    private static byte[] withBlend(byte[] root, int material, int blend) {
        // MVER (12) + MOHD chunk (8 + 64) + MOMT header (8), then 64-byte materials.
        ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(92 + material * 64 + 8, blend);
        return root;
    }

    /**
     * Issue #297: alpha-blend (2) and additive (3) batches now draw with the outdoor mapping, their
     * textures read; only modulate (4) and above are left out and counted.
     */
    @Test
    void blendedAndAdditiveBatchesDrawWithTheSharedMapping() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(302L, f.files.sources.get(301L));
            for (int blend : new int[] {2, 3}) {
                f.files.sources.put(
                        123L, withBlend(root(new long[] {201, 202}, 301, 302), 1, blend));
                f.files.sources.put(201L, twoBatchGroup(0, 1));
                f.files.sources.put(202L, twoBatchGroup(1, 1));
                var m = f.inspect();
                assertEquals(2, m.groups().size());
                assertEquals(java.util.Map.of(), m.skippedReasons());
                assertEquals(2, m.groups().getFirst().batches());
                assertEquals(0, m.skippedBlendedBatches());
                assertEquals(2, m.images().size(), "the blended material's texture is read");
            }
        }
    }

    @Test
    void modulatedBatchesAreLeftOutAndCounted() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(302L, f.files.sources.get(301L));
            for (int blend : new int[] {4, 5}) {
                f.files.sources.put(
                        123L, withBlend(root(new long[] {201, 202}, 301, 302), 1, blend));
                f.files.sources.put(201L, twoBatchGroup(0, 1));
                f.files.sources.put(202L, twoBatchGroup(1, 1));
                var m = f.inspect();
                assertEquals(1, m.groups().size(), "a group of only modulated batches is skipped");
                assertEquals(
                        java.util.Map.of("only surfaces with unsupported blend modes", 1),
                        m.skippedReasons());
                var group = m.groups().getFirst();
                assertEquals(1, group.batches());
                assertEquals(1, group.blendedBatches());
                assertEquals(1, group.drawnTriangles());
                assertEquals(1, m.skippedBlendedBatches());
                assertEquals(1, m.images().size(), "the modulated material's texture is not read");
                try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                    asset.withBorrow(
                            b -> {
                                assertEquals(1, b.spans().size());
                                assertEquals(0, b.spans().getFirst().material());
                                assertEquals(0, b.spans().getFirst().firstIndex());
                                return null;
                            });
                }
            }
            // Alpha-key (1) still draws, now as a cutout.
            f.files.sources.put(123L, withBlend(root(new long[] {201}, 301, 302), 1, 1));
            assertEquals(2, f.inspect().groups().getFirst().batches());
        }
    }

    @Test
    void unreadableOrUnlistedGroupsAreSkippedWithTheirReasonAndTheRestDraws() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(123L, root(new long[] {201, 0, 202, 203}, 301));
            f.files.sources.put(202L, group(true, false)); // overlapping batches
            f.files.sources.put(203L, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
            var m = f.inspect();
            assertEquals(1, m.groups().size());
            assertEquals(0, m.groups().getFirst().ordinal(), "drawn ordinals stay dense");
            assertEquals(3, m.skippedGroups());
            assertEquals(
                    java.util.Map.of(
                            "no group file is listed", 1,
                            "overlapping batches", 1,
                            "unsupported group layout", 1),
                    stripParserDetail(m.skippedReasons()));
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                assertEquals(3, asset.facts().vertices());
            }
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    @Test
    void manyGroupsMaterialsAndImagesBeyondTheFirstProfileQualify() throws Exception {
        try (var f = new Fixture()) {
            int groupCount = 40, imageCount = 30;
            long[] groups = new long[groupCount], images = new long[imageCount];
            for (int i = 0; i < groupCount; i++) {
                groups[i] = 1000 + i;
                var bytes = group(false, false);
                // Each group draws material i % imageCount (MOBA material byte at batch +23).
                int moba = bytes.length - 24;
                bytes[moba + 23] = (byte) (i % imageCount);
                f.files.sources.put(groups[i], bytes);
            }
            for (int i = 0; i < imageCount; i++) {
                images[i] = 2000 + i;
                f.files.sources.put(images[i], image(i % 2 == 0, false));
            }
            f.files.sources.put(123L, root(groups, images));
            var m = f.inspect();
            assertEquals(groupCount, m.groups().size());
            assertEquals(imageCount, m.images().size());
            assertEquals(groupCount * 3, m.vertices());
            assertEquals(groupCount, m.batches());
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                asset.withBorrow(
                        b -> {
                            assertEquals(groupCount, b.spans().size());
                            assertEquals(imageCount - 1, b.spans().get(imageCount - 1).material());
                            assertEquals(3 * (groupCount - 1), b.index(6 * (groupCount - 1)));
                            return null;
                        });
            }
        }
    }

    private static java.util.Map<String, Integer> stripParserDetail(
            java.util.Map<String, Integer> reasons) {
        var result = new java.util.TreeMap<String, Integer>();
        reasons.forEach((k, v) -> result.merge(k.replaceAll(" \\(.*\\)$", ""), v, Integer::sum));
        return result;
    }

    private static byte[] largeGroup() {
        int vertices = 16384, triangles = 16384;
        var h = buffer(68);
        h.putInt(8, 0x0a09);
        h.position(12);
        h.put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) 1);
        ByteBuffer positions = buffer(vertices * 12),
                normals = buffer(vertices * 12),
                uv = buffer(vertices * 8),
                indices = buffer(triangles * 6);
        for (int v = 0; v < vertices; v++) {
            positions.putFloat((v % 100) / 100f).putFloat(0).putFloat(0);
            normals.putFloat(0).putFloat(0).putFloat(1);
            uv.putFloat(0).putFloat(0);
        }
        for (int i = 0; i < triangles * 3; i++) {
            indices.putShort((short) (i % vertices));
        }
        var batch =
                buffer(24)
                        .putInt(12, 0)
                        .putShort(16, (short) (triangles * 3))
                        .putShort(20, (short) (vertices - 1));
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", positions.array()),
                                chunk("MONR", normals.array()),
                                chunk("MOTV", uv.array()),
                                chunk("MOVI", indices.array()),
                                chunk("MOPY", new byte[triangles * 2]),
                                chunk("MOBA", batch.array()))));
    }

    private static byte[] largeOpaqueImage() {
        var payload = new ByteArrayOutputStream();
        var header = buffer(148);
        header.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 1)
                .putInt(256)
                .putInt(256);
        for (int mip = 0, side = 256; side > 0; mip++, side >>= 1) {
            int bytes = ((side + 3) / 4) * ((side + 3) / 4) * 8;
            header.putInt(20 + mip * 4, 148 + payload.size()).putInt(84 + mip * 4, bytes);
            for (int at = 0; at < bytes; at += 8) {
                payload.writeBytes(
                        buffer(8).putShort((short) 0xf800).putShort((short) 0).putInt(0).array());
            }
        }
        return join(header.array(), payload.toByteArray());
    }

    @Test
    void fullGroupsGrowTheMeshArraysAndOnlyDrawnMaterialsReadImages() throws Exception {
        try (var f = new Fixture()) {
            long[] ids = new long[18];
            for (int i = 0; i < 18; i++) {
                ids[i] = 301 + i;
                f.files.sources.put(ids[i], largeOpaqueImage());
            }
            f.files.sources.put(123L, root(new long[] {201, 202, 203}, ids));
            f.files.sources.put(201L, largeGroup());
            f.files.sources.put(202L, largeGroup());
            f.files.sources.put(203L, largeGroup());
            try (var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                asset.withBorrow(
                        b -> {
                            assertEquals(49152, b.vertices());
                            assertEquals(147456, b.indices());
                            assertEquals(1, b.imageCount(), "only material 0 is drawn");
                            assertEquals(16384, b.index(49152));
                            assertEquals(32768, b.index(98304));
                            assertEquals(3, b.spans().size());
                            assertEquals(98304, b.spans().get(2).firstIndex());
                            assertEquals(262144, b.imageBytes(0));
                            return null;
                        });
                var mesh = asset.getClass().getDeclaredField("mesh");
                mesh.setAccessible(true);
                var indices = asset.getClass().getDeclaredField("indices");
                indices.setAccessible(true);
                // Doubling from one full group: 1, 2, then 4 groups' worth.
                assertEquals(4 * 16384 * 5, ((float[]) mesh.get(asset)).length);
                assertEquals(4 * 49152, ((int[]) indices.get(asset)).length);
                assertEquals(65536, asset.facts().pixels());
            }
            for (int i = 1; i < 18; i++) {
                assertEquals(0, f.files.opens(301 + i), "unused material image " + i);
            }
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    @Test
    void changedFinalBindingsCloseUnreturnedPayloadAndItsCredit() throws Exception {
        for (long changed : new long[] {1, 123, 201, 301}) {
            var enabled = new java.util.concurrent.atomic.AtomicBoolean();
            try (var f =
                    new Fixture(
                            new Files() {
                                @Override
                                FileTypes.FileStat stat(long id, Cancellation token) {
                                    if (enabled.get()
                                            && id == changed
                                            && stats.getOrDefault(id, new AtomicInteger()).get()
                                                    >= (id == 1 ? 3 : 2)) {
                                        changedId = id;
                                        changeAfterRead = true;
                                    }
                                    return super.stat(id, token);
                                }
                            })) {
                f.files.stats.clear();
                enabled.set(true);
                assertThrows(
                        WorldTerrainStreaming.Refusal.class,
                        () -> f.factory.createOwned(f.scope, SELECT, Cancellation.NONE));
                assertEquals(0, f.scopes.payloadCredits());
                assertFalse(f.scopes.isKnown(f.scope));
                assertEquals(f.files.totalOpens(), f.files.closed.get());
            }
        }
    }

    @Test
    void sourceCloseCancelsNewRawBuildBeforeWaitingForOldBorrowAndActualPins() throws Exception {
        var heldRead = new CompletableFuture<Integer>();
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var borrowEntered = new CompletableFuture<Void>();
        var borrowRelease = new CompletableFuture<Void>();
        var enabled = new java.util.concurrent.atomic.AtomicBoolean();
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileReadStream open(long id, Cancellation token) {
                                var original = super.open(id, token);
                                if (id != 201 || !enabled.get()) {
                                    return original;
                                }
                                return new FileReadStream() {
                                    Cancellation.Registration registration;

                                    public long length() {
                                        return original.length();
                                    }

                                    public long position() {
                                        return original.position();
                                    }

                                    public void seek(long p) {
                                        original.seek(p);
                                    }

                                    public List<FileTypes.FileHole> holes() {
                                        return original.holes();
                                    }

                                    public CompletableFuture<Integer> read(
                                            ByteBuffer target, Cancellation c) {
                                        registration = c.onCancel(() -> canceled.complete(null));
                                        entered.complete(null);
                                        return heldRead;
                                    }

                                    public void close() {
                                        if (registration != null) {
                                            registration.close();
                                        }
                                        original.close();
                                    }
                                };
                            }
                        })) {
            var old = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE);
            var borrow = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    old.withBorrow(
                                            b -> {
                                                borrowEntered.complete(null);
                                                borrowRelease.join();
                                                assertEquals(3, b.vertices());
                                                return null;
                                            });
                                    borrow.complete(null);
                                } catch (Throwable failure) {
                                    borrow.completeExceptionally(failure);
                                }
                            });
            var building = new CompletableFuture<Throwable>();
            try {
                borrowEntered.get(3, TimeUnit.SECONDS);
                enabled.set(true);
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        f.factory.createOwned(f.scope, SELECT, Cancellation.NONE);
                                        building.completeExceptionally(
                                                new AssertionError("Canceled asset escaped"));
                                    } catch (Throwable failure) {
                                        building.complete(failure);
                                    }
                                });
                entered.get(3, TimeUnit.SECONDS);
                assertEquals(2, f.scopes.payloadCredits());
                var sourceDrain = f.scopes.closeAsync();
                canceled.get(3, TimeUnit.SECONDS);
                assertFalse(sourceDrain.isDone());
                assertFalse(building.isDone());
                var closeCopy = old.closeAsync();
                closeCopy.cancel(false);
                assertEquals(2, f.scopes.payloadCredits());
                var originalStop = new java.util.concurrent.CancellationException();
                heldRead.completeExceptionally(originalStop);
                org.junit.jupiter.api.Assertions.assertSame(
                        originalStop, building.get(3, TimeUnit.SECONDS));
                assertEquals(1, f.scopes.payloadCredits());
                assertFalse(sourceDrain.isDone());
                borrowRelease.complete(null);
                borrow.get(3, TimeUnit.SECONDS);
                sourceDrain.get(3, TimeUnit.SECONDS);
                old.closeAsync().get(3, TimeUnit.SECONDS);
                assertEquals(0, f.scopes.payloadCredits());
                assertThrows(IllegalStateException.class, () -> old.withBorrow(b -> b.vertices()));
            } finally {
                heldRead.completeExceptionally(new java.util.concurrent.CancellationException());
                borrowRelease.complete(null);
                borrow.get(3, TimeUnit.SECONDS);
                old.close();
            }
        }
    }

    @Test
    void cancellationAfterCompleteBuilderStillClosesUnreturnedAssetBeforeTransfer()
            throws Exception {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var stop = new Cancellation();
        try (var f =
                new Fixture(
                        new Files() {
                            @Override
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                if (enabled.get()
                                        && id == 301
                                        && stats.getOrDefault(id, new AtomicInteger()).get() >= 2) {
                                    entered.complete(null);
                                    release.join();
                                    token.check();
                                }
                                return super.stat(id, token);
                            }
                        })) {
            enabled.set(true);
            var result = new CompletableFuture<Throwable>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    f.factory.createOwned(f.scope, SELECT, stop);
                                    result.completeExceptionally(
                                            new AssertionError("Canceled builder escaped"));
                                } catch (Throwable failure) {
                                    result.complete(failure);
                                }
                            });
            try {
                entered.get(3, TimeUnit.SECONDS);
                assertEquals(1, f.scopes.payloadCredits());
                stop.cancel();
                assertFalse(result.isDone());
                release.complete(null);
                var failure = result.get(3, TimeUnit.SECONDS);
                assertEquals(java.util.concurrent.CancellationException.class, failure.getClass());
                org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
                assertEquals(0, f.scopes.payloadCredits());
                assertEquals(f.files.totalOpens(), f.files.closed.get());
                try (var fresh = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
                    assertEquals(3, fresh.facts().vertices());
                }
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void foreignAndDirtyAsyncFailuresNeverBecomePayloadOrExpectedRefusal() throws Exception {
        var calls = new AtomicInteger();
        var foreign =
                new java.util.concurrent.CancellationException() {
                    @Override
                    public synchronized Throwable getCause() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }

                    @Override
                    public String getMessage() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        var dirty = new java.util.concurrent.CancellationException();
        dirty.initCause(new IllegalStateException("Auxiliary failure"));
        for (var original : List.of(foreign, dirty)) {
            try (var f =
                    new Fixture(
                            new Files() {
                                @Override
                                FileReadStream open(long id, Cancellation token) {
                                    var base = super.open(id, token);
                                    if (id != 301) {
                                        return base;
                                    }
                                    return new FileReadStream() {
                                        public long length() {
                                            return base.length();
                                        }

                                        public long position() {
                                            return base.position();
                                        }

                                        public void seek(long p) {
                                            base.seek(p);
                                        }

                                        public List<FileTypes.FileHole> holes() {
                                            return base.holes();
                                        }

                                        public CompletableFuture<Integer> read(
                                                ByteBuffer target, Cancellation c) {
                                            return CompletableFuture.failedFuture(original);
                                        }

                                        public void close() {
                                            base.close();
                                        }
                                    };
                                }
                            })) {
                var failure =
                        assertThrows(
                                IllegalStateException.class,
                                () -> f.factory.createOwned(f.scope, SELECT, Cancellation.NONE));
                assertFalse(failure instanceof WorldTerrainStreaming.Refusal);
                assertEquals(0, f.scopes.payloadCredits());
                assertEquals(f.files.totalOpens(), f.files.closed.get());
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    void throwingTrustedBorrowOwnsItsLocalResourceAndDoesNotLosePayloadCredit() throws Exception {
        try (var f = new Fixture();
                var asset = f.factory.createOwned(f.scope, SELECT, Cancellation.NONE)) {
            var localClosed = new AtomicInteger();
            var original = new AssertionError("Actual callback failure");
            var thrown =
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    asset.withBorrow(
                                            b -> {
                                                try (AutoCloseable local =
                                                        localClosed::incrementAndGet) {
                                                    throw original;
                                                } catch (Exception impossible) {
                                                    throw new AssertionError(impossible);
                                                }
                                            }));
            org.junit.jupiter.api.Assertions.assertSame(original, thrown);
            assertEquals(1, localClosed.get());
            assertEquals(1, f.scopes.payloadCredits());
            asset.closeAsync().get(3, TimeUnit.SECONDS);
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    private static final class OutputFile implements AutoCloseable {
        final java.nio.file.Path path;
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean moved;

        OutputFile(java.nio.file.Path path) {
            this.path = path;
            try {
                java.nio.file.Files.writeString(
                        path, "unadvertised", java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        @Override
        public void close() throws java.io.IOException {
            closes.incrementAndGet();
            if (!moved) {
                java.nio.file.Files.delete(path);
            }
        }
    }

    @Test
    void writerBorrowsActualPayloadBeforeFinalChecksAndExpiresItsView(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var saved = new AtomicReference<WorldGlobalAssetFactory.AssetBorrow>();
            var file = new AtomicReference<OutputFile>();
            var owned =
                    f.factory.createAndWrite(
                            f.scope,
                            SELECT,
                            Cancellation.NONE,
                            (borrow, token) -> {
                                token.check();
                                assertEquals(3, borrow.vertices());
                                assertEquals(6, borrow.indices());
                                assertEquals(
                                        0,
                                        borrow.imageByte(0, 3),
                                        "the source alpha is kept (#297)");
                                saved.set(borrow);
                                var output = new OutputFile(directory.resolve("global.wgs"));
                                file.set(output);
                                return output;
                            });
            assertThrows(IllegalStateException.class, () -> saved.get().vertices());
            assertEquals(0, file.get().closes.get());
            assertEquals(1, f.scopes.payloadCredits());
            owned.closeAsync().get(3, TimeUnit.SECONDS);
            assertFalse(java.nio.file.Files.exists(file.get().path));
            assertEquals(1, file.get().closes.get());
            assertEquals(0, f.scopes.payloadCredits());
        }
    }

    @Test
    void changedLastBindingDeletesUnreturnedOutputAndRetiresConstructionPin(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var file = new AtomicReference<OutputFile>();
            assertThrows(
                    WorldTerrainStreaming.Refusal.class,
                    () ->
                            f.factory.createAndWrite(
                                    f.scope,
                                    SELECT,
                                    Cancellation.NONE,
                                    (borrow, token) -> {
                                        var output =
                                                new OutputFile(directory.resolve("global.wgs"));
                                        file.set(output);
                                        f.files.changedId = 201;
                                        f.files.changeAfterRead = true;
                                        return output;
                                    }));
            assertEquals(1, file.get().closes.get());
            assertFalse(java.nio.file.Files.exists(file.get().path));
            assertEquals(0, f.scopes.payloadCredits());
            assertEquals(f.files.totalOpens(), f.files.closed.get());
        }
    }

    @Test
    void sourceCloseWaitsActualMoveCallbackAndCannotDeleteMovedInput(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var file = new OutputFile(directory.resolve("global.wgs"));
            var owned =
                    f.factory.createAndWrite(f.scope, SELECT, Cancellation.NONE, (b, t) -> file);
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var completed = new CompletableFuture<Void>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            owned.withWrittenResource(
                                                    resource -> {
                                                        entered.complete(null);
                                                        release.join();
                                                        resource.moved = true;
                                                    });
                                            completed.complete(null);
                                        } catch (RuntimeException | Error failure) {
                                            completed.completeExceptionally(failure);
                                        }
                                    });
            try {
                entered.get(3, TimeUnit.SECONDS);
                var sourceDrain = f.scopes.closeAsync();
                assertFalse(sourceDrain.isDone());
                assertEquals(0, file.closes.get());
                assertEquals(1, f.scopes.payloadCredits());
                release.complete(null);
                completed.get(3, TimeUnit.SECONDS);
                worker.join();
                sourceDrain.get(3, TimeUnit.SECONDS);
                assertEquals(1, file.closes.get());
                org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(file.path));
                assertThrows(IllegalStateException.class, () -> owned.withWrittenResource(r -> {}));
                java.nio.file.Files.delete(file.path); // The separate moved owner, never Source.
            } finally {
                release.complete(null);
                worker.join();
            }
        }
    }

    @Test
    void sourceCloseCancelsTheActualWriterTokenAndClosesUnreturnedFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var entered = new CompletableFuture<Void>();
            var canceled = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var failed = new CompletableFuture<Throwable>();
            var file = new AtomicReference<OutputFile>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            f.factory.createAndWrite(
                                                    f.scope,
                                                    SELECT,
                                                    Cancellation.NONE,
                                                    (borrow, token) -> {
                                                        var output =
                                                                new OutputFile(
                                                                        directory.resolve(
                                                                                "global.wgs"));
                                                        file.set(output);
                                                        try (var registration =
                                                                token.onCancel(
                                                                        () ->
                                                                                canceled.complete(
                                                                                        null))) {
                                                            entered.complete(null);
                                                            release.join();
                                                            return output;
                                                        }
                                                    });
                                            failed.completeExceptionally(
                                                    new AssertionError(
                                                            "Closed Source returned output"));
                                        } catch (RuntimeException | Error failure) {
                                            failed.complete(failure);
                                        }
                                    });
            try {
                entered.get(3, TimeUnit.SECONDS);
                var sourceDrain = f.scopes.closeAsync();
                canceled.get(3, TimeUnit.SECONDS);
                assertFalse(sourceDrain.isDone());
                assertEquals(0, file.get().closes.get());
                release.complete(null);
                org.junit.jupiter.api.Assertions.assertNotNull(failed.get(3, TimeUnit.SECONDS));
                worker.join();
                sourceDrain.get(3, TimeUnit.SECONDS);
                assertEquals(1, file.get().closes.get());
                assertFalse(java.nio.file.Files.exists(file.get().path));
                assertEquals(0, f.scopes.payloadCredits());
            } finally {
                release.complete(null);
                worker.join();
            }
        }
    }

    @Test
    void failedOutputDeletionIsFatalAndDoesNotSkipOtherActualOwners(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var a = new OutputFile(directory.resolve("first.wgs"));
            var b = new OutputFile(directory.resolve("second.wgs"));
            f.factory.createAndWrite(f.scope, SELECT, Cancellation.NONE, (read, token) -> a);
            f.factory.createAndWrite(f.scope, SELECT, Cancellation.NONE, (read, token) -> b);
            java.nio.file.Files.delete(a.path);
            java.nio.file.Files.createDirectory(a.path);
            java.nio.file.Files.writeString(a.path.resolve("held"), "held");
            assertThrows(IllegalStateException.class, f.scopes::closeAsync);
            assertEquals(1, a.closes.get());
            assertEquals(1, b.closes.get());
            assertFalse(java.nio.file.Files.exists(b.path));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> f.scopes.closeAsync().get(3, TimeUnit.SECONDS));
            assertEquals(0, f.scopes.payloadCredits());
            assertThrows(
                    IllegalStateException.class,
                    () -> f.factory.createOwned(f.scope, SELECT, Cancellation.NONE));
            assertEquals(1, a.closes.get()); // Unknown deletion never hot-retries.
        }
    }

    @Test
    void publicFacadeUsesActualSelectionAndExpiresScalarPayload() throws Exception {
        try (var f = new Fixture()) {
            var facade = new WorldGlobalScenes(f.scopes);
            var scope = facade.createScope(MAP, Cancellation.NONE);
            facade.activate(scope);
            var saved = new AtomicReference<WorldGlobalScenes.CheckedBorrow>();
            var closes = new AtomicInteger();
            var written =
                    facade.prepareAndWrite(
                            scope,
                            Cancellation.NONE,
                            (borrow, actual) -> {
                                actual.check();
                                saved.set(borrow);
                                assertEquals(3, borrow.vertices());
                                assertEquals(6, borrow.indices());
                                assertEquals(
                                        0,
                                        borrow.imageByte(0, 3),
                                        "the source alpha is kept (#297)");
                                assertEquals(0xffff_ffffL, borrow.facts().declaration().uniqueId());
                                assertEquals(1, borrow.facts().groups().size());
                                return (AutoCloseable) closes::incrementAndGet;
                            });
            assertThrows(IllegalStateException.class, () -> saved.get().vertices());
            org.junit.jupiter.api.Assertions.assertTrue(written.isOpen());
            assertEquals(3, written.withPayload(WorldGlobalScenes.CheckedBorrow::vertices));
            written.withWrittenResource(resource -> assertEquals(0, closes.get()));
            written.closeAsync().get(3, TimeUnit.SECONDS);
            assertEquals(1, closes.get());
            assertFalse(written.isOpen());
            assertThrows(RuntimeException.class, () -> written.withPayload(b -> b.vertices()));
        }
    }

    @Test
    void publicFacadeRejectsForeignScopeBeforeIoAndInvalidatesMetadata() throws Exception {
        try (var a = new Fixture();
                var b = new Fixture()) {
            var first = new WorldGlobalScenes(a.scopes);
            var second = new WorldGlobalScenes(b.scopes);
            var scope = first.createScope(MAP, Cancellation.NONE);
            assertThrows(IllegalArgumentException.class, () -> second.activate(scope));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            second.prepareAndWrite(
                                    scope, Cancellation.NONE, (r, t) -> (AutoCloseable) () -> {}));
            first.invalidate();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            first.prepareAndWrite(
                                    scope, Cancellation.NONE, (r, t) -> (AutoCloseable) () -> {}));
        }
    }

    @Test
    void publicFacadeSourceCloseWaitsPinnedMove() throws Exception {
        try (var f = new Fixture()) {
            var facade = new WorldGlobalScenes(f.scopes);
            var scope = facade.createScope(MAP, Cancellation.NONE);
            var closes = new AtomicInteger();
            var written =
                    facade.prepareAndWrite(
                            scope,
                            Cancellation.NONE,
                            (b, t) -> (AutoCloseable) closes::incrementAndGet);
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var moved =
                    CompletableFuture.runAsync(
                            () ->
                                    written.withWrittenResource(
                                            r -> {
                                                entered.complete(null);
                                                release.join();
                                            }));
            try {
                entered.get(3, TimeUnit.SECONDS);
                facade.invalidate();
                var drain = f.scopes.closeAsync();
                assertFalse(drain.isDone());
                assertFalse(f.scopes.whenClosed().isDone());
                assertFalse(written.isOpen());
                assertEquals(0, closes.get());
                release.complete(null);
                moved.get(3, TimeUnit.SECONDS);
                drain.get(3, TimeUnit.SECONDS);
                assertEquals(1, closes.get());
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void globalWhenClosedRetainsOtherPinsAfterResourceCleanupThrows() throws Exception {
        try (var f = new Fixture()) {
            var facade = new WorldGlobalScenes(f.scopes);
            var scope = facade.createScope(MAP, Cancellation.NONE);
            facade.prepareAndWrite(
                    scope,
                    Cancellation.NONE,
                    (b, t) ->
                            (AutoCloseable)
                                    () -> {
                                        throw new IllegalStateException("cleanup failed");
                                    });
            var second =
                    facade.prepareAndWrite(
                            scope, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {});
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var borrowed =
                    CompletableFuture.runAsync(
                            () ->
                                    second.withPayload(
                                            b -> {
                                                entered.complete(null);
                                                release.join();
                                                return null;
                                            }));
            try {
                entered.get(3, TimeUnit.SECONDS);
                assertThrows(RuntimeException.class, f.scopes::closeAsync);
                var actual = f.scopes.whenClosed();
                assertFalse(actual.isDone());
                release.complete(null);
                borrowed.get(3, TimeUnit.SECONDS);
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> actual.get(3, TimeUnit.SECONDS));
                assertEquals(0, f.scopes.payloadCredits());
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void publicFacadeForwardsActualWriterCancellationWithExternalNone(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var f = new Fixture()) {
            var facade = new WorldGlobalScenes(f.scopes);
            var scope = facade.createScope(MAP, Cancellation.NONE);
            var entered = new CompletableFuture<Void>();
            var canceled = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var failed = new CompletableFuture<Throwable>();
            var file = new AtomicReference<OutputFile>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            facade.prepareAndWrite(
                                                    scope,
                                                    Cancellation.NONE,
                                                    (borrow, token) -> {
                                                        var output =
                                                                new OutputFile(
                                                                        directory.resolve(
                                                                                "global.wgs"));
                                                        file.set(output);
                                                        try (var registration =
                                                                token.onCancel(
                                                                        () ->
                                                                                canceled.complete(
                                                                                        null))) {
                                                            entered.complete(null);
                                                            release.join();
                                                            return output;
                                                        }
                                                    });
                                            failed.completeExceptionally(
                                                    new AssertionError(
                                                            "Closed Source returned output"));
                                        } catch (RuntimeException | Error failure) {
                                            failed.complete(failure);
                                        }
                                    });
            try {
                entered.get(3, TimeUnit.SECONDS);
                var sourceDrain = f.scopes.closeAsync();
                canceled.get(3, TimeUnit.SECONDS);
                assertFalse(sourceDrain.isDone());
                assertEquals(0, file.get().closes.get());
                release.complete(null);
                org.junit.jupiter.api.Assertions.assertNotNull(failed.get(3, TimeUnit.SECONDS));
                worker.join();
                sourceDrain.get(3, TimeUnit.SECONDS);
                assertEquals(1, file.get().closes.get());
                assertFalse(java.nio.file.Files.exists(file.get().path));
                assertEquals(0, f.scopes.payloadCredits());
            } finally {
                release.complete(null);
                worker.join();
            }
        }
    }

    @Test
    void publicFacadeNeverConvertsStickyCleanupFailureToExpectedRetirement() throws Exception {
        try (var f = new Fixture()) {
            var facade = new WorldGlobalScenes(f.scopes);
            var scope = facade.createScope(MAP, Cancellation.NONE);
            var written =
                    facade.prepareAndWrite(
                            scope,
                            Cancellation.NONE,
                            (b, t) ->
                                    (AutoCloseable)
                                            () -> {
                                                throw new IllegalStateException(
                                                        "actual resource cleanup");
                                            });
            assertThrows(RuntimeException.class, written::close);
            facade.invalidate();
            java.util.List<org.junit.jupiter.api.function.Executable> calls =
                    java.util.List.of(
                            () -> written.withPayload(b -> b.vertices()),
                            () -> written.withWrittenResource(r -> {}),
                            () ->
                                    facade.prepareAndWrite(
                                            scope,
                                            Cancellation.NONE,
                                            (b, t) -> (AutoCloseable) () -> {}),
                            () -> facade.createScope(MAP, Cancellation.NONE),
                            () -> facade.activate(scope));
            for (var call : calls) {
                var error = assertThrows(RuntimeException.class, call);
                assertFalse(error instanceof WorldTerrainStreaming.Refusal);
            }
        }
    }
}
