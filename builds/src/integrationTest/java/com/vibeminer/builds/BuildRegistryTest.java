package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class BuildRegistryTest {
    @TempDir Path directory;

    @Test
    void loadedStatusAndSelectionFactsSurviveReopenWithoutPromotingPreparation() throws Exception {
        Path path = database();
        try (var registry = BuildRegistry.open(path).join()) {
            var discovered =
                    registry.observe("canonical", ref("us"), "11.2.0", "enUS", Cancellation.NONE)
                            .join();
            assertEquals(BuildRegistry.Status.DISCOVERED, discovered.status());
            assertEquals(0, discovered.attempt());
            var attempt =
                    registry.beginLoad("canonical", ref("us"), "11.2.0", "enUS", Cancellation.NONE)
                            .join();
            assertEquals(
                    BuildRegistry.Status.LOADING,
                    registry.get("canonical", Cancellation.NONE).join().status());
            assertTrue(registry.loaded(attempt, Cancellation.NONE).join());
        }
        try (var registry = BuildRegistry.open(path).join()) {
            var loaded = registry.get("canonical", Cancellation.NONE).join();
            assertEquals(BuildRegistry.Status.LOADED, loaded.status());
            assertEquals("enUS", loaded.locale());
            assertEquals("11.2.0", loaded.version());
            assertEquals(ref("us"), loaded.reference());
            assertNull(loaded.errorCode());
            assertNotNull(loaded.updatedAt());
            assertEquals(1, registry.list(null, 10, Cancellation.NONE).join().size());
        }
    }

    @Test
    void observationRouteChangesDoNotResetLoadedStatusOrSplitCanonicalOwner() throws Exception {
        try (var registry = BuildRegistry.open(database()).join()) {
            var attempt =
                    registry.beginLoad("canonical", ref("us"), "v1", "enUS", Cancellation.NONE)
                            .join();
            registry.loaded(attempt, Cancellation.NONE).join();
            var updated =
                    registry.observe("canonical", ref("eu"), null, null, Cancellation.NONE).join();
            assertEquals(BuildRegistry.Status.LOADED, updated.status());
            assertEquals("eu", updated.reference().region());
            assertEquals("v1", updated.version());
            assertEquals("enUS", updated.locale());
            assertEquals(1, registry.list(null, 10, Cancellation.NONE).join().size());
            BuildTypes.BuildRef other =
                    new BuildTypes.BuildRef("wow", "3".repeat(32), "2".repeat(32), "us");
            assertThrows(
                    CompletionException.class,
                    () ->
                            registry.observe("canonical", other, null, null, Cancellation.NONE)
                                    .join());
            assertEquals(
                    ref("eu"), registry.get("canonical", Cancellation.NONE).join().reference());
        }
    }

    @Test
    void supersededAndDuplicateCompletionsDoNotOverwriteNewAttempt() throws Exception {
        try (var registry = BuildRegistry.open(database()).join()) {
            var first =
                    registry.beginLoad("canonical", ref("us"), null, null, Cancellation.NONE)
                            .join();
            var second =
                    registry.beginLoad("canonical", ref("us"), null, null, Cancellation.NONE)
                            .join();
            assertFalse(registry.loaded(first, Cancellation.NONE).join());
            assertTrue(
                    registry.failed(second, "BUILD_CORRUPT", "Invalid metadata", Cancellation.NONE)
                            .join());
            assertFalse(registry.loaded(second, Cancellation.NONE).join());
            var status = registry.get("canonical", Cancellation.NONE).join();
            assertEquals(BuildRegistry.Status.FAILED, status.status());
            assertEquals("BUILD_CORRUPT", status.errorCode());
            assertEquals("Invalid metadata", status.errorMessage());
            assertEquals(2, status.attempt());
        }
    }

    @Test
    void abandonedLoadingBecomesInterruptedOnRestartAndCanRetry() throws Exception {
        Path path = database();
        try (var registry = BuildRegistry.open(path).join()) {
            registry.beginLoad("canonical", ref("us"), "v1", null, Cancellation.NONE).join();
        }
        try (var registry = BuildRegistry.open(path).join()) {
            var interrupted = registry.get("canonical", Cancellation.NONE).join();
            assertEquals(BuildRegistry.Status.INTERRUPTED, interrupted.status());
            assertEquals("BUILD_LOAD_INTERRUPTED", interrupted.errorCode());
            var retry =
                    registry.beginLoad("canonical", ref("us"), "v1", null, Cancellation.NONE)
                            .join();
            assertEquals(2, retry.attempt());
            assertTrue(registry.loaded(retry, Cancellation.NONE).join());
        }
    }

    @Test
    void cancellationIsPersistedAsInterruptedAndCanceledMutationsDoNothing() throws Exception {
        try (var registry = BuildRegistry.open(database()).join()) {
            var attempt =
                    registry.beginLoad("canonical", ref("us"), null, null, Cancellation.NONE)
                            .join();
            assertTrue(registry.interrupted(attempt, Cancellation.NONE).join());
            Cancellation canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    CancellationException.class,
                    () -> registry.observe("missing", ref("us"), null, null, canceled).join());
            assertNull(registry.get("missing", Cancellation.NONE).join());
            assertEquals(
                    BuildRegistry.Status.INTERRUPTED,
                    registry.get("canonical", Cancellation.NONE).join().status());
        }
    }

    @Test
    void pagingIsBoundedAndSecondRegistryCannotResetActiveLoad() throws Exception {
        Path path = database();
        try (var registry = BuildRegistry.open(path).join()) {
            registry.beginLoad("b", ref("us"), null, null, Cancellation.NONE).join();
            registry.observe("a", ref("us"), null, null, Cancellation.NONE).join();
            registry.observe("c", ref("us"), null, null, Cancellation.NONE).join();
            assertEquals(
                    List.of("a", "b"),
                    registry.list(null, 2, Cancellation.NONE).join().stream()
                            .map(BuildRegistry.Entry::identity)
                            .toList());
            assertEquals(
                    List.of("c"),
                    registry.list("b", 2, Cancellation.NONE).join().stream()
                            .map(BuildRegistry.Entry::identity)
                            .toList());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.list(null, 1025, Cancellation.NONE));
            assertThrows(CompletionException.class, () -> BuildRegistry.open(path).join());
            assertEquals(
                    BuildRegistry.Status.LOADING,
                    registry.get("b", Cancellation.NONE).join().status());
        }
    }

    @Test
    void foreignOrFutureFormatIsRejectedBeforeDatabaseMutation() throws Exception {
        Path path = database();
        try (var connection = new DerivedSqliteConnection(path)) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("PRAGMA application_id=99");
                        return null;
                    });
        }
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        assertThrows(CompletionException.class, () -> BuildRegistry.open(path).join());
        assertEquals(
                java.util.HexFormat.of().formatHex(before),
                java.util.HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(path))));
        try (var connection = new DerivedSqliteConnection(path)) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("PRAGMA application_id=2");
                        session.execute(
                                "CREATE TABLE builds_registry_metadata(version INTEGER NOT NULL)");
                        session.execute("INSERT INTO builds_registry_metadata VALUES(99)");
                        return null;
                    });
        }
        assertThrows(CompletionException.class, () -> BuildRegistry.open(path).join());
        try (var connection = DerivedSqliteConnection.openReadOnly(path)) {
            connection.run(
                    Cancellation.NONE,
                    false,
                    session -> {
                        assertEquals(
                                0L,
                                session.query(
                                                "SELECT count(*) FROM sqlite_master WHERE name='builds_registry'")
                                        .getFirst()[0]);
                        assertEquals(
                                99L,
                                session.query("SELECT version FROM builds_registry_metadata")
                                        .getFirst()[0]);
                        return null;
                    });
        }
    }

    @Test
    void callbacksCanCloseAndClosedRegistryRejectsWork() throws Exception {
        Path path = database();
        BuildRegistry registry = BuildRegistry.open(path).join();
        registry.observe("a", ref("us"), null, null, Cancellation.NONE)
                .thenRun(() -> registry.closeAsync().join())
                .get(5, TimeUnit.SECONDS);
        assertThrows(CompletionException.class, () -> registry.get("a", Cancellation.NONE).join());
        BuildRegistry.open(path)
                .thenAccept(opened -> opened.closeAsync().join())
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void actualSharedPackLibraryReopensWithoutDiscardingBuildStatusTables() {
        Path folder = directory.resolve("actual-library");
        ObjectStore library = ObjectStore.openLibrary(folder).join();
        try (var registry = BuildRegistry.open(library.databasePath()).join()) {
            var attempt =
                    registry.beginLoad("canonical", ref("us"), "v1", "enUS", Cancellation.NONE)
                            .join();
            registry.loaded(attempt, Cancellation.NONE).join();
        } finally {
            library.closeAsync().join();
        }
        library = ObjectStore.openLibrary(folder).join();
        try (var registry = BuildRegistry.open(library.databasePath()).join()) {
            assertEquals(
                    BuildRegistry.Status.LOADED,
                    registry.get("canonical", Cancellation.NONE).join().status());
        } finally {
            library.closeAsync().join();
        }
    }

    private Path database() throws Exception {
        Path path = directory.resolve("library.sqlite");
        try (var connection = new DerivedSqliteConnection(path)) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("PRAGMA application_id=2");
                        session.execute("PRAGMA user_version=1");
                        session.execute("CREATE TABLE storage_metadata(version INTEGER NOT NULL)");
                        session.execute("INSERT INTO storage_metadata VALUES(1)");
                        session.execute(
                                "CREATE TABLE storage_objects(k BLOB PRIMARY KEY,length INTEGER NOT NULL,role TEXT NOT NULL)");
                        return null;
                    });
        }
        return path;
    }

    private static BuildTypes.BuildRef ref(String region) {
        return new BuildTypes.BuildRef("wow", "1".repeat(32), "2".repeat(32), region);
    }
}
