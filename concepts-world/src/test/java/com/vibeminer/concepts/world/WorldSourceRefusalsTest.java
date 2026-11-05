package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
@SuppressWarnings("try")
class WorldSourceRefusalsTest {
    @Test
    void canceledActualRootReadFuturePreservesOriginalStopAfterStreamRetires() throws Exception {
        actualRootReadOutcome(new CancellationException("Authored actual root stop"), true);
    }

    @Test
    void dirtyActualRootReadCancellationRemainsFatalAndRetiresStream() throws Exception {
        var dirty = new CancellationException("Authored dirty root stop");
        dirty.initCause(new IllegalStateException("Authored auxiliary failure"));
        actualRootReadOutcome(dirty, false);
    }

    @Test
    void foreignActualRootReadCancellationAccessorsAreNeverInspected() throws Exception {
        var calls = new AtomicInteger();
        var foreign =
                new CancellationException() {
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
        actualRootReadOutcome(foreign, false);
        assertEquals(0, calls.get());
    }

    private static void actualRootReadOutcome(CancellationException original, boolean clean)
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var read = new CompletableFuture<Integer>();
        var once = new AtomicBoolean();
        var closes = new AtomicInteger();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    com.vibeminer.files.FileReadStream open(long id, Cancellation token) {
                        var base = super.open(id, token);
                        if (id != 2) {
                            return base;
                        }
                        return new com.vibeminer.files.FileReadStream() {
                            Cancellation.Registration registration;

                            public long length() {
                                return base.length();
                            }

                            public long position() {
                                return base.position();
                            }

                            public void seek(long position) {
                                base.seek(position);
                            }

                            public java.util.List<FileTypes.FileHole> holes() {
                                return base.holes();
                            }

                            public CompletableFuture<Integer> read(
                                    java.nio.ByteBuffer target, Cancellation stop) {
                                if (once.compareAndSet(false, true)) {
                                    registration = stop.onCancel(() -> canceled.complete(null));
                                    entered.complete(null);
                                    return read;
                                }
                                return base.read(target, stop);
                            }

                            public void close() {
                                if (registration != null) {
                                    registration.close();
                                }
                                closes.incrementAndGet();
                                base.close();
                            }
                        };
                    }
                };
        try (var fixture = new Fixture(files)) {
            var stop = new Cancellation();
            var preparing =
                    CompletableFuture.supplyAsync(
                            () ->
                                    fixture.source.prepareAndWrite(
                                            fixture.scope,
                                            TILE,
                                            stop,
                                            (borrow, cancel) -> (AutoCloseable) () -> {}));
            try {
                entered.get(5, TimeUnit.SECONDS);
                stop.cancel();
                canceled.get(5, TimeUnit.SECONDS);
                assertFalse(
                        preparing.isDone(),
                        "Actual root input owner stays active while async read has not retired");
                read.completeExceptionally(original);
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> preparing.get(5, TimeUnit.SECONDS));
                if (clean) {
                    assertTrue(
                            failure.getCause() == original,
                            "Trusted future preserves ORIGINAL clean stop identity");
                    assertEquals(null, failure.getCause().getCause());
                } else {
                    assertTrue(
                            failure.getCause() instanceof IllegalStateException,
                            "Dirty/foreign cancellation stays fatal");
                    assertFalse(failure.getCause() instanceof WorldTerrainStreaming.Refusal);
                }
                assertEquals(
                        1,
                        closes.get(),
                        "Actual root stream retires before output failure escapes");
                assertEquals(0, fixture.pool.stats().leaseClaims());
                if (clean) {
                    try (var fresh = fixture.prepare()) {
                        assertEquals(
                                WorldTerrainStreaming.Outcome.TEXTURED, fresh.facts().outcome());
                    }
                }
            } finally {
                read.completeExceptionally(new CancellationException());
            }
        }
    }

    @Test
    void canceledOwnedMaterialPreservesOriginalStopAfterActualDrainAndScopeRemainsUsable()
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var once = new AtomicBoolean();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 10 && once.compareAndSet(false, true)) {
                            entered.complete(null);
                            release.join();
                            token.check();
                        }
                        return super.stat(id, token);
                    }
                };
        try (var fixture = new Fixture(files)) {
            var stop = new Cancellation();
            var preparing =
                    CompletableFuture.supplyAsync(
                            () ->
                                    fixture.source.prepareAndWrite(
                                            fixture.scope,
                                            TILE,
                                            stop,
                                            (borrow, cancel) -> (AutoCloseable) () -> {}));
            try {
                entered.get(5, TimeUnit.SECONDS);
                stop.cancel();
                assertFalse(preparing.isDone(), "Held material work must actually retire");
                release.complete(null);
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> preparing.get(5, TimeUnit.SECONDS));
                assertEquals(
                        CancellationException.class,
                        failure.getCause().getClass(),
                        "A clean owned material stop must not become a source failure");
                assertEquals(null, failure.getCause().getCause());
                assertEquals(0, failure.getCause().getSuppressed().length);
                assertEquals(0, fixture.pool.stats().leaseClaims());
                try (var fresh = fixture.prepare()) {
                    fresh.withPayload(
                            borrow ->
                                    assertEquals(
                                            WorldTerrainStreaming.Outcome.TEXTURED,
                                            borrow.facts().outcome()));
                }
            } finally {
                release.complete(null);
            }
        }
    }

    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    private static final class Fixture implements AutoCloseable {
        final WorldTerrainTextureSourceTest.Files files;
        final WorldStreamScopesTest.Tables tables;
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final WorldService world;
        final WorldTerrainStreaming source;
        final WorldTerrainStreaming.Scope scope;
        boolean failedCleanup;

        Fixture() {
            this(new WorldTerrainTextureSourceTest.Files());
        }

        Fixture(WorldTerrainTextureSourceTest.Files files) {
            this.files = files;
            tables = new WorldStreamScopesTest.Tables(files);
            world = new WorldService(tables.handle(), files.files(), pool);
            source = world.terrainStreaming().orElseThrow();
            scope = source.createScope(MAP, Cancellation.NONE);
            source.activate(scope);
        }

        WorldTerrainStreaming.WrittenTile<AutoCloseable> prepare() {
            return source.prepareAndWrite(scope, TILE, Cancellation.NONE, (b, c) -> () -> {});
        }

        public void close() throws Exception {
            if (failedCleanup) {
                assertThrows(
                        ExecutionException.class,
                        () -> world.closeAsync().get(5, TimeUnit.SECONDS));
            } else {
                world.closeAsync().get(5, TimeUnit.SECONDS);
            }
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void cancellationSubclassCannotHideAuxiliaryFailure() {
        var canceled = new CancellationException("provider cancellation") {};
        canceled.initCause(new IllegalStateException("cleanup failed"));
        assertFalse(
                WorldSourceRefusals.boundary(canceled) instanceof CancellationException,
                "Unknown cancellation subclass with auxiliary failure must remain fatal");
    }

    @Test
    void cancellationSubclassAccessorsAreNotInspected() {
        var canceled =
                new CancellationException("provider cancellation") {
                    @Override
                    public synchronized Throwable getCause() {
                        throw new AssertionError("arbitrary cause accessor");
                    }

                    @Override
                    public String getMessage() {
                        throw new AssertionError("arbitrary message accessor");
                    }
                };
        assertFalse(WorldSourceRefusals.boundary(canceled) instanceof CancellationException);
    }

    @Test
    void foreignScopeActivationIsFatalRatherThanExpectedRetirement() throws Exception {
        try (var first = new Fixture();
                var second = new Fixture()) {
            var error =
                    assertThrows(RuntimeException.class, () -> second.source.activate(first.scope));
            assertFalse(
                    error instanceof WorldTerrainStreaming.Refusal,
                    "Foreign-owned scope is a caller/program error, not source retirement");
        }
    }

    @Test
    void actualOutputAndPoolCreditsRefuseRetryWithoutWriterOrFakeHeightThenRecover()
            throws Exception {
        try (var f = new Fixture()) {
            var outputs = new ArrayList<WorldTerrainStreaming.WrittenTile<AutoCloseable>>();
            try {
                for (int i = 0; i < 12; i++) {
                    outputs.add(f.prepare());
                }
                int opens = f.files.totalOpens();
                var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::prepare);
                assertEquals(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER, refusal.reason());
                assertEquals("OUTPUT_CREDIT", refusal.site());
                assertEquals(opens, f.files.totalOpens());
                assertTrue(outputs.getFirst().isOpen());
            } finally {
                for (var output : outputs) {
                    output.close();
                }
            }
            try (var recovered = f.prepare()) {
                assertTrue(recovered.isOpen());
            }
        }
        try (var f = new Fixture()) {
            var reserved = new ArrayList<TerrainAtlasPool.Reservation>();
            var writes = new AtomicInteger();
            try {
                for (int i = 0; i < 18; i++) {
                    reserved.add(f.pool.reserve().orElseThrow());
                }
                var refusal =
                        assertThrows(
                                WorldTerrainStreaming.Refusal.class,
                                () ->
                                        f.source.prepareAndWrite(
                                                f.scope,
                                                TILE,
                                                Cancellation.NONE,
                                                (b, c) -> {
                                                    writes.incrementAndGet();
                                                    return (AutoCloseable) () -> {};
                                                }));
                assertEquals(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER, refusal.reason());
                assertEquals("MATERIAL_POOL", refusal.site());
                assertEquals(0, writes.get());
            } finally {
                for (var reservation : reserved) {
                    reservation.close();
                }
            }
            try (var recovered = f.prepare()) {
                assertEquals(WorldTerrainStreaming.Outcome.TEXTURED, recovered.facts().outcome());
            }
        }
    }

    @Test
    void onlyActualDefaultStrictMarkerMeansUnavailableWhileProviderUnsupportedStaysFatal()
            throws Exception {
        for (boolean defaultMarker : List.of(true, false)) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new WorldStreamScopesTest.Tables(files);
            var raw = tables.handle();
            var plain = new UnsupportedOperationException("Authored provider bug");
            var legacy = new AtomicInteger();
            var handle =
                    (BuildTables)
                            Proxy.newProxyInstance(
                                    BuildTables.class.getClassLoader(),
                                    new Class<?>[] {BuildTables.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("capturePreparedLocal")) {
                                            @SuppressWarnings("unchecked")
                                            var actual =
                                                    (CompletableFuture<CapturedRowsTypes.Capture>)
                                                            method.invoke(raw, args);
                                            return actual.thenApply(
                                                    capture -> {
                                                        var delegate = capture.rows();
                                                        CapturedRows rows =
                                                                new CapturedRows() {
                                                                    public CapturedRowsTypes.Receipt
                                                                            receipt() {
                                                                        return delegate.receipt();
                                                                    }

                                                                    public CompletableFuture<
                                                                                    CapturedRowsTypes
                                                                                            .Page>
                                                                            readOrdinalPage(
                                                                                    long first,
                                                                                    int count,
                                                                                    Cancellation
                                                                                            cancel) {
                                                                        legacy.incrementAndGet();
                                                                        throw new AssertionError(
                                                                                "No legacy fallback");
                                                                    }

                                                                    @Override
                                                                    public CompletableFuture<
                                                                                    CapturedRowsTypes
                                                                                            .Page>
                                                                            readOrdinalPage(
                                                                                    long first,
                                                                                    int count,
                                                                                    CapturedRowsTypes
                                                                                                    .ReadLimits
                                                                                            limits,
                                                                                    Cancellation
                                                                                            cancel) {
                                                                        if (defaultMarker) {
                                                                            return CapturedRows
                                                                                    .super
                                                                                    .readOrdinalPage(
                                                                                            first,
                                                                                            count,
                                                                                            limits,
                                                                                            cancel);
                                                                        }
                                                                        return CompletableFuture
                                                                                .failedFuture(
                                                                                        plain);
                                                                    }

                                                                    public CompletableFuture<Void>
                                                                            closeAsync() {
                                                                        return delegate
                                                                                .closeAsync();
                                                                    }
                                                                };
                                                        return new CapturedRowsTypes.Capture(
                                                                capture.state(),
                                                                rows,
                                                                capture.code(),
                                                                capture.message());
                                                    });
                                        }
                                        try {
                                            return method.invoke(raw, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                    });
            var pool = new TerrainAtlasPool();
            var world = new WorldService(handle, files.files(), pool);
            try {
                var source = world.terrainStreaming().orElseThrow();
                var error =
                        assertThrows(
                                RuntimeException.class,
                                () -> source.createScope(MAP, Cancellation.NONE));
                if (defaultMarker) {
                    var refused = (WorldTerrainStreaming.Refusal) error;
                    assertEquals(
                            WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE,
                            refused.reason());
                    assertEquals("STRICT_ROWS", refused.site());
                } else {
                    assertFalse(error instanceof WorldTerrainStreaming.Refusal);
                    assertTrue(error instanceof CompletionException && error.getCause() == plain);
                }
                assertEquals(0, legacy.get());
                assertEquals(0, files.totalOpens());
            } finally {
                world.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
                tables.close();
            }
        }
    }

    @Test
    void explicitIdentityAndNegativeLookupChangesRefuseInvalidationAndRetireUnadvertisedR()
            throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (var f = new Fixture()) {
                int selected = mode;
                var closed = new AtomicInteger();
                if (mode == 0) {
                    f.tables.stampMode = 1;
                }
                if (mode == 1) {
                    f.files.changedId = 1;
                    f.files.changeAfterRead = true;
                }
                if (mode == 2) {
                    f.files.sources.remove(3L);
                }
                if (mode == 3) {
                    f.files.changedId = 10;
                    f.files.changeAfterRead = true;
                }
                var refused =
                        assertThrows(
                                WorldTerrainStreaming.Refusal.class,
                                () ->
                                        f.source.prepareAndWrite(
                                                f.scope,
                                                TILE,
                                                Cancellation.NONE,
                                                (borrow, cancel) -> {
                                                    if (selected == 2) {
                                                        assertEquals(
                                                                WorldTerrainStreaming.Outcome
                                                                        .HEIGHT_FALLBACK,
                                                                borrow.facts().outcome());
                                                        f.files.sources.put(3L, new byte[16]);
                                                    }
                                                    return (AutoCloseable) closed::incrementAndGet;
                                                }));
                assertEquals(
                        WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED, refused.reason());
                assertEquals(mode == 2 ? 1 : 0, closed.get());
                assertEquals(0, f.pool.stats().leaseClaims());
            }
        }
    }

    @Test
    void unknownProviderFailureRemainsFatalEvenThoughScopeBecameUnknown() throws Exception {
        var fail = new AtomicBoolean();
        var original =
                new UnsupportedOperationException("Unexpected provider implementation error");
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation cancel) {
                        if (id == 1 && fail.get()) {
                            throw original;
                        }
                        return super.stat(id, cancel);
                    }
                };
        try (var f = new Fixture(files);
                var previous = f.prepare()) {
            fail.set(true);
            var error = assertThrows(RuntimeException.class, f::prepare);
            assertTrue(error == original);
            assertFalse(error instanceof WorldTerrainStreaming.Refusal);
            assertFalse(previous.isOpen());
            fail.set(false);
        }
    }

    @Test
    void expectedChangeWithThrowingResourceCleanupIsFatalAndCannotKeepRefusalType()
            throws Exception {
        try (var f = new Fixture()) {
            f.failedCleanup = true;
            var cleanup = new IllegalStateException("Authored resource cleanup failure");
            var error =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    f.source.prepareAndWrite(
                                            f.scope,
                                            TILE,
                                            Cancellation.NONE,
                                            (borrow, cancel) -> {
                                                f.tables.stampMode = 1;
                                                return (AutoCloseable)
                                                        () -> {
                                                            throw cleanup;
                                                        };
                                            }));
            assertFalse(error instanceof WorldTerrainStreaming.Refusal);
            assertFalse(error instanceof java.util.concurrent.CancellationException);
            assertEquals(0, f.pool.stats().leaseClaims());
        }
    }

    @Test
    void boundedNormalizationNeverWalksUnknownCausesOrMasksAuxiliaryFailure() {
        var expected = WorldSourceRefusals.at(WorldSourceRefusals.Site.OUTPUT_CREDIT);
        assertEquals(0, expected.getStackTrace().length);
        assertTrue(expected.getMessage().length() <= 256);
        assertTrue(expected.getCause() == null);
        for (var constructor : WorldTerrainStreaming.Refusal.class.getDeclaredConstructors()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        }
        RuntimeException wrapped = expected;
        for (int i = 0; i < 8; i++) {
            wrapped = new CompletionException(wrapped);
        }
        assertTrue(WorldSourceRefusals.boundary(wrapped) == expected);
        var overBound = new CompletionException(wrapped);
        assertTrue(WorldSourceRefusals.boundary(overBound) == overBound);
        var auxiliary = new CompletionException(expected);
        auxiliary.addSuppressed(new IllegalStateException("Auxiliary callback error"));
        assertFalse(
                WorldSourceRefusals.boundary(auxiliary) instanceof WorldTerrainStreaming.Refusal);
        var canceled = new java.util.concurrent.CancellationException();
        canceled.addSuppressed(new IllegalStateException("Unknown cancellation cleanup"));
        assertFalse(
                WorldSourceRefusals.boundary(canceled)
                        instanceof java.util.concurrent.CancellationException);
        var calls = new AtomicInteger();
        @SuppressWarnings("serial")
        var trap =
                new RuntimeException() {
                    @Override
                    public synchronized Throwable getCause() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        assertTrue(WorldSourceRefusals.boundary(trap) == trap);
        assertEquals(0, calls.get());
    }

    /**
     * Mirrors the files layer: {@code Io.async} stores the thrown failure as-is, so a JDK join on a
     * canceled future rethrows a fresh {@code CancellationException("join")} caused by it.
     */
    static <T> CompletableFuture<T> filesIo(java.util.function.Supplier<T> work) {
        var answer = new CompletableFuture<T>();
        try {
            answer.complete(work.get());
        } catch (RuntimeException failure) {
            answer.completeExceptionally(failure);
        }
        return answer;
    }

    /**
     * ObjectStore.readCore stop, then ListfileStore.nameOf join, then BuildFilesHandle.stat join.
     */
    static <T> CompletableFuture<T> canceledStat(CancellationException original) {
        var read = new CompletableFuture<T>();
        read.completeExceptionally(original);
        var nameOf = filesIo(read::join);
        return filesIo(nameOf::join);
    }

    private static RuntimeException awaitFailure(CompletableFuture<?> future) {
        return assertThrows(RuntimeException.class, () -> WorldSourceFutures.await(future));
    }

    private static CancellationException cancellationChain(int links) {
        var link = new CancellationException();
        for (int i = 1; i < links; i++) {
            var outer = new CancellationException("join");
            outer.initCause(link);
            link = outer;
        }
        return link;
    }

    @Test
    void nestedJoinCancellationFromFilesLayerIsTheOriginalStop() {
        var original = new CancellationException();
        var failure = awaitFailure(canceledStat(original));
        // The exact Windows chain: await wrapper, stat join, nameOf join, original stop.
        assertEquals(CompletionException.class, failure.getClass());
        assertEquals("World source await failed", failure.getMessage());
        var statJoin = failure.getCause();
        assertEquals(CancellationException.class, statJoin.getClass());
        assertEquals("join", statJoin.getMessage());
        var nameJoin = statJoin.getCause();
        assertEquals(CancellationException.class, nameJoin.getClass());
        assertEquals("join", nameJoin.getMessage());
        assertSame(original, nameJoin.getCause());

        assertSame(original, WorldSourceRefusals.boundary(failure));
        assertSame(original, WorldSourceRefusals.boundary((RuntimeException) statJoin));
        var plain = new CancellationException();
        assertSame(plain, WorldSourceRefusals.boundary(plain));
    }

    @Test
    void realFailureInsideCancellationChainStaysFatal() {
        var original = new CancellationException();
        var middle = new IllegalStateException("cleanup failed");
        middle.initCause(original);
        var outer = new CancellationException("join");
        outer.initCause(middle);
        assertFatal(new CompletionException("World source await failed", outer));

        var subclass = new CancellationException("provider cancellation") {};
        subclass.initCause(new CancellationException());
        var overSubclass = new CancellationException("join");
        overSubclass.initCause(subclass);
        assertFatal(overSubclass);

        var endsInFailure = new CancellationException("join");
        endsInFailure.initCause(new IllegalStateException("not a stop"));
        assertFatal(endsInFailure);
    }

    @Test
    void suppressedFailureOnAnyCancellationLinkStaysFatal() {
        for (int at = 0; at < 3; at++) {
            var chain = cancellationChain(3);
            Throwable link = chain;
            for (int i = 0; i < at; i++) {
                link = link.getCause();
            }
            link.addSuppressed(new IllegalStateException("auxiliary failure"));
            assertFatal(chain);
        }
        var wrapped = cancellationChain(3);
        var completion = new CompletionException(wrapped);
        completion.addSuppressed(new IllegalStateException("auxiliary failure"));
        assertFatal(completion);
    }

    @Test
    void cancellationSubclassInsideChainIsNotInspected() {
        var calls = new AtomicInteger();
        var foreign =
                new CancellationException() {
                    @Override
                    public synchronized Throwable getCause() {
                        calls.incrementAndGet();
                        throw new AssertionError("arbitrary cause accessor");
                    }

                    @Override
                    public String getMessage() {
                        calls.incrementAndGet();
                        throw new AssertionError("arbitrary message accessor");
                    }
                };
        var outer = new CancellationException("join");
        outer.initCause(foreign);
        assertFatal(outer);
        assertEquals(0, calls.get());
    }

    @Test
    void cancellationCycleAndOverBoundChainStayFatal() {
        var first = new CancellationException("join");
        var second = new CancellationException("join");
        first.initCause(second);
        second.initCause(first);
        assertFatal(first);

        var deepest = cancellationChain(8);
        Throwable origin = deepest;
        while (origin.getCause() != null) {
            origin = origin.getCause();
        }
        assertSame(origin, WorldSourceRefusals.boundary(deepest));
        var overBound = new CancellationException("join");
        overBound.initCause(deepest);
        assertFatal(overBound);
        assertFatal(cancellationChain(10_000));
    }

    private static void assertFatal(RuntimeException failure) {
        var result = WorldSourceRefusals.boundary(failure);
        assertFalse(
                result instanceof CancellationException,
                "A chain that is not purely cancellation must not become cancellation");
        assertEquals(IllegalStateException.class, result.getClass());
        assertSame(failure, result.getCause());
    }
}
