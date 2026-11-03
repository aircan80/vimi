package com.vimi.gui;

import com.vibeminer.builds.BuildTypes;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Preparation milestones are relational facts in the shared library, never payload objects. */
public final class SqlPreparationReceiptsTest {
    private static final String CONFIG = "0123456789abcdef0123456789abcdef";
    private static final String CDN = "11111111111111111111111111111111";
    private static final BuildInfo BUILD =
            new BuildInfo(
                    "wow",
                    "Retail",
                    "1.0",
                    "us",
                    CONFIG,
                    BuildInfo.BuildSource.SAVED,
                    false,
                    null,
                    0);
    private static final BuildTypes.BuildRef SOURCE =
            new BuildTypes.BuildRef("wow", CONFIG, CDN, "us");

    private SqlPreparationReceiptsTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-sql-preparation-receipts-");
        try {
            milestonesPersistWithoutPayloads(directory.resolve("milestones"));
            changedContextReplacesItsHistoricalFact(directory.resolve("context"));
            incompatibleSchemaIsReadOnly(directory.resolve("future"), true);
            incompatibleSchemaIsReadOnly(directory.resolve("unversioned"), false);
            System.out.println("SQL preparation receipts: 4 cases passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void milestonesPersistWithoutPayloads(Path directory) throws Exception {
        Path database = initialize(directory);
        long objects = number(database, "SELECT count(*) FROM storage_objects");
        var history = PreparationHistory.forLibrary(database);
        history.load();
        require(history.summary(BUILD) == null, "Fresh library has no historical readiness");
        for (var ready :
                new BuildPreparation.Ready[] {
                    BuildPreparation.Ready.METADATA_READY,
                    BuildPreparation.Ready.FILES_READY,
                    BuildPreparation.Ready.TABLES_READY
                }) {
            history.record(BUILD, SOURCE, "enUS", ready);
            require(
                    ready.name().equals(row(database)[0]),
                    "Each milestone reaches the SQL receipt");
        }
        history.record(BUILD, SOURCE, "enUS", BuildPreparation.Ready.METADATA_READY);
        require("TABLES_READY".equals(row(database)[0]), "Same-context receipt cannot regress");
        var reopened = PreparationHistory.forLibrary(database);
        reopened.load();
        require(
                reopened.summary(BUILD).contains("Tables ready"),
                "Reopen restores table readiness");
        require(reopened.summary(BUILD).contains("enUS"), "Reopen restores its locale");
        require(
                number(database, "SELECT count(*) FROM builds_preparation_receipts") == 1,
                "Milestones update one canonical build row");
        require(
                number(database, "SELECT count(*) FROM storage_objects") == objects,
                "Status writes create no payload objects");
    }

    private static void changedContextReplacesItsHistoricalFact(Path directory) throws Exception {
        Path database = initialize(directory);
        var history = PreparationHistory.forLibrary(database);
        history.load();
        history.record(BUILD, SOURCE, "enUS", BuildPreparation.Ready.TABLES_READY);
        var alternate =
                new BuildTypes.BuildRef("wow", CONFIG, "22222222222222222222222222222222", "eu");
        history.record(BUILD, alternate, "deDE", BuildPreparation.Ready.METADATA_READY);
        var reopened = PreparationHistory.forLibrary(database);
        reopened.load();
        var receipt = reopened.retainedReceipts().getFirst();
        require(
                receipt.ready().equals("METADATA_READY")
                        && receipt.locale().equals("deDE")
                        && receipt.cdnConfig().equals(alternate.cdnConfig())
                        && receipt.region().equals("eu"),
                "New source/locale retains its own fact rather than inheriting prior readiness");
        require(
                number(database, "SELECT count(*) FROM builds_preparation_receipts") == 1,
                "Context change preserves one historical row for the build");
    }

    private static void incompatibleSchemaIsReadOnly(Path directory, boolean future)
            throws Exception {
        Path database = initialize(directory);
        if (future) {
            PreparationHistory.forLibrary(database);
            execute(database, "UPDATE builds_preparation_metadata SET version=2");
        } else {
            execute(database, "CREATE TABLE builds_preparation_receipts(unrecognized TEXT)");
        }
        byte[] before = digest(database);
        boolean rejected = false;
        try {
            PreparationHistory.forLibrary(database);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "Unsupported or unversioned preparation schema must be rejected");
        require(Arrays.equals(before, digest(database)), "Rejected schema is never mutated");
        require(
                number(
                                database,
                                "SELECT count(*) FROM sqlite_master WHERE type='table'"
                                        + " AND name='builds_preparation_metadata'")
                        == (future ? 1 : 0),
                "Rejection does not create missing schema metadata");
    }

    private static Path initialize(Path directory) throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            catalog.retainOwner(BUILD.identity(), LibraryCatalog.Scope.BUILD).join();
            return library.databasePath();
        } finally {
            library.closeAsync().join();
        }
    }

    private static Object[] row(Path database) throws Exception {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    Cancellation.NONE,
                    false,
                    session ->
                            session.query(
                                            "SELECT readiness,locale,cdn_config,region FROM"
                                                + " builds_preparation_receipts WHERE identity=?",
                                            BUILD.identity())
                                    .getFirst());
        }
    }

    private static long number(Path database, String sql) throws Exception {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    Cancellation.NONE,
                    false,
                    session -> ((Number) session.query(sql).getFirst()[0]).longValue());
        }
    }

    private static void execute(Path database, String sql) throws Exception {
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute(sql);
                        return null;
                    });
        }
    }

    private static byte[] digest(Path database) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
