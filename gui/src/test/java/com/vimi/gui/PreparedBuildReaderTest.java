package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link PreparedBuildReader} over an authored offline library (#346): it refuses an unprepared
 * build before reading anything, reads a prepared build's World alongside a writer that holds the
 * library, and never changes a library file. With no writer, only SQLite's WAL index sidecars
 * ({@code -shm} and an empty {@code -wal}) may appear.
 */
public final class PreparedBuildReaderTest {
    private PreparedBuildReaderTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-prepared-build-reader-");
        Throwable primary = null;
        try {
            var fixture = FullPreparationLibraryIntegrationTest.seed(directory);
            BuildInfo build;
            var service =
                    BuildApplication.open(directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                build =
                        service.listBuilds().get(15, TimeUnit.SECONDS).stream()
                                .filter(value -> value.config().equals(fixture.buildConfig().hex()))
                                .findFirst()
                                .orElseThrow();
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }
            var evidence = build.observations().getFirst();
            var unprepared =
                    new PreparedBuildReader.Prepared(
                            build.identity(),
                            build.product(),
                            build.version(),
                            build.config(),
                            evidence.cdnConfig(),
                            evidence.region(),
                            "enUS");

            // 1. Unprepared: not listed, and refused before any read, writing nothing.
            require(PreparedBuildReader.list(directory).isEmpty(), "Nothing is listed unprepared");
            var before = files(directory.resolve("library"));
            try {
                PreparedBuildReader.open(directory, unprepared, Cancellation.NONE).close();
                throw new AssertionError("Opened an unprepared build read-only");
            } catch (PreparedBuildReader.Refused refused) {
                require(
                        refused.code().equals("BUILD_NEEDS_PREPARATION"),
                        "Unprepared refusal: " + refused.code() + " " + refused.getMessage());
            }
            requireUnchanged(before, files(directory.resolve("library")), "unprepared refusal");

            // 2. Prepare it in the application, then close the application.
            service =
                    BuildApplication.open(directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                var job = service.preparation().start(build, evidence, "enUS");
                var snapshot = job.completion().get(40, TimeUnit.SECONDS);
                require(
                        snapshot.ready() == BuildPreparation.Ready.TABLES_READY,
                        "Fixture prepares: " + snapshot.problem());
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }

            // 3. Prepared, no writer: listed, opens read-only, reads World, changes no file.
            var listed = PreparedBuildReader.list(directory);
            require(listed.size() == 1, "One prepared build is listed: " + listed);
            var prepared = listed.getFirst();
            require(prepared.locale().equals("enUS"), "The receipt supplies the locale");
            require(
                    build.version().equals(prepared.version()),
                    "The registry supplies the version: " + prepared.version());
            before = files(directory.resolve("library"));
            try (var reader = PreparedBuildReader.open(directory, prepared, Cancellation.NONE)) {
                reader.world().catalog(Cancellation.NONE).get(20, TimeUnit.SECONDS);
                require(reader.files() != null, "Files are available");
            }
            requireUnchanged(before, files(directory.resolve("library")), "read-only open");

            // 4. Alongside a writer holding the library: opens and reads, no new or changed file.
            service =
                    BuildApplication.open(directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                before = files(directory.resolve("library"));
                try (var reader =
                        PreparedBuildReader.open(directory, prepared, Cancellation.NONE)) {
                    reader.world().catalog(Cancellation.NONE).get(20, TimeUnit.SECONDS);
                }
                var after = files(directory.resolve("library"));
                require(
                        after.keySet().equals(before.keySet()),
                        "No file appears beside a writer: " + before.keySet() + " -> " + after.keySet());
                for (var entry : before.entrySet()) {
                    if (entry.getKey().contains(".sqlite3")) continue; // The writer's own files.
                    require(
                            entry.getValue().equals(after.get(entry.getKey())),
                            "Unchanged beside a writer: " + entry.getKey());
                }
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }
            System.out.println(
                    "Prepared build reader: unprepared refused before reading; prepared World read"
                            + " with and without a writer; no library file changed");
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            TestCleanup.deleteTree(primary, directory);
        }
    }

    /** Every file under {@code root} with its size and modification time. */
    private static Map<String, String> files(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                result.put(
                        root.relativize(file).toString().replace('\\', '/'),
                        attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
            }
        }
        return result;
    }

    /**
     * Every file keeps its size and modification time, except SQLite's WAL sidecars: the {@code
     * -shm} shared-memory index is where every WAL reader records its read marks, so it may appear
     * or change; a {@code -wal} may appear only empty and an existing one keeps its size. See
     * {@link PreparedBuildReader}.
     */
    private static void requireUnchanged(
            Map<String, String> before, Map<String, String> after, String what) {
        var was = new TreeMap<>(before);
        var now = new TreeMap<>(after);
        for (var files : java.util.List.of(was, now)) {
            files.keySet().removeIf(name -> name.endsWith(".sqlite3-shm"));
        }
        for (String name : java.util.List.copyOf(now.keySet())) {
            if (!name.endsWith(".sqlite3-wal")) continue;
            String size = now.remove(name).split("@")[0];
            String previous = was.remove(name);
            require(
                    size.equals(previous == null ? "0" : previous.split("@")[0]),
                    what + ": the WAL " + name + " grew to " + size);
        }
        require(now.equals(was), what + " changed library files: " + before + " -> " + after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
