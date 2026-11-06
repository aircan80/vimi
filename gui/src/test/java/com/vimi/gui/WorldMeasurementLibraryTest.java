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
                                directory, version, OfflineSourceFixtures::publicKeys, ignored -> {})
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
                            directory, version, OfflineSourceFixtures::publicKeys, ignored -> {})) {
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
            System.out.println(
                    "World measurement open: refuses unprepared builds and the application's"
                            + " library; no stored object or payload write passed");
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            TestCleanup.deleteTree(primary, directory);
        }
    }

    private static void refusesMissingDefaultAndUnseededLibraries() throws Exception {
        Path own = Files.createTempDirectory("vimi-world-measurement-own-");
        try {
            Files.createDirectories(own.resolve("library"));
            require(
                    WorldMeasurementOpen.libraryRefusal(null, own) != null
                            && WorldMeasurementOpen.libraryRefusal(" ", own) != null,
                    "A missing -PdataDirectory is refused");
            require(
                    WorldMeasurementOpen.libraryRefusal(own.toString(), own) != null,
                    "The application's own library is refused");
            require(
                    WorldMeasurementOpen.libraryRefusal(own.resolve(".").toString(), own) != null,
                    "The application's own library is refused through another spelling");
            Path copy = Files.createTempDirectory("vimi-world-measurement-copy-");
            try {
                require(
                        WorldMeasurementOpen.libraryRefusal(copy.toString(), own) != null,
                        "A directory without a library folder is refused");
                Files.createDirectories(copy.resolve("library"));
                require(
                        WorldMeasurementOpen.libraryRefusal(copy.toString(), own) == null,
                        "A copy is accepted");
            } finally {
                TestCleanup.deleteTree(null, copy);
            }
        } finally {
            TestCleanup.deleteTree(null, own);
        }
        require(WorldMeasurementProbe.exitCode(false, false, false) == 0, "complete exits 0");
        require(WorldMeasurementProbe.exitCode(false, true, false) == 3, "incomplete exits 3");
        require(WorldMeasurementProbe.exitCode(false, false, true) == 3, "overrun exits 3");
        require(WorldMeasurementProbe.exitCode(true, true, true) == 2, "failure exits 2");
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
