package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Authored local files only; no game installation, network, keys, JavaFX or GPU. */
public final class ClientHotfixStoreTest {
    private ClientHotfixStoreTest() {}

    private static final ClientHotfixStore.Scope SCOPE =
            new ClientHotfixStore.Scope(
                    new BuildRef("wow", "01".repeat(16), "02".repeat(16), "us"),
                    "enUS",
                    "1.60.1.70707");

    public static void main(String[] args) throws Exception {
        importReopenAndDisableRetainPrivateImmutableFiles();
        reimportDoesNotChangeOldSnapshotAndMismatchedOrCanceledImportKeepsBinding();
        actualHeldReadKeepsSnapshotAndStoreDrainPending();
        concurrentOriginalMutationAndBoundedQueuedCloseDoNotPublish();
        symbolicLinksAreRefusedAndCleanupErrorsRemainSticky();
        metadataCallbacksAndCleanupFailuresDoNotLeakAcceptedOwners();
        watchedRefreshDetectsAndAppliesOnlyNewerSameBuildCaches();
        watchesAreOptInAndClearedByPlainImportUnwatchAndDisable();
        confirmedBuildFollowMovesTheWatchAndKeepsTheOldImport();
        buildFollowMatchesOnePreparedBuildOrAsks();
        System.out.println(
                "CLIENT_HOTFIX_STORE_PASS authoredScenarios=10 noNetwork=true noGameAssets=true");
    }

    private static byte[] cache(int build, int value) {
        return ByteBuffer.allocate(79)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x48544658)
                .putInt(9)
                .putInt(build)
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

    private static byte[] payload(ClientHotfixStore.Snapshot snapshot) {
        return HotfixCacheReader.readPayloadBlocking(
                snapshot.source(), snapshot.index(), 0, 16, Cancellation.NONE);
    }

    private static void importReopenAndDisableRetainPrivateImmutableFiles() throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-store-").toRealPath();
        Path original = root.resolve("DBCache.bin"), storage = root.resolve("private-store");
        byte[] input = cache(70707, 1);
        Files.write(original, input);
        String hash;
        try {
            try (var store = new ClientHotfixStore(storage)) {
                check(
                        store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                        "unconfigured is explicit null");
                var summary =
                        store.importCache(SCOPE, original, Cancellation.NONE)
                                .get(3, TimeUnit.SECONDS);
                hash = summary.sha256();
                check(
                        summary.build() == 70707 && summary.records() == 1,
                        "qualified import summary");
                try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                    check(payload(snapshot)[0] == 1, "selected payload");
                }
                check(
                        Arrays.equals(input, Files.readAllBytes(original)),
                        "original bytes unchanged");
                if (Files.getFileStore(storage).supportsFileAttributeView("posix")) {
                    check(
                            Files.getPosixFilePermissions(storage)
                                    .equals(PosixFilePermissions.fromString("rwx------")),
                            "private directory");
                    check(
                            Files.getPosixFilePermissions(
                                            storage.resolve("objects").resolve(hash + ".bin"))
                                    .equals(PosixFilePermissions.fromString("rw-------")),
                            "private imported file");
                }
            }
            try (var store = new ClientHotfixStore(storage)) {
                try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                    check(
                            snapshot.index().sha256().equals(hash),
                            "durable binding reopens exact snapshot");
                }
                var different = new ClientHotfixStore.Scope(SCOPE.build(), "deDE", SCOPE.version());
                check(
                        store.open(different, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                        "locale association does not leak");
                store.disable(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
                check(
                        store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                        "disabled binding");
                check(
                        Files.exists(storage.resolve("objects").resolve(hash + ".bin")),
                        "disable deliberately retains import");
            }
        } finally {
            remove(root);
        }
    }

    private static void reimportDoesNotChangeOldSnapshotAndMismatchedOrCanceledImportKeepsBinding()
            throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-reimport-").toRealPath();
        Path original = root.resolve("DBCache.bin");
        Files.write(original, cache(70707, 1));
        var cancelNext = new AtomicBoolean();
        try (var store =
                new ClientHotfixStore(
                        root.resolve("store"),
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeCopy(long copied, Cancellation token) {
                                if (cancelNext.get()) token.cancel();
                            }
                        })) {
            var first =
                    store.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            try (var old = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                Files.write(original, cache(70707, 9));
                var second =
                        store.importCache(SCOPE, original, Cancellation.NONE)
                                .get(3, TimeUnit.SECONDS);
                check(!first.sha256().equals(second.sha256()), "new content identity");
                check(payload(old)[0] == 1, "old snapshot remains immutable through reimport");
                try (var fresh = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                    check(payload(fresh)[0] == 9, "new binding uses new bytes");
                }
                Files.write(original, cache(70753, 4));
                fails(store.importCache(SCOPE, original, Cancellation.NONE));
                Files.write(original, cache(70707, 5));
                cancelNext.set(true);
                fails(store.importCache(SCOPE, original, Cancellation.NONE));
                cancelNext.set(false);
                try (var fresh = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                    check(
                            fresh.index().sha256().equals(second.sha256()),
                            "failed or canceled import preserves prior binding");
                }
            }
        } finally {
            remove(root);
        }
    }

    private static void actualHeldReadKeepsSnapshotAndStoreDrainPending() throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-held-").toRealPath();
        Path input = root.resolve("DBCache.bin");
        Files.write(input, cache(70707, 1));
        var hold = new AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var store =
                new ClientHotfixStore(
                        root.resolve("store"),
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeRead() {
                                if (hold.get()) {
                                    entered.complete(null);
                                    release.join();
                                }
                            }
                        });
        try {
            store.importCache(SCOPE, input, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            hold.set(true);
            var reading =
                    CompletableFuture.runAsync(
                            () ->
                                    snapshot.source()
                                            .readRange(0, ByteBuffer.allocate(4), Cancellation.NONE)
                                            .join());
            entered.get(3, TimeUnit.SECONDS);
            var closing = snapshot.closeAsync();
            var storeClosing = store.closeAsync();
            check(
                    !closing.isDone() && !storeClosing.isDone(),
                    "actual held read retains file/store ownership");
            fails(snapshot.source().readRange(0, ByteBuffer.allocate(4), Cancellation.NONE));
            release.complete(null);
            reading.get(3, TimeUnit.SECONDS);
            closing.get(3, TimeUnit.SECONDS);
            storeClosing.get(3, TimeUnit.SECONDS);
            store.closeAsync().get(3, TimeUnit.SECONDS);
        } finally {
            release.complete(null);
            store.closeAsync().get(3, TimeUnit.SECONDS);
            remove(root);
        }
    }

    private static void concurrentOriginalMutationAndBoundedQueuedCloseDoNotPublish()
            throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-mutation-").toRealPath();
        Path original = root.resolve("DBCache.bin");
        Files.write(original, cache(70707, 1));
        var mutate = new AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var hold = new AtomicBoolean();
        var store =
                new ClientHotfixStore(
                        root.resolve("store"),
                        new ClientHotfixStore.Probe() {
                            @Override
                            public void beforeCopy(long copied, Cancellation token) {
                                if (mutate.compareAndSet(true, false)) {
                                    try {
                                        Files.write(original, cache(70707, 9));
                                        Files.setLastModifiedTime(
                                                original,
                                                FileTime.fromMillis(
                                                        System.currentTimeMillis() + 10000));
                                    } catch (Exception error) {
                                        throw new RuntimeException(error);
                                    }
                                }
                                if (hold.get()) {
                                    entered.complete(null);
                                    release.join();
                                }
                            }
                        });
        try {
            var first =
                    store.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            mutate.set(true);
            fails(store.importCache(SCOPE, original, Cancellation.NONE));
            try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                check(
                        snapshot.index().sha256().equals(first.sha256()),
                        "source mutation preserves prior binding");
            }
            hold.set(true);
            var active = new ArrayList<CompletableFuture<?>>();
            active.add(store.importCache(SCOPE, original, Cancellation.NONE));
            entered.get(3, TimeUnit.SECONDS);
            for (int i = 1; i < ClientHotfixStore.MAX_PENDING; i++)
                active.add(store.open(SCOPE, Cancellation.NONE));
            fails(store.open(SCOPE, Cancellation.NONE));
            var close = store.closeAsync();
            check(!close.isDone(), "accepted original-file copy still owns handles");
            release.complete(null);
            for (var operation : active) fails(operation);
            close.get(3, TimeUnit.SECONDS);
        } finally {
            release.complete(null);
            store.closeAsync().get(3, TimeUnit.SECONDS);
            remove(root);
        }
    }

    private static void metadataCallbacksAndCleanupFailuresDoNotLeakAcceptedOwners()
            throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-callback-").toRealPath();
        Path original = root.resolve("DBCache.bin");
        Files.write(original, cache(70707, 1));
        try {
            try (var store = new ClientHotfixStore(root.resolve("callback"))) {
                store.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
                store.open(SCOPE, Cancellation.NONE)
                        .thenAccept(ClientHotfixStore.Snapshot::close)
                        .get(3, TimeUnit.SECONDS);
                store.open(SCOPE, Cancellation.NONE)
                        .thenCompose(snapshot -> snapshot.closeAsync())
                        .thenRun(() -> store.closeAsync().join())
                        .get(3, TimeUnit.SECONDS);
            }
            var dispatch = new AssertionError("synthetic dispatch failure");
            var registration = new AssertionError("synthetic registration cleanup");
            var failedDispatch =
                    new ClientHotfixStore(
                            root.resolve("dispatch"),
                            new ClientHotfixStore.Probe() {
                                @Override
                                public void beforeDispatch() {
                                    throw dispatch;
                                }

                                @Override
                                public void afterRegistrationClose() {
                                    throw registration;
                                }
                            });
            check(
                    root(fails(failedDispatch.open(SCOPE, Cancellation.NONE))) == dispatch,
                    "dispatch original preserved");
            check(
                    Arrays.asList(dispatch.getSuppressed()).contains(registration),
                    "registration cleanup suppressed");
            check(
                    root(fails(failedDispatch.closeAsync())) == registration,
                    "failed dispatch retires accepted operation");
            var armed = new AtomicBoolean();
            var closed = new AtomicInteger();
            var unreturned =
                    new ClientHotfixStore(
                            root.resolve("unreturned"),
                            new ClientHotfixStore.Probe() {
                                @Override
                                public void afterRegistrationClose() {
                                    if (armed.get()) throw registration;
                                }

                                @Override
                                public void afterSnapshotClose() {
                                    if (armed.get()) closed.incrementAndGet();
                                }
                            });
            unreturned.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            armed.set(true);
            check(
                    root(fails(unreturned.open(SCOPE, Cancellation.NONE))) == registration,
                    "unreturned snapshot reports original cleanup failure");
            check(closed.get() == 1, "unreturned file owner closed before failed publication");
            check(
                    root(fails(unreturned.closeAsync())) == registration,
                    "unreturned cleanup remains sticky");
            var channelArmed = new AtomicBoolean();
            var channelFailure = new AssertionError("synthetic owned-channel cleanup");
            var owned =
                    new ClientHotfixStore(
                            root.resolve("channels"),
                            new ClientHotfixStore.Probe() {
                                @Override
                                public void afterOwnedChannelClose() {
                                    if (channelArmed.compareAndSet(true, false))
                                        throw channelFailure;
                                }
                            });
            owned.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            channelArmed.set(true);
            check(
                    root(fails(owned.importCache(SCOPE, original, Cancellation.NONE)))
                            == channelFailure,
                    "owned channel close failure survives");
            check(
                    root(fails(owned.open(SCOPE, Cancellation.NONE))) == channelFailure,
                    "owned channel cleanup poisons subsequent opens");
            check(
                    root(fails(owned.closeAsync())) == channelFailure,
                    "owned channel cleanup reaches store drain");
            Path stagedDirectory = root.resolve("staged-cleanup");
            var mutated = new AtomicBoolean();
            var stagedCloseFailure = new AssertionError("synthetic staged close failure");
            var stagedStore =
                    new ClientHotfixStore(
                            stagedDirectory,
                            new ClientHotfixStore.Probe() {
                                @Override
                                public void beforeRead() {
                                    if (!mutated.compareAndSet(false, true)) return;
                                    try (var files =
                                            Files.list(stagedDirectory.resolve("objects"))) {
                                        Path file =
                                                files.filter(
                                                                p ->
                                                                        p.getFileName()
                                                                                .toString()
                                                                                .startsWith(
                                                                                        "import-"))
                                                        .findFirst()
                                                        .orElseThrow();
                                        byte[] bytes = Files.readAllBytes(file);
                                        bytes[76] = 7;
                                        Files.write(file, bytes);
                                    } catch (Exception failure) {
                                        throw new RuntimeException(failure);
                                    }
                                }

                                @Override
                                public void afterSnapshotClose() {
                                    throw stagedCloseFailure;
                                }
                            });
            Throwable stagedFailure =
                    root(fails(stagedStore.importCache(SCOPE, original, Cancellation.NONE)));
            check(
                    stagedFailure instanceof java.io.UncheckedIOException,
                    "primary cache-byte failure survives staged cleanup");
            check(
                    Arrays.asList(stagedFailure.getCause().getSuppressed())
                            .contains(stagedCloseFailure),
                    "staged cleanup is suppressed on original validation failure");
            check(
                    root(fails(stagedStore.closeAsync())) == stagedCloseFailure,
                    "staged cleanup poison survives store drain");

        } finally {
            remove(root);
        }
    }

    private static void symbolicLinksAreRefusedAndCleanupErrorsRemainSticky() throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-errors-").toRealPath();
        Path original = root.resolve("DBCache.bin");
        Files.write(original, cache(70707, 1));
        try {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Path link = root.resolve("linked.bin");
                Files.createSymbolicLink(link, original);
                try (var store = new ClientHotfixStore(root.resolve("symlink-store"))) {
                    fails(store.importCache(SCOPE, link, Cancellation.NONE));
                }
                Path sourceDirectory = root.resolve("source-directory");
                Files.createDirectory(sourceDirectory);
                Path actual = sourceDirectory.resolve("DBCache.bin");
                Files.write(actual, cache(70707, 1));
                Path parentAlias = root.resolve("source-alias");
                Files.createSymbolicLink(parentAlias, sourceDirectory);
                Path storageAlias = root.resolve("storage-alias");
                Files.createSymbolicLink(storageAlias, root);
                try (var store = new ClientHotfixStore(storageAlias.resolve("canonical-store"))) {
                    store.importCache(SCOPE, parentAlias.resolve("DBCache.bin"), Cancellation.NONE)
                            .get(3, TimeUnit.SECONDS);
                    try (var snapshot =
                            store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                        check(payload(snapshot)[0] == 1, "trusted parent aliases are accepted");
                    }
                }
            }
            var armed = new AtomicBoolean();
            var closed = new AtomicInteger();
            var originalFailure = new AssertionError("synthetic close failure");
            var store =
                    new ClientHotfixStore(
                            root.resolve("cleanup-store"),
                            new ClientHotfixStore.Probe() {
                                @Override
                                public void afterSnapshotClose() {
                                    if (armed.get()) {
                                        closed.incrementAndGet();
                                        throw originalFailure;
                                    }
                                }
                            });
            store.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            var first = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            var second = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            armed.set(true);
            check(
                    root(fails(first.closeAsync())) == originalFailure,
                    "snapshot cleanup failed before store close");
            check(
                    root(fails(store.open(SCOPE, Cancellation.NONE))) == originalFailure,
                    "cleanup fault forbids new positive opens");
            check(
                    root(fails(store.closeAsync())) == originalFailure,
                    "original cleanup error survives store close");
            check(closed.get() == 2, "attempt-all snapshot cleanup");
            check(root(fails(first.closeAsync())) == originalFailure, "first close sticky");
            check(root(fails(second.closeAsync())) == originalFailure, "second close sticky");
            check(root(fails(store.closeAsync())) == originalFailure, "store close sticky");
        } finally {
            remove(root);
        }
    }

    private static Throwable fails(CompletableFuture<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            throw new AssertionError("Expected failure");
        } catch (ExecutionException | CancellationException expected) {
            return root(expected);
        }
    }

    private static Throwable root(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static java.util.List<ClientHotfixStore.RefreshResult> refresh(ClientHotfixStore store)
            throws Exception {
        return store.refreshWatched(Cancellation.NONE).get(5, TimeUnit.SECONDS);
    }

    private static ClientHotfixStore.RefreshResult only(
            java.util.List<ClientHotfixStore.RefreshResult> results,
            ClientHotfixStore.RefreshOutcome outcome,
            String message) {
        check(results.size() == 1, message + ": one watched scope");
        var result = results.getFirst();
        check(result.outcome() == outcome, message + ": " + result.outcome());
        check(SCOPE.equals(result.scope()), message + ": exact scope");
        return result;
    }

    /** Distinct mtimes so the size/mtime fast path cannot hide authored rewrites. */
    private static void rewrite(Path original, byte[] bytes, int step) throws Exception {
        Files.write(original, bytes);
        Files.setLastModifiedTime(
                original, FileTime.fromMillis(1_700_000_000_000L + step * 10_000L));
    }

    private static byte[] boundPayload(ClientHotfixStore store) throws Exception {
        try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
            return payload(snapshot);
        }
    }

    private static void watchedRefreshDetectsAndAppliesOnlyNewerSameBuildCaches()
            throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-refresh-").toRealPath();
        Path original = root.resolve("DBCache.bin"), storage = root.resolve("store");
        rewrite(original, cache(70707, 1), 1);
        try {
            try (var store = new ClientHotfixStore(storage)) {
                var first =
                        store.importCache(SCOPE, original, true, Cancellation.NONE)
                                .get(3, TimeUnit.SECONDS);
                var unchanged = only(refresh(store), ClientHotfixStore.RefreshOutcome.UNCHANGED,
                        "untouched original");
                check(unchanged.summary().sha256().equals(first.sha256()), "same binding");
                var held = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
                try {
                    check(held.watched(), "snapshot reports watch");
                    rewrite(original, cache(70707, 2), 2);
                    var updated = only(refresh(store), ClientHotfixStore.RefreshOutcome.UPDATED,
                            "newer same-build cache");
                    check(!updated.summary().sha256().equals(first.sha256()), "new digest bound");
                    check(payload(held)[0] == 1, "open snapshot stays immutable");
                    check(boundPayload(store)[0] == 2, "new opens use refreshed import");
                    check(Files.exists(storage.resolve("objects").resolve(first.sha256() + ".bin")),
                            "earlier import retained");

                    Files.setLastModifiedTime(original, FileTime.fromMillis(1_700_000_100_000L));
                    var touched = only(refresh(store), ClientHotfixStore.RefreshOutcome.UNCHANGED,
                            "mtime-only change");
                    check(touched.summary().equals(updated.summary()),
                            "identical bytes do not rewrite the binding");
                    check(only(refresh(store), ClientHotfixStore.RefreshOutcome.UNCHANGED,
                                    "recorded touched stat").summary().equals(updated.summary()),
                            "touched stat is recorded");

                    rewrite(original, cache(70800, 3), 3);
                    var moved = only(refresh(store),
                            ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED, "client update");
                    check(moved.clientBuild() == 70800, "declared client build reported");
                    check(boundPayload(store)[0] == 2, "other-build cache is not applied");

                    byte[] malformed = cache(70707, 4);
                    malformed[44] = 0;
                    rewrite(original, malformed, 4);
                    var failed = only(refresh(store), ClientHotfixStore.RefreshOutcome.FAILED,
                            "malformed same-build cache");
                    check(failed.failure() != null && !String.valueOf(failed.summary()).contains(root.toString()),
                            "failure retained without path in summary");
                    check(boundPayload(store)[0] == 2, "malformed cache keeps binding");
                    check(ClientHotfixDetails.refreshFailed(java.util.List.of(failed)), "failure surfaces");

                    Files.delete(original);
                    only(refresh(store), ClientHotfixStore.RefreshOutcome.MISSING, "missing original");
                    check(boundPayload(store)[0] == 2, "missing original keeps binding");
                } finally {
                    held.close();
                }
                byte[] manifestBytes;
                try (var bindings = Files.list(storage.resolve("bindings"))) {
                    manifestBytes = Files.readAllBytes(bindings.findFirst().orElseThrow());
                }
                check(!new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)
                                .contains("DBCache"),
                        "binding manifest still omits the original path");
                if (Files.getFileStore(storage).supportsFileAttributeView("posix")) {
                    check(Files.getPosixFilePermissions(storage.resolve("watches"))
                                    .equals(PosixFilePermissions.fromString("rwx------")),
                            "private watch directory");
                    try (var watches = Files.list(storage.resolve("watches"))) {
                        for (var watch : watches.toList())
                            check(Files.getPosixFilePermissions(watch)
                                            .equals(PosixFilePermissions.fromString("rw-------")),
                                    "private watch record");
                    }
                }
            }
            rewrite(original, cache(70707, 5), 5);
            try (var store = new ClientHotfixStore(storage)) {
                only(refresh(store), ClientHotfixStore.RefreshOutcome.UPDATED,
                        "watch survives store reopen");
                check(boundPayload(store)[0] == 5, "reopened store applies newer cache");
                var other = new ClientHotfixStore.Scope(SCOPE.build(), "deDE", SCOPE.version());
                check(store.open(other, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                        "refresh never binds another locale");
            }
            check(ClientHotfixDetails.refreshSummary(java.util.List.of()).startsWith("No client caches"),
                    "empty summary");
        } finally {
            remove(root);
        }
    }

    private static final ClientHotfixStore.Scope NEXT =
            new ClientHotfixStore.Scope(
                    new BuildRef("wow", "03".repeat(16), "04".repeat(16), "us"),
                    "enUS",
                    "1.60.1.70800");

    private static void confirmedBuildFollowMovesTheWatchAndKeepsTheOldImport() throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-follow-").toRealPath();
        Path original = root.resolve("DBCache.bin"), storage = root.resolve("store");
        rewrite(original, cache(70707, 1), 1);
        try (var store = new ClientHotfixStore(storage)) {
            var old = store.importCache(SCOPE, original, true, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            rewrite(original, cache(70800, 2), 2);
            var moved = only(refresh(store), ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED,
                    "client update");
            check(moved.clientBuild() == 70800, "declared build reported");
            check(store.open(NEXT, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                    "nothing is bound before the user confirms");

            var wrong = new ClientHotfixStore.Scope(NEXT.build(), "enUS", "1.60.1.70801");
            check(fails(store.follow(SCOPE, wrong, Cancellation.NONE)) != null,
                    "a build the cache does not declare is refused");
            check(store.open(wrong, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                    "refused follow binds nothing");
            check(boundPayload(store)[0] == 1, "refused follow keeps the old binding");
            only(refresh(store), ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED,
                    "refused follow keeps the old watch");

            var followed = store.follow(SCOPE, NEXT, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(followed.build() == 70800 && followed.version().equals(NEXT.version()), "new scope summary");
            try (var snapshot = store.open(NEXT, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                check(payload(snapshot)[0] == 2 && snapshot.watched(), "new build bound and watched");
            }
            try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                check(snapshot.index().sha256().equals(old.sha256()) && !snapshot.watched(),
                        "old build keeps its import without the watch");
            }
            var after = refresh(store);
            check(after.size() == 1 && after.getFirst().scope().equals(NEXT)
                            && after.getFirst().outcome() == ClientHotfixStore.RefreshOutcome.UNCHANGED,
                    "refresh now checks the new build only");
            check(fails(store.follow(SCOPE, NEXT, Cancellation.NONE)) != null,
                    "an unwatched build cannot be followed again");
            try {
                store.follow(SCOPE, new ClientHotfixStore.Scope(NEXT.build(), "deDE", NEXT.version()),
                        Cancellation.NONE);
                throw new AssertionError("another locale was accepted");
            } catch (IllegalArgumentException expected) {
                // Locale and product never change on a follow.
            }
        } finally {
            remove(root);
        }
    }

    private static BuildInfo info(String version, String config, String region) {
        return new BuildInfo("wow", "Retail", version, region, config, BuildInfo.BuildSource.CURRENT,
                true, null, 0, "wow:" + config + ":" + region, null, null, java.util.List.of());
    }

    private static PreparationHistory.Receipt receipt(BuildInfo info, String cdn, String region,
            String locale, BuildPreparation.Ready ready) {
        return new PreparationHistory.Receipt(info.identity(), info.product(), info.config(), cdn, region,
                locale, ready.name());
    }

    private static void buildFollowMatchesOnePreparedBuildOrAsks() {
        var moved = new ClientHotfixStore.RefreshResult(SCOPE,
                ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED, null, 70800, null);
        var next = info("1.60.1.70800", "03".repeat(16), "us");
        var nextEu = info("1.60.1.70800", "05".repeat(16), "eu");
        var older = info("1.60.1.70790", "06".repeat(16), "us");
        var ready = BuildPreparation.Ready.TABLES_READY;
        var follow = ClientHotfixDetails.buildFollow(moved, java.util.List.of(next, older),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "enUS", ready),
                        receipt(older, "07".repeat(16), "us", "enUS", ready))).orElseThrow();
        check(follow.to().equals(NEXT) && follow.from().equals(SCOPE), "exact prepared scope");
        check(follow.prompt().equals("Client updated to build 70800. Apply this cache to build 70800?"),
                "owner wording: " + follow.prompt());
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(next, nextEu),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "enUS", ready),
                        receipt(nextEu, "08".repeat(16), "eu", "enUS", ready))).orElseThrow().to().equals(NEXT),
                "same region preferred");
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(nextEu),
                java.util.List.of(receipt(nextEu, "08".repeat(16), "eu", "enUS", ready))).isPresent(),
                "a single other-region build is still offered");
        var twoUs = info("1.60.1.70800", "09".repeat(16), "us");
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(next, twoUs),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "enUS", ready),
                        receipt(twoUs, "0a".repeat(16), "us", "enUS", ready))).isEmpty(),
                "ambiguous builds are not guessed");
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(next),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "enUS",
                        BuildPreparation.Ready.FILES_READY))).isEmpty(), "unprepared build is not offered");
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(next),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "deDE", ready))).isEmpty(),
                "another locale is not offered");
        check(ClientHotfixDetails.buildFollow(moved, java.util.List.of(older),
                java.util.List.of(receipt(older, "07".repeat(16), "us", "enUS", ready))).isEmpty(),
                "a build without that number is not offered");
        var unchanged = new ClientHotfixStore.RefreshResult(SCOPE,
                ClientHotfixStore.RefreshOutcome.UNCHANGED, null, 70707, null);
        check(ClientHotfixDetails.buildFollow(unchanged, java.util.List.of(next),
                java.util.List.of(receipt(next, "04".repeat(16), "us", "enUS", ready))).isEmpty(),
                "only a client build change prompts");
    }

    private static void watchesAreOptInAndClearedByPlainImportUnwatchAndDisable()
            throws Exception {
        Path root = Files.createTempDirectory("client-hotfix-watch-").toRealPath();
        Path original = root.resolve("DBCache.bin");
        rewrite(original, cache(70707, 1), 1);
        try (var store = new ClientHotfixStore(root.resolve("store"))) {
            store.importCache(SCOPE, original, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(refresh(store).isEmpty(), "plain import is not watched");
            try (var snapshot = store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS)) {
                check(!snapshot.watched(), "unwatched snapshot");
            }
            store.importCache(SCOPE, original, true, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(refresh(store).size() == 1, "opted-in watch");
            store.importCache(SCOPE, original, false, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(refresh(store).isEmpty(), "later plain import clears watch");

            store.importCache(SCOPE, original, true, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            store.unwatch(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(refresh(store).isEmpty(), "unwatch clears watch");
            check(boundPayload(store)[0] == 1, "unwatch keeps binding");

            store.importCache(SCOPE, original, true, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            store.disable(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            check(refresh(store).isEmpty(), "disable clears watch");
            rewrite(original, cache(70707, 2), 2);
            check(refresh(store).isEmpty(), "disabled scope is never re-enabled by refresh");
            check(store.open(SCOPE, Cancellation.NONE).get(3, TimeUnit.SECONDS) == null,
                    "disabled stays disabled");

            // Atomic replacement keeping size and mtime is still detected by file identity.
            rewrite(original, cache(70707, 6), 6);
            store.importCache(SCOPE, original, true, Cancellation.NONE).get(3, TimeUnit.SECONDS);
            var keptTime = Files.getLastModifiedTime(original);
            Path replacement = root.resolve("DBCache.bin.new");
            Files.write(replacement, cache(70707, 7));
            Files.setLastModifiedTime(replacement, keptTime);
            Files.move(replacement, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            var replaced = refresh(store);
            var replacedKey = Files.readAttributes(original,
                    java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            if (replacedKey != null) {
                check(replaced.size() == 1
                                && replaced.getFirst().outcome() == ClientHotfixStore.RefreshOutcome.UPDATED,
                        "same size/mtime replacement detected by file identity");
                check(boundPayload(store)[0] == 7, "replacement applied");
            }

            // A corrupt private watch record fails one refresh, then is removed.
            Path watches = root.resolve("store").resolve("watches");
            Path record;
            try (var listed = Files.list(watches)) {
                record = listed.findFirst().orElseThrow();
            }
            Files.writeString(record, "not a watch record\n");
            var corrupt = refresh(store);
            check(corrupt.size() == 1 && corrupt.getFirst().scope() == null
                            && corrupt.getFirst().outcome() == ClientHotfixStore.RefreshOutcome.FAILED,
                    "corrupt watch reported once");
            check(refresh(store).isEmpty(), "corrupt watch removed after reporting");
            check(boundPayload(store)[0] == (replacedKey != null ? 7 : 6),
                    "corrupt watch keeps the binding");
        } finally {
            remove(root);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void remove(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
