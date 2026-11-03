package com.vimi.gui;

import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.files.names.NamesTypes;
import com.vibeminer.formats.definitions.BundledDefinitions;
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

/** Application lifetime source orchestration; controls read immutable cached snapshots only. */
public final class DataSourcesService {
    public enum Id {
        QUESTIE,
        LISTFILE,
        DEFINITIONS,
        TACT_KEYS
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

    public record DefinitionsState(String revision, long tables, String license, String origin) {}

    public record Mapping(
            String choice, String flavor, String versionId, String release, String message) {}

    public record TactState(int keys, String fingerprint, long bytes, String problem) {}
    private volatile com.vibeminer.files.keys.TactKeys tactKeys = com.vibeminer.files.keys.TactKeys.EMPTY;
    private volatile TactState tactState = new TactState(0, null, 0, null);
    private final com.vibeminer.network.TactKeyClient tactClient;
    private final com.vibeminer.store.ObjectReader tactReader;
    private final com.vibeminer.store.ObjectWriter tactWriter;
    private final Map<Id, com.vibeminer.network.Cancellation> networkTokens = new java.util.EnumMap<>(Id.class);
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
    private final Map<Id, com.vibeminer.generated.Cancellation> tokens =
            new java.util.EnumMap<>(Id.class);
    private final Map<Id, com.vibeminer.access.Cancellation> accessTokens =
            new java.util.EnumMap<>(Id.class);
    private final Map<Id, Activity> activity = new ConcurrentHashMap<>();
    private volatile QuestieState questieState = new QuestieState(List.of(), List.of(), null, null);
    private volatile NamesTypes.FileNamesStatus listfile;
    private volatile DefinitionsState definitions;
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
        var settings = new SourcePreferences(database);
        var repository =
                QuestieRepository.open(directory, com.vibeminer.generated.Cancellation.NONE).join();
        DataSourcesService service = null;
        try {
            catalog.retainOwner("provider:community-listfile", LibraryCatalog.Scope.GLOBAL).join();
            service = new DataSourcesService(settings, repository, names, catalog, directory, client, reader, writer);
            service.refreshLocal().join();
            var opened = service;
            service.timer.scheduleWithFixedDelay(opened::checkDue, 1, 60, TimeUnit.SECONDS);
            return service;
        } catch (Throwable error) {
            if (service == null) repository.closeAsync().join();
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
            com.vibeminer.store.ObjectReader reader, com.vibeminer.store.ObjectWriter writer) {
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

    public DefinitionsState definitions() {
        return definitions;
    }

    public LibraryCatalog.Figures listfileBytes() {
        return listfileBytes;
    }

    public Activity activity(Id id) {
        return activity.get(id);
    }

    public String checkedAt(Id id) {
        return preferences.get("checked." + id, "Never checked");
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
        if (id == Id.DEFINITIONS && checks != Checks.MANUAL)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Bundled definitions update with the app"));
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
                    preferences.put("default." + kind.upstream(), versionId);
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
                    loadTactKeys();
                    try {
                        listfile = names.status(com.vibeminer.access.Cancellation.NONE).join();
                    } catch (Throwable error) {
                        activity.put(Id.LISTFILE, new Activity(false, null, -1, message(error)));
                    }
                    var bundled =
                            BundledDefinitions.open(com.vibeminer.access.Cancellation.NONE).join();
                    definitions =
                            new DefinitionsState(
                                    BundledDefinitions.REVISION,
                                    bundled.tables().size(),
                                    bundled.info().license(),
                                    bundled.info().sourceUrl());
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
                        var result = names.update(true, access).join();
                        if (result.state() != NamesTypes.FileNamesState.READY)
                            throw new IllegalStateException(
                                    result.message() == null
                                            ? "Listfile is not ready"
                                            : result.message());
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
            pending = jobs.get(Id.TACT_KEYS);
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
                var reply = tactClient.fetchTactKeys(network).join();
                token.check();
                byte[] bytes = reply.bytes();
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
        for (Id id : List.of(Id.QUESTIE, Id.LISTFILE, Id.TACT_KEYS)) {
            if (id == Id.TACT_KEYS) {
                // The same reservation path serves startup and build preparation.
                synchronized (this) {
                    var value = policy(id);
                    if (!closing && tactClient != null && value.enabled() && !jobs.containsKey(id)
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
                                if (id == Id.QUESTIE) checkQuestie();
                                else if (id == Id.LISTFILE) updateListfile();
                                else updateTactKeys();
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
        var accepted = jobs.values().toArray(CompletableFuture[]::new);
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        CompletableFuture.allOf(accepted).handle((v, e) -> null).join();
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        questie.closeAsync().join();
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
