package com.vibeminer.eglink;

import com.vibeminer.eglink.EgLinkTypes.Build;
import com.vibeminer.eglink.EgLinkTypes.Catalog;
import com.vibeminer.eglink.EgLinkTypes.Dataset;
import com.vibeminer.eglink.EgLinkTypes.Page;
import com.vibeminer.eglink.EgLinkTypes.Progress;
import com.vibeminer.eglink.EgLinkTypes.Record;
import com.vibeminer.eglink.EgLinkTypes.Snapshot;
import com.vibeminer.network.Cancellation;
import com.vibeminer.network.EgLinkClient;
import com.vibeminer.store.DerivedSqliteConnection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Independent GitHub source snapshots and disposable indexed records. No application SQL access.
 */
public final class EgLinkRepository implements AutoCloseable {
    public static final int MAX_FILE_BYTES = 16 << 20, MAX_SNAPSHOT_BYTES = 64 << 20;

    /** Snapshots each product keeps after a sync, by default ({@link #retainedOrder}). */
    public static final int RETAINED_SNAPSHOTS = 10;

    private static final int APPLICATION_ID = 0x45474c31;
    private final Path root;
    private final EgLinkClient client;
    private final int retained;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("vimi-eg-link").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private boolean closing;
    private CompletableFuture<Void> closed;

    public EgLinkRepository(Path directory, EgLinkClient client) throws IOException {
        this(directory, client, RETAINED_SNAPSHOTS);
    }

    /** As above, keeping at most {@code retained} snapshots per product after each sync. */
    public EgLinkRepository(Path directory, EgLinkClient client, int retained) throws IOException {
        if (retained < 1 || retained > 1024) {
            throw new IllegalArgumentException("Retained EG Link snapshots must be 1 to 1024");
        }
        this.retained = retained;
        this.root = directory.toAbsolutePath().normalize();
        this.client = java.util.Objects.requireNonNull(client);
        Files.createDirectories(root.resolve("sources"));
        Files.createDirectories(root.resolve("derived"));
    }

    public CompletableFuture<Catalog> catalog(Cancellation token) {
        return call(
                token,
                () -> {
                    Path pointer = root.resolve("catalog.json");
                    if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
                        return new Catalog(null, null, List.of());
                    }
                    var stored = EgJson.object(EgJson.parse(read(pointer, 2 << 20), 2 << 20));
                    if (!"vimi-eg-link-catalog-v1".equals(stored.get("format"))) {
                        throw new IOException("Unsupported EG Link catalog format");
                    }
                    return parseIndex(
                            EgJson.text(stored.get("revision")),
                            Instant.parse(EgJson.text(stored.get("checkedAt"))),
                            EgJson.bytes(stored.get("index")));
                });
    }

    public CompletableFuture<Catalog> check(Cancellation token) {
        return call(token, () -> checkNow(token));
    }

    private Catalog checkNow(Cancellation token) throws Exception {
        Path existing = root.resolve("catalog.json");
        if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
            var previous = EgJson.object(EgJson.parse(read(existing, 2 << 20), 2 << 20));
            if (!"vimi-eg-link-catalog-v1".equals(previous.get("format"))) {
                throw new IOException("Unsupported EG Link catalog format");
            }
        }
        var head = client.fetchEgLinkHead(token).join();
        token.check();
        var header = EgJson.object(EgJson.parse(head.bytes(), 64 << 10));
        String revision = EgJson.text(EgJson.object(header.get("object")).get("sha"));
        if (!revision.matches("[0-9a-f]{40}")) {
            throw new IOException("EG Link commit is not immutable");
        }
        if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
            var previous = EgJson.object(EgJson.parse(read(existing, 2 << 20), 2 << 20));
            if (revision.equals(previous.get("revision"))) {
                var cached =
                        parseIndex(
                                revision, head.observedAt(), EgJson.bytes(previous.get("index")));
                token.check();
                previous.put("checkedAt", head.observedAt().toString());
                atomic(existing, EgJson.bytes(previous));
                return cached;
            }
        }
        var index = client.fetchEgLinkFile(revision, "data/index.json", token).join();
        var value = parseIndex(revision, index.observedAt(), index.bytes());
        token.check();
        atomic(
                root.resolve("catalog.json"),
                EgJson.bytes(
                        Map.of(
                                "format",
                                "vimi-eg-link-catalog-v1",
                                "revision",
                                revision,
                                "checkedAt",
                                value.checkedAt().toString(),
                                "index",
                                EgJson.parse(index.bytes(), 1 << 20))));
        return value;
    }

    /**
     * Updates latest advertised build per product and every distinct installed product/build.
     * Unchanged prepared snapshots are verified/reused: the same commit, or a prepared snapshot of
     * the same product and build whose datasets declare the same records, sources and collection
     * dates (a later commit that left the build alone). Historical builds are never bulk fetched.
     * Partial failures are returned as issues while completed snapshots remain available. Then each
     * product keeps its {@code retained} snapshots in {@link #retainedOrder} and the rest are
     * deleted.
     */
    public CompletableFuture<EgLinkTypes.Sync> sync(
            Consumer<Progress> progress, Cancellation token) {
        return sync(progress, snapshots -> Set.of(), token);
    }

    /**
     * As {@link #sync(Consumer, Cancellation)}; {@code keep} names, from the snapshots held after
     * the downloads, the ones pruning must never delete (the snapshot an opened or library build
     * reads, one a user chose). They are kept on top of the retained count.
     */
    public CompletableFuture<EgLinkTypes.Sync> sync(
            Consumer<Progress> progress,
            java.util.function.Function<List<Snapshot>, Set<String>> keep,
            Cancellation token) {
        java.util.Objects.requireNonNull(keep);
        return call(
                token,
                () -> {
                    progress.accept(new Progress("Checking EG Link GitHub commit", 0, 1));
                    purgeTrash();
                    Catalog catalog = checkNow(token);
                    List<Snapshot> installed = local(token);
                    var targets = new LinkedHashMap<String, Build>();
                    var advertised = new LinkedHashMap<String, Build>();
                    var latest = new LinkedHashMap<String, Build>();
                    for (Build build : catalog.builds()) {
                        advertised.put(build.product() + "/" + build.version(), build);
                        latest.merge(
                                build.product(),
                                build,
                                (before, candidate) ->
                                        compareVersions(before.version(), candidate.version()) < 0
                                                ? candidate
                                                : before);
                    }
                    var issues = new ArrayList<String>();
                    for (Snapshot snapshot : installed) {
                        String key = snapshot.build().product() + "/" + snapshot.build().version();
                        Build current = advertised.get(key);
                        if (current != null) {
                            targets.put(key, current);
                        } else if (!issues.contains(
                                "No longer advertised: " + key + "; local data retained")) {
                            issues.add("No longer advertised: " + key + "; local data retained");
                        }
                    }
                    latest.values()
                            .forEach(
                                    build ->
                                            targets.put(
                                                    build.product() + "/" + build.version(),
                                                    build));
                    int downloaded = 0, prepared = 0, reused = 0;
                    for (Build build : targets.values()) {
                        token.check();
                        String sourceId = id(build);
                        boolean retained =
                                Files.exists(source(sourceId), LinkOption.NOFOLLOW_LINKS);
                        String label = build.product() + " " + build.version();
                        Snapshot unchanged = unchanged(installed, build, sourceId);
                        if (unchanged != null) {
                            reused++;
                            progress.accept(
                                    new Progress("EG Link " + label + " is unchanged", 1, 1));
                            continue;
                        }
                        try {
                            downloadNow(
                                    build,
                                    value ->
                                            progress.accept(
                                                    new Progress(
                                                            label + ": " + value.message(),
                                                            value.done(),
                                                            value.total())),
                                    token);
                            if (!retained) {
                                downloaded++;
                            }
                            Snapshot ready =
                                    installed.stream()
                                            .filter(
                                                    value ->
                                                            value.id().equals(sourceId)
                                                                    && value.indexedRecords()
                                                                            != null
                                                                    && value.problem() == null)
                                            .findFirst()
                                            .orElse(null);
                            if (ready != null) {
                                reused++;
                                progress.accept(
                                        new Progress("Reusing prepared EG Link " + label, 1, 1));
                            } else {
                                prepareNow(
                                        sourceId,
                                        value ->
                                                progress.accept(
                                                        new Progress(
                                                                label + ": " + value.message(),
                                                                value.done(),
                                                                value.total())),
                                        token);
                                prepared++;
                            }
                        } catch (java.util.concurrent.CancellationException canceled) {
                            throw canceled;
                        } catch (Exception failure) {
                            token.check();
                            Throwable cause = failure;
                            while (cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            issues.add(
                                    label
                                            + ": "
                                            + (cause.getMessage() == null
                                                    ? cause.getClass().getSimpleName()
                                                    : cause.getMessage()));
                        }
                    }
                    var held = local(token);
                    Set<String> kept;
                    try {
                        kept = Set.copyOf(keep.apply(held));
                    } catch (RuntimeException failure) {
                        // Without knowing what is in use, nothing is pruned this time.
                        kept = null;
                        issues.add("Older snapshots were kept: " + failure.getMessage());
                    }
                    int pruned = kept == null ? 0 : prune(held, kept, issues, token);
                    if (pruned > 0) {
                        progress.accept(
                                new Progress(
                                        "Removed " + pruned + " older EG Link snapshots", 1, 1));
                    }
                    return new EgLinkTypes.Sync(local(token), downloaded, prepared, reused, issues);
                });
    }

    /**
     * A prepared snapshot of another commit with the same product, build and declared datasets as
     * {@code build}, or null. The index declares no per-dataset digest, only record and source
     * counts and the collection date, so a build whose content changed while all three stayed the
     * same would keep its earlier snapshot until one of them changes. Upstream bumps the collection
     * date whenever it collects again, so the risk is a same-day correction.
     */
    private static Snapshot unchanged(List<Snapshot> installed, Build build, String sourceId) {
        return installed.stream()
                .filter(
                        value ->
                                !value.id().equals(sourceId)
                                        && value.problem() == null
                                        && value.indexedRecords() != null
                                        && value.build().product().equals(build.product())
                                        && value.build().version().equals(build.version())
                                        && value.build().datasets().equals(build.datasets()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The order in which one product's snapshots are kept: first the newest commit of each build,
     * newest build first, then the older commits of every build, most recently downloaded first. A
     * later commit of a build supersedes an earlier one, so distinct builds (which record different
     * NPCs) outlast repeated commits of the latest build.
     */
    static List<Snapshot> retainedOrder(List<Snapshot> snapshots) {
        var newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(
                Comparator.comparing(
                                (Snapshot value) -> value.build().version(),
                                EgLinkRepository::compareVersions)
                        .reversed()
                        .thenComparing(Snapshot::downloadedAt, Comparator.reverseOrder())
                        .thenComparing(Snapshot::id));
        var heads = new ArrayList<Snapshot>();
        var rest = new ArrayList<Snapshot>();
        var seen = new java.util.HashSet<String>();
        for (Snapshot value : newestFirst) {
            (seen.add(value.build().version()) ? heads : rest).add(value);
        }
        rest.sort(
                Comparator.comparing(Snapshot::downloadedAt, Comparator.reverseOrder())
                        .thenComparing(Snapshot::id));
        heads.addAll(rest);
        return heads;
    }

    /**
     * Deletes each product's snapshots beyond the retained count, except those in {@code keep}; the
     * number deleted. A snapshot that cannot be removed now (a file another process holds open, as
     * Windows refuses to delete it) is an issue, never a sync failure: it stays listed and is tried
     * again at the next sync.
     */
    private int prune(
            List<Snapshot> snapshots, Set<String> keep, List<String> issues, Cancellation token)
            throws IOException {
        var byProduct = new LinkedHashMap<String, List<Snapshot>>();
        for (Snapshot value : snapshots) {
            byProduct.computeIfAbsent(value.build().product(), key -> new ArrayList<>()).add(value);
        }
        int pruned = 0;
        for (var product : byProduct.values()) {
            var order = retainedOrder(product);
            for (Snapshot value : order.subList(Math.min(retained, order.size()), order.size())) {
                token.check();
                if (keep.contains(value.id())) {
                    continue;
                }
                try {
                    removeSnapshot(value.id());
                    pruned++;
                } catch (IOException | RuntimeException failure) {
                    issues.add(
                            "Could not remove older snapshot "
                                    + value.build().product()
                                    + " "
                                    + value.build().version()
                                    + " now; it is tried again at the next sync ("
                                    + (failure.getMessage() == null
                                            ? failure.getClass().getSimpleName()
                                            : failure.getMessage())
                                    + ")");
                }
            }
        }
        return pruned;
    }

    /** Test seam: told each snapshot moved to the trash, before its files are deleted. */
    static volatile Consumer<String> trashedForTest;

    /**
     * Removes one snapshot. Its open handles and remembered answers go first ({@link #release});
     * then its folder moves atomically to a trash name, so a crash never leaves a listed snapshot
     * without its manifest; then its database and the trash are deleted. Leftover trash is purged
     * at the next sync.
     */
    private void removeSnapshot(String id) throws IOException {
        release(id);
        Path folder = source(id);
        Path trash = root.resolve("sources").resolve(".trash-" + id + "-" + System.nanoTime());
        Files.move(folder, trash, StandardCopyOption.ATOMIC_MOVE);
        var seam = trashedForTest;
        if (seam != null) {
            seam.accept(id);
        }
        Path db = database(id);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-wal"));
        Files.deleteIfExists(Path.of(db + "-shm"));
        remove(trash);
    }

    /** Test seam: run by {@link #release} before a snapshot is removed. */
    static volatile Consumer<String> releaseForTest;

    /**
     * Closes everything this repository holds open for snapshot {@code id} and forgets answers it
     * remembered from it ({@link #forget}), before the snapshot's files are deleted: Windows
     * refuses to delete an open SQLite file.
     */
    private void release(String id) throws IOException {
        var seam = releaseForTest;
        if (seam != null) {
            seam.accept(id);
        }
        try {
            forget(id);
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Could not close snapshot " + id, failure);
        }
    }

    /**
     * Deletes trash a crash left behind, and prepared databases whose snapshot folder is gone. Best
     * effort: what cannot go now is tried again at the next sync.
     */
    private void purgeTrash() {
        try (var paths = Files.list(root.resolve("sources"))) {
            for (Path path :
                    paths.filter(p -> p.getFileName().toString().startsWith(".trash-")).toList()) {
                try {
                    remove(path);
                } catch (IOException failure) {
                    // Left for the next sync.
                }
            }
        } catch (IOException failure) {
            // Left for the next sync.
        }
        try (var paths = Files.list(root.resolve("derived"))) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                String id = name.replaceFirst("\\.sqlite(-wal|-shm)?$", "");
                if (id.matches("[0-9a-f]{64}")
                        && !id.equals(name)
                        && !Files.exists(source(id), LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        // Left for the next sync.
                    }
                }
            }
        } catch (IOException failure) {
            // Left for the next sync.
        }
    }

    public static Catalog parseIndex(String revision, Instant checkedAt, byte[] bytes)
            throws IOException {
        var index = EgJson.object(EgJson.parse(bytes, 1 << 20));
        checkCredit(index);
        var builds = new ArrayList<Build>();
        for (var product : EgJson.object(index.get("products")).entrySet()) {
            for (var version : EgJson.object(product.getValue()).entrySet()) {
                var datasets = new ArrayList<Dataset>();
                for (var entry : EgJson.object(version.getValue()).entrySet()) {
                    var info = EgJson.object(entry.getValue());
                    datasets.add(
                            new Dataset(
                                    entry.getKey(),
                                    EgJson.number(info.get("records")),
                                    EgJson.number(info.get("sources")),
                                    EgJson.text(info.get("lastCollectedOn"))));
                }
                datasets.sort(Comparator.comparing(Dataset::name));
                builds.add(new Build(revision, product.getKey(), version.getKey(), datasets));
                if (builds.size() > 1024) {
                    throw new IOException("Too many EG Link builds");
                }
            }
        }
        builds.sort(
                Comparator.comparing(Build::product)
                        .thenComparing(Build::version, EgLinkRepository::compareVersions));
        return new Catalog(revision, checkedAt, builds);
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 4; i++) {
            int comparison = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    public CompletableFuture<List<Snapshot>> snapshots(Cancellation token) {
        return call(token, () -> local(token));
    }

    private List<Snapshot> local(Cancellation token) throws Exception {
        var snapshots = new ArrayList<Snapshot>();
        try (var paths = Files.list(root.resolve("sources"))) {
            for (Path path :
                    paths.filter(value -> value.getFileName().toString().matches("[0-9a-f]{64}"))
                            .limit(1025)
                            .toList()) {
                token.check();
                if (!path.getFileName().toString().matches("[0-9a-f]{64}")) {
                    continue;
                }
                if (snapshots.size() >= 1024) {
                    throw new IOException("Too many retained EG Link snapshots");
                }
                var manifest = manifest(path.getFileName().toString());
                Snapshot snapshot = snapshot(manifest, null, null, null);
                Path db = database(snapshot.id());
                if (Files.exists(db, LinkOption.NOFOLLOW_LINKS)) {
                    try (var connection =
                            checkedDatabase(db, snapshot.id(), hashUnchecked(manifest))) {
                        long count =
                                connection.run(
                                        com.vibeminer.store.Cancellation.NONE,
                                        false,
                                        sql ->
                                                ((Number)
                                                                sql.query(
                                                                                "SELECT count(*) FROM eg_records")
                                                                        .getFirst()[0])
                                                        .longValue());
                        snapshot = snapshot(manifest, count, Files.size(db), null);
                    } catch (RuntimeException | IOException failure) {
                        snapshot =
                                snapshot(
                                        manifest,
                                        null,
                                        null,
                                        "Prepared data is unavailable; prepare again");
                    }
                }
                snapshots.add(snapshot);
            }
        }
        snapshots.sort(
                Comparator.comparing((Snapshot value) -> value.build().product())
                        .thenComparing(
                                value -> value.build().version(),
                                EgLinkRepository::compareVersions));
        return List.copyOf(snapshots);
    }

    public CompletableFuture<Snapshot> download(
            Build requested, Consumer<Progress> progress, Cancellation token) {
        return call(token, () -> downloadNow(requested, progress, token));
    }

    private Snapshot downloadNow(Build requested, Consumer<Progress> progress, Cancellation token)
            throws Exception {
        Catalog catalog = catalogNow();
        Build build =
                catalog.builds().stream()
                        .filter(requested::equals)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Choose an advertised EG Link snapshot"));
        String id = id(build);
        Path destination = source(id);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            var retained = manifest(id);
            verify(retained, token);
            return snapshot(retained, null, null, null);
        }
        if (local(token).size() >= 1024) {
            throw new IOException("Retained snapshot limit reached");
        }
        Path temporary = Files.createTempDirectory(root.resolve("sources"), ".download-");
        try {
            long bytes = 0;
            int done = 0;
            var hashes = new LinkedHashMap<String, Object>();
            for (Dataset dataset : build.datasets()) {
                token.check();
                progress.accept(
                        new Progress(
                                "Downloading " + dataset.name(), done, build.datasets().size()));
                String path =
                        "data/"
                                + build.product()
                                + "/"
                                + build.version()
                                + "/"
                                + dataset.name()
                                + ".json";
                var reply = client.fetchEgLinkFile(build.revision(), path, token).join();
                byte[] body = reply.bytes();
                bytes += body.length;
                if (bytes > MAX_SNAPSHOT_BYTES) {
                    throw new IOException("EG Link snapshot exceeds byte bound");
                }
                validateDataset(body, build, dataset);
                atomic(temporary.resolve(dataset.name() + ".json"), body);
                hashes.put(dataset.name(), sha(body));
                done++;
            }
            token.check();
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("format", "vimi-eg-link-source-v1");
            manifest.put("id", id);
            manifest.put("revision", build.revision());
            manifest.put("product", build.product());
            manifest.put("build", build.version());
            manifest.put("downloadedAt", Instant.now().toString());
            manifest.put("bytes", bytes);
            manifest.put("datasets", encodeDatasets(build.datasets()));
            manifest.put("hashes", hashes);
            atomic(temporary.resolve("source.json"), EgJson.bytes(manifest));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            progress.accept(new Progress("Original snapshot retained", done, done));
            return snapshot(manifest, null, null, null);
        } finally {
            remove(temporary);
        }
    }

    public CompletableFuture<Snapshot> prepare(
            String id, Consumer<Progress> progress, Cancellation token) {
        return call(token, () -> prepareNow(id, progress, token));
    }

    private Snapshot prepareNow(String id, Consumer<Progress> progress, Cancellation token)
            throws Exception {
        var manifest = manifest(id);
        verify(manifest, token);
        Build build = build(manifest);
        forget(id);
        Path destination = database(id),
                temporary =
                        root.resolve("derived")
                                .resolve(".prepare-" + UUID.randomUUID() + ".sqlite");
        try {
            long count;
            try (var connection = new DerivedSqliteConnection(temporary)) {
                count =
                        connection.run(
                                com.vibeminer.store.Cancellation.NONE,
                                true,
                                sql -> {
                                    sql.execute("PRAGMA application_id=" + APPLICATION_ID);
                                    sql.execute("PRAGMA user_version=1");
                                    sql.execute(
                                            "CREATE TABLE eg_metadata(k TEXT PRIMARY KEY,v TEXT NOT NULL) WITHOUT ROWID");
                                    sql.execute("INSERT INTO eg_metadata VALUES('source',?)", id);
                                    sql.execute(
                                            "INSERT INTO eg_metadata VALUES('source_manifest',?)",
                                            hashUnchecked(manifest));
                                    sql.execute(
                                            "CREATE TABLE eg_records(dataset TEXT NOT NULL,collection TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(dataset,collection,id)) WITHOUT ROWID");
                                    long indexed = 0;
                                    int done = 0;
                                    for (Dataset dataset : build.datasets()) {
                                        token.check();
                                        progress.accept(
                                                new Progress(
                                                        "Indexing " + dataset.name(),
                                                        done,
                                                        build.datasets().size()));
                                        try {
                                            byte[] bytes =
                                                    read(
                                                            source(id)
                                                                    .resolve(
                                                                            dataset.name()
                                                                                    + ".json"),
                                                            MAX_FILE_BYTES);
                                            var object = validateDataset(bytes, build, dataset);
                                            for (var collection : object.entrySet()) {
                                                if (collection.getKey().equals("meta")) {
                                                    continue;
                                                }
                                                if (collection.getKey().length() > 128) {
                                                    throw new IOException(
                                                            "Collection name exceeds bound");
                                                }
                                                if (collection.getValue()
                                                        instanceof Map<?, ?> values) {
                                                    for (var entry : values.entrySet()) {
                                                        token.check();
                                                        insert(
                                                                sql,
                                                                dataset.name(),
                                                                collection.getKey(),
                                                                entry.getKey().toString(),
                                                                entry.getValue());
                                                        if (++indexed > 1_000_000) {
                                                            throw new IOException(
                                                                    "Too many indexed EG Link records");
                                                        }
                                                    }
                                                } else if (collection.getValue()
                                                        instanceof List<?> values) {
                                                    for (int i = 0; i < values.size(); i++) {
                                                        token.check();
                                                        insert(
                                                                sql,
                                                                dataset.name(),
                                                                collection.getKey(),
                                                                Integer.toString(i),
                                                                values.get(i));
                                                        if (++indexed > 1_000_000) {
                                                            throw new IOException(
                                                                    "Too many indexed EG Link records");
                                                        }
                                                    }
                                                } else {
                                                    insert(
                                                            sql,
                                                            dataset.name(),
                                                            collection.getKey(),
                                                            "value",
                                                            collection.getValue());
                                                    indexed++;
                                                }
                                            }
                                        } catch (IOException failure) {
                                            throw new java.io.UncheckedIOException(failure);
                                        }
                                        done++;
                                    }
                                    sql.execute(
                                            "CREATE INDEX eg_names ON eg_records(dataset,collection,name COLLATE NOCASE,id)");
                                    return indexed;
                                });
                connection.run(
                        com.vibeminer.store.Cancellation.NONE,
                        false,
                        sql -> {
                            sql.query("PRAGMA wal_checkpoint(TRUNCATE)");
                            sql.execute("PRAGMA journal_mode=DELETE");
                            return null;
                        });
            }
            token.check();
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            progress.accept(new Progress("Prepared locally", count, count));
            return snapshot(manifest, count, Files.size(destination), null);
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(Path.of(temporary + "-wal"));
            Files.deleteIfExists(Path.of(temporary + "-shm"));
        }
    }

    private static void insert(
            DerivedSqliteConnection.Session sql,
            String dataset,
            String collection,
            String id,
            Object value)
            throws IOException {
        if (id.length() > 512) {
            throw new IOException("Record ID exceeds bound");
        }
        String name =
                value instanceof Map<?, ?> map && map.get("name") instanceof String text
                        ? text
                        : "";
        if (name.length() > 4096) {
            throw new IOException("Record name exceeds bound");
        }
        byte[] bytes = EgJson.bytes(value);
        if (bytes.length > (1 << 20)) {
            throw new IOException("Record exceeds byte bound");
        }
        sql.execute(
                "INSERT INTO eg_records VALUES(?,?,?,?,?)",
                dataset,
                collection,
                id,
                name,
                new String(bytes, StandardCharsets.UTF_8));
    }

    public CompletableFuture<Optional<Record>> lookup(
            String source, String dataset, String collection, String id, Cancellation token) {
        return call(
                token,
                () -> {
                    var manifest = requirePrepared(source);
                    try (var connection =
                            checkedDatabase(database(source), source, hashUnchecked(manifest))) {
                        return connection.run(
                                com.vibeminer.store.Cancellation.NONE,
                                false,
                                sql ->
                                        sql
                                                .query(
                                                        "SELECT dataset,collection,id,name,json FROM eg_records WHERE dataset=? AND collection=? AND id=?",
                                                        bounded(dataset, 128),
                                                        bounded(collection, 128),
                                                        bounded(id, 512))
                                                .stream()
                                                .map(row -> record(source, row))
                                                .findFirst());
                    }
                });
    }

    /** The most displays one creature record may list. */
    static final int MAX_CREATURE_DISPLAYS = 64;

    /**
     * The displays the snapshot's {@code creature_details.<locale>} records for one creature, or
     * empty when the snapshot has no such dataset or record. A record whose displays are malformed
     * is an error, never a partial list.
     */
    public CompletableFuture<Optional<EgLinkTypes.CreatureDisplays>> creatureDisplays(
            String source, String locale, long creatureId, Cancellation token) {
        return creatureDisplays(source, locale, List.of(creatureId), token)
                .thenApply(
                        found -> {
                            var answer = found.get(creatureId);
                            if (answer.isPresent() && answer.get().malformed()) {
                                // One creature asked for: its malformed record is an error.
                                throw new java.util.concurrent.CompletionException(
                                        new IOException(answer.get().problem()));
                            }
                            return answer;
                        });
    }

    /** The most remembered creature display lookups (one per snapshot, locale and creature). */
    static final int DISPLAY_CACHE = 16_384;

    /** Creature IDs read per query of a batch lookup. */
    private static final int DISPLAY_QUERY_IDS = 500;

    /**
     * As {@link #creatureDisplays(String, String, long, Cancellation)} for several creatures at
     * once: remembered answers return at once, the rest are read in one task through the snapshot's
     * open read-only database. A prepared snapshot never changes (its ID is its content), so
     * answers, absences included, are remembered (bounded). A record whose displays are malformed
     * is answered as recorded but unreadable ({@link EgLinkTypes.CreatureDisplays#malformed}), so
     * it never fails the rest of the batch.
     */
    public CompletableFuture<Map<Long, Optional<EgLinkTypes.CreatureDisplays>>> creatureDisplays(
            String source,
            String locale,
            java.util.Collection<Long> creatureIds,
            Cancellation token) {
        for (long id : creatureIds) {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Creature ID is uint32");
            }
        }
        String dataset = "creature_details." + bounded(locale, 8);
        synchronized (this) {
            if (closing) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("EG Link repository is closed"));
            }
        }
        var found = new LinkedHashMap<Long, Optional<EgLinkTypes.CreatureDisplays>>();
        var missing = new java.util.LinkedHashSet<Long>();
        synchronized (displayCache) {
            for (long id : creatureIds) {
                var hit = displayCache.get(source + "|" + dataset + "|" + id);
                if (hit != null) {
                    found.put(id, hit);
                } else {
                    missing.add(id);
                }
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(found);
        }
        return call(
                token,
                () -> {
                    var read = readDisplays(source, dataset, List.copyOf(missing), token);
                    synchronized (displayCache) {
                        read.forEach(
                                (id, value) ->
                                        displayCache.put(source + "|" + dataset + "|" + id, value));
                    }
                    var answer = new LinkedHashMap<>(found);
                    answer.putAll(read);
                    return answer;
                });
    }

    /** Worker only: one prepared snapshot's database, opened once and checked once. */
    private record OpenSnapshot(DerivedSqliteConnection connection, Build build) {}

    private final Map<String, OpenSnapshot> openSnapshots = new LinkedHashMap<>();

    /** Tests: how many snapshot databases are open now (read on the worker). */
    CompletableFuture<Integer> openSnapshotCount() {
        return call(Cancellation.NONE, openSnapshots::size);
    }

    private final Map<String, Optional<EgLinkTypes.CreatureDisplays>> displayCache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, Optional<EgLinkTypes.CreatureDisplays>> eldest) {
                    return size() > DISPLAY_CACHE;
                }
            };

    /** Worker only. */
    private OpenSnapshot open(String source) throws Exception {
        var open = openSnapshots.get(source);
        if (open == null) {
            var manifest = requirePrepared(source);
            if (openSnapshots.size() >= 4) {
                // A small pool: the oldest open snapshot is closed first.
                var eldest = openSnapshots.keySet().iterator().next();
                openSnapshots.remove(eldest).connection().close();
            }
            open =
                    new OpenSnapshot(
                            checkedDatabase(database(source), source, hashUnchecked(manifest)),
                            build(manifest));
            openSnapshots.put(source, open);
        }
        return open;
    }

    /**
     * Worker only: closes a snapshot's open database handle and forgets its remembered answers.
     * Call it before replacing or deleting the snapshot's database (pruning included): an open
     * SQLite file cannot be deleted on Windows.
     */
    void forget(String source) throws Exception {
        var open = openSnapshots.remove(source);
        if (open != null) {
            open.connection().close();
        }
        synchronized (displayCache) {
            displayCache.keySet().removeIf(key -> key.startsWith(source + "|"));
        }
    }

    /** Worker only. */
    private Map<Long, Optional<EgLinkTypes.CreatureDisplays>> readDisplays(
            String source, String dataset, List<Long> ids, Cancellation token) throws Exception {
        var answer = new LinkedHashMap<Long, Optional<EgLinkTypes.CreatureDisplays>>();
        var open = open(source);
        var recorded =
                open.build().datasets().stream()
                        .filter(value -> value.name().equals(dataset))
                        .findFirst();
        for (long id : ids) {
            answer.put(id, Optional.empty());
        }
        if (recorded.isEmpty()) {
            return answer;
        }
        for (int from = 0; from < ids.size(); from += DISPLAY_QUERY_IDS) {
            token.check();
            var chunk = ids.subList(from, Math.min(ids.size(), from + DISPLAY_QUERY_IDS));
            var parameters = new Object[chunk.size() + 1];
            parameters[0] = dataset;
            for (int i = 0; i < chunk.size(); i++) {
                parameters[i + 1] = Long.toString(chunk.get(i));
            }
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            var rows =
                    open.connection()
                            .run(
                                    com.vibeminer.store.Cancellation.NONE,
                                    false,
                                    sql ->
                                            sql.query(
                                                    "SELECT dataset,collection,id,name,json FROM eg_records WHERE dataset=? AND collection='creatures' AND id IN ("
                                                            + marks
                                                            + ")",
                                                    parameters));
            for (var row : rows) {
                long id;
                try {
                    id = Long.parseLong(String.valueOf(row[2]));
                } catch (NumberFormatException unnamed) {
                    continue; // never one of the numeric IDs asked for
                }
                List<EgLinkTypes.CreatureDisplay> displays = List.of();
                String problem = null;
                try {
                    displays = parseDisplays(record(source, row).json());
                } catch (IOException | RuntimeException malformed) {
                    // One malformed record never fails the others: recorded, but unreadable.
                    problem =
                            malformed.getMessage() == null
                                    ? malformed.getClass().getSimpleName()
                                    : malformed.getMessage();
                }
                answer.put(
                        id,
                        Optional.of(
                                new EgLinkTypes.CreatureDisplays(
                                        source,
                                        open.build().product(),
                                        open.build().version(),
                                        recorded.get().lastCollectedOn(),
                                        id,
                                        displays,
                                        problem)));
            }
        }
        return answer;
    }

    /** The {@code displays} of one {@code creature_details} creature record, validated. */
    static List<EgLinkTypes.CreatureDisplay> parseDisplays(String json) throws IOException {
        Object parsed = EgJson.parse(json.getBytes(StandardCharsets.UTF_8), 1 << 20);
        if (!(parsed instanceof Map<?, ?> creature)) {
            throw new IOException("Creature record is not an object");
        }
        Object listed = creature.get("displays");
        if (listed == null) {
            return List.of();
        }
        if (!(listed instanceof List<?> displays) || displays.size() > MAX_CREATURE_DISPLAYS) {
            throw new IOException("Creature displays are not a bounded list");
        }
        var result = new ArrayList<EgLinkTypes.CreatureDisplay>();
        for (Object value : displays) {
            if (!(value instanceof Map<?, ?> display)
                    || !(display.get("displayId") instanceof Number id)
                    || !(display.get("probability") instanceof Number probability)) {
                throw new IOException("Creature display lacks its ID or probability");
            }
            double scale =
                    display.get("scale") instanceof Number recorded ? recorded.doubleValue() : 1;
            try {
                result.add(
                        new EgLinkTypes.CreatureDisplay(
                                EgJson.number(id), probability.doubleValue(), scale));
            } catch (IllegalArgumentException invalid) {
                throw new IOException(invalid.getMessage(), invalid);
            }
        }
        return List.copyOf(result);
    }

    public CompletableFuture<Page> search(
            String source,
            String dataset,
            String collection,
            String search,
            int offset,
            int limit,
            Cancellation token) {
        return call(
                token,
                () -> {
                    if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 200) {
                        throw new IllegalArgumentException("Invalid page bounds");
                    }
                    var manifest = requirePrepared(source);
                    try (var connection =
                            checkedDatabase(database(source), source, hashUnchecked(manifest))) {
                        return connection.run(
                                com.vibeminer.store.Cancellation.NONE,
                                false,
                                sql -> {
                                    String text =
                                            bounded(search, 200)
                                                    .replace("\\", "\\\\")
                                                    .replace("%", "\\%")
                                                    .replace("_", "\\_");
                                    var rows =
                                            sql.query(
                                                    "SELECT dataset,collection,id,name,json FROM eg_records WHERE dataset=? AND collection=? AND name LIKE ? ESCAPE '\\' ORDER BY name COLLATE NOCASE,id LIMIT ? OFFSET ?",
                                                    bounded(dataset, 128),
                                                    bounded(collection, 128),
                                                    "%" + text + "%",
                                                    limit + 1,
                                                    offset);
                                    return new Page(
                                            rows.stream()
                                                    .limit(limit)
                                                    .map(row -> record(source, row))
                                                    .toList(),
                                            rows.size() > limit);
                                });
                    }
                });
    }

    private Map<String, Object> requirePrepared(String id) throws Exception {
        var manifest = manifest(id);
        if (!Files.isRegularFile(database(id), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Prepare this EG Link snapshot first");
        }
        return manifest;
    }

    private static Record record(String source, Object[] row) {
        return new Record(
                source,
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4]);
    }

    private static String bounded(String value, int max) {
        if (value == null || value.length() > max) {
            throw new IllegalArgumentException("Query exceeds bounds");
        }
        return value;
    }

    private Catalog catalogNow() throws Exception {
        var stored =
                EgJson.object(EgJson.parse(read(root.resolve("catalog.json"), 2 << 20), 2 << 20));
        if (!"vimi-eg-link-catalog-v1".equals(stored.get("format"))) {
            throw new IOException("Unsupported EG Link catalog format");
        }
        return parseIndex(
                EgJson.text(stored.get("revision")),
                Instant.parse(EgJson.text(stored.get("checkedAt"))),
                EgJson.bytes(stored.get("index")));
    }

    private Map<String, Object> manifest(String id) throws Exception {
        Path folder = source(id);
        var value =
                EgJson.object(EgJson.parse(read(folder.resolve("source.json"), 1 << 20), 1 << 20));
        if (!"vimi-eg-link-source-v1".equals(value.get("format"))
                || !id.equals(value.get("id"))
                || !id.equals(id(build(value)))) {
            throw new IOException("Invalid retained source identity");
        }
        EgJson.number(value.get("bytes"));
        Instant.parse(EgJson.text(value.get("downloadedAt")));
        return value;
    }

    private void verify(Map<String, Object> manifest, Cancellation token) throws Exception {
        Build build = build(manifest);
        var hashes = EgJson.object(manifest.get("hashes"));
        long total = 0;
        for (Dataset dataset : build.datasets()) {
            token.check();
            byte[] bytes =
                    read(source(id(build)).resolve(dataset.name() + ".json"), MAX_FILE_BYTES);
            total += bytes.length;
            if (total > MAX_SNAPSHOT_BYTES || !sha(bytes).equals(hashes.get(dataset.name()))) {
                throw new IOException("Retained EG Link source failed verification");
            }
            validateDataset(bytes, build, dataset);
        }
        if (total != EgJson.number(manifest.get("bytes"))) {
            throw new IOException("Retained source length differs");
        }
    }

    private static Map<String, Object> validateDataset(byte[] bytes, Build build, Dataset dataset)
            throws IOException {
        var object = EgJson.object(EgJson.parse(bytes, MAX_FILE_BYTES));
        var meta = EgJson.object(object.get("meta"));
        checkCredit(meta);
        if (!build.product().equals(meta.get("product"))
                || !build.version().equals(meta.get("clientBuild"))
                || !dataset.kind().equals(meta.get("dataset"))
                || (!dataset.locale().isEmpty() && !dataset.locale().equals(meta.get("locale")))
                || EgJson.number(meta.get("schemaVersion")) != 1) {
            throw new IOException("Dataset identity or schema differs");
        }
        return object;
    }

    private static void checkCredit(Map<String, Object> object) throws IOException {
        if (!EgLinkTypes.LICENSE.equals(object.get("license"))
                || !(object.get("attribution") instanceof String text)
                || !text.equals(EgLinkTypes.ATTRIBUTION)) {
            throw new IOException("Unsupported EG Link attribution/license");
        }
    }

    private static List<Object> encodeDatasets(List<Dataset> values) {
        return values.stream()
                .map(
                        value ->
                                (Object)
                                        Map.of(
                                                "name",
                                                value.name(),
                                                "records",
                                                value.declaredRecords(),
                                                "sources",
                                                value.sources(),
                                                "date",
                                                value.lastCollectedOn()))
                .toList();
    }

    private static Build build(Map<String, Object> value) {
        if (!(value.get("datasets") instanceof List<?> datasets)) {
            throw new IllegalArgumentException("Missing dataset inventory");
        }
        return new Build(
                EgJson.text(value.get("revision")),
                EgJson.text(value.get("product")),
                EgJson.text(value.get("build")),
                datasets.stream()
                        .map(EgJson::object)
                        .map(
                                item ->
                                        new Dataset(
                                                EgJson.text(item.get("name")),
                                                EgJson.number(item.get("records")),
                                                EgJson.number(item.get("sources")),
                                                EgJson.text(item.get("date"))))
                        .toList());
    }

    private static Snapshot snapshot(
            Map<String, Object> value, Long records, Long bytes, String problem) {
        return new Snapshot(
                EgJson.text(value.get("id")),
                build(value),
                Instant.parse(EgJson.text(value.get("downloadedAt"))),
                EgJson.number(value.get("bytes")),
                records,
                bytes,
                problem);
    }

    private static DerivedSqliteConnection checkedDatabase(
            Path file, String id, String expectedManifest) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Prepared EG Link file is unavailable");
        }
        var connection = DerivedSqliteConnection.openReadOnly(file);
        try {
            connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    sql -> {
                        if (((Number) sql.query("PRAGMA application_id").getFirst()[0]).intValue()
                                        != APPLICATION_ID
                                || ((Number) sql.query("PRAGMA user_version").getFirst()[0])
                                                .intValue()
                                        != 1
                                || !id.equals(
                                        sql.query("SELECT v FROM eg_metadata WHERE k='source'")
                                                .getFirst()[0])) {
                            throw new IllegalStateException(
                                    "Unsupported prepared EG Link database");
                        }
                        if (!expectedManifest.equals(
                                sql.query("SELECT v FROM eg_metadata WHERE k='source_manifest'")
                                        .getFirst()[0])) {
                            throw new IllegalStateException(
                                    "Prepared EG Link source identity differs");
                        }
                        sql.query("SELECT dataset,collection,id,name,json FROM eg_records LIMIT 0");
                        return null;
                    });
            return connection;
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    private Path source(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid EG Link source ID");
        }
        Path path = root.resolve("sources").resolve(id);
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Symbolic EG Link snapshots are not supported");
        }
        return path;
    }

    private Path database(String id) {
        source(id);
        return root.resolve("derived").resolve(id + ".sqlite");
    }

    private static String id(Build build) {
        return sha(
                (build.revision() + "\n" + build.product() + "\n" + build.version())
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static String hashUnchecked(Map<String, Object> value) {
        try {
            return sha(EgJson.bytes(value));
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
            throw new IOException("Retained EG Link file unavailable or oversized");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("EG Link file exceeds bound");
            }
            return bytes;
        }
    }

    private static void atomic(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling("." + path.getFileName() + "-" + UUID.randomUUID());
        try {
            try (var channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void remove(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(child);
            }
        }
    }

    @FunctionalInterface
    private interface Task<T> {
        T run() throws Exception;
    }

    private synchronized <T> CompletableFuture<T> call(Cancellation token, Task<T> task) {
        var result = new CompletableFuture<T>();
        if (closing) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("EG Link repository is closed"));
        }
        try {
            worker.execute(
                    () -> {
                        try {
                            token.check();
                            T value = task.run();
                            Thread.startVirtualThread(() -> result.complete(value));
                        } catch (Throwable failure) {
                            Thread.startVirtualThread(() -> result.completeExceptionally(failure));
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed != null) {
            return closed.copy();
        }
        closing = true;
        worker.shutdown();
        closed = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        for (var open : openSnapshots.values()) {
                            try {
                                open.connection().close();
                            } catch (Exception ignored) {
                                // Read-only handles: nothing to lose.
                            }
                        }
                        openSnapshots.clear();
                        closed.complete(null);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        closed.completeExceptionally(failure);
                    }
                });
        return closed.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
