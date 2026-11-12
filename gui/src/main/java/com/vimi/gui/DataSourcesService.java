package com.vimi.gui;

import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.files.names.NamesTypes;
import com.vibeminer.questie.QuestieRepository;
import com.vibeminer.questie.QuestieTypes;
import com.vibeminer.questie.data.QuestieDataTypes.PreparedVersion;
import com.vibeminer.store.LibraryCatalog;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Application lifetime source orchestration; controls read immutable cached snapshots only.
 *
 * <p>Only downloaded providers are sources. Table definitions (WoWDBDefs) ship with the
 * application and are verified where they are loaded, so they are neither listed, refreshed nor
 * switched here; preferences an older library stored for them ({@code *.DEFINITIONS}) are never
 * read.
 */
public final class DataSourcesService {
    public enum Id {
        QUESTIE,
        LISTFILE,
        TACT_KEYS,
        EG_LINK,
        POLESTAR
    }

    public enum Checks {
        MANUAL,
        STARTUP,
        DAILY
    }

    public record Policy(boolean enabled, Checks checks) {}

    public record Database(
            String sourceId,
            String versionId,
            String release,
            String flavor,
            long archiveBytes,
            long databaseBytes,
            Long records,
            List<String> locales,
            Instant preparedAt,
            List<String> diagnostics,
            boolean preferred) {}

    public record QuestieState(
            List<Database> databases, List<String> releases, String latest, String issue) {}

    public record Activity(boolean busy, String message, double fraction, String problem) {}

    public record Mapping(
            String choice, String flavor, String versionId, String release, String message) {}

    public record TactState(int keys, String fingerprint, long bytes, String problem) {}
    private volatile com.vibeminer.files.keys.TactKeys tactKeys = com.vibeminer.files.keys.TactKeys.EMPTY;
    private volatile TactState tactState = new TactState(0, null, 0, null);
    private final com.vibeminer.network.TactKeyClient tactClient;
    private final com.vibeminer.store.ObjectReader tactReader;
    private final com.vibeminer.store.ObjectWriter tactWriter;
    private final Map<Id, com.vibeminer.network.Cancellation> networkTokens = new java.util.EnumMap<>(Id.class);
    public record EgLinkState(com.vibeminer.eglink.EgLinkTypes.Catalog catalog,
            List<com.vibeminer.eglink.EgLinkTypes.Snapshot> snapshots, String issue) {}
    private volatile EgLinkState egLinkState = new EgLinkState(new com.vibeminer.eglink.EgLinkTypes.Catalog(null, null, List.of()), List.of(), null);
    private final com.vibeminer.eglink.EgLinkRepository egLink;

    /** A library or opened build whose EG Link snapshots pruning keeps. */
    public record BuildKey(String product, String version) {}

    /** The library's builds, for the EG Link snapshots pruning must keep; set by the application. */
    private volatile java.util.function.Supplier<java.util.Collection<BuildKey>> libraryBuilds = List::of;
    /** Builds opened this session (their NPC, quest or location sources were read). */
    private final java.util.Set<BuildKey> openedBuilds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** EG Link snapshots a user downloaded or prepared by hand this session. */
    private final java.util.Set<String> chosenEgLink = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public record PolestarState(java.util.Optional<com.vibeminer.polestar.PolestarTypes.Catalog> catalog,
            List<com.vibeminer.polestar.PolestarTypes.Snapshot> snapshots, String issue) {
        public PolestarState { snapshots = List.copyOf(snapshots); }
    }
    private final com.vibeminer.polestar.PolestarRepository polestar;
    private volatile PolestarState polestarState = new PolestarState(java.util.Optional.empty(), List.of(), null);
    private final SourcePreferences preferences;
    private final QuestieRepository questie;
    private final CommunityFileNames names;
    private final LibraryCatalog catalog;
    private final Path questieDirectory;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    Thread.ofPlatform().daemon().name("vimi-source-policy").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.ScheduledExecutorService timer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("vimi-source-checks").factory());
    private final Map<Id, CompletableFuture<Void>> jobs = new java.util.EnumMap<>(Id.class);
    private final Map<Id, CompletableFuture<Void>> fullSyncs = new java.util.EnumMap<>(Id.class);
    private final Map<Id, Long> cancelEpoch = new java.util.EnumMap<>(Id.class);
    private final Map<Id, com.vibeminer.generated.Cancellation> tokens =
            new java.util.EnumMap<>(Id.class);
    private final Map<Id, com.vibeminer.access.Cancellation> accessTokens =
            new java.util.EnumMap<>(Id.class);
    private final Map<Id, Activity> activity = new ConcurrentHashMap<>();
    private volatile QuestieState questieState = new QuestieState(List.of(), List.of(), null, null);
    private volatile NamesTypes.FileNamesStatus listfile;
    private volatile LibraryCatalog.Figures listfileBytes;
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();
    private boolean closing;
    private CompletableFuture<Void> closed;

    static DataSourcesService open(
            Path database, Path directory, CommunityFileNames names, LibraryCatalog catalog)
            throws Exception {
        return open(database, directory, names, catalog, null, null, null);
    }

    static DataSourcesService open(Path database, Path directory, CommunityFileNames names,
            LibraryCatalog catalog, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer) throws Exception {
        return open(database, directory, names, catalog, client, reader, writer, null, null);
    }

    static DataSourcesService open(Path database, Path directory, CommunityFileNames names,
            LibraryCatalog catalog, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer,
            com.vibeminer.network.EgLinkClient egClient) throws Exception {
        return open(database,directory,names,catalog,client,reader,writer,egClient,null);
    }

    static DataSourcesService open(Path database, Path directory, CommunityFileNames names,
            LibraryCatalog catalog, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer,
            com.vibeminer.network.EgLinkClient egClient,
            com.vibeminer.network.PolestarClient polestarClient) throws Exception {
        return open(database, directory, names, catalog, client, reader, writer, egClient,
                polestarClient, com.vibeminer.activity.TransferObserver.NONE);
    }

    static DataSourcesService open(Path database, Path directory, CommunityFileNames names,
            LibraryCatalog catalog, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer,
            com.vibeminer.network.EgLinkClient egClient,
            com.vibeminer.network.PolestarClient polestarClient,
            com.vibeminer.activity.TransferObserver observer) throws Exception {
        return open(database, directory, names, catalog, client, reader, writer, egClient,
                polestarClient, observer, true);
    }

    /**
     * With {@code scheduledChecks} false, no startup or daily provider check is ever scheduled;
     * only explicit requests sync (measurement probes open the library this way).
     */
    static DataSourcesService open(Path database, Path directory, CommunityFileNames names,
            LibraryCatalog catalog, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer,
            com.vibeminer.network.EgLinkClient egClient,
            com.vibeminer.network.PolestarClient polestarClient,
            com.vibeminer.activity.TransferObserver observer, boolean scheduledChecks)
            throws Exception {
        var settings = new SourcePreferences(database);
        var repository =
                QuestieRepository.open(directory, com.vibeminer.generated.Cancellation.NONE, observer).join();
        DataSourcesService service = null;
        com.vibeminer.polestar.PolestarRepository polestarRepository = null;
        try {
            polestarRepository = new com.vibeminer.polestar.PolestarRepository(directory.resolveSibling("polestar"),
                    polestarClient == null ? unavailablePolestar() : polestarClient);
            catalog.retainOwner("provider:community-listfile", LibraryCatalog.Scope.GLOBAL).join();
            service = new DataSourcesService(settings, repository, names, catalog, directory, client, reader, writer, egClient, polestarRepository);
            service.refreshLocal().join();
            var opened = service;
            if (scheduledChecks)
                service.timer.scheduleWithFixedDelay(opened::checkDue, 1, 60, TimeUnit.SECONDS);
            return service;
        } catch (Throwable error) {
            if (service == null) {
                repository.closeAsync().join();
                if (polestarRepository != null) polestarRepository.closeAsync().join();
            }
            else service.closeAsync().join();
            throw error;
        }
    }

    private DataSourcesService(
            SourcePreferences preferences,
            QuestieRepository questie,
            CommunityFileNames names,
            LibraryCatalog catalog,
            Path directory, com.vibeminer.network.TactKeyClient client,
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer,
            com.vibeminer.network.EgLinkClient egClient,
            com.vibeminer.polestar.PolestarRepository polestar) throws java.io.IOException {
        egLink = egClient == null ? null : new com.vibeminer.eglink.EgLinkRepository(directory.resolveSibling("eg-link"), egClient);
        this.polestar = polestar;
        tactClient = client; tactReader = reader; tactWriter = writer;
        this.preferences = preferences;
        this.questie = questie;
        this.names = names;
        this.catalog = catalog;
        this.questieDirectory = directory;
        for (Id id : Id.values()) {
            policy(id);
            activity.put(id, new Activity(false, null, -1, null));
        }
    }

    public Policy policy(Id id) {
        return new Policy(
                Boolean.parseBoolean(preferences.get("enabled." + id, "true")),
                Checks.valueOf(preferences.get("checks." + id, id == Id.TACT_KEYS ? "DAILY" : "MANUAL")));
    }

    public long revision() {
        return revision.get();
    }

    public QuestieState questie() {
        return questieState;
    }

    public NamesTypes.FileNamesStatus listfile() {
        return listfile;
    }

    public LibraryCatalog.Figures listfileBytes() {
        return listfileBytes;
    }

    public Activity activity(Id id) {
        return activity.get(id);
    }

    public String checkedAt(Id id) {
        String fallback = id == Id.POLESTAR && polestarState.catalog().isPresent()
                ? polestarState.catalog().get().checkedAt().toString() : "Never checked";
        return preferences.get("checked." + id, fallback);
    }

    /**
     * The prepared snapshot of {@code product} with {@code creature_details.<locale>} whose build is
     * the newest of the same x.y.z not after {@code version}, or null. Pure.
     */
    static com.vibeminer.eglink.EgLinkTypes.Snapshot npcDisplaySnapshot(
            List<com.vibeminer.eglink.EgLinkTypes.Snapshot> snapshots, String product, String version, String locale) {
        var ordered = npcDisplaySnapshots(snapshots, product, version, locale);
        return ordered.isEmpty() ? null : ordered.getFirst();
    }

    /** Every prepared snapshot an NPC's displays may come from, in the order they are tried. */
    public List<com.vibeminer.eglink.EgLinkTypes.Snapshot> npcDisplaySnapshots(BuildInfo build, String locale) {
        if (egLink == null || build == null || !policy(Id.EG_LINK).enabled()) return List.of();
        opened(build);
        return npcDisplaySnapshots(egLinkState.snapshots(), build.product(), build.version(), locale);
    }

    /**
     * The prepared snapshots of {@code product} with {@code creature_details.<locale>} of the same
     * x.y.z and not after {@code version}: newest build first, then the newest commit of a build
     * first. Never a snapshot newer than the opened build. Pure.
     */
    static List<com.vibeminer.eglink.EgLinkTypes.Snapshot> npcDisplaySnapshots(
            List<com.vibeminer.eglink.EgLinkTypes.Snapshot> snapshots, String product, String version, String locale) {
        return snapshots.stream()
                .filter(v -> v.indexedRecords() != null && v.problem() == null
                        && v.build().product().equals(product)
                        && compatibleNpcBuild(v.build().version(), version)
                        && v.build().datasets().stream().anyMatch(d -> d.name().equals("creature_details." + locale)))
                .sorted(java.util.Comparator.comparing(
                                (com.vibeminer.eglink.EgLinkTypes.Snapshot v) -> v.build().version(),
                                DataSourcesService::compareNpcBuild)
                        .reversed()
                        .thenComparing(com.vibeminer.eglink.EgLinkTypes.Snapshot::downloadedAt,
                                java.util.Comparator.reverseOrder())
                        .thenComparing(com.vibeminer.eglink.EgLinkTypes.Snapshot::id))
                .toList();
    }

    /** The NPC's observed displays in {@code snapshot}, or empty. Reads local data only. */
    public java.util.Optional<com.vibeminer.eglink.EgLinkTypes.CreatureDisplays> npcDisplays(
            com.vibeminer.eglink.EgLinkTypes.Snapshot snapshot, String locale, long npcId,
            com.vibeminer.network.Cancellation token) {
        if (egLink == null || snapshot == null) return java.util.Optional.empty();
        return egLink.creatureDisplays(snapshot.id(), locale, npcId, token).join();
    }

    /** Local prepared snapshots only, frozen for one NPC catalogue load. */
    public List<com.vibeminer.concepts.npcs.NpcCatalog.Input> npcInputs(BuildInfo build, String locale) {
        opened(build);
        var result = new java.util.ArrayList<com.vibeminer.concepts.npcs.NpcCatalog.Input>();
        var mapping = mapping(build);
        if (mapping.versionId() != null) result.add(com.vibeminer.concepts.npcs.NpcSources.questie(questie, mapping.versionId(), build.product(), build.version(), locale));
        if (policy(Id.POLESTAR).enabled() && "Forever".equals(automaticFlavor(build.product(),build.version()))) {
            polestarState.snapshots().stream().filter(v -> v.preparedAt()!=null && compatibleNpcBuild(v.catalog().clientBuild(),build.version()))
                .max(java.util.Comparator.comparing(v -> v.catalog().clientBuild(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.npcs.NpcSources.polestar(polestar,v,locale)));
        }
        if (egLink != null && policy(Id.EG_LINK).enabled()) {
            egLinkState.snapshots().stream().filter(v -> v.indexedRecords()!=null && v.problem()==null && v.build().product().equals(build.product()) && compatibleNpcBuild(v.build().version(),build.version())
                && v.build().datasets().stream().anyMatch(d -> d.name().equals("creature_details."+locale) || d.name().equals("npcs")))
                .max(java.util.Comparator.comparing(v -> v.build().version(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.npcs.NpcSources.egLink(egLink,v,locale)));
        }
        return List.copyOf(result);
    }

    public List<com.vibeminer.concepts.quests.QuestCatalog.Input> questInputs(BuildInfo build, String locale) {
        opened(build);
        var result = new java.util.ArrayList<com.vibeminer.concepts.quests.QuestCatalog.Input>();
        var mapping = mapping(build);
        if (mapping.versionId() != null) result.add(com.vibeminer.concepts.quests.QuestSources.questie(questie, mapping.versionId(), build.product(), build.version(), locale));
        if (policy(Id.POLESTAR).enabled() && "Forever".equals(automaticFlavor(build.product(),build.version()))) {
            polestarState.snapshots().stream().filter(v -> v.preparedAt()!=null && compatibleNpcBuild(v.catalog().clientBuild(),build.version()))
                .max(java.util.Comparator.comparing(v -> v.catalog().clientBuild(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.quests.QuestSources.polestar(polestar,v,locale)));
        }
        if (egLink != null && policy(Id.EG_LINK).enabled()) {
            egLinkState.snapshots().stream().filter(v -> v.indexedRecords()!=null && v.problem()==null && v.build().product().equals(build.product()) && compatibleNpcBuild(v.build().version(),build.version())
                && v.build().datasets().stream().anyMatch(d -> d.name().equals("quests."+locale)))
                .max(java.util.Comparator.comparing(v -> v.build().version(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.quests.QuestSources.egLink(egLink,v,locale)));
        }
        return List.copyOf(result);
    }

    /** Same prepared-source selection policy as NPC/quest catalogues; no downloads. */
    public List<com.vibeminer.concepts.locations.LocationInput> locationInputs(BuildInfo build, String locale) {
        opened(build);
        var result = new java.util.ArrayList<com.vibeminer.concepts.locations.LocationInput>();
        var mapping = mapping(build);
        if (mapping.versionId() != null)
            result.add(com.vibeminer.concepts.locations.LocationSources.questie(questie, mapping.versionId(), mapping.flavor(), locale));
        if (policy(Id.POLESTAR).enabled() && "Forever".equals(automaticFlavor(build.product(), build.version()))) {
            polestarState.snapshots().stream()
                .filter(v -> v.preparedAt() != null && compatibleNpcBuild(v.catalog().clientBuild(), build.version()))
                .max(java.util.Comparator.comparing(v -> v.catalog().clientBuild(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.locations.LocationSources.polestar(polestar, v)));
        }
        if (egLink != null && policy(Id.EG_LINK).enabled()) {
            egLinkState.snapshots().stream()
                .filter(v -> v.indexedRecords() != null && v.problem() == null
                    && v.build().product().equals(build.product())
                    && compatibleNpcBuild(v.build().version(), build.version())
                    && v.build().datasets().stream().anyMatch(d -> d.name().equals("npcs")))
                .max(java.util.Comparator.comparing(v -> v.build().version(), DataSourcesService::compareNpcBuild))
                .ifPresent(v -> result.add(com.vibeminer.concepts.locations.LocationSources.egLink(egLink, v)));
        }
        return List.copyOf(result);
    }

    /**
     * Declared world-yard NPC spawns for World placement: only Polestar records world yards on a
     * client Map ID. Questie area percentages and EG interaction observations are not admitted.
     * Local prepared snapshots only; never downloads or prepares.
     */
    public com.vibeminer.concepts.locations.WorldSpawns.Index worldSpawns(BuildInfo build,
            com.vibeminer.access.Cancellation token) {
        var unsupported = com.vibeminer.concepts.locations.LocationTypes.State.UNSUPPORTED;
        if (!policy(Id.POLESTAR).enabled())
            return com.vibeminer.concepts.locations.WorldSpawns.unavailable(unsupported,
                    "Polestar is disabled; no other source records world-yard spawn points.");
        if (!"Forever".equals(automaticFlavor(build.product(), build.version())))
            return com.vibeminer.concepts.locations.WorldSpawns.unavailable(unsupported,
                    "No reviewed world-yard spawn source applies to this build.");
        var snapshot = worldSpawnSnapshot(build);
        if (snapshot.isEmpty())
            return com.vibeminer.concepts.locations.WorldSpawns.unavailable(
                    com.vibeminer.concepts.locations.LocationTypes.State.UNKNOWN,
                    "No prepared Polestar snapshot at or before this build; use Data Sources.");
        return com.vibeminer.concepts.locations.WorldSpawns.polestar(polestar, snapshot.get(), token);
    }

    private java.util.Optional<com.vibeminer.polestar.PolestarTypes.Snapshot> worldSpawnSnapshot(BuildInfo build) {
        return polestarState.snapshots().stream()
                .filter(v -> v.preparedAt() != null && compatibleNpcBuild(v.catalog().clientBuild(), build.version()))
                .max(java.util.Comparator.comparing(v -> v.catalog().clientBuild(), DataSourcesService::compareNpcBuild));
    }

    /** Changes only when the world-spawn source policy or selected snapshot changes. */
    public Object worldSpawnsKey(BuildInfo build) {
        return List.of(policy(Id.POLESTAR).enabled(),
                worldSpawnSnapshot(build).map(com.vibeminer.polestar.PolestarTypes.Snapshot::id).orElse(""));
    }

    /**
     * Service-NPC roles for Maps overlays from the build's mapped prepared Questie version only.
     * Local reads; never downloads, prepares or substitutes another flavor.
     */
    public com.vibeminer.concepts.locations.MapRoles.Catalog mapRoles(BuildInfo build, String locale,
            com.vibeminer.access.Cancellation token) {
        var mapping = mapping(build);
        if (mapping.versionId() == null) throw new IllegalStateException(mapping.message());
        return com.vibeminer.concepts.locations.MapRoles.questie(questie, mapping.versionId(),
                com.vibeminer.questie.QuestieTypes.Flavor.parse(mapping.flavor()), locale, token);
    }

    /** Changes only when the mapped Questie version for this build changes. */
    public Object mapRolesKey(BuildInfo build) {
        var mapping = mapping(build);
        return java.util.Arrays.asList(mapping.flavor(), mapping.versionId(), mapping.message());
    }

    static boolean compatibleNpcBuild(String source, String target) {
        if (source == null || target == null || !source.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || !target.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return false;
        try {
            return source.substring(0,source.lastIndexOf('.')).equals(target.substring(0,target.lastIndexOf('.'))) && compareNpcBuild(source,target)<=0;
        } catch (NumberFormatException invalid) { return false; }
    }

    static int compareNpcBuild(String left,String right) {
        return Long.compare(Long.parseLong(left.substring(left.lastIndexOf('.')+1)),Long.parseLong(right.substring(right.lastIndexOf('.')+1)));
    }

    public CompletableFuture<Void> setEnabled(Id id, boolean enabled) {
        return submit(
                () -> {
                    preferences.put("enabled." + id, Boolean.toString(enabled));
                    if (!enabled) cancel(id);
                    revision.incrementAndGet();
                });
    }

    public CompletableFuture<Void> setChecks(Id id, Checks checks) {
        return submit(
                () -> {
                    preferences.put("checks." + id, checks.name());
                    revision.incrementAndGet();
                });
    }

    public CompletableFuture<Void> setDefault(String flavor, String versionId) {
        return submit(
                () -> {
                    var kind = QuestieTypes.Flavor.parse(flavor);
                    if (questieState.databases().stream()
                            .noneMatch(
                                    v ->
                                            versionId.equals(v.versionId())
                                                    && kind.upstream().equals(v.flavor())))
                        throw new IllegalArgumentException(
                                "Choose a prepared database for this flavor");
                    try (var handle =
                            questie.openVersion(
                                            versionId, com.vibeminer.generated.Cancellation.NONE)
                                    .join()) {
                        if (!handle.version().flavor().equals(kind))
                            throw new IllegalArgumentException("Prepared flavor mismatch");
                    }
                    preferences.pinDefault(kind.upstream(), versionId);
                    loadQuestie();
                    revision.incrementAndGet();
                });
    }

    public CompletableFuture<Void> setMapping(BuildInfo build, String choice) {
        if (!choice.equals("Automatic") && !choice.equals("None"))
            QuestieTypes.Flavor.parse(choice);
        return submit(
                () -> {
                    preferences.put("mapping." + build.identity(), choice);
                    revision.incrementAndGet();
                });
    }

    public Mapping mapping(BuildInfo build) {
        String choice = preferences.get("mapping." + build.identity(), "Automatic");
        String flavor =
                choice.equals("Automatic")
                        ? automaticFlavor(build.product(), build.version())
                        : choice.equals("None") ? null : choice;
        if (!policy(Id.QUESTIE).enabled())
            return new Mapping(choice, flavor, null, null, "QuestieDB is disabled");
        if (flavor == null)
            return new Mapping(
                    choice,
                    null,
                    null,
                    null,
                    choice.equals("None")
                            ? "Questie is not used for this build"
                            : "No automatic flavor mapping; choose a flavor");
        String selected = preferences.get("default." + flavor, "");
        var database =
                questieState.databases().stream()
                        .filter(v -> selected.equals(v.versionId()) && flavor.equals(v.flavor()))
                        .findFirst();
        return database.map(
                        v ->
                                new Mapping(
                                        choice,
                                        flavor,
                                        v.versionId(),
                                        v.release(),
                                        "Selected database: " + v.release() + " · " + flavor))
                .orElseGet(
                        () ->
                                new Mapping(
                                        choice,
                                        flavor,
                                        null,
                                        null,
                                        "No default prepared database for "
                                                + flavor
                                                + "; select one in Data Sources"));
    }

    static String automaticFlavor(String product, String version) {
        if (product.equals("wowf")) return "Forever";
        if (product.equals("wow_classic_titan"))
            return version != null && version.startsWith("3.") ? "Wrath" : null;
        if (!java.util.Set.of(
                        "wow_classic",
                        "wow_classic_ptr",
                        "wow_classic_era",
                        "wow_classic_era_ptr",
                        "wow_classic_era_beta",
                        "wow_anniversary",
                        "wow_classic_beta",
                        "wow_cn_beta")
                .contains(product)) return null;
        if (version == null) return null;
        if (version.startsWith("1.60.")) return "Forever";
        if (version.startsWith("1.")) return "Vanilla";
        if (version.startsWith("2.")) return "TBC";
        if (version.startsWith("3.")) return "Wrath";
        if (version.startsWith("4.")) return "Cata";
        if (version.startsWith("5.")) return "Mists";
        return null;
    }

    public CompletableFuture<Void> refreshLocal() {
        return submit(
                () -> {
                    loadQuestie();
                    loadEgLink();
                    loadPolestar();
                    loadTactKeys();
                    try {
                        listfile = names.status(com.vibeminer.access.Cancellation.NONE).join();
                    } catch (Throwable error) {
                        activity.put(Id.LISTFILE, new Activity(false, null, -1, message(error)));
                    }
                    listfileBytes =
                            catalog.figures(
                                            "provider:community-listfile",
                                            com.vibeminer.store.Cancellation.NONE)
                                    .join();
                    revision.incrementAndGet();
                });
    }

    private void loadQuestie() {
        try {
            var sources = questie.sourceSnapshots(com.vibeminer.generated.Cancellation.NONE).join();
            var versions = questie.versions(com.vibeminer.generated.Cancellation.NONE).join();
            var databases = new java.util.ArrayList<Database>();
            for (var source : sources) {
                var prepared =
                        versions.stream().filter(v -> v.sourceId().equals(source.id())).toList();
                if (prepared.isEmpty())
                    databases.add(
                            new Database(
                                    source.id(),
                                    null,
                                    source.releaseTag(),
                                    source.flavor().upstream(),
                                    source.bytes(),
                                    0,
                                    null,
                                    List.of(),
                                    null,
                                    List.of(),
                                    false));
                for (var v : prepared) databases.add(database(v, source.bytes()));
            }
            boolean orphaned = false;
            for (var version : versions) {
                if (sources.stream().noneMatch(source -> source.id().equals(version.sourceId()))) {
                    databases.add(database(version, -1));
                    orphaned = true;
                }
            }
            String issue =
                    orphaned ? "Some prepared databases have no readable original archive" : null;
            Path folder = questieDirectory.resolve("derived");
            if (Files.isDirectory(folder))
                try (var files = Files.list(folder)) {
                    long count =
                            files.filter(p -> p.getFileName().toString().endsWith(".sqlite"))
                                    .limit(4097)
                                    .count();
                    if (count > versions.size())
                        issue =
                                "Some local databases are unreadable; retained files have not been"
                                        + " removed";
                }
            var releases =
                    questie.releases(com.vibeminer.generated.Cancellation.NONE).join().stream()
                            .map(r -> r.tag())
                            .distinct()
                            .toList();
            questieState =
                    new QuestieState(
                            List.copyOf(databases),
                            releases,
                            preferences.get("latest.QUESTIE", null),
                            issue);
        } catch (Throwable error) {
            var prior = questieState;
            questieState =
                    new QuestieState(
                            prior.databases(),
                            prior.releases(),
                            prior.latest(),
                            "Local inventory unavailable: " + message(error));
        }
    }

    private Database database(PreparedVersion version, long archiveBytes)
            throws java.io.IOException {
        long size = -1;
        if (version.id().matches("[0-9a-f]{64}")) {
            Path file = questieDirectory.resolve("derived").resolve(version.id() + ".sqlite");
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) size = Files.size(file);
        }
        return new Database(
                version.sourceId(),
                version.id(),
                version.releaseTag(),
                version.flavor().upstream(),
                archiveBytes,
                size,
                version.entityCount(),
                version.locales(),
                version.preparedAt(),
                version.diagnostics(),
                version.id().equals(preferences.get("default." + version.flavor().upstream(), "")));
    }

    /** Full provider update boundary used by startup, the global action and periodic checks. */
    public synchronized CompletableFuture<Void> sync(Id id) {
        if (closing || !policy(id).enabled()) return CompletableFuture.failedFuture(new IllegalStateException("Enable this source first"));
        var current = fullSyncs.get(id);
        if (current != null) return current.copy();
        var done = new CompletableFuture<Void>();
        fullSyncs.put(id, done);
        long epoch = cancelEpoch.getOrDefault(id, 0L);
        var prior = jobs.get(id);
        var ready = prior == null ? CompletableFuture.<Void>completedFuture(null) : prior.handle((value, error) -> null);
        ready.thenCompose(ignored -> {
            synchronized (this) {
                if (closing || cancelEpoch.getOrDefault(id, 0L) != epoch || !policy(id).enabled())
                    return CompletableFuture.<Void>failedFuture(new java.util.concurrent.CancellationException());
                startupChecked.add(id);
                if (prior != null && (id == Id.TACT_KEYS || id == Id.LISTFILE)) {
                    // These individual operations already include every preparation phase.
                    return submit(() -> preferences.put("attempt." + id, Instant.now().toString())).thenCompose(doneAt -> prior);
                }
                return startSync(id, epoch);
            }
        }).whenComplete((value, error) -> {
            synchronized (this) { fullSyncs.remove(id, done); }
            if (error == null) done.complete(null); else done.completeExceptionally(error);
        });
        return done.copy();
    }

    private CompletableFuture<Void> startSync(Id id, long epoch) {
        return submit(() -> preferences.put("attempt." + id, Instant.now().toString())).thenCompose(ignored -> {
            synchronized (this) {
                if (closing || cancelEpoch.getOrDefault(id, 0L) != epoch || !policy(id).enabled())
                    return CompletableFuture.<Void>failedFuture(new java.util.concurrent.CancellationException());
                var active = jobs.get(id);
                if (active != null) return active.handle((value, error) -> null).thenCompose(done -> startSync(id, epoch));
                return launchSync(id);
            }
        });
    }

    private CompletableFuture<Void> launchSync(Id id) {
        return switch (id) {
            case QUESTIE -> syncQuestie();
            case LISTFILE -> updateListfile();
            case TACT_KEYS -> updateTactKeys();
            case EG_LINK -> syncEgLink();
            case POLESTAR -> syncPolestar();
        };
    }

    private CompletableFuture<Void> syncQuestie() {
        return job(Id.QUESTIE, (token, progress) -> {
            var result = questie.sync(progress, token).join();
            submit(() -> {
                token.check();
                preferences.put("latest.QUESTIE", result.release());
                preferences.put("checked.QUESTIE", Instant.now().toString());
                for (var version : result.versions()) {
                    preferences.followLatestDefault(version.flavor().upstream(), version.id());
                }
                loadQuestie(); revision.incrementAndGet();
            }).join();
            if (!result.issues().isEmpty()) throw new IllegalStateException(String.join("; ", result.issues()));
        });
    }

    public CompletableFuture<Void> checkQuestie() {
        return job(
                Id.QUESTIE,
                (token, progress) -> {
                    var result = questie.checkForUpdates(token).join();
                    if (result.offline()) throw new IllegalStateException(result.message());
                    submit(
                                    () -> {
                                        if (result.latest() != null)
                                            preferences.put(
                                                    "latest.QUESTIE", result.latest().tag());
                                        preferences.put(
                                                "checked.QUESTIE", Instant.now().toString());
                                        loadQuestie();
                                        revision.incrementAndGet();
                                    })
                            .join();
                });
    }

    public CompletableFuture<Void> download(String release, String flavor) {
        return job(
                Id.QUESTIE,
                (token, progress) -> {
                    var value = questie.release(release, token).join();
                    questie.download(value, QuestieTypes.Flavor.parse(flavor), progress, token)
                            .join();
                    submit(
                                    () -> {
                                        loadQuestie();
                                        revision.incrementAndGet();
                                    })
                            .join();
                });
    }

    public CompletableFuture<Void> prepare(String sourceId) {
        return job(
                Id.QUESTIE,
                (token, progress) -> {
                    questie.prepare(sourceId, progress, token).join();
                    submit(
                                    () -> {
                                        loadQuestie();
                                        revision.incrementAndGet();
                                    })
                            .join();
                });
    }

    public CompletableFuture<Void> updateListfile() {
        return job(
                Id.LISTFILE,
                (token, progress) -> {
                    var access = new com.vibeminer.access.Cancellation();
                    synchronized (this) {
                        accessTokens.put(Id.LISTFILE, access);
                        if (token.isCanceled()) access.cancel();
                    }
                    try {
                        progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.DISCOVERING, 0, null, "source", "Checking listfile and preparing changed names…"));
                        var result = names.update(true, access).join();
                        if (result.state() != NamesTypes.FileNamesState.READY || result.code() != null) {
                            submit(() -> { listfile = result; revision.incrementAndGet(); }).join();
                            throw new IllegalStateException(
                                    result.message() == null
                                            ? "Listfile is not ready"
                                            : result.message());
                        }
                        progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.READY, result.count(), (long) result.count(), "names", "Listfile names are ready"));
                        submit(
                                        () -> {
                                            listfile = result;
                                            preferences.put(
                                                    "checked.LISTFILE", Instant.now().toString());
                                            listfileBytes =
                                                    catalog.figures(
                                                                    "provider:community-listfile",
                                                                    com.vibeminer.store.Cancellation
                                                                            .NONE)
                                                            .join();
                                            revision.incrementAndGet();
                                        })
                                .join();
                    } finally {
                        synchronized (this) {
                            accessTokens.remove(Id.LISTFILE);
                        }
                    }
                });
    }

    public EgLinkState egLink() { return egLinkState; }

    private void loadEgLink() {
        if (egLink == null) return;
        try {
            var token = com.vibeminer.network.Cancellation.NONE;
            egLinkState = new EgLinkState(egLink.catalog(token).join(), egLink.snapshots(token).join(), null);
        } catch (RuntimeException failure) {
            var before = egLinkState;
            egLinkState = new EgLinkState(before.catalog(), before.snapshots(), "EG Link local inventory unavailable: " + message(failure));
        }
    }

    private void opened(BuildInfo build) {
        if (build != null && build.product() != null && build.version() != null)
            openedBuilds.add(new BuildKey(build.product(), build.version()));
    }

    /** Sets where the library's builds come from, for the EG Link snapshots pruning keeps. */
    void libraryBuilds(java.util.function.Supplier<java.util.Collection<BuildKey>> builds) {
        libraryBuilds = java.util.Objects.requireNonNull(builds);
    }

    /**
     * The EG Link snapshots pruning keeps: for each library or opened build, the newest compatible
     * prepared snapshot holding each dataset (what its NPC, quest, location and display lookups
     * read), and every snapshot chosen by hand. Pure.
     */
    static java.util.Set<String> keptEgLink(List<com.vibeminer.eglink.EgLinkTypes.Snapshot> snapshots,
            java.util.Collection<BuildKey> builds, java.util.Collection<String> chosen) {
        var kept = new java.util.HashSet<String>(chosen);
        var newestFirst = java.util.Comparator.comparing(
                        (com.vibeminer.eglink.EgLinkTypes.Snapshot v) -> v.build().version(),
                        DataSourcesService::compareNpcBuild)
                .reversed()
                .thenComparing(com.vibeminer.eglink.EgLinkTypes.Snapshot::downloadedAt,
                        java.util.Comparator.reverseOrder());
        for (var build : builds) {
            var compatible = snapshots.stream()
                    .filter(v -> v.indexedRecords() != null && v.problem() == null
                            && v.build().product().equals(build.product())
                            && compatibleNpcBuild(v.build().version(), build.version()))
                    .sorted(newestFirst)
                    .toList();
            var covered = new java.util.HashSet<String>();
            for (var snapshot : compatible) {
                for (var dataset : snapshot.build().datasets()) {
                    if (covered.add(dataset.name())) kept.add(snapshot.id());
                }
            }
        }
        return java.util.Set.copyOf(kept);
    }

    public CompletableFuture<Void> syncEgLink() {
        return egLinkJob((token, progress) -> {
            var result = egLink.sync(progress, held -> {
                var builds = new java.util.LinkedHashSet<BuildKey>(libraryBuilds.get());
                builds.addAll(openedBuilds);
                return keptEgLink(held, builds, chosenEgLink);
            }, token).join();
            submit(() -> {
                preferences.put("checked.EG_LINK", Instant.now().toString());
                loadEgLink(); revision.incrementAndGet();
            }).join();
            if (!result.issues().isEmpty()) throw new IllegalStateException(String.join("; ", result.issues()));
        });
    }

    public CompletableFuture<Void> checkEgLink() {
        return egLinkJob((token, progress) -> {
            var catalog = egLink.check(token).join();
            submit(() -> {
                preferences.put("checked.EG_LINK", catalog.checkedAt().toString());
                preferences.put("attempt.EG_LINK", Instant.now().toString());
                loadEgLink(); revision.incrementAndGet();
            }).join();
        });
    }

    public CompletableFuture<Void> downloadEgLink(com.vibeminer.eglink.EgLinkTypes.Build build) {
        return egLinkJob((token, progress) -> {
            chosenEgLink.add(egLink.download(build, progress, token).join().id());
            submit(() -> { loadEgLink(); revision.incrementAndGet(); }).join();
        });
    }

    public CompletableFuture<Void> prepareEgLink(String sourceId) {
        return egLinkJob((token, progress) -> {
            chosenEgLink.add(sourceId);
            egLink.prepare(sourceId, progress, token).join();
            submit(() -> { loadEgLink(); revision.incrementAndGet(); }).join();
        });
    }

    @FunctionalInterface private interface EgWork {
        void run(com.vibeminer.network.Cancellation token, Consumer<com.vibeminer.eglink.EgLinkTypes.Progress> progress) throws Exception;
    }
    private CompletableFuture<Void> egLinkJob(EgWork work) {
        return job(Id.EG_LINK, (generated, progress) -> {
            if (egLink == null) throw new IllegalStateException("EG Link provider is unavailable");
            var token = new com.vibeminer.network.Cancellation();
            synchronized (this) { networkTokens.put(Id.EG_LINK, token); if (generated.isCanceled()) token.cancel(); }
            try {
                work.run(token, value -> progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.DOWNLOADING,
                        value.done(), value.total(), "files", value.message())));
            } finally { synchronized (this) { networkTokens.remove(Id.EG_LINK); } }
        });
    }

    public PolestarState polestar() { return polestarState; }

    private void loadPolestar() {
        try {
            polestarState = new PolestarState(polestar.catalog(com.vibeminer.network.Cancellation.NONE).join(),
                    polestar.snapshots(com.vibeminer.network.Cancellation.NONE).join(), null);
        } catch (RuntimeException failure) {
            var previous = polestarState;
            polestarState = new PolestarState(previous.catalog(), previous.snapshots(), message(failure));
        }
    }

    public CompletableFuture<Void> syncPolestar() {
        return polestarJob((network, progress) -> {
            polestar.sync(progress, network).join();
            var checked = polestar.catalog(network).join().orElseThrow().checkedAt();
            submit(() -> preferences.put("checked.POLESTAR", checked.toString())).join();
        });
    }

    public CompletableFuture<Void> checkPolestar() {
        return polestarJob((network, progress) -> {
            var value = polestar.check(network).join();
            submit(() -> {
                preferences.put("checked.POLESTAR", value.checkedAt().toString());
                preferences.put("latest.POLESTAR", value.version());
            }).join();
        });
    }

    public CompletableFuture<Void> downloadPolestar() {
        return polestarJob((network, progress) -> {
            var value = polestarState.catalog().orElseThrow(() -> new IllegalStateException("Check Polestar versions first"));
            polestar.download(value, progress, network).join();
        });
    }

    public CompletableFuture<Void> preparePolestar(String sourceId) {
        return polestarJob((network, progress) -> polestar.prepare(sourceId, progress, network).join());
    }

    @FunctionalInterface
    private interface PolestarWork {
        void run(com.vibeminer.network.Cancellation token,
                Consumer<com.vibeminer.polestar.PolestarTypes.Progress> progress) throws Exception;
    }

    private CompletableFuture<Void> polestarJob(PolestarWork work) {
        return job(Id.POLESTAR, (token, progress) -> {
            var network = new com.vibeminer.network.Cancellation();
            synchronized (this) {
                networkTokens.put(Id.POLESTAR, network);
                if (token.isCanceled()) network.cancel();
            }
            try {
                work.run(network, value -> progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.IMPORTING,
                        value.done(), value.total(), "records", value.message())));
            } finally {
                synchronized (this) { networkTokens.remove(Id.POLESTAR); }
                submit(() -> { loadPolestar(); revision.incrementAndGet(); }).join();
            }
        });
    }

    private static com.vibeminer.network.PolestarClient unavailablePolestar() {
        return new com.vibeminer.network.PolestarClient() {
            public CompletableFuture<com.vibeminer.network.NetworkTypes.PolestarReply> fetchPolestarHead(com.vibeminer.network.Cancellation token) {
                return CompletableFuture.failedFuture(new IllegalStateException("Polestar network provider is unavailable"));
            }
            public CompletableFuture<com.vibeminer.network.NetworkTypes.PolestarReply> fetchPolestarFile(String revision, String path, com.vibeminer.network.Cancellation token) {
                return CompletableFuture.failedFuture(new IllegalStateException("Polestar network provider is unavailable"));
            }
        };
    }

    public TactState tactState() { return tactState; }

    com.vibeminer.files.keys.TactKeys tactKeys() {
        return policy(Id.TACT_KEYS).enabled() ? tactKeys : com.vibeminer.files.keys.TactKeys.EMPTY;
    }

    private void loadTactKeys() {
        String document = preferences.get("tact.document", "");
        if (document.isEmpty() || tactReader == null) return;
        try {
            var key = com.vibeminer.store.ObjectKey.parse(com.vibeminer.store.ObjectKey.HISTORY_DOCUMENT, document);
            var read = tactReader.read(key).join();
            if (read.state() != com.vibeminer.store.StoreTypes.ReadState.PRESENT) throw new IllegalStateException("Cached TACT key list is unavailable");
            byte[] bytes = read.bytes();
            var parsed = com.vibeminer.files.keys.TactKeys.parse(bytes);
            tactKeys = parsed;
            tactState = new TactState(parsed.count(), com.vibeminer.files.keys.TactKeys.hash(bytes), bytes.length, null);
        } catch (RuntimeException error) {
            tactState = new TactState(tactKeys.count(), tactState.fingerprint(), tactState.bytes(), message(error));
        }
    }

    com.vibeminer.files.keys.TactKeys tactKeysForBuild(com.vibeminer.access.Cancellation token) {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (!policy(Id.TACT_KEYS).enabled()) return com.vibeminer.files.keys.TactKeys.EMPTY;
            pending = fullSyncs.getOrDefault(Id.TACT_KEYS, jobs.get(Id.TACT_KEYS));
            if (pending == null && tactClient != null && !closing) {
                var checks = policy(Id.TACT_KEYS).checks();
                boolean due = checks == Checks.STARTUP && startupChecked.add(Id.TACT_KEYS)
                        || checks == Checks.DAILY && dailyDue(Id.TACT_KEYS);
                if (due) {
                    // The shared job reserves this update before another build can request it.
                    pending = updateTactKeys();
                }
            }
        }
        if (pending != null) {
            while (!pending.isDone()) {
                token.check();
                try { pending.get(100, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException waiting) { /* Poll cancellation independently of shared source work. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                catch (java.util.concurrent.ExecutionException failure) { break; }
            }
        }
        token.check();
        return tactKeys();
    }

    private boolean dailyDue(Id id) {
        try { return !Instant.parse(preferences.get("attempt." + id, Instant.EPOCH.toString())).plus(Duration.ofDays(1)).isAfter(Instant.now()); }
        catch (java.time.format.DateTimeParseException invalid) { return true; }
    }

    public CompletableFuture<Void> updateTactKeys() {
        return job(Id.TACT_KEYS, (token, progress) -> {
            if (tactClient == null || tactReader == null || tactWriter == null) throw new IllegalStateException("TACT key provider is unavailable");
            submit(() -> preferences.put("attempt.TACT_KEYS", Instant.now().toString())).join();
            var network = new com.vibeminer.network.Cancellation();
            synchronized (this) {
                networkTokens.put(Id.TACT_KEYS, network);
                if (token.isCanceled()) network.cancel();
            }
            try {
                progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.DISCOVERING, 0, null, "source", "Checking public TACT keys…"));
                var reply = tactClient.fetchTactKeys(network).join();
                token.check();
                byte[] bytes = reply.bytes();
                progress.accept(new QuestieTypes.Progress(QuestieTypes.Stage.VERIFYING, bytes.length, (long) bytes.length, "bytes", "Validating public TACT keys…"));
                var parsed = com.vibeminer.files.keys.TactKeys.parse(bytes);
                var key = new com.vibeminer.store.ObjectKey(com.vibeminer.store.ObjectKey.HISTORY_DOCUMENT,
                        java.security.MessageDigest.getInstance("MD5").digest(bytes));
                var stored = tactWriter.put(List.of(new com.vibeminer.store.StoreTypes.PutObject(key, bytes.length, bytes,
                        com.vibeminer.store.StoreTypes.Origin.UNKNOWN)), true, com.vibeminer.store.Cancellation.NONE).join().getFirst();
                if (stored.outcome() != com.vibeminer.store.StoreTypes.PutOutcome.STORED
                        && stored.outcome() != com.vibeminer.store.StoreTypes.PutOutcome.ALREADY_PRESENT)
                    throw new IllegalStateException("TACT key list could not be stored");
                token.check();
                submit(() -> {
                    token.check();
                    preferences.put("tact.document", key.hex());
                    preferences.put("checked.TACT_KEYS", reply.observedAt().toString());
                    preferences.put("attempt.TACT_KEYS", java.time.Instant.now().toString());
                    tactKeys = parsed;
                    tactState = new TactState(parsed.count(), com.vibeminer.files.keys.TactKeys.hash(bytes), bytes.length, null);
                    revision.incrementAndGet();
                }).join();
            } finally {
                synchronized (this) { networkTokens.remove(Id.TACT_KEYS); }
            }
        });
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final java.util.Set<com.vibeminer.access.Cancellation> nameAcquisitions =
            new java.util.HashSet<>();

    synchronized Registration nameAcquisition(com.vibeminer.access.Cancellation token) {
        if (closing || !policy(Id.LISTFILE).enabled()) token.cancel();
        if (nameAcquisitions.size() >= 64)
            throw new IllegalStateException("Too many listfile consumers");
        nameAcquisitions.add(token);
        return () -> {
            synchronized (DataSourcesService.this) {
                nameAcquisitions.remove(token);
            }
        };
    }

    public synchronized void cancel(Id id) {
        cancelEpoch.merge(id, 1L, Long::sum);
        if (id == Id.LISTFILE) for (var acquisition : nameAcquisitions) acquisition.cancel();
        var network = networkTokens.get(id);
        if (network != null) network.cancel();
        var token = tokens.get(id);
        if (token != null) token.cancel();
        var access = accessTokens.get(id);
        if (access != null) access.cancel();
    }

    @FunctionalInterface
    private interface Work {
        void run(
                com.vibeminer.generated.Cancellation token,
                Consumer<QuestieTypes.Progress> progress)
                throws Exception;
    }

    private synchronized CompletableFuture<Void> job(Id id, Work work) {
        if (closing || !policy(id).enabled())
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Enable this source first"));
        if (jobs.containsKey(id))
            return CompletableFuture.failedFuture(new IllegalStateException("This source is busy"));
        var token = new com.vibeminer.generated.Cancellation();
        var done = new CompletableFuture<Void>();
        jobs.put(id, done);
        tokens.put(id, token);
        activity.put(id, new Activity(true, "Starting…", -1, null));
        revision.incrementAndGet();
        Thread.startVirtualThread(
                () -> {
                    Throwable failure = null;
                    try {
                        work.run(
                                token,
                                p -> {
                                    activity.put(
                                            id,
                                            new Activity(
                                                    true,
                                                    p.detail(),
                                                    p.total() == null || p.total() == 0
                                                            ? -1
                                                            : (double) p.done() / p.total(),
                                                    null));
                                    revision.incrementAndGet();
                                });
                    } catch (Throwable error) {
                        failure = error;
                    }
                    synchronized (this) {
                        jobs.remove(id);
                        tokens.remove(id);
                    }
                    activity.put(
                            id,
                            new Activity(
                                    false,
                                    token.isCanceled()
                                            ? "Canceled; completed data retained"
                                            : failure == null ? "Completed" : null,
                                    -1,
                                    failure == null || token.isCanceled()
                                            ? null
                                            : message(failure)));
                    revision.incrementAndGet();
                    if (failure == null) done.complete(null);
                    else done.completeExceptionally(failure);
                });
        return done.copy();
    }

    private final java.util.Set<Id> startupChecked = ConcurrentHashMap.newKeySet();

    private void checkDue() {
        for (Id id : List.of(Id.QUESTIE, Id.LISTFILE, Id.TACT_KEYS, Id.EG_LINK, Id.POLESTAR)) {
            if (id == Id.TACT_KEYS) {
                // The same reservation path serves startup and build preparation.
                synchronized (this) {
                    var value = policy(id);
                    if (!closing && tactClient != null && value.enabled() && !jobs.containsKey(id) && !fullSyncs.containsKey(id)
                            && (value.checks() == Checks.DAILY && dailyDue(id)
                                || value.checks() == Checks.STARTUP && startupChecked.add(id))) updateTactKeys();
                }
                continue;
            }
            var policy = policy(id);
            if (!policy.enabled() || policy.checks() == Checks.MANUAL || activity(id).busy())
                continue;
            if (policy.checks() == Checks.STARTUP && !startupChecked.add(id)) continue;
            if (policy.checks() == Checks.DAILY) {
                try {
                    if (Instant.parse(preferences.get("attempt." + id, Instant.EPOCH.toString()))
                            .plus(Duration.ofDays(1))
                            .isAfter(Instant.now())) continue;
                } catch (java.time.format.DateTimeParseException ignored) {
                    continue;
                }
            }
            submit(() -> preferences.put("attempt." + id, Instant.now().toString()))
                    .thenRun(
                            () -> {
                                sync(id);
                            });
        }
    }

    @FunctionalInterface
    private interface Command {
        void run() throws Exception;
    }

    private CompletableFuture<Void> submit(Command command) {
        var result = new CompletableFuture<Void>();
        synchronized (this) {
            if (closing)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Data sources closed"));
            try {
                worker.execute(
                        () -> {
                            Throwable failure = null;
                            try {
                                command.run();
                            } catch (Throwable error) {
                                failure = error;
                            }
                            var error = failure;
                            Thread.startVirtualThread(
                                    () -> {
                                        if (error == null) result.complete(null);
                                        else result.completeExceptionally(error);
                                    });
                        });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed != null) return closed.copy();
        closing = true;
        closed = new CompletableFuture<>();
        timer.shutdownNow();
        for (var token : tokens.values()) token.cancel();
        for (var access : accessTokens.values()) access.cancel();
        for (var network : networkTokens.values()) network.cancel();
        for (var acquisition : nameAcquisitions) acquisition.cancel();
        var accepted = java.util.stream.Stream.concat(jobs.values().stream(), fullSyncs.values().stream()).toArray(CompletableFuture[]::new);
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        CompletableFuture.allOf(accepted).handle((v, e) -> null).join();
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        CompletableFuture.allOf(questie.closeAsync(), polestar.closeAsync(), egLink == null ? CompletableFuture.completedFuture(null) : egLink.closeAsync()).join();
                        closed.complete(null);
                    } catch (Throwable error) {
                        closed.completeExceptionally(error);
                    }
                });
        return closed.copy();
    }

    private static String message(Throwable error) {
        while (error.getCause() != null
                && (error instanceof java.util.concurrent.CompletionException
                        || error instanceof java.util.concurrent.ExecutionException))
            error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
