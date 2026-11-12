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
            formerDefinitionsSourceSettingsAreIgnored(directory.resolve("former-definitions"));
            var bundled = String.join("\n", SettingsView.bundledDefinitionsLines());
            require(
                    bundled.contains("WoWDBDefs")
                            && bundled.contains("Revision b9a63096")
                            && bundled.contains("CC BY-SA 4.0")
                            && bundled.contains("https://github.com/wowdev/WoWDBDefs/tree/b9a63096"),
                    "Settings attributes the bundled definitions with revision, license and source");
            var history = String.join("\n", SettingsView.bundledNpcHistoryLines());
            require(
                    history.contains("EG Link")
                            && history.contains("Efficient Games")
                            && history.contains("CC BY 4.0")
                            && history.contains("1.60.1.70862")
                            && history.contains("https://github.com/JIVESCORP/eg-link-output-wowf"),
                    "Settings attributes the bundled EG Link NPC history with builds, license and source");
            mappingRules();
            require(DataSourcesService.compatibleNpcBuild("1.60.1.70532","1.60.1.70707"),"Older same-patch source is eligible");
            require(!DataSourcesService.compatibleNpcBuild("1.60.1.70707","1.60.1.70532"),"Newer sources do not leak backwards");
            require(!DataSourcesService.compatibleNpcBuild("1.15.1.70532","1.60.1.70707"),"Different branches are not compatible");
            require(!DataSourcesService.compatibleNpcBuild("invalid","1.60.1.70707"),"Invalid source build is rejected");
            communityKeys(directory.resolve("keys"));
            polestar(directory.resolve("polestar"));
            egLink(directory.resolve("eg-link"));
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

    private static void polestar(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var owner = library.forOwner("provider:test-names", LibraryCatalog.Scope.GLOBAL, "source", LibraryCatalog.Role.ORIGINAL);
            var access = CachingObjectSource.localOnly(owner, owner);
            var generated = new PackGeneratedStore(library, library.forOwner("provider:test-names", LibraryCatalog.Scope.GLOBAL, "index", LibraryCatalog.Role.DERIVED));
            var names = new CommunityFileNames(access,generated, token -> java.util.concurrent.CompletableFuture.completedFuture(true));
            var files = com.vibeminer.polestar.PolestarFixtures.files();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            com.vibeminer.network.PolestarClient client = new com.vibeminer.network.PolestarClient() {
                public java.util.concurrent.CompletableFuture<com.vibeminer.network.NetworkTypes.PolestarReply> fetchPolestarHead(com.vibeminer.network.Cancellation token) {
                    calls.incrementAndGet(); token.check();
                    return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.PolestarReply(
                            com.vibeminer.network.SourceUrls.POLESTAR_HEAD,java.time.Instant.EPOCH,("{\"object\":{\"sha\":\""+"a".repeat(40)+"\"}}").getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                }
                public java.util.concurrent.CompletableFuture<com.vibeminer.network.NetworkTypes.PolestarReply> fetchPolestarFile(String revision,String path,com.vibeminer.network.Cancellation token) {
                    calls.incrementAndGet(); token.check();
                    return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.PolestarReply(
                            com.vibeminer.network.SourceUrls.polestar(revision,path),java.time.Instant.EPOCH,files.get(path)));
                }
            };
            var service = DataSourcesService.open(library.databasePath(),directory.resolve("questie"),names,catalog,null,null,null,null,client);
            try {
                require(calls.get()==0 && service.polestar().snapshots().isEmpty(),"Opening Polestar settings reads only local metadata");
                service.checkPolestar().join();
                require(service.polestar().catalog().isPresent() && service.polestar().snapshots().isEmpty(),"Checking publication does not download datasets");
                service.downloadPolestar().join();
                require(service.polestar().snapshots().size()==1 && service.polestar().snapshots().getFirst().preparedAt()==null,"Download and preparation are distinct states");
                service.preparePolestar(service.polestar().snapshots().getFirst().id()).join();
                require(service.polestar().snapshots().getFirst().records()==15,"Preparation publishes indexed local records");
                var selectedBuild = new BuildInfo("wowf","Forever","1.60.1.70707","us","config",BuildInfo.BuildSource.CURRENT,false,null,0);
                var npcInputs = service.npcInputs(selectedBuild,"enUS");
                var npcResult = com.vibeminer.concepts.npcs.NpcCatalog.load(npcInputs,com.vibeminer.access.Cancellation.NONE);
                require(npcResult.npcs().size()==1 && "Authored record".equals(npcResult.npcs().getFirst().name()),"NPC adapter reads prepared Polestar data");
                require(npcResult.npcs().getFirst().evidence().getFirst().source().build().equals("1.60.1.70532"),"Older source build provenance retained");
                var questResult=com.vibeminer.concepts.quests.QuestCatalog.load(service.questInputs(selectedBuild,"enUS"),com.vibeminer.access.Cancellation.NONE);
                require(questResult.quests().size()==1 && questResult.quests().getFirst().title()==null,"Quest adapter retains ID without inventing title from unsupported name field");
                int beforeSpawns = calls.get();
                var spawns = service.worldSpawns(selectedBuild,com.vibeminer.access.Cancellation.NONE);
                require(calls.get()==beforeSpawns && spawns.source().orElseThrow().build().equals("1.60.1.70532"),"World spawns read prepared Polestar locally with source provenance");
                require(spawns.spawns()==0 && spawns.state()==com.vibeminer.concepts.locations.LocationTypes.State.PARTIAL,"Rows without a declared spawn kind are not placements");
                require(spawns.npc(1).orElseThrow().name().equals("Authored record"),"Spawn labels come from the same snapshot");
                var classic = new BuildInfo("wow_classic","Classic","1.15.7.61582","us","config",BuildInfo.BuildSource.CURRENT,false,null,0);
                require(service.worldSpawns(classic,com.vibeminer.access.Cancellation.NONE).source().isEmpty(),"No world-yard source is guessed for other products");
                var spawnKey = service.worldSpawnsKey(selectedBuild);
                require(spawnKey.equals(service.worldSpawnsKey(selectedBuild)),"World spawn key is stable for one selection");
                int beforeSync = calls.get();
                service.sync(DataSourcesService.Id.POLESTAR).join();
                require(calls.get() == beforeSync + 1 && service.polestar().snapshots().getFirst().records() == 15,
                        "Global Polestar sync checks one head and reuses prepared records");
                service.setEnabled(DataSourcesService.Id.POLESTAR,false).join();
                require(service.npcInputs(selectedBuild,"enUS").isEmpty() && service.questInputs(selectedBuild,"enUS").isEmpty(),"Disabled source excluded from both catalogues");
                require(service.worldSpawns(selectedBuild,com.vibeminer.access.Cancellation.NONE).state()==com.vibeminer.concepts.locations.LocationTypes.State.UNSUPPORTED,"Disabled Polestar supplies no world spawns");
                require(!spawnKey.equals(service.worldSpawnsKey(selectedBuild)),"Disabling Polestar changes the world spawn key");
                expectFailure(() -> service.syncPolestar().join());
                require(service.polestar().snapshots().size()==1,"Disabling retains completed source data");
            } finally { service.closeAsync().join(); }
            var reopened = DataSourcesService.open(library.databasePath(),directory.resolve("questie"),names,catalog,null,null,null,null,client);
            try {
                require(!reopened.policy(DataSourcesService.Id.POLESTAR).enabled() && reopened.polestar().snapshots().getFirst().records()==15,"Polestar policy and prepared inventory survive restart");
            } finally { reopened.closeAsync().join(); access.closeAsync().join(); }
        } finally { library.closeAsync().join(); }
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
            var blocked = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<com.vibeminer.network.NetworkTypes.TactKeyReply>>();
            var entered = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch>();
            com.vibeminer.network.TactKeyClient client = token -> {
                calls.incrementAndGet(); token.check();
                var waiting = blocked.get();
                if (waiting != null) {
                    entered.get().countDown();
                    return waiting.thenApply(reply -> { token.check(); return reply; });
                }
                return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.TactKeyReply(
                        com.vibeminer.network.SourceUrls.TACT_KEYS, java.time.Instant.now(), body.get().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            };
            DataSourcesService service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner);
            try {
                var defaults = new SourcePreferences(library.databasePath());
                defaults.followLatestDefault("Forever", "first");
                defaults.followLatestDefault("Forever", "second");
                require(defaults.get("default.Forever", "").equals("second"), "Auto-created defaults follow later prepared releases");
                defaults.pinDefault("Forever", "first");
                defaults.followLatestDefault("Forever", "third");
                require(defaults.get("default.Forever", "").equals("first"), "Explicit default selection stays pinned");
                defaults.put("default.TBC", "legacy-selected");
                defaults.followLatestDefault("TBC", "new-release");
                require(defaults.get("default.TBC", "").equals("legacy-selected"), "Legacy explicit defaults stay pinned");
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
                blocked.set(new java.util.concurrent.CompletableFuture<>());
                entered.set(new java.util.concurrent.CountDownLatch(1));
                var existingUpdate = service.updateTactKeys();
                require(entered.get().await(2, java.util.concurrent.TimeUnit.SECONDS), "Existing full key update entered");
                int beforeJoin = calls.get();
                var joinedUpdate = service.sync(DataSourcesService.Id.TACT_KEYS);
                blocked.get().complete(new com.vibeminer.network.NetworkTypes.TactKeyReply(com.vibeminer.network.SourceUrls.TACT_KEYS,
                        java.time.Instant.now(), body.get().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                existingUpdate.join(); joinedUpdate.join();
                require(calls.get() == beforeJoin, "Global refresh joins an existing complete key update without another download");
                blocked.set(new java.util.concurrent.CompletableFuture<>());
                entered.set(new java.util.concurrent.CountDownLatch(1));
                var individual = service.updateTactKeys();
                require(entered.get().await(2, java.util.concurrent.TimeUnit.SECONDS), "Individual source job entered");
                int atStart = calls.get();
                var full = service.sync(DataSourcesService.Id.TACT_KEYS);
                var joined = service.sync(DataSourcesService.Id.TACT_KEYS);
                service.cancel(DataSourcesService.Id.TACT_KEYS);
                blocked.get().complete(new com.vibeminer.network.NetworkTypes.TactKeyReply(com.vibeminer.network.SourceUrls.TACT_KEYS,
                        java.time.Instant.now(), body.get().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                expectFailure(individual::join); expectFailure(full::join); expectFailure(joined::join);
                require(calls.get() == atStart, "Canceled queued full sync must not start another acquisition");
                blocked.set(null);
                service.sync(DataSourcesService.Id.TACT_KEYS).join();
                require(calls.get() == atStart + 1, "Full sync can run after a canceled request");

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
            // Measurement opens schedule no provider check, even one that is overdue.
            service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner,
                    null, null, com.vibeminer.activity.TransferObserver.NONE, false);
            try {
                int before = calls.get();
                Thread.sleep(1_500);
                require(calls.get() == before, "No scheduled check without scheduled checks");
            } finally { service.closeAsync().join(); }
            service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, client, owner, owner);
            try {
                int before = calls.get();
                require(service.tactKeysForBuild(com.vibeminer.access.Cancellation.NONE).count() == 2 && calls.get() == before + 1, "Overdue daily update runs after restart");
            } finally { service.closeAsync().join(); access.closeAsync().join(); }
        } finally { library.closeAsync().join(); }
    }

    private static void egLink(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var owner = library.forOwner("provider:test-eg", LibraryCatalog.Scope.GLOBAL, "source", LibraryCatalog.Role.ORIGINAL);
            var access = CachingObjectSource.localOnly(owner, owner);
            var generated = new PackGeneratedStore(library, library.forOwner("provider:test-eg", LibraryCatalog.Scope.GLOBAL, "index", LibraryCatalog.Role.DERIVED));
            var names = new CommunityFileNames(access, generated, token -> java.util.concurrent.CompletableFuture.completedFuture(true));
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            com.vibeminer.network.EgLinkClient client = new com.vibeminer.network.EgLinkClient() {
                public java.util.concurrent.CompletableFuture<com.vibeminer.network.NetworkTypes.EgLinkReply> fetchEgLinkHead(com.vibeminer.network.Cancellation token) {
                    requests.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.EgLinkReply(com.vibeminer.network.SourceUrls.EG_LINK_HEAD, java.time.Instant.EPOCH,
                            ("{\"object\":{\"sha\":\"" + "a".repeat(40) + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                public java.util.concurrent.CompletableFuture<com.vibeminer.network.NetworkTypes.EgLinkReply> fetchEgLinkFile(String revision, String path, com.vibeminer.network.Cancellation token) {
                    token.check(); requests.incrementAndGet();
                    String credit = "\"license\":\"CC BY 4.0\",\"attribution\":\"" + com.vibeminer.eglink.EgLinkTypes.ATTRIBUTION + "\"";
                    String body = path.equals("data/index.json")
                            ? "{" + credit + ",\"products\":{\"wowf\":{\"1.60.1.70707\":{\"npcs\":{\"records\":1,\"sources\":1,\"lastCollectedOn\":\"2026-11-01\"}}}}}"
                            : "{\"meta\":{" + credit + ",\"dataset\":\"npcs\",\"product\":\"wowf\",\"clientBuild\":\"1.60.1.70707\",\"schemaVersion\":1},\"npcs\":{\"197\":{\"name\":\"Authored NPC\"}}}";
                    return java.util.concurrent.CompletableFuture.completedFuture(new com.vibeminer.network.NetworkTypes.EgLinkReply(com.vibeminer.network.SourceUrls.egLink(revision,path),java.time.Instant.EPOCH,body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            };
            var service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, null, owner, owner, client);
            try {
                require(service.policy(DataSourcesService.Id.EG_LINK).checks() == DataSourcesService.Checks.MANUAL, "EG Link defaults to no additional scheduled checks");
                require(requests.get() == 0 && service.egLink().snapshots().isEmpty(), "Local source opening performs no EG Link requests");
                service.checkEgLink().join();
                require(service.egLink().catalog().builds().size() == 1, "GitHub snapshot inventory is cached");
                service.downloadEgLink(service.egLink().catalog().builds().getFirst()).join();
                var snapshot = service.egLink().snapshots().getFirst();
                require(snapshot.indexedRecords() == null, "Download and processing are separate actions");
                service.prepareEgLink(snapshot.id()).join();
                require(service.egLink().snapshots().getFirst().indexedRecords() == 1, "Prepared inventory shows actual local records");
                var selectedBuild = new BuildInfo("wowf","Forever","1.60.1.70707","us","config",BuildInfo.BuildSource.CURRENT,false,null,0);
                int beforeBrowse=requests.get();
                var npcs=com.vibeminer.concepts.npcs.NpcCatalog.load(service.npcInputs(selectedBuild,"enUS"),com.vibeminer.access.Cancellation.NONE);
                require(npcs.npcs().size()==1 && npcs.npcs().getFirst().id()==197 && "Authored NPC".equals(npcs.npcs().getFirst().name()),"EG adapter reads creature NPC collection only");
                require(requests.get()==beforeBrowse,"Browsing performs no source network requests");
                int checked = requests.get();
                service.sync(DataSourcesService.Id.EG_LINK).join();
                require(requests.get() == checked + 1 && service.egLink().snapshots().getFirst().indexedRecords() == 1,
                        "Full sync reuses unchanged prepared EG Link data after one head check");
                service.setEnabled(DataSourcesService.Id.EG_LINK, false).join();
                var current = service;
                expectFailure(() -> current.checkEgLink().join());
                expectFailure(() -> current.sync(DataSourcesService.Id.EG_LINK).join());
            } finally { service.closeAsync().join(); }
            int previous = requests.get();
            service = DataSourcesService.open(library.databasePath(), directory.resolve("questie"), names, catalog, null, owner, owner, client);
            try {
                require(!service.policy(DataSourcesService.Id.EG_LINK).enabled(), "EG Link enablement persists");
                require(service.egLink().snapshots().getFirst().indexedRecords() == 1 && requests.get() == previous, "Prepared EG Link inventory restores offline");
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
                            java.util.Arrays.stream(DataSourcesService.Id.values())
                                    .noneMatch(id -> id.name().equals("DEFINITIONS")),
                            "Bundled table definitions are not a data source");
                    require(
                            "Forever".equals(sources.mapping(build).flavor()),
                            "Reviewed product maps directly");
                    require(
                            sources.mapping(build).versionId() == null,
                            "No default database is invented");
                    sources.setMapping(build, "TBC").join();
                    sources.setEnabled(DataSourcesService.Id.LISTFILE, false).join();
                    sources.setEnabled(DataSourcesService.Id.QUESTIE, false).join();
                    sources.setEnabled(DataSourcesService.Id.TACT_KEYS, false).join();
                    sources.setEnabled(DataSourcesService.Id.POLESTAR, false).join();
                    sources.setEnabled(DataSourcesService.Id.EG_LINK, false).join();
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

    /**
     * A library from before table definitions stopped being a source still holds their switch,
     * check schedule and timestamps. It opens cleanly and none of them is read.
     */
    private static void formerDefinitionsSourceSettingsAreIgnored(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var former = new SourcePreferences(library.databasePath());
            former.put("enabled.DEFINITIONS", "false");
            former.put("checks.DEFINITIONS", "MANUAL");
            former.put("checked.DEFINITIONS", java.time.Instant.EPOCH.toString());
            former.put("attempt.DEFINITIONS", java.time.Instant.EPOCH.toString());
            var original =
                    library.forOwner(
                            "provider:community-listfile",
                            LibraryCatalog.Scope.GLOBAL,
                            "source",
                            LibraryCatalog.Role.ORIGINAL);
            var access = CachingObjectSource.localOnly(original, original);
            var names =
                    new CommunityFileNames(
                            access,
                            new PackGeneratedStore(
                                    library,
                                    library.forOwner(
                                            "provider:community-listfile",
                                            LibraryCatalog.Scope.GLOBAL,
                                            "index",
                                            LibraryCatalog.Role.DERIVED)),
                            token -> java.util.concurrent.CompletableFuture.completedFuture(true));
            try {
                var sources =
                        DataSourcesService.open(
                                library.databasePath(), directory.resolve("questie"), names, catalog);
                try {
                    sources.refreshLocal().join();
                    for (var id : DataSourcesService.Id.values())
                        require(sources.policy(id).enabled(), "Other sources keep their defaults");
                    require(
                            java.util.Arrays.stream(DataSourcesService.Id.values())
                                    .noneMatch(id -> id.name().equals("DEFINITIONS")),
                            "The former definitions switch is not offered");
                } finally {
                    sources.closeAsync().join();
                }
                require(
                        new SourcePreferences(library.databasePath())
                                .get("enabled.DEFINITIONS", "")
                                .equals("false"),
                        "The stored row is left as it was: no schema change or rewrite");
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
