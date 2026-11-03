package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.generated.PackGeneratedStore;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletionException;

/**
 * Offline source policy, bounded lifecycle, clean empty inventories and conservative flavor rules.
 */
public final class DataSourcesServiceTest {
    private DataSourcesServiceTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-source-settings-");
        try {
            persistenceAndLocalInventory(directory.resolve("local"));
            futureSchemaIsReadOnly(directory.resolve("future"));
            mappingRules();
            communityKeys(directory.resolve("keys"));
            System.out.println(
                    "Data sources: persisted policy, mapping, empty/invalid inventories and future"
                            + " schema passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(file);
            }
        }
    }

    private static void communityKeys(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var owner = library.forOwner("provider:tact-keys", LibraryCatalog.Scope.GLOBAL, "source", LibraryCatalog.Role.ORIGINAL);
            var access = CachingObjectSource.localOnly(owner, owner);
            var generated = new PackGeneratedStore(library,
                    library.forOwner("provider:test-names", LibraryCatalog.Scope.GLOBAL, "index", LibraryCatalog.Role.DERIVED));
            var names = new CommunityFileNames(access, generated, token -> java.util.concurrent.CompletableFuture.completedFuture(true));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var body = new java.util.concurrent.atomic.AtomicReference<>("FFFFFFFFFFFFFFFF 00112233445566778899AABBCCDDEEFF\n");
            com.vibeminer.network.TactKeyClient client = token -> {
                calls.incrementAndGet(); token.check();
                return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.TactKeyReply(
                        com.vibeminer.network.SourceUrls.TACT_KEYS, java.time.Instant.now(), body.get().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            };
            DataSourcesService service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner);
            try {
                require(service.policy(DataSourcesService.Id.TACT_KEYS).enabled(), "Public keys default ON");
                require(service.policy(DataSourcesService.Id.TACT_KEYS).checks() == DataSourcesService.Checks.DAILY, "Public keys default daily");
                require(service.tactKeysForBuild(com.vibeminer.access.Cancellation.NONE).count() == 1, "First build waits for due public keys");
                String fingerprint = service.tactState().fingerprint();
                service.tactKeysForBuild(com.vibeminer.access.Cancellation.NONE);
                require(calls.get() == 1, "Daily update reused on another build open");
                body.set("invalid response");
                var current = service;
                expectFailure(() -> current.updateTactKeys().join());
                require(service.tactKeys().count() == 1 && fingerprint.equals(service.tactState().fingerprint()), "Malformed update preserves last valid keys");
                body.set("FFFFFFFFFFFFFFFF 00112233445566778899AABBCCDDEEFF\n0000000000000001 11112222333344445555666677778888\n");
                service.updateTactKeys().join();
                require(service.tactKeys().count() == 2 && !fingerprint.equals(service.tactState().fingerprint()), "New keys change effective fingerprint");
                service.setEnabled(DataSourcesService.Id.TACT_KEYS, false).join();
                require(service.tactKeys().count() == 0 && service.tactState().keys() == 2, "Disabled source retains cached inventory without supplying keys");
                service.setChecks(DataSourcesService.Id.TACT_KEYS, DataSourcesService.Checks.MANUAL).join();
            } finally { service.closeAsync().join(); }
            service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner);
            try {
                require(!service.policy(DataSourcesService.Id.TACT_KEYS).enabled(), "Disabled policy persisted");
                require(service.tactState().keys() == 2, "Validated key list restored offline");
                service.setEnabled(DataSourcesService.Id.TACT_KEYS, true).join();
                int previous = calls.get();
                require(service.tactKeysForBuild(com.vibeminer.access.Cancellation.NONE).count() == 2 && previous == calls.get(), "Manual policy does not fetch when opening a build");
                service.setChecks(DataSourcesService.Id.TACT_KEYS, DataSourcesService.Checks.DAILY).join();
            } finally { service.closeAsync().join(); }
            new SourcePreferences(library.databasePath()).put("attempt.TACT_KEYS", java.time.Instant.EPOCH.toString());
            service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner);
            try {
                int before = calls.get();
                require(service.tactKeysForBuild(com.vibeminer.access.Cancellation.NONE).count() == 2 && calls.get() == before + 1, "Overdue daily update runs after restart");
            } finally { service.closeAsync().join(); access.closeAsync().join(); }
        } finally { library.closeAsync().join(); }
    }

    private static void persistenceAndLocalInventory(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var original =
                    library.forOwner(
                            "provider:community-listfile",
                            LibraryCatalog.Scope.GLOBAL,
                            "source",
                            LibraryCatalog.Role.ORIGINAL);
            var access = CachingObjectSource.localOnly(original, original);
            var generated =
                    new PackGeneratedStore(
                            library,
                            library.forOwner(
                                    "provider:community-listfile",
                                    LibraryCatalog.Scope.GLOBAL,
                                    "index",
                                    LibraryCatalog.Role.DERIVED));
            var names =
                    new CommunityFileNames(
                            access,
                            generated,
                            token -> java.util.concurrent.CompletableFuture.completedFuture(true));
            try {
                var sources =
                        DataSourcesService.open(
                                library.databasePath(),
                                directory.resolve("questie"),
                                names,
                                catalog);
                var build =
                        new BuildInfo(
                                "wowf",
                                "Forever",
                                "1.60.1.70000",
                                "us",
                                "a".repeat(32),
                                BuildInfo.BuildSource.SAVED,
                                false,
                                null,
                                0);
                try {
                    require(
                            sources.questie().databases().isEmpty()
                                    && sources.questie().issue() == null,
                            "Fresh local inventory is empty, no network needed");
                    require(
                            sources.definitions().tables() > 1000,
                            "Actual bundled definitions are measured");
                    require(
                            "Forever".equals(sources.mapping(build).flavor()),
                            "Reviewed product maps directly");
                    require(
                            sources.mapping(build).versionId() == null,
                            "No default database is invented");
                    sources.setMapping(build, "TBC").join();
                    sources.setEnabled(DataSourcesService.Id.LISTFILE, false).join();
                    sources.setEnabled(DataSourcesService.Id.DEFINITIONS, false).join();
                    sources.setEnabled(DataSourcesService.Id.QUESTIE, false).join();
                    sources.setEnabled(DataSourcesService.Id.TACT_KEYS, false).join();
                    sources.setChecks(
                                    DataSourcesService.Id.QUESTIE, DataSourcesService.Checks.DAILY)
                            .join();
                    expectFailure(() -> sources.checkQuestie().join());
                    expectFailure(() -> sources.updateListfile().join());
                    expectFailure(() -> sources.setDefault("TBC", "f".repeat(64)).join());
                    require(
                            library.stats().join().objects() == 0,
                            "Policy and local summaries create no payloads");
                } finally {
                    sources.closeAsync().join();
                }
                var reopened =
                        DataSourcesService.open(
                                library.databasePath(),
                                directory.resolve("questie"),
                                names,
                                catalog);
                try {
                    for (var id : DataSourcesService.Id.values())
                        require(!reopened.policy(id).enabled(), "Disabled sources persist");
                    require(
                            reopened.policy(DataSourcesService.Id.QUESTIE).checks()
                                    == DataSourcesService.Checks.DAILY,
                            "Update policy persists separately");
                    require(
                            "TBC".equals(reopened.mapping(build).choice()),
                            "Build override survives restart");
                    reopened.setChecks(
                                    DataSourcesService.Id.QUESTIE, DataSourcesService.Checks.MANUAL)
                            .join();
                    reopened.setEnabled(DataSourcesService.Id.QUESTIE, true).join();
                    require(
                            "TBC".equals(reopened.mapping(build).flavor()),
                            "Reenable reuses the saved mapping");
                    reopened.setMapping(build, "None").join();
                    require(
                            reopened.mapping(build).flavor() == null,
                            "Explicit None suppresses auto mapping");
                    Path damaged =
                            directory
                                    .resolve("questie/derived")
                                    .resolve("d".repeat(64) + ".sqlite");
                    Files.createDirectories(damaged.getParent());
                    Files.write(damaged, new byte[] {1, 2, 3});
                    reopened.refreshLocal().join();
                    require(
                            reopened.questie().issue() != null,
                            "Unreadable retained database is reported, not silently forgotten");
                    require(Files.exists(damaged), "Reporting does not delete damaged data");
                } finally {
                    reopened.closeAsync().join();
                }
            } finally {
                access.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    private static void futureSchemaIsReadOnly(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory).join();
        Path database = library.databasePath();
        try (var catalog = LibraryCatalog.open(database).join()) {
            catalog.retainOwner("fixture", LibraryCatalog.Scope.GLOBAL).join();
            new SourcePreferences(database);
            try (var connection = new DerivedSqliteConnection(database)) {
                connection.run(
                        Cancellation.NONE,
                        true,
                        sql -> {
                            sql.execute("UPDATE sources_metadata SET version=99");
                            return null;
                        });
            }
        } finally {
            library.closeAsync().join();
        }
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database));
        try {
            new SourcePreferences(database);
            throw new AssertionError("Future schema was accepted");
        } catch (IllegalArgumentException expected) {
        }
        require(
                Arrays.equals(
                        before,
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database))),
                "Unsupported schema is not changed");
    }

    private static void mappingRules() {
        require(
                "Forever".equals(DataSourcesService.automaticFlavor("wowf", "unknown")),
                "Dedicated product needs no version guess");
        require(
                "Vanilla".equals(DataSourcesService.automaticFlavor("wow_classic", "1.13.2")),
                "Historical vanilla progression");
        require(
                "TBC".equals(DataSourcesService.automaticFlavor("wow_classic", "2.5.1")),
                "Historical TBC progression");
        require(
                "Wrath".equals(DataSourcesService.automaticFlavor("wow_classic", "3.4.0")),
                "Historical Wrath progression");
        require(
                "Cata".equals(DataSourcesService.automaticFlavor("wow_classic", "4.4.0")),
                "Historical Cata progression");
        require(
                "Mists".equals(DataSourcesService.automaticFlavor("wow_classic", "5.5.0")),
                "Historical Mists progression");
        require(
                DataSourcesService.automaticFlavor("wow", "12.1.5") == null,
                "Retail is not assigned a Classic flavor");
        require(
                DataSourcesService.automaticFlavor("wowdev", "1.60.1") == null,
                "Unknown products do not inherit unrelated mappings");
    }

    private static void expectFailure(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected refusal");
        } catch (CompletionException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
