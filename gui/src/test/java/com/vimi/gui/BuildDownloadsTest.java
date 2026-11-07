package com.vimi.gui;

import com.vibeminer.builds.DownloadAllJobs;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistence, queue isolation and shutdown races without a network or JavaFX runtime. */
public final class BuildDownloadsTest {
    private BuildDownloadsTest() {}

    public static void main(String[] args) throws Exception {
        queuePauseResumeAndRestart();
        restartActiveAndRetryFailure();
        refusesFutureSchemaWithoutMutation();
        System.out.println(
                "Download policy: durable intent, bounded queue, pause/resume, shutdown, retry and"
                        + " non-resuming open passed");
    }

    private static BuildInfo build(int id) {
        return new BuildInfo(
                "wow",
                "Retail",
                "fixture",
                "us",
                String.format("%032x", id),
                BuildInfo.BuildSource.SAVED,
                false,
                null,
                0);
    }

    private static BuildInfo.RegionEvidence route() {
        return new BuildInfo.RegionEvidence("us", String.format("%032x", 999), true, null, null);
    }

    private static DownloadAllJobs.Result completed(DownloadIntents.Entry entry) {
        var progress =
                new DownloadAllJobs.Progress(
                        entry.build(), DownloadAllJobs.Stage.FETCHING, 2, 2, 30, 30, 0, 0);
        return new DownloadAllJobs.Result(
                entry.build(), DownloadAllJobs.Outcome.COMPLETED, progress, 0, 0, 0, 0, null, null);
    }

    private static void queuePauseResumeAndRestart() throws Exception {
        var directory = Files.createTempDirectory("vimi-download-policy-");
        var library = ObjectStore.openLibrary(directory).join();
        var first = build(1);
        var second = build(2);
        var started = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var downloads =
                BuildDownloads.open(
                        library.databasePath(),
                        (entry, token, progress) -> {
                            calls.incrementAndGet();
                            require(
                                    new DownloadIntents(library.databasePath())
                                            .load().stream()
                                                    .anyMatch(
                                                            e ->
                                                                    e.identity()
                                                                                    .equals(
                                                                                            entry
                                                                                                    .identity())
                                                                            && e.enabled()),
                                    "Intent commits before runner starts");
                            if (entry.identity().equals(first.identity()) && calls.get() == 1) {
                                started.countDown();
                                var canceled = new CompletableFuture<Void>();
                                var registration = token.onCancel(() -> canceled.complete(null));
                                try {
                                    canceled.get(10, TimeUnit.SECONDS);
                                    token.check();
                                } finally {
                                    registration.close();
                                }
                            }
                            progress.accept(completed(entry).finalProgress());
                            return completed(entry);
                        });
        try {
            downloads.enable(first, route()).get(5, TimeUnit.SECONDS);
            require(started.await(5, TimeUnit.SECONDS), "First starts");
            downloads.enable(second, route()).get(5, TimeUnit.SECONDS);
            require(
                    downloads.snapshot(second.identity()).state() == BuildDownloads.State.QUEUED,
                    "Second is queued independently of visible workspace");
            downloads.pause(first.identity()).get(5, TimeUnit.SECONDS);
            await(downloads, second, BuildDownloads.State.COMPLETED);
            require(
                    downloads.snapshot(first.identity()).state() == BuildDownloads.State.PAUSED,
                    "Cancellation completion cannot overwrite pause");
            downloads.resume(first.identity()).get(5, TimeUnit.SECONDS);
            await(downloads, first, BuildDownloads.State.COMPLETED);
            downloads.onDemand(second.identity()).get(5, TimeUnit.SECONDS);
        } finally {
            downloads.closeAsync().get(10, TimeUnit.SECONDS);
        }
        var reopened =
                BuildDownloads.open(
                        library.databasePath(),
                        (entry, token, progress) -> {
                            throw new AssertionError("Completed/off policies must not start");
                        });
        try {
            require(
                    reopened.snapshot(first.identity()).state() == BuildDownloads.State.COMPLETED,
                    "Completed outcome persists");
            require(!reopened.snapshot(second.identity()).enabled(), "On demand persists");
        } finally {
            reopened.closeAsync().get(5, TimeUnit.SECONDS);
            library.closeAsync().join();
            delete(directory);
        }
    }

    private static void restartActiveAndRetryFailure() throws Exception {
        var directory = Files.createTempDirectory("vimi-download-resume-");
        var library = ObjectStore.openLibrary(directory).join();
        var build = build(3);
        var started = new CountDownLatch(1);
        var running =
                BuildDownloads.open(
                        library.databasePath(),
                        (entry, token, progress) -> {
                            started.countDown();
                            var canceled = new CompletableFuture<Void>();
                            var registration = token.onCancel(() -> canceled.complete(null));
                            try {
                                canceled.get(10, TimeUnit.SECONDS);
                                token.check();
                                return completed(entry);
                            } finally {
                                registration.close();
                            }
                        });
        running.enable(build, route()).get(5, TimeUnit.SECONDS);
        require(started.await(5, TimeUnit.SECONDS), "Active starts");
        running.closeAsync().get(10, TimeUnit.SECONDS);
        // Measurement opens load the persisted intent but never resume it on their own.
        var quiet =
                BuildDownloads.open(
                        library.databasePath(),
                        (entry, token, progress) -> {
                            throw new AssertionError("A non-resuming open started a download");
                        },
                        false);
        try {
            Thread.sleep(300);
            require(
                    quiet.snapshot(build.identity()).enabled(),
                    "Non-resuming open still shows the persisted intent");
        } finally {
            quiet.closeAsync().get(5, TimeUnit.SECONDS);
        }
        var attempts = new AtomicInteger();
        var resumed =
                BuildDownloads.open(
                        library.databasePath(),
                        (entry, token, progress) -> {
                            if (attempts.incrementAndGet() == 1)
                                throw new IllegalStateException("offline fixture");
                            return completed(entry);
                        });
        try {
            await(resumed, build, BuildDownloads.State.FAILED);
            require(attempts.get() == 1, "No retry spin after failure");
            resumed.resume(build.identity()).get(5, TimeUnit.SECONDS);
            await(resumed, build, BuildDownloads.State.COMPLETED);
            require(attempts.get() == 2, "Explicit retry starts a new missing-content check");
        } finally {
            resumed.closeAsync().get(5, TimeUnit.SECONDS);
            library.closeAsync().join();
            delete(directory);
        }
    }

    private static void refusesFutureSchemaWithoutMutation() throws Exception {
        var directory = Files.createTempDirectory("vimi-download-version-");
        var library = ObjectStore.openLibrary(directory).join();
        var database = library.databasePath();
        new DownloadIntents(database);
        try (var connection = new com.vibeminer.store.DerivedSqliteConnection(database)) {
            connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute("UPDATE builds_download_metadata SET version=2");
                        return null;
                    });
        }
        library.closeAsync().join();
        var before =
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(database));
        try {
            new DownloadIntents(database);
            throw new AssertionError("Future schema accepted");
        } catch (IllegalArgumentException expected) {
        }
        var after =
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(database));
        require(
                java.util.Arrays.equals(before, after),
                "Unsupported schema rejected before mutation");
        delete(directory);
    }

    private static void await(BuildDownloads downloads, BuildInfo build, BuildDownloads.State state)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (downloads.snapshot(build.identity()).state() != state && System.nanoTime() < until)
            Thread.sleep(10);
        require(
                downloads.snapshot(build.identity()).state() == state,
                "Expected " + state + " but got " + downloads.snapshot(build.identity()));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void delete(java.nio.file.Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
