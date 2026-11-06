package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * {@link PreparedBuildReader} over an authored offline library (#346): it refuses an unprepared
 * build before reading anything, reads a prepared build's World alongside a writer that holds the
 * library, and never changes a library file. Only SQLite's WAL sidecars may change: every WAL
 * reader records read marks in {@code -shm}, and with no writer running it creates the sidecars,
 * the {@code -wal} empty. Refusals carry honest codes and release everything they opened.
 */
public final class PreparedBuildReaderTest {
    private PreparedBuildReaderTest() {}

    public static void main(String[] args) throws Exception {
        refusalCodes();
        missingLibrary();
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
            Path library = directory.resolve("library");

            // 1. Unprepared: not listed, refused before any read, nothing written or held open.
            require(PreparedBuildReader.list(directory).isEmpty(), "Nothing is listed unprepared");
            var before = files(library);
            refused(directory, unprepared, "BUILD_NEEDS_PREPARATION");
            requireUnchanged(before, files(library), "unprepared refusal");
            requireReleased(library, "unprepared refusal");

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
            before = files(library);
            var reader = PreparedBuildReader.open(directory, prepared, Cancellation.NONE);
            try {
                reader.world().catalog(Cancellation.NONE).get(20, TimeUnit.SECONDS);
                require(reader.files() != null, "Files are available");
            } finally {
                reader.close();
                reader.close(); // Idempotent.
            }
            requireUnchanged(before, files(library), "read-only open");
            requireReleased(library, "close");

            // Refusals that preparing again would not fix.
            refused(directory, withLocale(prepared, "xxXX"), "LOCALE_NOT_IN_BUILD");
            refused(directory, withVersion(prepared, null), "BUILD_NOT_REGISTERED");
            requireReleased(library, "locale refusal");

            // 4. Alongside a writer holding the library: opens and reads, no file changes.
            service =
                    BuildApplication.openWithoutBackgroundWork(
                                    directory, OfflineSourceFixtures::publicKeys)
                            .get(20, TimeUnit.SECONDS);
            try {
                before = files(library);
                try (var beside = PreparedBuildReader.open(directory, prepared, Cancellation.NONE)) {
                    beside.world().catalog(Cancellation.NONE).get(20, TimeUnit.SECONDS);
                }
                requireUnchanged(before, files(library), "read beside a writer");
            } finally {
                service.closeAsync().get(20, TimeUnit.SECONDS);
            }

            // 5. Stored but unreadable data: refused as unreadable, not as needing preparation.
            corruptPacks(library);
            refused(directory, prepared, "PREPARED_DATA_UNREADABLE");
            requireReleased(library, "unreadable refusal");
            System.out.println(
                    "Prepared build reader: unprepared refused before reading; prepared World read"
                            + " with and without a writer; honest refusal codes; no library file"
                            + " changed; nothing held open after a refusal or close");
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            TestCleanup.deleteTree(primary, directory);
        }
    }

    private static PreparedBuildReader.Prepared withLocale(
            PreparedBuildReader.Prepared build, String locale) {
        return new PreparedBuildReader.Prepared(
                build.identity(),
                build.product(),
                build.version(),
                build.config(),
                build.cdnConfig(),
                build.region(),
                locale);
    }

    private static PreparedBuildReader.Prepared withVersion(
            PreparedBuildReader.Prepared build, String version) {
        return new PreparedBuildReader.Prepared(
                build.identity(),
                build.product(),
                version,
                build.config(),
                build.cdnConfig(),
                build.region(),
                build.locale());
    }

    private static void refused(Path directory, PreparedBuildReader.Prepared build, String code) {
        try {
            PreparedBuildReader.open(directory, build, Cancellation.NONE).close();
            throw new AssertionError("Opened read-only; expected " + code);
        } catch (PreparedBuildReader.Refused refused) {
            require(
                    refused.code().equals(code),
                    "Expected " + code + ", got " + refused.code() + ": " + refused.getMessage());
        }
    }

    /** The mapping from step failures to refusal codes; cancellation is never a refusal. */
    private static void refusalCodes() {
        var canceled = new CancellationException("stop");
        require(
                PreparedBuildReader.refusal("x", new java.util.concurrent.CompletionException(canceled))
                        == canceled,
                "Cancellation propagates");
        require(
                code(new com.vibeminer.store.StoreException("OBJECT_STORE_CLOSED", "closed"))
                        .equals("PREPARED_DATA_UNREADABLE"),
                "A closed store is unreadable data");
        require(
                code(
                                new com.vibeminer.generated.GeneratedStoreException(
                                        com.vibeminer.generated.GeneratedTypes.READ_ONLY, "write"))
                        .equals("BUILD_NEEDS_PREPARATION"),
                "A refused write means preparation is missing");
        require(
                code(
                                new com.vibeminer.generated.GeneratedStoreException(
                                        com.vibeminer.generated.GeneratedTypes.BUSY, "busy"))
                        .equals("PREPARED_DATA_UNREADABLE"),
                "A busy generated store is unreadable data");
        require(
                code(new IllegalStateException("other")).equals("READ_FAILED"),
                "Anything else does not suggest preparing");
    }

    private static String code(RuntimeException failure) {
        return ((PreparedBuildReader.Refused)
                        PreparedBuildReader.refusal(
                                "x", new java.util.concurrent.CompletionException(failure)))
                .code();
    }

    private static void missingLibrary() throws Exception {
        Path empty = Files.createTempDirectory("vimi-prepared-build-reader-empty-");
        try {
            for (var attempt : java.util.List.<Runnable>of(
                    () -> PreparedBuildReader.list(empty),
                    () ->
                            PreparedBuildReader.open(
                                            empty,
                                            new PreparedBuildReader.Prepared(
                                                    "i", "wow", "1", "c", "d", "us", "enUS"),
                                            Cancellation.NONE)
                                    .close())) {
                try {
                    attempt.run();
                    throw new AssertionError("A missing library was read");
                } catch (PreparedBuildReader.Refused refused) {
                    require(refused.code().equals("LIBRARY_MISSING"), "Missing: " + refused.code());
                }
            }
        } finally {
            TestCleanup.deleteTree(null, empty);
        }
    }

    /** Flips every payload byte of every pack, so stored objects read as corrupt. */
    private static void corruptPacks(Path library) throws Exception {
        try (var packs = Files.list(library.resolve("packs"))) {
            for (Path pack : packs.toList()) {
                byte[] bytes = Files.readAllBytes(pack);
                for (int i = 64; i < bytes.length; i++) {
                    bytes[i] ^= (byte) 0x5a;
                }
                try (var channel = FileChannel.open(pack, StandardOpenOption.WRITE)) {
                    channel.write(java.nio.ByteBuffer.wrap(bytes));
                }
            }
        }
    }

    /**
     * Nothing under {@code library} is held open: on Windows an open handle makes the rename fail.
     * (Elsewhere the rename always succeeds and this checks nothing.)
     */
    private static void requireReleased(Path library, String after) throws Exception {
        Path moved = library.resolveSibling("library-released-check");
        try {
            Files.move(library, moved);
        } catch (java.io.IOException held) {
            throw new AssertionError("Library files still open after " + after, held);
        }
        // Moving back may briefly race a file indexer or scanner on the renamed folder.
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(moved, library);
                return;
            } catch (java.nio.file.AccessDeniedException busy) {
                if (attempt == 100) throw busy;
                Thread.sleep(20);
            }
        }
    }

    /** Every file under {@code root} with its size, modification time and SHA-256. */
    private static Map<String, String> files(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                String name = root.relativize(file).toString().replace('\\', '/');
                // Lock files are byte-range locked by a running writer, and the WAL sidecars are
                // exempt below: size and time only for those.
                boolean locked =
                        name.endsWith(".lock")
                                || name.endsWith(".sqlite3-shm")
                                || name.endsWith(".sqlite3-wal");
                String content =
                        locked
                                ? ""
                                : "#"
                                        + HexFormat.of()
                                                .formatHex(
                                                        MessageDigest.getInstance("SHA-256")
                                                                .digest(Files.readAllBytes(file)));
                result.put(
                        name,
                        attributes.size()
                                + "@"
                                + attributes.lastModifiedTime().toMillis()
                                + content);
            }
        }
        return result;
    }

    /**
     * Every file keeps its size, modification time and content, except SQLite's WAL sidecars: the
     * {@code -shm} shared-memory index is where every WAL reader records its read marks, so it may
     * appear or change; a {@code -wal} may appear only empty, and an existing one keeps its size.
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
        require(now.equals(was), what + " changed library files: " + was + " -> " + now);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
