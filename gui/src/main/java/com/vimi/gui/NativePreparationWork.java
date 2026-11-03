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
                        + ":" + BundledDefinitions.SOURCES_SHA256, result);
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
        var result = builds.open(ref, token).join();
        long indexesReady = System.nanoTime();
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

    private TablesService tablesService(Cancellation token, boolean onlinePreparation) {
        var definitions = new TableDefinitions(definitionsAllowed ? BundledDefinitions.open(token).join() : BundledDefinitions.of(List.of(), java.util.Map.of()), null, null);
        var available = effectiveKeys();
        var status = new TactKeyStatus(available.fingerprints());
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
                onlinePreparation ? objects : new LocalMetadataSource(localObjects);
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
        try (var bootstrap = openTables(tablesService(token, true), token)) {
            var found = BuildTactKeys.load(bootstrap, TableTypes.Priority.BACKGROUND, token).join();
            if (found.keyTable().outcome() == BuildTactKeys.Outcome.READ
                    && found.lookupTable().outcome() == BuildTactKeys.Outcome.READ) {
                token.check();
                keys.set(found);
                rebuildEffectiveKeys();
                for (String name : found.names()) {
                    long value = Long.parseUnsignedLong(name, 16);
                    byte[] external = communityKeys.find(value);
                    if (external != null && !java.util.Arrays.equals(external, found.find(value)))
                        issues.add("TACT key conflict for " + name + "; build-provided key retained");
                }
            }
            if (found.keyTable().outcome() != BuildTactKeys.Outcome.READ)
                issues.add("TactKey: " + found.keyTable().outcome());
            if (found.lookupTable().outcome() != BuildTactKeys.Outcome.READ)
                issues.add("TactKeyLookup: " + found.lookupTable().outcome());
        }
        return List.copyOf(issues);
    }

    @Override
    public TableTypes.PreloadResult tables(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
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
            try (var preparing = openTables(tablesService(token, true), token)) {
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
        var localService = tablesService(token, false);
        var next = openTables(localService, token);
        boolean staged = false;
        try {
            synchronized (handlesGate) {
                token.check();
                if (closed.get()) throw new java.util.concurrent.CancellationException();
                pending = new Workspace(next, localService, null, null, null, new com.vibeminer.concepts.talents.TalentsService(next,info.version()), new com.vibeminer.concepts.races.RacesService(next, fileHandle, generated), new com.vibeminer.concepts.world.WorldService(next,fileHandle), new com.vibeminer.concepts.mounts.MountsService(next,fileHandle,generated), new com.vibeminer.concepts.professions.ProfessionsService(next), new com.vibeminer.concepts.dungeons.DungeonsService(next), new com.vibeminer.concepts.locations.LocationMapsService(next,fileHandle,generated), 0);
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
        return String.join(
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
                new com.vibeminer.formats.db2.Db2Types.Limits().toString(),
                keyFingerprint);
    }

    @Override
    public List<String> warm(Cancellation token) {
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
            if (nextItems != null && indexDirectory != null) {
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
                                        token)
                                .join();
                if (catalog.state() != com.vibeminer.concepts.items.ItemsPageState.OK)
                    issues.add("Item catalog: " + catalog.message());
                else {
                    var attributeIssues =
                            nextItems
                                    .prepareAttributes(TableTypes.Priority.BACKGROUND, token)
                                    .join();
                    attributeIssues.forEach(issue -> issues.add("Item attributes: " + issue));
                }
            }
            token.check();
            var creatureOpen =
                    new CreaturesService(staged.service(), files, images)
                            .forBuild(ref, locale, token)
                            .join();
            nextCreatures = creatureOpen.creatures();
            if (nextCreatures == null) issues.add("Creatures: " + creatureOpen.code());
            else
                appendWarm(
                        issues, nextCreatures.warmup(TableTypes.Priority.BACKGROUND, token).join());
            token.check();
            var effectOpen =
                    new ItemEffectsService(staged.service()).forBuild(ref, locale, token).join();
            nextEffects = effectOpen.effects();
            if (nextEffects == null) issues.add("Effects: " + effectOpen.code());
            else
                appendWarm(
                        issues, nextEffects.warmup(TableTypes.Priority.BACKGROUND, token).join());
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
        var available = effectiveKeys();
        var fingerprints = available.fingerprint();
        return com.vibeminer.formats.internal.Json.sha256(
                com.vibeminer.formats.internal.Json.bytes(
                        List.of(
                                definitionsAllowed ? "native-item-catalog-v1" : "native-item-catalog-definitions-disabled-v1",
                                BundledDefinitions.REVISION,
                                BundledDefinitions.MANIFEST_SHA256,
                                BundledDefinitions.SOURCES_SHA256,
                                opened.metadata().rootContentKey().hex(),
                                opened.metadata().encodingContentKey().hex(),
                                fingerprints)));
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
        synchronized (handlesGate) {
            if (!closed.compareAndSet(false, true)) return;
            waiting = List.copyOf(retirementJobs);
        }
        Throwable failure = null;
        try {
            try {
                if (usageAccounting != null) usageAccounting.close();
            } catch (Throwable error) {
                failure = error;
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
