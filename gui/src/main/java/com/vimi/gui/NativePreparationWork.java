package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.Builds;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreaturesService;
import com.vibeminer.concepts.effects.BuildItemEffects;
import com.vibeminer.concepts.effects.ItemEffectsService;
import com.vibeminer.concepts.items.BuildItems;
import com.vibeminer.concepts.items.ItemsService;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileSystem;
import com.vibeminer.files.FileSystemOptions;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.files.names.LoadedNames;
import com.vibeminer.files.names.NamesTypes;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.generated.TactKeyStatus;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.BuildTactKeys;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.TablesOptions;
import com.vibeminer.formats.tables.TablesService;
import com.vibeminer.generated.GeneratedStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One retained build/locale workspace; public data methods never acquire media. */
public final class NativePreparationWork implements BuildPreparation.Work {
    private BuildUsageAccounting usageAccounting;
    private Runnable lifecycle = () -> {};

    NativePreparationWork withLifecycle(Runnable value) {
        if (closed.get()) {
            throw new IllegalStateException("Workspace is already closed");
        }
        lifecycle = java.util.Objects.requireNonNull(value);
        return this;
    }

    private volatile com.vibeminer.files.keys.TactKeys effectiveKeySet = com.vibeminer.files.keys.TactKeys.EMPTY;
    private com.vibeminer.files.keys.TactKeys communityKeys = com.vibeminer.files.keys.TactKeys.EMPTY;
    private boolean communityKeysAllowed;
    private DataSourcesService sourcePolicies;
    private boolean listfileAllowed = true, definitionsAllowed = true;
    private ClientHotfixStore hotfixImports;
    private CompletableFuture<ClientHotfixStore.Snapshot> hotfixLoad;
    private Cancellation hotfixCancellation;
    private volatile ClientHotfixStore.Snapshot hotfixSnapshot;
    private volatile String hotfixStatus = "Base DB2 data · no imported client cache";
    java.util.function.Consumer<ClientHotfixStore.Snapshot> hotfixAdoptionProbe = value -> {};

    NativePreparationWork withHotfixImports(ClientHotfixStore value) {
        hotfixImports = java.util.Objects.requireNonNull(value);
        return this;
    }

    ClientHotfixStore.Scope hotfixScope() {
        return new ClientHotfixStore.Scope(ref, locale, info.version());
    }

    private volatile String hotfixSha, refreshedHotfixSha;
    private volatile boolean hotfixWatched;

    String hotfixStatus() {
        String status = hotfixStatus;
        if (hotfixSha != null && hotfixWatched) status += " · checked on Refresh sources";
        if (hotfixRefreshPending())
            status += "\nRefresh sources imported a newer local cache for this build; reopen to apply it.";
        return status;
    }

    /** Whether the loaded scope's original is checked by Refresh sources. */
    boolean hotfixWatched() { return hotfixWatched; }

    void hotfixWatched(boolean value) { hotfixWatched = value; }

    /** A newer verified import is bound; this open workspace keeps its loaded snapshot. */
    boolean hotfixRefreshPending() {
        String loaded = hotfixSha, refreshed = refreshedHotfixSha;
        return loaded != null && refreshed != null && !loaded.equals(refreshed);
    }

    /**
     * Records a refresh-bound import. The comparison is made when read, so a snapshot whose
     * adoption completes after this note is still compared; equal snapshots need no reopen.
     */
    void noteRefreshedHotfix(String sha256) {
        refreshedHotfixSha = java.util.Objects.requireNonNull(sha256);
    }

    boolean canImportHotfix() {
        try { hotfixScope(); return true; }
        catch (IllegalArgumentException | NullPointerException unavailable) { return false; }
    }

    private void ensureHotfix(Cancellation token) {
        if (hotfixImports == null) return;
        if (!canImportHotfix()) {
            hotfixStatus = "Client cache needs an exact build version; using base data";
            return;
        }
        CompletableFuture<ClientHotfixStore.Snapshot> load;
        Cancellation child;
        boolean start;
        synchronized (handlesGate) {
            if (closed.get()) throw new java.util.concurrent.CancellationException();
            start = hotfixLoad == null;
            if (start) {
                hotfixLoad = new CompletableFuture<>();
                hotfixCancellation = new Cancellation();
            }
            load = hotfixLoad;
            child = hotfixCancellation;
        }
        if (start) {
            try {
                var registration = token.onCancel(child::cancel);
                hotfixImports.open(hotfixScope(), child).whenComplete((snapshot, error) -> {
                    registration.close();
                    if (error != null) {
                        failHotfixLoad(load, error);
                        return;
                    }
                    // Retain the returned owner before reading any fallible metadata. Close
                    // can then account for it even if adoption fails or the store retires it.
                    hotfixSnapshot = snapshot;
                    try {
                        if (snapshot != null) hotfixAdoptionProbe.accept(snapshot);
                        if (closed.get()) {
                            if (snapshot == null) load.complete(null);
                            else snapshot.closeAsync().whenComplete((ignored, cleanup) -> {
                                if (cleanup != null) failHotfixLoad(load, cleanup);
                                else load.complete(null);
                            });
                            return;
                        }
                        if (snapshot != null) {
                            var index = snapshot.index();
                            hotfixSha = index.sha256();
                            hotfixWatched = snapshot.watched();
                            hotfixStatus = "Local hotfix cache · build " + index.build() + " · "
                                    + locale + " · " + index.entries().size() + " cached operations · "
                                    + index.sha256().substring(0, 16);
                        }
                        load.complete(snapshot);
                    } catch (RuntimeException | Error failure) {
                        if (snapshot == null) failHotfixLoad(load, failure);
                        else {
                            try {
                                snapshot.closeAsync().whenComplete((ignored, cleanup) -> {
                                    if (cleanup != null && cleanup != failure) failure.addSuppressed(cleanup);
                                    failHotfixLoad(load, failure);
                                });
                            } catch (RuntimeException | Error cleanup) {
                                if (cleanup != failure) failure.addSuppressed(cleanup);
                                failHotfixLoad(load, failure);
                            }
                        }
                    }
                });
            } catch (RuntimeException | Error error) {
                failHotfixLoad(load, error);
            }
        }
        load.join();
        token.check();
        if (closed.get()) throw new java.util.concurrent.CancellationException();
    }

    private void failHotfixLoad(CompletableFuture<ClientHotfixStore.Snapshot> load, Throwable error) {
        hotfixStatus = "Imported client hotfix cache unavailable; reimport it";
        synchronized (handlesGate) {
            if (!closed.get() && hotfixLoad == load) hotfixLoad = null;
        }
        load.completeExceptionally(error);
    }

    NativePreparationWork withSourcePolicy(boolean listfile, boolean definitions) {
        listfileAllowed = listfile; definitionsAllowed = definitions; return this;
    }

    NativePreparationWork withSourcePolicies(DataSourcesService sources) {
        sourcePolicies = sources;
        communityKeysAllowed = sources != null && sources.policy(DataSourcesService.Id.TACT_KEYS).enabled();
        return withSourcePolicy(sources == null || sources.policy(DataSourcesService.Id.LISTFILE).enabled(),
                sources == null || sources.policy(DataSourcesService.Id.DEFINITIONS).enabled());
    }

    /** Immutable local comparison key snapshot; never requests a source refresh. */
    NativePreparationWork withPinnedCommunityKeys(com.vibeminer.files.keys.TactKeys snapshot) {
        communityKeys = java.util.Objects.requireNonNull(snapshot);
        communityKeysAllowed = false;
        sourcePolicies = null;
        rebuildEffectiveKeys();
        return this;
    }

    record CachedTableInventory(String preparationIdentity, String definitionIdentity,
            TableTypes.PreloadResult result) {}

    /** Restore only the exact existing prepareKnown publication; never prepares missing tables. */
    CachedTableInventory cachedTableInventory(Cancellation token) {
        token.check();
        if (!definitionsAllowed || opened == null || fileHandle == null) return null;
        String identity = tableCacheIdentity();
        var result = new com.vibeminer.formats.tables.PreparedTablesCache(generated)
                .restore(identity, ignored -> {}, token).join();
        token.check();
        if (result == null) return null;
        return new CachedTableInventory(identity,
                BundledDefinitions.REVISION + ":" + BundledDefinitions.MANIFEST_SHA256
                        + ":" + BundledDefinitions.SOURCES_SHA256
                        + ":" + com.vibeminer.formats.definitions.SupplementalDefinitions.IDENTITY, result);
    }

    NativePreparationWork withAccounting(OverviewAccounting value) {
        if (value != null)
            usageAccounting = new BuildUsageAccounting(value, info, ref, locale, community);
        return this;
    }

    @Override
    public BuildAccounting accounting() {
        return usageAccounting == null ? null : usageAccounting.view();
    }

    private PreparationHistory history;

    NativePreparationWork withHistory(PreparationHistory value) {
        history = value;
        return this;
    }

    private java.util.function.Consumer<BuildPreparation.Ready> assessment = ignored -> {};

    NativePreparationWork withAssessment(java.util.function.Consumer<BuildPreparation.Ready> listener) {
        assessment = java.util.Objects.requireNonNull(listener);
        return this;
    }

    @Override
    public void assessed(BuildPreparation.Ready ready) {
        if (history != null) history.record(info, ref, locale, ready);
        if (ready == BuildPreparation.Ready.METADATA_READY && usageAccounting != null)
            usageAccounting.retain();
        if (ready == BuildPreparation.Ready.TABLES_READY && usageAccounting != null)
            usageAccounting.workspace(published.tables(), published.items());
        assessment.accept(ready);
    }

    private final java.nio.file.Path indexDirectory;
    private final com.vibeminer.concepts.world.TerrainAtlasPool terrainAtlases;
    private final BuildInfo info;
    private final BuildTypes.BuildRef ref;
    private final String locale;
    private final Builds builds;
    private final ObjectSource objects;
    private final ObjectSource localObjects;
    private final FileNames names;
    private final GeneratedStore generated;
    private final CommunityFileNames community;
    private final FileSystem files;
    private final AtomicReference<BuildTactKeys.KeySet> keys = new AtomicReference<>();
    private final AtomicBoolean namesEnabled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile com.vibeminer.builds.Build opened;
    private volatile BuildFiles fileHandle;
    private final Object handlesGate = new Object();

    private record Workspace(
            BuildTables tables,
            TablesService service,
            BuildItems items,
            BuildCreatures creatures,
            BuildItemEffects effects,
            com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races,
            com.vibeminer.concepts.world.BuildWorld world,
            com.vibeminer.concepts.mounts.BuildMounts mounts,
            com.vibeminer.concepts.professions.BuildProfessions professions,
            com.vibeminer.concepts.dungeons.BuildDungeons dungeons,
            com.vibeminer.concepts.locations.BuildLocationMaps locationMaps,
            long revision) {}

    private volatile Workspace published = new Workspace(null, null, null, null, null, null, null, null, null, null, null, null, 0);
    private Workspace pending;
    private boolean pendingWarmed;
    private boolean tablePassAttempted;
    private final List<Workspace> retired = new ArrayList<>();
    private final List<CompletableFuture<Void>> retirementJobs = new ArrayList<>();
    private final java.util.Set<Workspace> retiring =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public NativePreparationWork(
            BuildInfo info,
            BuildInfo.RegionEvidence source,
            String locale,
            Builds builds,
            ObjectSource objects,
            GeneratedStore generated,
            CommunityFileNames community,
            ObjectSource localObjects) {
        this(info, source, locale, builds, objects, generated, community, localObjects, null);
    }

    public NativePreparationWork(
            BuildInfo info,
            BuildInfo.RegionEvidence source,
            String locale,
            Builds builds,
            ObjectSource objects,
            GeneratedStore generated,
            CommunityFileNames community,
            ObjectSource localObjects,
            java.nio.file.Path indexDirectory) {
        this(info, source, locale, builds, objects, generated, community, localObjects,
                indexDirectory, null);
    }

    public NativePreparationWork(
            BuildInfo info,
            BuildInfo.RegionEvidence source,
            String locale,
            Builds builds,
            ObjectSource objects,
            GeneratedStore generated,
            CommunityFileNames community,
            ObjectSource localObjects,
            java.nio.file.Path indexDirectory,
            com.vibeminer.concepts.world.TerrainAtlasPool terrainAtlases) {
        this.terrainAtlases = terrainAtlases;
        this.indexDirectory = indexDirectory;
        this.info = info;
        this.ref =
                new BuildTypes.BuildRef(
                        info.product(), info.config(), source.cdnConfig(), source.region());
        this.localObjects = java.util.Objects.requireNonNull(localObjects);
        if (objects == localObjects)
            throw new IllegalArgumentException("Prepared tables require an isolated local source");
        this.locale = locale;
        this.builds = builds;
        this.objects = objects;
        this.generated = generated;
        this.community = community;
        names =
                new FileNames() {
                    public CompletableFuture<NamesTypes.FileNamesStatus> status(
                            Cancellation token) {
                        return namesEnabled.get()
                                ? community.status(token)
                                : FileNames.OFF.status(token);
                    }

                    public CompletableFuture<NamesTypes.FileNamesStatus> update(
                            boolean force, Cancellation token) {
                        return namesEnabled.get()
                                ? community.update(force, token)
                                : FileNames.OFF.update(force, token);
                    }

                    public CompletableFuture<LoadedNames> current(Cancellation token) {
                        return namesEnabled.get()
                                ? community.current(token)
                                : FileNames.OFF.current(token);
                    }
                };
        var defaults = FileSystemOptions.defaults();
        files =
                new FileSystem(
                        builds,
                        objects,
                        new FileSystemOptions(
                                defaults.presenceBatch(),
                                defaults.progressInterval(),
                                defaults.clock(),
                                defaults.maxReadBytes(),
                                name -> {
                                    return effectiveKeySet.find(name);
                                },
                                names));
    }

    /** The generated (derived artifact) store shared by this build's components; may be null. */
    public GeneratedStore generatedHandle() {
        return generated;
    }

    public BuildFiles filesHandle() {
        return fileHandle;
    }

    /** Selected-item media request; never used by preparation or data-only reads. */
    public CompletableFuture<com.vibeminer.formats.images.BlpImageTypes.Result> icon(
            long fileDataId, Cancellation token) {
        return new BlpImages(generated)
                .decodeLargestWithin(
                        filesHandle(),
                        com.vibeminer.files.FileTypes.FileRef.id(fileDataId),
                        64,
                        token);
    }

    public com.vibeminer.concepts.races.BuildRaces racesHandle() { return published.races(); }

    public com.vibeminer.concepts.talents.BuildTalents talentsHandle() {
        return published.talents();
    }

    public com.vibeminer.concepts.world.BuildWorld worldHandle() { return published.world(); }

    public com.vibeminer.concepts.mounts.BuildMounts mountsHandle() { return published.mounts(); }

    public com.vibeminer.concepts.professions.BuildProfessions professionsHandle() { return published.professions(); }

    public com.vibeminer.concepts.dungeons.BuildDungeons dungeonsHandle() { return published.dungeons(); }

    /** Resolve only a selected recipe's item reference using prepared item data. */
    public CompletableFuture<String> itemName(long id, Cancellation token) {
        var items = itemsHandle();
        if (items == null) return CompletableFuture.completedFuture(null);
        return items.getData(id, TableTypes.Priority.INTERACTIVE, token)
                .thenApply(result -> {
                    var detail = result.detail();
                    var name = detail == null || detail.summary() == null ? null : detail.summary().name();
                    return name == null ? null : name.value();
                });
    }

    public com.vibeminer.concepts.locations.BuildLocationMaps locationMapsHandle() { return published.locationMaps(); }

    public BuildTables tablesHandle() {
        return published.tables();
    }

    public BuildItems itemsHandle() {
        return published.items();
    }

    public BuildCreatures creaturesHandle() {
        return published.creatures();
    }

    public BuildItemEffects effectsHandle() {
        return published.effects();
    }

    public String locale() {
        return locale;
    }

    public long revision() {
        return published.revision();
    }

    @Override
    public BuildTypes.BuildMetadata metadata(Cancellation token) {
        var result = builds.metadata(ref, token).join();
        if (result.state() != BuildTypes.BuildOpenState.OPEN || result.metadata() == null)
            throw new IllegalStateException(
                    "Metadata " + result.state() + ": " + result.code() + " " + result.message());
        if (!result.metadata().locales().contains(locale))
            throw new IllegalStateException("Locale is not in this build: " + locale);
        return result.metadata();
    }

    @Override
    public BuildPreparation.FilesSummary files(
            Consumer<BuildPreparation.Counter> progress, Cancellation token) {
        long started = System.nanoTime();
        progress.accept(new BuildPreparation.Counter(0, null, "Opening file resolution indexes"));
        openFileHandles(token);
        long indexesReady = System.nanoTime();
        var cache = new com.vibeminer.files.PreparedFileCatalog(generated);
        var identity =
                new com.vibeminer.files.PreparedFileCatalog.Identity(
                        ref, locale, opened.metadata());
        progress.accept(new BuildPreparation.Counter(0, null, "Restoring local file catalog"));
        var catalog = cache.restore(identity, token).join();
        if (catalog == null) {
            long total = 0;
            for (long ignored : opened.fileDataIds(locale)) {
                token.check();
                total++;
            }
            progress.accept(new BuildPreparation.Counter(0, total, "files"));
            catalog =
                    cache.prepare(
                                    opened,
                                    identity,
                                    total,
                                    (done, count) ->
                                            progress.accept(
                                                    new BuildPreparation.Counter(
                                                            done, count, "files")),
                                    token)
                            .join();
        } else {
            progress.accept(
                    new BuildPreparation.Counter(catalog.files(), catalog.files(), "cached files"));
        }
        token.check();
        System.getLogger(NativePreparationWork.class.getName())
                .log(
                        System.Logger.Level.INFO,
                        "Files {0}: {1} records; resolution indexes {2} ms; catalog {3} ms",
                        catalog.restored() ? "restored" : "prepared",
                        catalog.files(),
                        (indexesReady - started) / 1_000_000,
                        (System.nanoTime() - indexesReady) / 1_000_000);
        if (usageAccounting != null) usageAccounting.files(opened, catalog);
        return new BuildPreparation.FilesSummary(catalog.files(), 0, catalog.unknownSize(), false);
    }

    private void rebuildEffectiveKeys() {
        var values = new java.util.HashMap<Long, byte[]>();
        for (String name : communityKeys.fingerprints().keySet()) {
            long value = Long.parseUnsignedLong(name, 16);
            values.put(value, communityKeys.find(value));
        }
        var buildKeys = keys.get();
        if (buildKeys != null) for (String name : buildKeys.names()) {
            long value = Long.parseUnsignedLong(name, 16);
            values.put(value, buildKeys.find(value));
        }
        effectiveKeySet = new com.vibeminer.files.keys.TactKeys(values);
    }

    private com.vibeminer.files.keys.TactKeys effectiveKeys() { return effectiveKeySet; }

    TablesOptions tablesOptions(TactKeyStatus status, TableRole role) {
        var defaults =
                new TablesOptions(
                        (build, cancellation) -> {
                            cancellation.check();
                            return CompletableFuture.completedFuture(info.version());
                        });
        var options =
                new TablesOptions(
                                defaults.buildVersion(),
                                defaults.limits(),
                                build -> status,
                                defaults.scheduler(),
                                defaults.maxScanRows(),
                                defaults.maxReferenceSources(),
                                defaults.maxReferenceScanRows())
                        .withLocalDefinitionsOnly();
        var snapshot = hotfixSnapshot;
        if (role.hotfix && snapshot != null) {
            options = options.withHotfix(new com.vibeminer.formats.tables.HotfixBinding(
                    ref, locale, info.version(), snapshot.source(), snapshot.index()));
        }
        return options;
    }

    /** What a table handle is for; decides source access and whether the hotfix overlay applies. */
    enum TableRole {
        /** Key bootstrap and known-table preparation: configured sources, effective rows. */
        PREPARATION(true, true),
        /** The published workspace every view reads: local objects only, effective rows. */
        WORKSPACE(false, true),
        /** Prepared-only key bootstrap and local comparison: local objects, physical rows only. */
        LOCAL_BASE(false, false);

        final boolean online, hotfix;

        TableRole(boolean online, boolean hotfix) {
            this.online = online;
            this.hotfix = hotfix;
        }
    }

    private TablesService tablesService(Cancellation token, TableRole role) {
        var definitions = new TableDefinitions(definitionsAllowed ? BundledDefinitions.open(token).join() : BundledDefinitions.of(List.of(), java.util.Map.of()), null, null);
        if (definitionsAllowed) definitions = definitions.withSupplementalDefinitions();
        var available = effectiveKeys();
        var status = new TactKeyStatus(available.fingerprints());
        var options = tablesOptions(status, role);
        // Keep decoder keys and generated-table fingerprints from the same immutable snapshot.
        var fileDefaults = FileSystemOptions.defaults();
        var retained =
                java.util.Objects.requireNonNull(opened, "Files must be prepared before tables");
        Builds pinnedBuilds =
                (requested, cancellation) -> {
                    cancellation.check();
                    if (!ref.equals(requested))
                        return CompletableFuture.failedFuture(
                                new IllegalArgumentException("Different build context"));
                    return CompletableFuture.completedFuture(
                            new BuildTypes.BuildOpenResult(
                                    ref,
                                    BuildTypes.BuildOpenState.OPEN,
                                    retained,
                                    null,
                                    null,
                                    null,
                                    null));
                };
        ObjectSource tableObjects =
                role.online ? objects : new LocalMetadataSource(localObjects);
        var pinnedFiles =
                new FileSystem(
                        pinnedBuilds,
                        tableObjects,
                        new FileSystemOptions(
                                fileDefaults.presenceBatch(),
                                fileDefaults.progressInterval(),
                                fileDefaults.clock(),
                                fileDefaults.maxReadBytes(),
                                name -> available == null ? null : available.find(name),
                                names));
        return new TablesService(pinnedFiles, definitions, new GeneratedTables(generated), options);
    }

    private BuildTables openTables(TablesService service, Cancellation token) {
        var open = service.forBuild(ref, locale, token).join();
        if (open.state() != FileTypes.FilesOpenState.OPEN || open.tables() == null)
            throw new IllegalStateException(
                    "Table context " + open.state() + ": " + open.message());
        return open.tables();
    }

    @Override
    public List<String> keys(Cancellation token) {
        var issues = new ArrayList<String>();
        ensureHotfix(token);
        if (communityKeysAllowed && sourcePolicies != null) {
            communityKeys = sourcePolicies.tactKeysForBuild(token);
            rebuildEffectiveKeys();
            var problem = sourcePolicies.activity(DataSourcesService.Id.TACT_KEYS).problem();
            if (problem != null) issues.add("Community TACT keys: " + problem);
        }
        if (!definitionsAllowed) {
            issues.add("Table definitions are disabled for this workspace");
            return List.copyOf(issues);
        }
        try (var bootstrap = openTables(tablesService(token, TableRole.PREPARATION), token)) {
            var found = BuildTactKeys.load(bootstrap, TableTypes.Priority.BACKGROUND, token).join();
            applyBuildKeys(withDeliveredKeys(bootstrap, found, token), issues, token);
        }
        return List.copyOf(issues);
    }

    /**
     * Joins the TactKey blocks the effective client-cache rows delivered into {@code found} under
     * the TactKey join rule. A failure to read them only means fewer keys, never a key-loading
     * error; cancellation still propagates.
     */
    static BuildTactKeys.KeySet withDeliveredKeys(
            BuildTables tables, BuildTactKeys.KeySet found, Cancellation token) {
        List<com.vibeminer.formats.hotfix.HotfixRowDecoder.OptionalKey> delivered;
        try {
            delivered = tables.deliveredKeys(TableTypes.Priority.BACKGROUND, token).join();
        } catch (java.util.concurrent.CompletionException | IllegalStateException e) {
            token.check();
            return found;
        }
        try {
            return BuildTactKeys.withDelivered(found, delivered);
        } finally {
            // The key set holds its own copies; the delivered blocks are not needed any more.
            for (var block : delivered) {
                block.clear();
            }
        }
    }

    /** Cached generated key graphs only; never invokes normal table preparation or source refresh. */
    public List<String> keysPreparedLocal(Cancellation token) {
        var issues = new ArrayList<String>();
        if (!definitionsAllowed) {
            issues.add("Table definitions are disabled for this workspace");
            return List.copyOf(issues);
        }
        try (var bootstrap = openTables(tablesService(token, TableRole.LOCAL_BASE), token)) {
            var found = BuildTactKeys.loadPreparedLocal(bootstrap, token).join();
            // No delivered keys here: LOCAL_BASE has no hotfix overlay and this path never
            // decodes the client cache. keys() joins them on the overlay handle.
            applyBuildKeys(found, issues, token);
        }
        return List.copyOf(issues);
    }

    /**
     * The build's resolution indexes and file handle only, for measurement probes (#422): unlike
     * {@link #files} it neither restores nor prepares (and so never writes) the file catalog.
     */
    void openFilesForMeasurement(Cancellation token) {
        openFileHandles(token);
    }

    private void openFileHandles(Cancellation token) {
        var result = builds.open(ref, token).join();
        if (result.state() != BuildTypes.BuildOpenState.OPEN || result.opened() == null)
            throw new IllegalStateException(
                    "Files " + result.state() + ": " + result.code() + " " + result.message());
        opened = result.opened();
        if (fileHandle == null) {
            var filesOpen = files.forBuild(ref, locale, token).join();
            if (filesOpen.state() != FileTypes.FilesOpenState.OPEN || filesOpen.files() == null)
                throw new IllegalStateException(
                        "File catalog " + filesOpen.state() + ": " + filesOpen.message());
            fileHandle = filesOpen.files();
        }
    }

    /**
     * A World service over its own workspace table handle, for measurement probes (#422). Needs
     * {@link #metadata}, the file handles ({@link #files} or {@link #openFilesForMeasurement}) and
     * keys first, but neither the known-table pass nor the lookup warmup, and it never stages or publishes a workspace. Caller closes it before
     * this work.
     */
    MeasurementWorld openWorldForMeasurement(Cancellation token) {
        token.check();
        if (opened == null || fileHandle == null)
            throw new IllegalStateException("Files must be prepared before a measurement World");
        var tables = openTables(tablesService(token, TableRole.WORKSPACE), token);
        try {
            return new MeasurementWorld(
                    tables,
                    new com.vibeminer.concepts.world.WorldService(tables, fileHandle, terrainAtlases));
        } catch (RuntimeException | Error failure) {
            tables.close();
            throw failure;
        }
    }

    /**
     * Creature models and humanoid NPC appearances over a measurement World's table handle, for
     * the measurement probe's opt-in creature counts (#553). Same requirements as {@link
     * #openWorldForMeasurement}; it never stages or publishes a workspace. Its lookups may add
     * derived entries (decoded images, appearances) to this work's generated store, as the
     * workspace's do. Caller closes it before the World.
     */
    MeasurementCreatures openCreaturesForMeasurement(BuildTables tables, Cancellation token) {
        token.check();
        if (opened == null || fileHandle == null)
            throw new IllegalStateException("Files must be prepared before measurement creatures");
        var open =
                new CreaturesService(
                                tablesService(token, TableRole.WORKSPACE),
                                files,
                                new BlpImages(generated))
                        .forBuild(ref, locale, token)
                        .join();
        if (open.creatures() == null)
            throw new IllegalStateException("Creatures " + open.code());
        try {
            // As the workspace does before publishing: the creature lookups need their warmup.
            var issues = new ArrayList<String>();
            appendWarm(
                    issues,
                    open.creatures().warmup(TableTypes.Priority.BACKGROUND, token).join());
            return new MeasurementCreatures(
                    issues,
                    open.creatures(),
                    new com.vibeminer.concepts.races.RacesService(tables, fileHandle, generated));
        } catch (RuntimeException | Error failure) {
            closeResources(open.creatures());
            throw failure;
        }
    }

    /** Measurement creature and race handles; both close before the World's tables. */
    record MeasurementCreatures(
            List<String> warmup,
            BuildCreatures creatures,
            com.vibeminer.concepts.races.BuildRaces races)
            implements AutoCloseable {
        @Override
        public void close() {
            closeResources(races, creatures);
        }
    }

    /** One measurement World and the table handle it borrows; the World closes first. */
    record MeasurementWorld(BuildTables tables, com.vibeminer.concepts.world.WorldService world)
            implements AutoCloseable {
        @Override
        public void close() {
            closeResources(world, tables);
        }
    }

    /** Caller owns this dedicated local table handle and closes it before this work context. */
    public BuildTables openLocalTablesForComparison(Cancellation token) {
        if (!definitionsAllowed) throw new IllegalStateException("Table definitions are disabled");
        token.check();
        return openTables(tablesService(token, TableRole.LOCAL_BASE), token);
    }

    private void applyBuildKeys(BuildTactKeys.KeySet found, List<String> issues, Cancellation token) {
        if (found.keyTable().outcome() == BuildTactKeys.Outcome.READ
                && found.lookupTable().outcome() == BuildTactKeys.Outcome.READ) {
            token.check();
            keys.set(found);
            rebuildEffectiveKeys();
            for (String name : found.names()) {
                long value = Long.parseUnsignedLong(name, 16);
                byte[] external = communityKeys.find(value);
                if (external != null && !java.util.Arrays.equals(external, found.find(value))) {
                    issues.add("TACT key conflict for " + name + "; build-provided key retained");
                }
            }
        }
        if (found.keyTable().outcome() != BuildTactKeys.Outcome.READ) {
            issues.add("TactKey: " + found.keyTable().outcome());
        }
        if (found.lookupTable().outcome() != BuildTactKeys.Outcome.READ) {
            issues.add("TactKeyLookup: " + found.lookupTable().outcome());
        }
    }

    @Override
    public TableTypes.PreloadResult tables(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        ensureHotfix(token);
        long started = System.nanoTime();
        abortPending();
        TableTypes.PreloadResult prepared = null;
        var cache = new com.vibeminer.formats.tables.PreparedTablesCache(generated);
        String identity = tableCacheIdentity();
        if (!tablePassAttempted) {
            prepared = cache.restore(identity, progress, token).join();
        }
        boolean restored = prepared != null;
        tablePassAttempted = true;
        if (prepared == null) {
            System.getLogger(NativePreparationWork.class.getName())
                    .log(
                            System.Logger.Level.INFO,
                            "Prepared table inventory unavailable or stale; assessing known"
                                    + " tables");
            try (var preparing = openTables(tablesService(token, TableRole.PREPARATION), token)) {
                prepared = preparing.prepareKnown(progress, token).join();
            }
            if (prepared.outcome() == TableTypes.PreloadOutcome.COMPLETED) {
                cache.save(identity, prepared, token).join();
            }
        }
        token.check();
        if (prepared.outcome() != TableTypes.PreloadOutcome.COMPLETED) return prepared;
        if (usageAccounting != null) usageAccounting.tables(identity, prepared.tables());
        System.getLogger(NativePreparationWork.class.getName())
                .log(
                        System.Logger.Level.INFO,
                        "Tables {0}: {1} known table outcomes in {2} ms",
                        restored ? "restored from local cache" : "prepared",
                        prepared.tables().size(),
                        (System.nanoTime() - started) / 1_000_000);
        var localService = tablesService(token, TableRole.WORKSPACE);
        var next = openTables(localService, token);
        boolean staged = false;
        try {
            synchronized (handlesGate) {
                token.check();
                if (closed.get()) throw new java.util.concurrent.CancellationException();
                pending = new Workspace(next, localService, null, null, null, new com.vibeminer.concepts.talents.TalentsService(next,info.version()), new com.vibeminer.concepts.races.RacesService(next, fileHandle, generated), new com.vibeminer.concepts.world.WorldService(next,fileHandle,terrainAtlases), new com.vibeminer.concepts.mounts.MountsService(next,fileHandle,generated), new com.vibeminer.concepts.professions.ProfessionsService(next), new com.vibeminer.concepts.dungeons.DungeonsService(next), new com.vibeminer.concepts.locations.LocationMapsService(next,fileHandle,generated), 0);
                pendingWarmed = false;
                staged = true;
            }
            return prepared;
        } finally {
            if (!staged) next.close();
        }
    }

    private String tableCacheIdentity() {
        var available = effectiveKeys();
        String keyFingerprint = available.fingerprint();
        String identity = String.join(
                "\n",
                definitionsAllowed ? "native-table-policy-v1" : "native-table-policy-definitions-disabled-v1",
                ref.product(),
                ref.buildConfig(),
                ref.cdnConfig(),
                ref.region(),
                locale,
                info.version(),
                opened.metadata().rootContentKey().hex(),
                opened.metadata().encodingContentKey().hex(),
                BundledDefinitions.REVISION,
                BundledDefinitions.MANIFEST_SHA256,
                BundledDefinitions.SOURCES_SHA256,
                definitionsAllowed ? com.vibeminer.formats.definitions.SupplementalDefinitions.IDENTITY : "supplemental-disabled",
                new com.vibeminer.formats.db2.Db2Types.Limits().toString(),
                keyFingerprint);
        // The known-table inventory holds base DB2 outcomes; the hotfix overlay is applied on
        // top of it when the workspace opens. Keys delivered by a hotfix already change the
        // key fingerprint above, so a new cache snapshot never forces a full re-assessment.
        return identity;
    }

    /** Loaded hotfix input and decoder revision, or null while the workspace reads base data. */
    String hotfixIdentity() {
        var snapshot = hotfixSnapshot;
        return snapshot == null
                ? null
                : new com.vibeminer.formats.tables.HotfixBinding(
                                        ref, locale, info.version(), snapshot.source(), snapshot.index())
                                .inputFingerprint()
                        + "\n"
                        + com.vibeminer.formats.hotfix.HotfixRowDecoder.REVISION;
    }

    @Override
    public List<String> warm(Cancellation token) {
        return warm(ignored -> {}, token);
    }

    /** The research lookup steps, in order, as the status names them (#338). */
    static final List<String> LOOKUP_STEPS =
            List.of("items", "item catalog", "item attributes", "creatures", "effects");

    /** Reports step {@code index} as started and returns the time it started. */
    private static long lookupStep(
            java.util.function.Consumer<BuildPreparation.Counter> progress, int index) {
        progress.accept(
                new BuildPreparation.Counter(
                        index,
                        (long) LOOKUP_STEPS.size(),
                        "lookup steps · " + LOOKUP_STEPS.get(index)));
        return System.nanoTime();
    }

    /** "lookup step 2 of 5 (item catalog)" for step {@code index}. */
    static String stepLabel(int index) {
        return "lookup step "
                + (index + 1)
                + " of "
                + LOOKUP_STEPS.size()
                + " ("
                + LOOKUP_STEPS.get(index)
                + ")";
    }

    /** One log line with the whole step's duration and each lookup's share (#338). */
    static String lookupTimings(long[] stepNanos, long totalNanos) {
        var text = new StringBuilder("Research lookups prepared in ");
        text.append(totalNanos / 1_000_000).append(" ms (");
        for (int i = 0; i < LOOKUP_STEPS.size(); i++) {
            if (i > 0) text.append(", ");
            text.append(LOOKUP_STEPS.get(i)).append(' ').append(stepNanos[i] / 1_000_000).append(" ms");
        }
        return text.append(')').toString();
    }

    @Override
    public List<String> warm(
            java.util.function.Consumer<BuildPreparation.Counter> progress, Cancellation token) {
        java.util.Objects.requireNonNull(progress);
        long started = System.nanoTime();
        long[] stepNanos = new long[LOOKUP_STEPS.size()];
        final Workspace staged;
        synchronized (handlesGate) {
            staged = pending;
        }
        if (staged == null || staged.service() == null)
            throw new IllegalStateException("Table context is not staged");
        var issues = new ArrayList<String>();
        var images = new BlpImages(generated);
        BuildItems nextItems = null;
        BuildCreatures nextCreatures = null;
        BuildItemEffects nextEffects = null;
        boolean ownedByPending = false;
        try {
            token.check();
            long step = lookupStep(progress, 0);
            var itemOpen =
                    new ItemsService(
                                    staged.service(),
                                    files,
                                    images,
                                    generated,
                                    indexDirectory,
                                    itemCatalogInputs())
                            .forBuild(ref, locale, token)
                            .join();
            nextItems = itemOpen.items();
            if (nextItems == null) issues.add("Items: " + itemOpen.code());
            else appendWarm(issues, nextItems.warmup(TableTypes.Priority.BACKGROUND, token).join());
            stepNanos[0] = System.nanoTime() - step;
            if (nextItems != null && indexDirectory != null) {
                step = lookupStep(progress, 1);
                // Rebuilding the derived catalog can take minutes on a new build: report its rows.
                com.vibeminer.concepts.items.BuildItems.CatalogProgress rows =
                        (stage, done) ->
                                progress.accept(
                                        new BuildPreparation.Counter(
                                                done, null, stage + " · " + stepLabel(1)));
                var catalog =
                        nextItems
                                .grid(
                                        new com.vibeminer.concepts.items.ItemGridQuery(
                                                "",
                                                null,
                                                null,
                                                null,
                                                null,
                                                com.vibeminer.concepts.items.ItemGridQuery.Sort.ID,
                                                false,
                                                0,
                                                1),
                                        TableTypes.Priority.BACKGROUND,
                                        rows,
                                        token)
                                .join();
                stepNanos[1] = System.nanoTime() - step;
                if (catalog.state() != com.vibeminer.concepts.items.ItemsPageState.OK)
                    issues.add("Item catalog: " + catalog.message());
                else {
                    step = lookupStep(progress, 2);
                    var attributeIssues =
                            nextItems
                                    .prepareAttributes(TableTypes.Priority.BACKGROUND, token)
                                    .join();
                    attributeIssues.forEach(issue -> issues.add("Item attributes: " + issue));
                    stepNanos[2] = System.nanoTime() - step;
                }
            }
            token.check();
            step = lookupStep(progress, 3);
            var creatureOpen =
                    new CreaturesService(staged.service(), files, images)
                            .forBuild(ref, locale, token)
                            .join();
            nextCreatures = creatureOpen.creatures();
            if (nextCreatures == null) issues.add("Creatures: " + creatureOpen.code());
            else
                appendWarm(
                        issues, nextCreatures.warmup(TableTypes.Priority.BACKGROUND, token).join());
            stepNanos[3] = System.nanoTime() - step;
            token.check();
            step = lookupStep(progress, 4);
            var effectOpen =
                    new ItemEffectsService(staged.service(),
                            new com.vibeminer.concepts.effects.EffectsTypes.Options(), info.version()).forBuild(ref, locale, token).join();
            nextEffects = effectOpen.effects();
            if (nextEffects == null) issues.add("Effects: " + effectOpen.code());
            else
                appendWarm(
                        issues, nextEffects.warmup(TableTypes.Priority.BACKGROUND, token).join());
            stepNanos[4] = System.nanoTime() - step;
            System.getLogger(NativePreparationWork.class.getName())
                    .log(
                            System.Logger.Level.INFO,
                            lookupTimings(stepNanos, System.nanoTime() - started));
            synchronized (handlesGate) {
                token.check();
                if (closed.get() || pending != staged)
                    throw new java.util.concurrent.CancellationException();
                pending =
                        new Workspace(
                                staged.tables(),
                                staged.service(),
                                nextItems,
                                nextCreatures,
                                nextEffects,
                                staged.talents(),
                                staged.races(),
                                staged.world(),
                                staged.mounts(),
                                staged.professions(),
                                staged.dungeons(),
                                staged.locationMaps(),
                                0);
                pendingWarmed = true;
                ownedByPending = true;
            }
            return List.copyOf(issues);
        } catch (Throwable failure) {
            if (!ownedByPending) {
                var loose = new Workspace(null, null, nextItems, nextCreatures, nextEffects, null, null, null, null, null, null, null, 0);
                try {
                    closeWorkspace(loose);
                } catch (Throwable cleanupFailure) {
                    synchronized (handlesGate) {
                        retired.add(loose);
                    }
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                abortPending();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Called under the preparation job's commit lock: no I/O, callbacks or thread startup. */
    @Override
    public void commit(Cancellation token) {
        synchronized (handlesGate) {
            token.check();
            if (closed.get()) throw new java.util.concurrent.CancellationException();
            if (pending == null || !pendingWarmed)
                throw new IllegalStateException("Lookups are not staged");
            var next =
                    new Workspace(
                            pending.tables(),
                            pending.service(),
                            pending.items(),
                            pending.creatures(),
                            pending.effects(),
                            pending.talents(),
                            pending.races(),
                            pending.world(),
                            pending.mounts(),
                            pending.professions(),
                            pending.dungeons(),
                            pending.locationMaps(),
                            published.revision() + 1);
            if (published.tables() != null) retired.add(published);
            published = next;
            pending = null;
            pendingWarmed = false;
        }
    }

    @Override
    public void abortPending() {
        Workspace abandoned;
        synchronized (handlesGate) {
            abandoned = pending;
            pending = null;
            pendingWarmed = false;
        }
        try {
            closeWorkspace(abandoned);
        } catch (Throwable failure) {
            synchronized (handlesGate) {
                if (abandoned != null) retired.add(abandoned);
            }
            throw failure;
        }
    }

    /** Retire old accepted reads off the commit lock. Failure keeps ownership until final close. */
    @Override
    public void retireCommitted() {
        final List<Workspace> batch;
        final CompletableFuture<Void> done;
        synchronized (handlesGate) {
            if (retired.isEmpty() || closed.get()) return;
            batch = retired.stream().filter(workspace -> !retiring.contains(workspace)).toList();
            if (batch.isEmpty()) return;
            retiring.addAll(batch);
            done = new CompletableFuture<>();
            retirementJobs.add(done);
        }
        try {
            Thread.startVirtualThread(
                    () -> {
                        Throwable failure = null;
                        for (var workspace : batch) {
                            try {
                                closeWorkspace(workspace);
                                synchronized (handlesGate) {
                                    retired.removeIf(value -> value == workspace);
                                }
                            } catch (Throwable error) {
                                if (failure == null) failure = error;
                                else failure.addSuppressed(error);
                            }
                        }
                        // Failed workspaces remain in retired and are retried by final close.
                        synchronized (handlesGate) {
                            retiring.removeAll(batch);
                            retirementJobs.remove(done);
                        }
                        done.complete(null);
                    });
        } catch (Throwable startFailure) {
            synchronized (handlesGate) {
                retiring.removeAll(batch);
                retirementJobs.remove(done);
            }
            done.complete(null);
        }
    }

    private static void closeWorkspace(Workspace workspace) {
        if (workspace != null)
            closeResources(
                    workspace.talents(),
                    workspace.races(),
                    workspace.world(),
                    workspace.mounts(),
                    workspace.professions(),
                    workspace.dungeons(),
                    workspace.locationMaps(),
                    workspace.items(),
                    workspace.creatures(),
                    workspace.effects(),
                    workspace.tables());
    }

    private String itemCatalogInputs() {
        return itemCatalogInputs(baseItemCatalogInputs());
    }

    /** Item grids and stats derive from the published effective tables. */
    String itemCatalogInputs(List<Object> base) {
        var inputs = new ArrayList<Object>(base);
        // Each hotfix input needs its own entries; base-only identities stay unchanged.
        String hotfix = hotfixIdentity();
        if (hotfix != null) inputs.add(hotfix);
        return com.vibeminer.formats.internal.Json.sha256(
                com.vibeminer.formats.internal.Json.bytes(inputs));
    }

    private List<Object> baseItemCatalogInputs() {
        var available = effectiveKeys();
        var fingerprints = available.fingerprint();
        var inputs =
                new ArrayList<Object>(
                        List.of(
                                definitionsAllowed ? "native-item-catalog-v1" : "native-item-catalog-definitions-disabled-v1",
                                BundledDefinitions.REVISION,
                                BundledDefinitions.MANIFEST_SHA256,
                                BundledDefinitions.SOURCES_SHA256,
                                definitionsAllowed ? com.vibeminer.formats.definitions.SupplementalDefinitions.IDENTITY : "supplemental-disabled",
                                opened.metadata().rootContentKey().hex(),
                                opened.metadata().encodingContentKey().hex(),
                                fingerprints));
        return inputs;
    }

    private static void appendWarm(
            List<String> issues, com.vibeminer.concepts.core.ConceptWarmup.Result result) {
        for (var entry : result.entries())
            if (!entry.ready())
                issues.add(entry.table() + ": " + entry.code() + " " + entry.message());
    }

    private static void closeResources(AutoCloseable... resources) {
        Throwable first = null;
        for (var resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw new java.util.concurrent.CompletionException(first);
    }

    @Override
    public void enrichNames(Cancellation token) {
        if (!listfileAllowed) return;
        NamesTypes.FileNamesStatus status;
        if (sourcePolicies == null) status = community.update(false, token).join();
        else {
            var registration = sourcePolicies.nameAcquisition(token);
            try { token.check(); status = community.status(token).join(); }
            finally { registration.close(); }
        }
        token.check();
        if (status.state() == NamesTypes.FileNamesState.READY) {
            namesEnabled.set(true);
            if (usageAccounting != null) usageAccounting.namesReady();
        }
        if (status.state() == NamesTypes.FileNamesState.FAILED)
            throw new IllegalStateException(status.code() + " " + status.message());
    }

    @Override
    public void close() {
        final List<CompletableFuture<Void>> waiting;
        final Cancellation hotfixStop;
        synchronized (handlesGate) {
            if (!closed.compareAndSet(false, true)) return;
            waiting = new ArrayList<>(retirementJobs);
            // A failed import result is not a workspace retirement failure: the store
            // retains/quarantines unreturned owners. Returned snapshots are closed below.
            if (hotfixLoad != null) waiting.add(hotfixLoad.handle((value, error) -> null));
            hotfixStop = hotfixCancellation;
        }
        Throwable failure = null;
        if (hotfixStop != null) {
            try { hotfixStop.cancel(); }
            catch (RuntimeException | Error error) { failure = error; }
        }
        try {
            try {
                if (usageAccounting != null) usageAccounting.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            try {
                CompletableFuture.allOf(waiting.toArray(CompletableFuture[]::new)).join();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            final List<Workspace> owned = new ArrayList<>();
            synchronized (handlesGate) {
                if (pending != null) owned.add(pending);
                owned.add(published);
                owned.addAll(retired);
                pending = null;
                published = new Workspace(null, null, null, null, null, null, null, null, null, null, null, null, published.revision());
                retired.clear();
                retirementJobs.clear();
            }
            for (var workspace : owned) {
                try {
                    closeWorkspace(workspace);
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            try {
                closeResources(fileHandle);
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            try {
                if (hotfixSnapshot != null) hotfixSnapshot.closeAsync().join();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        } finally {
            try {
                lifecycle.run();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw new java.util.concurrent.CompletionException(failure);
    }
}
