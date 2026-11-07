package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The World measurement probe's open path over an authored offline library: it refuses an
 * unprepared build and the application's own library, and opening a World through it adds no
 * stored object and changes no payload file.
 */
public final class WorldMeasurementLibraryTest {
    private WorldMeasurementLibraryTest() {}

    public static void main(String[] args) throws Exception {
        refusesMissingDefaultAndUnseededLibraries();
        Path directory = Files.createTempDirectory("vimi-world-measurement-open-");
        Throwable primary = null;
        try {
            var fixture = FullPreparationLibraryIntegrationTest.seed(directory);
            Files.writeString(directory.resolve(WorldMeasurementOpen.MARKER), "");
            String version;
            var service =
                    BuildApplication.open(directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                var build =
                        service.listBuilds().get(15, TimeUnit.SECONDS).stream()
                                .filter(value -> value.config().equals(fixture.buildConfig().hex()))
                                .findFirst()
                                .orElseThrow();
                version = build.version();
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }
            try {
                WorldMeasurementOpen.open(
                                directory,
                                version,
                                OfflineSourceFixtures::publicKeys,
                                ignored -> {},
                                TimeUnit.MINUTES.toNanos(1))
                        .close();
                throw new AssertionError("Opened a build with no Tables-ready receipt");
            } catch (IllegalStateException expected) {
                require(
                        expected.getMessage().contains("Tables-ready preparation"),
                        "Unprepared build refused: " + expected.getMessage());
            }
            service =
                    BuildApplication.open(directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                var build =
                        service.listBuilds().get(15, TimeUnit.SECONDS).stream()
                                .filter(value -> value.config().equals(fixture.buildConfig().hex()))
                                .findFirst()
                                .orElseThrow();
                var job = service.preparation().start(build, build.observations().getFirst(), "enUS");
                var snapshot = job.completion().get(40, TimeUnit.SECONDS);
                require(
                        snapshot.ready() == BuildPreparation.Ready.TABLES_READY,
                        "Fixture prepares: " + snapshot.problem());
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }
            var payloadsBefore = payloads(directory.resolve("library"));
            long objectsBefore = storedObjects(directory);
            try (var opened =
                    WorldMeasurementOpen.open(
                            directory,
                                version,
                                OfflineSourceFixtures::publicKeys,
                                ignored -> {},
                                TimeUnit.MINUTES.toNanos(1))) {
                require(opened.receipt.locale().equals("enUS"), "Receipt supplies the locale");
                opened.world().catalog(Cancellation.NONE).get(20, TimeUnit.SECONDS);
            }
            require(
                    storedObjects(directory) == objectsBefore,
                    "Measurement open stores no object: "
                            + objectsBefore
                            + " -> "
                            + storedObjects(directory));
            var payloadsAfter = payloads(directory.resolve("library"));
            require(
                    payloadsAfter.equals(payloadsBefore),
                    "Measurement open changes no payload file: " + changed(payloadsBefore, payloadsAfter));
            creatureModeKeysAndOpenWriteNothing(directory, version);
            System.out.println(
                    "World measurement open: refuses unprepared builds and the application's"
                            + " library; no stored object or payload write; creatures mode pins"
                            + " the loaded keys only, without a refresh, and its creature open"
                            + " writes nothing passed");
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            TestCleanup.deleteTree(primary, directory);
        }
    }

    /**
     * Creatures mode (#553): the work is pinned to exactly the library's loaded community key
     * snapshot, and the key client is never asked; every other mode pins no keys. Opening the
     * measurement creatures over the measured World returns its warmup issues, closes cleanly, and
     * neither open stores an object or changes a payload file.
     */
    private static void creatureModeKeysAndOpenWriteNothing(Path directory, String version)
            throws Exception {
        var fetches = new java.util.concurrent.atomic.AtomicInteger();
        com.vibeminer.network.TactKeyClient counting =
                token -> {
                    fetches.incrementAndGet();
                    return OfflineSourceFixtures.publicKeys(token);
                };
        var payloadsBefore = payloads(directory.resolve("library"));
        long objectsBefore = storedObjects(directory);
        try (var opened =
                WorldMeasurementOpen.open(
                        directory,
                        version,
                        counting,
                        ignored -> {},
                        TimeUnit.MINUTES.toNanos(1),
                        true)) {
            var loaded = opened.sources().tactKeys();
            require(loaded.count() > 0, "The fixture library has loaded community keys");
            require(opened.pinnedKeys == loaded, "Creatures mode pins the loaded key snapshot");
            try (var creatures = opened.openCreatures(Cancellation.NONE)) {
                require(
                        creatures.warmup() != null
                                && creatures.creatures() != null
                                && creatures.races() != null,
                        "Measurement creatures open with their warmup issues: "
                                + creatures.warmup());
            }
        }
        try (var opened =
                WorldMeasurementOpen.open(
                        directory,
                        version,
                        counting,
                        ignored -> {},
                        TimeUnit.MINUTES.toNanos(1),
                        false)) {
            require(
                    opened.pinnedKeys == com.vibeminer.files.keys.TactKeys.EMPTY,
                    "Other modes pin no community keys");
        }
        require(fetches.get() == 0, "No TACT key refresh is started: " + fetches.get());
        require(
                storedObjects(directory) == objectsBefore,
                "Creature-mode open stores no object: "
                        + objectsBefore
                        + " -> "
                        + storedObjects(directory));
        var payloadsAfter = payloads(directory.resolve("library"));
        require(
                payloadsAfter.equals(payloadsBefore),
                "Creature-mode open changes no payload file: "
                        + changed(payloadsBefore, payloadsAfter));
    }

    private static void refusesMissingDefaultAndUnseededLibraries() throws Exception {
        Path own = Files.createTempDirectory("vimi-world-measurement-own-");
        Path copy = Files.createTempDirectory("vimi-world-measurement-copy-");
        Path linked = Files.createTempDirectory("vimi-world-measurement-linked-");
        try {
            Files.createDirectories(own.resolve("library"));
            require(
                    WorldMeasurementOpen.libraryRefusal(null, own) != null
                            && WorldMeasurementOpen.libraryRefusal(" ", own) != null,
                    "A missing -PdataDirectory is refused");
            Files.writeString(own.resolve(WorldMeasurementOpen.MARKER), "");
            require(
                    WorldMeasurementOpen.libraryRefusal(own.toString(), own) != null,
                    "The application's own library is refused, even with a marker");
            require(
                    WorldMeasurementOpen.libraryRefusal(own.resolve(".").toString(), own) != null,
                    "The application's own library is refused through another spelling");
            Files.createDirectories(copy.resolve("library"));
            require(
                    WorldMeasurementOpen.libraryRefusal(copy.toString(), own).contains("MEASUREMENT_COPY"),
                    "A library without the marker is refused");
            Files.writeString(copy.resolve(WorldMeasurementOpen.MARKER), "");
            require(
                    WorldMeasurementOpen.libraryRefusal(copy.toString(), own) == null,
                    "A marked copy is accepted");
            Files.writeString(linked.resolve(WorldMeasurementOpen.MARKER), "");
            require(
                    WorldMeasurementOpen.libraryRefusal(linked.toString(), own) != null,
                    "A marked folder without a library is refused");
            if (link(linked.resolve("library"), own.resolve("library"))) {
                require(
                        WorldMeasurementOpen.libraryRefusal(linked.toString(), own) != null,
                        "A marked copy whose library links to the real one is refused");
            } else {
                System.out.println("World measurement open: no link or junction support here;"
                        + " linked-library refusal not exercised");
            }
        } finally {
            TestCleanup.deleteTree(null, linked);
            TestCleanup.deleteTree(null, copy);
            TestCleanup.deleteTree(null, own);
        }
        require(WorldMeasurementProbe.exitCode(false, false, false) == 0, "complete exits 0");
        require(WorldMeasurementProbe.exitCode(false, true, false) == 3, "incomplete exits 3");
        require(WorldMeasurementProbe.exitCode(false, false, true) == 3, "overrun exits 3");
        require(WorldMeasurementProbe.exitCode(true, true, true) == 2, "failure exits 2");
        require(
                WorldMeasurementProbe.MAX_DEADLINE_SECONDS
                                + WorldMeasurementProbe.CONTROLLER_CLOSE_SECONDS
                                + WorldMeasurementProbe.HELPER_CLOSE_SECONDS
                                + WorldMeasurementOpen.CLOSE_SECONDS
                        < 600
                        && WorldMeasurementProbe.MAX_DEADLINE_SECONDS
                                        + WorldMeasurementProbe.GRACE_SECONDS
                                < 600,
                "Deadline plus every close timeout fits the ten-minute task timeout");
    }

    /** A symbolic link, or on Windows a directory junction; false when neither can be made. */
    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception unsupported) {
            // Windows without the symlink privilege: a junction needs none.
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            var process =
                    new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                            .redirectErrorStream(true)
                            .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS)
                    && process.exitValue() == 0
                    && Files.exists(link);
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Every non-SQLite file under the library, by relative path, with its SHA-256. */
    private static Map<String, String> payloads(Path library) throws Exception {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(library)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                String name = library.relativize(file).toString().replace('\\', '/');
                if (name.contains(".sqlite3") || name.endsWith(".lock")) continue;
                result.put(
                        name,
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(file))));
            }
        }
        return result;
    }

    private static long storedObjects(Path directory) throws Exception {
        try (var database =
                DerivedSqliteConnection.openReadOnly(directory.resolve("library/objects.sqlite3"))) {
            return database.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    session ->
                            ((Number) session.query("SELECT count(*) FROM storage_objects").getFirst()[0])
                                    .longValue());
        }
    }

    private static String changed(Map<String, String> before, Map<String, String> after) {
        var names = new TreeMap<String, String>();
        before.forEach((name, hash) -> { if (!hash.equals(after.get(name))) names.put(name, "changed"); });
        after.forEach((name, hash) -> { if (!before.containsKey(name)) names.put(name, "added"); });
        return names.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
