package com.vimi.gui;

import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.History;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Native, authored-data migration contract. No JavaFX toolkit or live downloads are needed. */
public final class PackLibraryMigrationTest {
    private static final String OWNER = "library:migration";
    private static final String SOURCE = "legacy-intent";
    private static final String VERSION = "pack-library-v1";
    private static final GeneratedKey RECEIPT_KEY =
            GeneratedKey.hash("vimi-preparation-history-v1".getBytes(StandardCharsets.UTF_8));
    private static final ObjectKey RECEIPT_OBJECT =
            new ObjectKey(ObjectKey.GENERATED, RECEIPT_KEY.bytes());

    private PackLibraryMigrationTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vimi-pack-migration-");
        try {
            preservesHistoryReceiptsOwnersAndLegacyPayloads(root.resolve("preserve"));
            completionMarkerSurvivesReopenAndExistingValuesWin(root.resolve("restart"));
            activeLegacyWriterPreventsMigrationAndReceipt(root.resolve("busy"));
            failedPreservationDoesNotMarkCompleteAndCanRetry(root.resolve("retry"));
            emptyPriorDirectoryMigratesWithoutInventingIntent(root.resolve("empty"));
            System.out.println("Passed 5 unified pack migration contracts");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void preservesHistoryReceiptsOwnersAndLegacyPayloads(Path directory)
            throws Exception {
        Legacy fixture = legacy(directory, true);
        Map<String, String> original = sourceHashes(directory);
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            try (PackLibraryMigration migration =
                    PackLibraryMigration.open(directory, library, catalog).join()) {
                require(
                        migration.legacyObjects() != null,
                        "Legacy source is retained as a read-only fallback");
                require(
                        Arrays.equals(
                                fixture.cachedBytes(),
                                migration.legacyObjects().read(fixture.cachedKey()).join().bytes()),
                        "Old cached content remains readable without being discarded");
                require(
                        !library.has(fixture.cachedKey()).join(),
                        "Migration copies required intent, not every cached payload");
                var history = library.readHistory(new History.Query(), Cancellation.NONE).join();
                require(history.size() == 1, "Observed history record is indexed in new store");
                require(
                        Arrays.equals(
                                fixture.observation().reply(),
                                library.read(history.getFirst().document()).join().bytes()),
                        "History document bytes are preserved");
                require(
                        Arrays.equals(
                                fixture.receipt(), library.read(RECEIPT_OBJECT).join().bytes()),
                        "Saved build receipt bytes are preserved");
                require(
                        catalog.owners(null, 1024, Cancellation.NONE)
                                .join()
                                .contains(
                                        new LibraryCatalog.Owner(
                                                "saved-canonical", LibraryCatalog.Scope.BUILD)),
                        "Retained canonical build intent is preserved");
                require(
                        !catalog.figures("saved-canonical", Cancellation.NONE)
                                .join()
                                .original()
                                .inventoryComplete(),
                        "Restored owner does not invent a completed inventory");
                require(
                        catalog.current(OWNER, SOURCE, VERSION).join(),
                        "Migration receipt is published after required copies");
                expectFailure(
                        () -> ObjectStore.open(directory.resolve("objects")).join(),
                        "Borrowed source lease excludes a legacy writer");
                require(
                        original.equals(sourceHashes(directory)),
                        "Original payload/index bytes must remain unchanged");
            }
            ObjectStore afterRelease = ObjectStore.open(directory.resolve("objects")).join();
            afterRelease.closeAsync().join();
        } finally {
            library.closeAsync().join();
        }
    }

    private static void completionMarkerSurvivesReopenAndExistingValuesWin(Path directory)
            throws Exception {
        legacy(directory, true);
        byte[] existing = receipt("newer-destination");
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var target =
                    library.forOwner(
                            "library:saved-builds",
                            LibraryCatalog.Scope.GLOBAL,
                            "receipts",
                            LibraryCatalog.Role.DERIVED);
            target.put(List.of(new StoreTypes.PutObject(RECEIPT_OBJECT, existing))).join();
            try (PackLibraryMigration migration =
                    PackLibraryMigration.open(directory, library, catalog).join()) {
                require(migration.legacyObjects() != null, "First migration opens legacy reader");
                require(
                        Arrays.equals(existing, library.read(RECEIPT_OBJECT).join().bytes()),
                        "Existing destination receipt must win");
            }
        } finally {
            library.closeAsync().join();
        }
        library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            // A repeated migration must not reopen the old generated writer just to recopy
            // receipts.
            ObjectStore generatedWriter =
                    ObjectStore.open(directory.resolve("generated"), generatedOptions()).join();
            try {
                try (PackLibraryMigration migration =
                        PackLibraryMigration.open(directory, library, catalog).join()) {
                    require(
                            migration.legacyObjects() != null,
                            "Legacy CDN fallback remains available after restart");
                    require(
                            catalog.current(OWNER, SOURCE, VERSION).join(),
                            "Completion marker survives restart");
                    require(
                            Arrays.equals(existing, library.read(RECEIPT_OBJECT).join().bytes()),
                            "Restart must preserve the destination's existing receipt");
                }
            } finally {
                generatedWriter.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    private static void activeLegacyWriterPreventsMigrationAndReceipt(Path directory)
            throws Exception {
        legacy(directory, false);
        ObjectStore oldWriter = ObjectStore.open(directory.resolve("objects")).join();
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            expectFailure(
                    () -> PackLibraryMigration.open(directory, library, catalog).join(),
                    "Cannot preserve a live legacy writer");
            require(
                    !catalog.current(OWNER, SOURCE, VERSION).join(),
                    "Failed source lease cannot mark migration complete");
        } finally {
            library.closeAsync().join();
            oldWriter.closeAsync().join();
        }
    }

    private static void failedPreservationDoesNotMarkCompleteAndCanRetry(Path directory)
            throws Exception {
        Legacy fixture = legacy(directory, false);
        try (var wrong = new DerivedSqliteConnection(directory.resolve("accounting.sqlite"))) {
            wrong.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("CREATE TABLE unrelated(value TEXT)");
                        return null;
                    });
        }
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            expectFailure(
                    () -> PackLibraryMigration.open(directory, library, catalog).join(),
                    "Unreadable prior owner intent must fail preservation");
            require(
                    !catalog.current(OWNER, SOURCE, VERSION).join(),
                    "Partial preservation cannot publish completion");
            ObjectStore sourceAfterFailure = ObjectStore.open(directory.resolve("objects")).join();
            sourceAfterFailure.closeAsync().join();
            UsageIndex repaired = UsageIndex.open(directory.resolve("accounting.sqlite")).join();
            try {
                repaired.retain(
                                "saved-canonical",
                                false,
                                "encoding",
                                UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
            } finally {
                repaired.closeAsync().join();
            }
            try (PackLibraryMigration migration =
                    PackLibraryMigration.open(directory, library, catalog).join()) {
                require(
                        migration.legacyObjects() != null,
                        "Failed migration released its legacy source lease");
                require(
                        catalog.current(OWNER, SOURCE, VERSION).join(),
                        "Retry completes after source intent becomes readable");
                require(
                        library.readHistory(new History.Query(), Cancellation.NONE).join().size()
                                == 1,
                        "Retry does not duplicate history");
                require(
                        Arrays.equals(
                                fixture.receipt(), library.read(RECEIPT_OBJECT).join().bytes()),
                        "Required receipt remains preserved after retry");
            }
        } finally {
            library.closeAsync().join();
        }
    }

    private static void emptyPriorDirectoryMigratesWithoutInventingIntent(Path directory) {
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join();
                PackLibraryMigration migration =
                        PackLibraryMigration.open(directory, library, catalog).join()) {
            require(
                    migration.legacyObjects() == null,
                    "Empty library has no fabricated prior source");
            require(
                    catalog.current(OWNER, SOURCE, VERSION).join(),
                    "Empty prior source can be acknowledged explicitly");
            require(
                    library.readHistory(new History.Query(), Cancellation.NONE).join().isEmpty(),
                    "No prior observation is invented");
            require(!library.has(RECEIPT_OBJECT).join(), "No saved receipt is invented");
        } finally {
            library.closeAsync().join();
        }
    }

    private static Legacy legacy(Path directory, boolean owners) throws Exception {
        ObjectStore source = ObjectStore.open(directory.resolve("objects")).join();
        byte[] cached = new byte[] {7, 8, 9};
        ObjectKey key =
                new ObjectKey(ObjectKey.CONFIG, MessageDigest.getInstance("MD5").digest(cached));
        var observation =
                new History.Observation(
                        1,
                        "wow",
                        "us",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        "https://example.invalid/versions",
                        "authored prior versions".getBytes(StandardCharsets.UTF_8));
        try {
            source.put(List.of(new StoreTypes.PutObject(key, cached))).join();
            source.appendHistory(observation, Cancellation.NONE).join();
        } finally {
            source.closeAsync().join();
        }
        byte[] receipt = receipt("saved-canonical");
        ObjectStore generated =
                ObjectStore.open(directory.resolve("generated"), generatedOptions()).join();
        try {
            generated.put(List.of(new StoreTypes.PutObject(RECEIPT_OBJECT, receipt))).join();
        } finally {
            generated.closeAsync().join();
        }
        if (owners) {
            UsageIndex old = UsageIndex.open(directory.resolve("accounting.sqlite")).join();
            try {
                old.retain("saved-canonical", false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
            } finally {
                old.closeAsync().join();
            }
        }
        return new Legacy(observation, key, cached, receipt);
    }

    private static byte[] receipt(String identity) {
        return ("{\"Format\":\"vimi-preparation-history-v1\",\"Builds\":[{\"identity\":\""
                        + identity
                        + "\",\"product\":\"wow\",\"config\":\""
                        + "1".repeat(32)
                        + "\",\"cdnConfig\":\""
                        + "2".repeat(32)
                        + "\",\"region\":\"us\",\"locale\":\"enUS\",\"ready\":\"METADATA_READY\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static StoreTypes.Options generatedOptions() {
        return new StoreTypes.Options(StoreTypes.Kind.GENERATED, 256L << 20, Clock.systemUTC());
    }

    private static Map<String, String> sourceHashes(Path directory) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if ((relative.startsWith("objects/") || relative.startsWith("generated/"))
                        && (relative.endsWith("objects.sqlite3") || relative.endsWith(".vmp"))) {
                    hashes.put(
                            relative,
                            java.util.HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(Files.readAllBytes(path))));
                }
            }
        }
        return Map.copyOf(hashes);
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (CompletionException expected) {
            // Both native busy leases and unreadable preservation steps are asynchronous failures.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Legacy(
            History.Observation observation,
            ObjectKey cachedKey,
            byte[] cachedBytes,
            byte[] receipt) {}
}
