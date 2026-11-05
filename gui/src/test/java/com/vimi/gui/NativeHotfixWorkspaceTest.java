package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.Builds;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real immutable imports and workspace ownership; no source/network opening. */
@SuppressWarnings("try")
public final class NativeHotfixWorkspaceTest {
    private NativeHotfixWorkspaceTest() {}

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static void ensure(NativePreparationWork work, Cancellation token) {
        try {
            var method =
                    NativePreparationWork.class.getDeclaredMethod(
                            "ensureHotfix", Cancellation.class);
            method.setAccessible(true);
            method.invoke(work, token);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static NativePreparationWork work(ClientHotfixStore store, String version) {
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        version,
                        "us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        var region = new BuildInfo.RegionEvidence("us", "b".repeat(32), true, null, null);
        ObjectSource online = coldSource(), local = coldSource();
        Builds builds =
                (build, token) -> CompletableFuture.failedFuture(new AssertionError("No build IO"));
        return new NativePreparationWork(info, region, "enUS", builds, online, null, null, local)
                .withHotfixImports(store);
    }

    private static ObjectSource coldSource() {
        return (ObjectSource)
                Proxy.newProxyInstance(
                        ObjectSource.class.getClassLoader(),
                        new Class<?>[] {ObjectSource.class},
                        (proxy, method, values) -> {
                            throw new AssertionError("No source IO: " + method.getName());
                        });
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final ClientHotfixStore store;
        final NativePreparationWork work;

        Fixture(ClientHotfixStore.Probe probe) throws Exception {
            root = Files.createTempDirectory("native-hotfix-work-");
            store = new ClientHotfixStore(root.resolve("store"), probe);
            work = work(store, "1.60.1.70707");
            var input = root.resolve("DBCache.bin");
            Files.write(
                    input,
                    ByteBuffer.allocate(44)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0x48544658)
                            .putInt(9)
                            .putInt(70707)
                            .put(new byte[32])
                            .array());
            store.importCache(work.hotfixScope(), input, Cancellation.NONE)
                    .get(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                work.close();
            } finally {
                try {
                    store.closeAsync().get(3, TimeUnit.SECONDS);
                } finally {
                    try (var files = Files.walk(root)) {
                        for (var file : files.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        var error = future.handle((value, failure) -> failure).get(3, TimeUnit.SECONDS);
        if (error == null) throw new AssertionError("Expected failure");
        return error;
    }

    private static boolean contains(Throwable error, Throwable target) {
        for (int i = 0; error != null && i < 8; i++) {
            if (error == target) return true;
            if (error.getClass() != CompletionException.class
                    && error.getClass() != java.util.concurrent.ExecutionException.class)
                return false;
            error = error.getCause();
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        loadedSnapshotRetiresWithWork();
        closeCancelsOwnedQualificationButWaitsActualRead();
        canceledQualificationCanRetrySameWork();
        adoptionFailureTerminatesAndRetiresSnapshot();
        cleanupFailureRemainsPhysicalFailure();
        foreignCancellationIsNotClassified();
        invalidVersionNeverTouchesStore();
        refreshedBindingMarksOnlyOlderLoadedSnapshotsForReopen();
        publishedWorkspaceTablesApplyLoadedSnapshot();
        System.out.println("NATIVE_HOTFIX_WORKSPACE_PASS scenarios=9 noNetwork=true");
    }

    private static void loadedSnapshotRetiresWithWork() throws Exception {
        try (var fixture = new Fixture(new ClientHotfixStore.Probe() {})) {
            ensure(fixture.work, Cancellation.NONE);
            var snapshot = (ClientHotfixStore.Snapshot) field(fixture.work, "hotfixSnapshot");
            check(snapshot.index().entries().isEmpty(), "Actual same-build empty cache loaded");
            fixture.work.close();
            check(snapshot.closeAsync().isDone(), "Workspace retired actual snapshot");
            try {
                snapshot.index();
                throw new AssertionError("Retired snapshot metadata escaped");
            } catch (IllegalStateException expected) {
            }
        }
    }

    private static void closeCancelsOwnedQualificationButWaitsActualRead() throws Exception {
        var hold = new AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        try (var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeRead() {
                                if (hold.get()) {
                                    entered.complete(null);
                                    release.join();
                                }
                            }
                        })) {
            hold.set(true);
            var loading = CompletableFuture.runAsync(() -> ensure(fixture.work, Cancellation.NONE));
            try {
                entered.get(3, TimeUnit.SECONDS);
                var child = (Cancellation) field(fixture.work, "hotfixCancellation");
                var canceled = new CompletableFuture<Void>();
                var registration = child.onCancel(() -> canceled.complete(null));
                var closing = CompletableFuture.runAsync(fixture.work::close);
                canceled.get(3, TimeUnit.SECONDS);
                check(!closing.isDone() && !loading.isDone(), "Close waits actual held store read");
                release.complete(null);
                failure(loading);
                closing.get(3, TimeUnit.SECONDS);
                registration.close();
            } finally {
                release.complete(null);
            }
        }
    }

    private static void canceledQualificationCanRetrySameWork() throws Exception {
        var hold = new AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        try (var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeRead() {
                                if (hold.get()) {
                                    entered.complete(null);
                                    release.join();
                                }
                            }
                        })) {
            hold.set(true);
            var token = new Cancellation();
            var loading = CompletableFuture.runAsync(() -> ensure(fixture.work, token));
            try {
                entered.get(3, TimeUnit.SECONDS);
                token.cancel();
                release.complete(null);
                failure(loading);
                hold.set(false);
                ensure(fixture.work, Cancellation.NONE);
                check(
                        field(fixture.work, "hotfixSnapshot") != null,
                        "Same workspace retries after clean cancellation");
            } finally {
                release.complete(null);
            }
        }
    }

    private static void adoptionFailureTerminatesAndRetiresSnapshot() throws Exception {
        var retired = new AtomicInteger();
        try (var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void afterSnapshotClose() {
                                retired.incrementAndGet();
                            }
                        })) {
            int before = retired.get();
            fixture.work.hotfixAdoptionProbe = ClientHotfixStore.Snapshot::close;
            failure(CompletableFuture.runAsync(() -> ensure(fixture.work, Cancellation.NONE)));
            check(
                    retired.get() == before + 1,
                    "Metadata failure closes returned owner exactly once");
            fixture.work.close();
            check(retired.get() == before + 1, "Close does not duplicate retirement");
        }
    }

    private static void cleanupFailureRemainsPhysicalFailure() throws Exception {
        var fault = new AssertionError("synthetic snapshot close failure");
        var armed = new AtomicBoolean();
        var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void afterSnapshotClose() {
                                if (armed.get()) throw fault;
                            }
                        });
        try {
            armed.set(true);
            ensure(fixture.work, Cancellation.NONE);
            Throwable error = failure(CompletableFuture.runAsync(fixture.work::close));
            check(
                    contains(error, fault),
                    "Returned snapshot actual cleanup failure reaches workspace close");
            check(
                    contains(failure(fixture.store.closeAsync()), fault),
                    "Store retains physical cleanup failure");
        } finally {
            try {
                fixture.close();
            } catch (java.util.concurrent.ExecutionException expected) {
            }
        }
    }

    private static void foreignCancellationIsNotClassified() throws Exception {
        var armed = new AtomicBoolean();
        var getters = new AtomicInteger();
        var foreign =
                new CancellationException() {
                    @Override
                    public synchronized Throwable getCause() {
                        getters.incrementAndGet();
                        throw new AssertionError("Foreign getter");
                    }

                    @Override
                    public String getMessage() {
                        getters.incrementAndGet();
                        throw new AssertionError("Foreign message");
                    }
                };
        try (var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeRead() {
                                if (armed.get()) throw foreign;
                            }
                        })) {
            armed.set(true);
            var error =
                    failure(
                            CompletableFuture.runAsync(
                                    () -> ensure(fixture.work, Cancellation.NONE)));
            check(contains(error, foreign), "Unknown foreign failure remains original");
            check(getters.get() == 0, "No foreign classifier/accessor calls");
            fixture.work.close();
        }
    }

    private static void invalidVersionNeverTouchesStore() throws Exception {
        var reads = new AtomicInteger();
        try (var fixture =
                new Fixture(
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeRead() {
                                reads.incrementAndGet();
                            }
                        })) {
            int previous = reads.get();
            var invalid = work(fixture.store, "unknown");
            try {
                ensure(invalid, Cancellation.NONE);
                check(reads.get() == previous, "Invalid version uses base-only without store IO");
                check(
                        invalid.hotfixStatus().contains("exact build version"),
                        "Explicit base-only reason");
            } finally {
                invalid.close();
            }
        }
    }

    private static byte[] cacheWithRecord(int value) {
        return ByteBuffer.allocate(79)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x48544658)
                .putInt(9)
                .putInt(70707)
                .put(new byte[32])
                .putInt(0x48544658)
                .putInt(70)
                .putInt(1)
                .putInt(1)
                .putInt(0x12345678)
                .putInt(1328613)
                .putInt(3)
                .put((byte) 1)
                .put(new byte[3])
                .put((byte) value)
                .put((byte) 2)
                .put((byte) 3)
                .array();
    }

    /** Refresh notes compare at read time, so note/adoption ordering cannot hide a reopen. */
    private static void refreshedBindingMarksOnlyOlderLoadedSnapshotsForReopen() throws Exception {
        try (var fixture = new Fixture(new ClientHotfixStore.Probe() {})) {
            var scope = fixture.work.hotfixScope();
            var original = fixture.root.resolve("watched-DBCache.bin");
            Files.write(original, cacheWithRecord(1));
            Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
            var first = fixture.store.importCache(scope, original, true, Cancellation.NONE)
                    .get(3, TimeUnit.SECONDS);
            ensure(fixture.work, Cancellation.NONE);
            check(fixture.work.hotfixWatched(), "Loaded watched snapshot reports its watch");
            check(fixture.work.hotfixStatus().contains("checked on Refresh sources"),
                    "Status says the cache is checked on refresh");
            fixture.work.noteRefreshedHotfix(first.sha256());
            check(!fixture.work.hotfixRefreshPending(), "Unchanged binding needs no reopen");

            Files.write(original, cacheWithRecord(2));
            Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(1_700_000_010_000L));
            var results = fixture.store.refreshWatched(Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(results.size() == 1 && results.getFirst().outcome() == ClientHotfixStore.RefreshOutcome.UPDATED,
                    "Refresh imports the newer same-build cache");
            String newer = results.getFirst().summary().sha256();
            fixture.work.noteRefreshedHotfix(newer);
            check(fixture.work.hotfixRefreshPending(), "Older loaded snapshot is marked for reopen");
            check(fixture.work.hotfixStatus().contains("reopen to apply"), "Status asks for reopen");
            check(!fixture.work.hotfixStatus().contains(fixture.root.toString()), "Status never includes the path");
            var loaded = (ClientHotfixStore.Snapshot) field(fixture.work, "hotfixSnapshot");
            check(loaded.index().sha256().equals(first.sha256()), "Open workspace keeps its immutable snapshot");

            var reopened = work(fixture.store, "1.60.1.70707");
            try {
                // Note recorded before adoption completes: the reopened work loads the newer
                // binding and must not report a pending reopen.
                reopened.noteRefreshedHotfix(newer);
                check(!reopened.hotfixRefreshPending(), "Unloaded work is not pending");
                ensure(reopened, Cancellation.NONE);
                var adopted = (ClientHotfixStore.Snapshot) field(reopened, "hotfixSnapshot");
                check(adopted.index().sha256().equals(newer), "Reopened work applies refreshed import");
                check(!reopened.hotfixRefreshPending(), "Reopened work is current");
            } finally {
                reopened.close();
            }
        }
    }

    /**
     * The published workspace (talents, items, source details) must read the same effective view
     * as key bootstrap and table preparation. Only local comparison stays base-only.
     */
    private static void publishedWorkspaceTablesApplyLoadedSnapshot() throws Exception {
        try (var fixture = new Fixture(new ClientHotfixStore.Probe() {})) {
            var status = new com.vibeminer.formats.generated.TactKeyStatus(java.util.Map.of());
            check(fixture.work.tablesOptions(status, NativePreparationWork.TableRole.WORKSPACE).hotfix() == null,
                    "No hotfix binding before the snapshot loads");
            check(fixture.work.hotfixIdentity() == null, "No hotfix identity before the snapshot loads");
            ensure(fixture.work, Cancellation.NONE);
            var preparation =
                    fixture.work.tablesOptions(status, NativePreparationWork.TableRole.PREPARATION).hotfix();
            var workspace =
                    fixture.work.tablesOptions(status, NativePreparationWork.TableRole.WORKSPACE).hotfix();
            var comparison =
                    fixture.work.tablesOptions(status, NativePreparationWork.TableRole.LOCAL_BASE).hotfix();
            check(preparation != null, "Preparation reads the loaded snapshot");
            check(workspace != null, "Published workspace tables read the loaded snapshot");
            check(workspace.inputFingerprint().equals(preparation.inputFingerprint()),
                    "Published workspace and preparation share one hotfix input");
            check(comparison == null, "Local comparison stays base-only");
            check(fixture.work.hotfixIdentity() != null
                            && fixture.work.hotfixIdentity().contains(workspace.inputFingerprint()),
                    "Derived workspace caches are keyed by the hotfix input");
        }
    }
}
