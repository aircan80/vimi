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
        System.out.println(
                "CLIENT_HOTFIX_STORE_PASS authoredScenarios=5 noNetwork=true noGameAssets=true");
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

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void remove(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
