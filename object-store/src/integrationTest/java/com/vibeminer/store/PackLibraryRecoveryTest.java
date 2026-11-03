package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** FULL index/ownership commit is the publication boundary; orphan pack tails stay unpublished. */
@Timeout(30)
final class PackLibraryRecoveryTest {
    @TempDir Path folder;

    @Test
    void syncedButUncommittedTailDoesNotCreateOwnerlessObject() throws Exception {
        run(false);
    }

    @Test
    void committedPayloadAndOwnershipSurviveKilledWriter() throws Exception {
        run(true);
    }

    private void run(boolean committed) throws Exception {
        Path marker = folder.resolve("marker");
        Path log = folder.resolve("child.log");
        var arguments =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                Child.class.getName(),
                                folder.toString(),
                                marker.toString(),
                                Boolean.toString(committed)));
        String nativeLibrary = System.getProperty("vibeminer.sqlite.library");
        if (nativeLibrary != null) {
            arguments.add(1, "-Dvibeminer.sqlite.library=" + nativeLibrary);
        }
        Process child =
                new ProcessBuilder(arguments)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(
                    Files.exists(marker),
                    () -> {
                        try {
                            return Files.readString(log);
                        } catch (java.io.IOException error) {
                            return error.toString();
                        }
                    });
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
        byte[] bytes = new byte[] {1, 2, 3};
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        ObjectStore library = ObjectStore.openLibrary(folder).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            assertEquals(committed, library.has(key).join());
            assertTrue(
                    catalog.owners(null, 20, Cancellation.NONE).join().stream()
                            .anyMatch(owner -> owner.id().equals("retained-build")));
            if (committed) {
                assertArrayEquals(bytes, library.read(key).join().bytes());
                assertEquals(
                        3,
                        catalog.figures("newowner", Cancellation.NONE)
                                .join()
                                .original()
                                .storedBytes());
            } else {
                assertFalse(
                        catalog.owners(null, 20, Cancellation.NONE).join().stream()
                                .anyMatch(owner -> owner.id().equals("newowner")));
                assertEquals(64, Files.size(folder.resolve("packs/pack-000001.vmp")));
            }
        } finally {
            library.closeAsync().join();
        }
    }

    public static final class Child {
        public static void main(String[] arguments) throws Exception {
            ObjectStore library = ObjectStore.openLibrary(Path.of(arguments[0])).join();
            LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join();
            catalog.expect(
                            "retained-build",
                            LibraryCatalog.Scope.BUILD,
                            "encoding",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            Path marker = Path.of(arguments[1]);
            WritePoint point =
                    Boolean.parseBoolean(arguments[2])
                            ? WritePoint.INDEX_COMMITTED
                            : WritePoint.PACK_SYNCED;
            library.writeProbe =
                    (boundary, key) -> {
                        if (boundary == point) {
                            try {
                                Files.writeString(marker, "boundary");
                                System.in.read();
                            } catch (java.io.IOException error) {
                                throw new IllegalStateException(error);
                            }
                        }
                    };
            byte[] bytes = new byte[] {1, 2, 3};
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
            library.forOwner(
                            "newowner",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL)
                    .put(List.of(new StoreTypes.PutObject(key, bytes)))
                    .join();
        }
    }
}
