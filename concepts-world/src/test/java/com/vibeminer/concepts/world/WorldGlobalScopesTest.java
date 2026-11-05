package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.formats.world.WdtSceneFacts;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
@SuppressWarnings("try")
class WorldGlobalScopesTest {
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Decoration", null, 0, 999L);
    private static final WorldGlobalScopes.Selection SELECT =
            new WorldGlobalScopes.Selection(7, 0, 0xffff_ffffL);
    private static final int MAIN = 60, MAID = 32836, MODF = 163916;

    private static byte[] chunk(int tag, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(tag)
                        .putInt(data.length)
                        .array());
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] wdt() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                chunk(
                        0x4d564552,
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(18).array()));
        var mphd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(0x241);
        for (int i = 0; i < 7; i++) {
            mphd.putInt(i == 6 ? -1 : 11 + i);
        }
        out.writeBytes(chunk(0x4d504844, mphd.array()));
        out.writeBytes(chunk(0x4d41494e, new byte[32768]));
        out.writeBytes(chunk(0x4d414944, new byte[131072]));
        var modf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        modf.putInt(123).putInt(-1);
        modf.position(32);
        modf.putFloat(-100).putFloat(-200).putFloat(-300).putFloat(100).putFloat(200).putFloat(300);
        modf.putShort((short) 8).putShort((short) 0).putShort((short) 0).putShort((short) 1024);
        out.writeBytes(chunk(0x4d4f4446, modf.array()));
        return out.toByteArray();
    }

    private static class Files extends WorldTerrainTextureSourceTest.Files {
        boolean exactEof = true;
        final AtomicInteger reads = new AtomicInteger();

        Files() {
            sources.put(1L, wdt());
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

                public List<com.vibeminer.files.FileTypes.FileHole> holes() {
                    return base.holes();
                }

                public CompletableFuture<Integer> read(ByteBuffer target, Cancellation cancel) {
                    cancel.check();
                    reads.incrementAndGet();
                    return base.position() == base.length()
                            ? CompletableFuture.completedFuture(exactEof ? -1 : 0)
                            : base.read(target, cancel);
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
        final WorldGlobalScopes source;

        Fixture() {
            this(new Files());
        }

        Fixture(Files files) {
            this.files = files;
            tables = new WorldStreamScopesTest.Tables(files);
            source = new WorldGlobalScopes(tables.handle(), files.files());
        }

        WorldGlobalScopes.GlobalScope create() {
            return source.create(MAP, Cancellation.NONE);
        }

        public void close() throws Exception {
            try {
                source.closeAsync().get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                /* Sticky cleanup failure remains exceptional. */
            }
            tables.close();
        }
    }

    @Test
    void actualNativeMapZeroDomainsAndRecordedDeclarationHaveNoTerrainOrBodyReads()
            throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            f.source.activate(scope);
            var saved = new AtomicReference<WorldGlobalScopes.Borrow>();
            var closed = new AtomicInteger();
            try (var result =
                    f.source.withRecordedDeclaration(
                            scope,
                            SELECT,
                            Cancellation.NONE,
                            borrow -> {
                                saved.set(borrow);
                                var facts = borrow.facts();
                                assertEquals(7, facts.mapId());
                                assertEquals(1, facts.wdtId());
                                assertEquals(123, facts.declaration().reference());
                                assertEquals(0xffff_ffffL, facts.declaration().uniqueId());
                                assertEquals(
                                        WdtSceneFacts.ReferenceKind.FILE_DATA_ID,
                                        facts.declaration().referenceKind());
                                assertEquals(
                                        List.of(11L, 12L, 13L, 14L, 15L, 16L, 0xffff_ffffL),
                                        facts.environment());
                                assertEquals(163980, facts.consumedLength());
                                assertEquals(1 << 20, facts.sourceLimit());
                                return (AutoCloseable) closed::incrementAndGet;
                            })) {
                assertEquals(0, closed.get());
            }
            assertEquals(1, closed.get());
            assertThrows(IllegalStateException.class, () -> saved.get().facts());
            assertEquals(java.util.Set.of(1L), f.files.opens.keySet());
            assertEquals(java.util.Set.of(1L), f.files.stats.keySet());
            assertEquals(2, f.files.totalOpens());
            assertEquals(2, f.files.closed.get());
            assertTrue(f.tables.eofProbes.get() >= 4);
        }
    }

    @Test
    void everyUnsignedMaidColumnAndInactiveMainReservedWordRefuseProfile() throws Exception {
        for (int col = 0; col < 8; col++) {
            try (var f = new Fixture()) {
                ByteBuffer.wrap(f.files.sources.get(1L))
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(MAID + col * 4, 0x80000000);
                var failure = assertThrows(WorldTerrainStreaming.Refusal.class, f::create);
                assertEquals("GLOBAL_PROFILE", failure.site());
                assertEquals(0, f.source.retainedProofs());
            }
        }
        try (var f = new Fixture()) {
            ByteBuffer.wrap(f.files.sources.get(1L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(MAIN + 4, 2);
            assertEquals(
                    "GLOBAL_PROFILE",
                    assertThrows(WorldTerrainStreaming.Refusal.class, f::create).site());
        }
    }

    @Test
    void terrainMapRefusalSaysItIsATerrainMapNotAnInterior() throws Exception {
        try (var f = new Fixture()) {
            var raw = f.files.sources.get(1L);
            // Terrain-only: no global WMO flag, one active MAIN tile, no MODF.
            var terrain = java.util.Arrays.copyOf(raw, MODF - 8);
            ByteBuffer.wrap(terrain).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0).putInt(MAIN, 1);
            f.files.sources.put(1L, terrain);
            var failure = assertThrows(WorldTerrainStreaming.Refusal.class, f::create);
            assertEquals("GLOBAL_PROFILE", failure.site());
            assertEquals(
                    java.util.Optional.of(
                            "this instance is a terrain (ADT) map, not a single interior WMO;"
                                    + " it can only be explored as a map"),
                    failure.detail());
        }
    }

    @Test
    void onlyExactZeroGlobalProfileIsAdmittedAndMalformedChunksRemainFatal() throws Exception {
        for (int field :
                new int[] {MODF, MODF + 8, MODF + 20, MODF + 56, MODF + 58, MODF + 60, MODF + 62}) {
            try (var f = new Fixture()) {
                ByteBuffer.wrap(f.files.sources.get(1L))
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(field, (short) (field == MODF ? 0 : 1));
                assertThrows(RuntimeException.class, f::create);
            }
        }
        try (var f = new Fixture()) {
            var raw = f.files.sources.get(1L);
            f.files.sources.put(1L, java.util.Arrays.copyOf(raw, raw.length - 1));
            assertFalse(
                    assertThrows(RuntimeException.class, f::create)
                            instanceof WorldTerrainStreaming.Refusal);
        }
    }

    @Test
    void replacementBoundDropsOnlyPendingAfterSuccessfulMapBindAndForeignSelectionIsFatal()
            throws Exception {
        try (var f = new Fixture();
                var other = new Fixture()) {
            var current = f.create();
            f.source.activate(current);
            var pending = f.create();
            assertEquals(2, f.source.retainedProofs());
            f.files.sources.get(1L)[20] = 0;
            assertThrows(WorldTerrainStreaming.Refusal.class, f::create);
            assertTrue(f.source.isKnown(current));
            assertFalse(f.source.isKnown(pending));
            assertEquals(1, f.source.retainedProofs());
            assertFalse(
                    assertThrows(RuntimeException.class, () -> other.source.activate(current))
                            instanceof WorldTerrainStreaming.Refusal);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            f.source.withRecordedDeclaration(
                                    current,
                                    new WorldGlobalScopes.Selection(7, 0, 1),
                                    Cancellation.NONE,
                                    b -> () -> {}));
        }
    }

    @Test
    void changedBodyWithSameStatInvalidatesOnlyItsOwnProofAndClosesReturnedOwner()
            throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            var closed = new AtomicInteger();
            var error =
                    assertThrows(
                            WorldTerrainStreaming.Refusal.class,
                            () ->
                                    f.source.withRecordedDeclaration(
                                            scope,
                                            SELECT,
                                            Cancellation.NONE,
                                            b -> {
                                                f.files.changeAfterRead = true;
                                                f.files.changedId = 1;
                                                return (AutoCloseable) closed::incrementAndGet;
                                            }));
            assertEquals("GLOBAL_WDT_BINDING", error.site());
            assertEquals(1, closed.get());
            assertFalse(f.source.isKnown(scope));
        }
        try (var f = new Fixture()) {
            var scope = f.create();
            f.files.sources.get(1L)[24]++;
            assertThrows(
                    WorldTerrainStreaming.Refusal.class,
                    () ->
                            f.source.withRecordedDeclaration(
                                    scope, SELECT, Cancellation.NONE, b -> () -> {}));
            assertFalse(f.source.isKnown(scope));
        }
    }

    @Test
    void borrowRejectsOtherThreadAndCloseDuringCallbackClosesUnreturnedResource() throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            var closed = new AtomicInteger();
            var actual = new AtomicReference<CompletableFuture<Void>>();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.source.withRecordedDeclaration(
                                    scope,
                                    SELECT,
                                    Cancellation.NONE,
                                    b -> {
                                        CompletableFuture.runAsync(
                                                        () ->
                                                                assertThrows(
                                                                        IllegalStateException.class,
                                                                        b::facts))
                                                .join();
                                        actual.set(f.source.closeAsync());
                                        assertFalse(actual.get().isDone());
                                        return (AutoCloseable) closed::incrementAndGet;
                                    }));
            assertEquals(1, closed.get());
            actual.get().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeAfterCancellationSealStillRejectsTransferAtFinalGate() throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var closed = new AtomicInteger();
            var work =
                    CompletableFuture.runAsync(
                            () ->
                                    f.source.withRecordedDeclaration(
                                            scope,
                                            SELECT,
                                            Cancellation.NONE,
                                            b -> {
                                                try {
                                                    var field =
                                                            WorldGlobalScopes.class
                                                                    .getDeclaredField("active");
                                                    field.setAccessible(true);
                                                    var lane = field.get(f.source);
                                                    var doneField =
                                                            lane.getClass()
                                                                    .getDeclaredField("done");
                                                    doneField.setAccessible(true);
                                                    ((CompletableFuture<?>) doneField.get(lane))
                                                            .thenRun(
                                                                    () -> {
                                                                        entered.complete(null);
                                                                        release.join();
                                                                    });
                                                } catch (ReflectiveOperationException e) {
                                                    throw new AssertionError(e);
                                                }
                                                return (AutoCloseable) closed::incrementAndGet;
                                            }));
            try {
                entered.get(3, TimeUnit.SECONDS);
                var drain = f.source.closeAsync();
                assertFalse(drain.isDone());
                release.complete(null);
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> work.get(3, TimeUnit.SECONDS));
                drain.get(3, TimeUnit.SECONDS);
                assertEquals(1, closed.get());
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void nullBeforeStatIsNotInventedAndLaterEnrichmentPreservesConsumedIdentity() throws Exception {
        var unknown = new java.util.concurrent.atomic.AtomicBoolean(true);
        var files =
                new Files() {
                    @Override
                    com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                        var stat = super.stat(id, token);
                        var f = stat.file();
                        if (f == null || !unknown.get()) {
                            return stat;
                        }
                        return new com.vibeminer.files.FileTypes.FileStat(
                                stat.state(),
                                new com.vibeminer.files.FileTypes.BuildFile(
                                        f.fileDataId(),
                                        f.path(),
                                        f.pathSource(),
                                        null,
                                        f.contentKey(),
                                        f.format(),
                                        f.formatSource(),
                                        f.storage(),
                                        f.encrypted()));
                    }
                };
        try (var f = new Fixture(files)) {
            var scope = f.create();
            try (var r =
                    f.source.withRecordedDeclaration(
                            scope,
                            SELECT,
                            Cancellation.NONE,
                            b -> {
                                assertEquals(null, b.facts().declaredLength());
                                assertEquals(163980, b.facts().consumedLength());
                                return (AutoCloseable) () -> {};
                            })) {}
            unknown.set(false);
            try (var r =
                    f.source.withRecordedDeclaration(
                            scope,
                            SELECT,
                            Cancellation.NONE,
                            b -> {
                                assertEquals(163980L, b.facts().declaredLength());
                                return (AutoCloseable) () -> {};
                            })) {}
            assertTrue(f.source.isKnown(scope));
        }
    }

    @Test
    void knownOversizedInputRefusesBeforeOpenAndAllocation() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(1L, new byte[(1 << 20) + 1]);
            var refused = assertThrows(WorldTerrainStreaming.Refusal.class, f::create);
            assertEquals("SOURCE_INPUT", refused.site());
            assertTrue(refused.detail().orElseThrow().startsWith("the map's WDT file"));
            assertEquals(0, f.files.totalOpens());
        }
    }

    private static Cancellation activeChild(WorldGlobalScopes source) {
        try {
            var field = WorldGlobalScopes.class.getDeclaredField("active");
            field.setAccessible(true);
            var lane = field.get(source);
            var child = lane.getClass().getDeclaredField("child");
            child.setAccessible(true);
            return (Cancellation) child.get(lane);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void returnedOwnerClosesBeforeHeldRealCancellationCallbackAndFaultIsSticky() throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            var external = new Cancellation();
            var callback = new CompletableFuture<Void>();
            var ownerClosed = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var registered = new AtomicReference<Cancellation.Registration>();
            var work =
                    CompletableFuture.runAsync(
                            () ->
                                    f.source.withRecordedDeclaration(
                                            scope,
                                            SELECT,
                                            external,
                                            b -> {
                                                registered.set(
                                                        activeChild(f.source)
                                                                .onCancel(
                                                                        () -> {
                                                                            callback.complete(null);
                                                                            ownerClosed.join();
                                                                            release.join();
                                                                            throw new IllegalStateException(
                                                                                    "Authored lower cancellation failure");
                                                                        }));
                                                external.cancel();
                                                callback.join();
                                                return (AutoCloseable)
                                                        () -> ownerClosed.complete(null);
                                            }));
            try {
                ownerClosed.get(3, TimeUnit.SECONDS);
                assertFalse(work.isDone());
                int captures = f.tables.captures.get();
                assertEquals(
                        "SOURCE_WORKER",
                        assertThrows(WorldTerrainStreaming.Refusal.class, f::create).site());
                assertEquals(captures, f.tables.captures.get());
                release.complete(null);
                var failure =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> work.get(3, TimeUnit.SECONDS));
                assertFalse(failure.getCause() instanceof CancellationException);
                assertFalse(
                        assertThrows(RuntimeException.class, f::create)
                                instanceof WorldTerrainStreaming.Refusal);
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> f.source.closeAsync().get(3, TimeUnit.SECONDS));
            } finally {
                ownerClosed.complete(null);
                release.complete(null);
                if (registered.get() != null) {
                    registered.get().close();
                }
            }
        }
    }

    @Test
    void finalValidationAndReturnedCleanupFailuresCannotBecomeExpectedRefusal() throws Exception {
        try (var f = new Fixture()) {
            var scope = f.create();
            var cleanup = new IllegalStateException("Authored R cleanup failure");
            var thrown =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    f.source.withRecordedDeclaration(
                                            scope,
                                            SELECT,
                                            Cancellation.NONE,
                                            b -> {
                                                f.files.changeAfterRead = true;
                                                f.files.changedId = 1;
                                                return (AutoCloseable)
                                                        () -> {
                                                            throw cleanup;
                                                        };
                                            }));
            assertFalse(thrown instanceof WorldTerrainStreaming.Refusal);
            boolean preserved = false;
            for (Throwable at = thrown; at != null; at = at.getCause()) {
                if (at == cleanup) {
                    preserved = true;
                    break;
                }
                for (var auxiliary : at.getSuppressed()) {
                    if (auxiliary == cleanup) {
                        preserved = true;
                    }
                }
            }
            assertTrue(preserved, "Original cleanup failure remains observable");
            assertFalse(
                    assertThrows(RuntimeException.class, f::create)
                            instanceof WorldTerrainStreaming.Refusal);
        }
    }

    @Test
    void unknownStatOversizedStreamRefusesBeforeAnyReadAndAmbiguousEofIsFatal() throws Exception {
        var files =
                new Files() {
                    @Override
                    com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                        var stat = super.stat(id, token);
                        var f = stat.file();
                        return new com.vibeminer.files.FileTypes.FileStat(
                                stat.state(),
                                new com.vibeminer.files.FileTypes.BuildFile(
                                        f.fileDataId(),
                                        f.path(),
                                        f.pathSource(),
                                        null,
                                        f.contentKey(),
                                        f.format(),
                                        f.formatSource(),
                                        f.storage(),
                                        f.encrypted()));
                    }
                };
        try (var f = new Fixture(files)) {
            files.sources.put(1L, new byte[(1 << 20) + 1]);
            assertEquals(
                    "SOURCE_INPUT",
                    assertThrows(WorldTerrainStreaming.Refusal.class, f::create).site());
            assertEquals(1, files.totalOpens());
            assertEquals(1, files.closed.get());
            assertEquals(0, files.reads.get());
        }
        try (var f = new Fixture()) {
            f.files.exactEof = false;
            assertFalse(
                    assertThrows(RuntimeException.class, f::create)
                            instanceof WorldTerrainStreaming.Refusal);
            assertEquals(0, f.source.retainedProofs());
            assertEquals(1, f.files.closed.get());
        }
    }
}
