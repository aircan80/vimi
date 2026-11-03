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
    private static final int APPLICATION_ID = 0x45474c31;
    private final Path root;
    private final EgLinkClient client;
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
        return call(
                token,
                () -> {
                    Path existing = root.resolve("catalog.json");
                    if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                        var previous =
                                EgJson.object(EgJson.parse(read(existing, 2 << 20), 2 << 20));
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
                });
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
        return call(
                token,
                () -> {
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
                    Path temporary =
                            Files.createTempDirectory(root.resolve("sources"), ".download-");
                    try {
                        long bytes = 0;
                        int done = 0;
                        var hashes = new LinkedHashMap<String, Object>();
                        for (Dataset dataset : build.datasets()) {
                            token.check();
                            progress.accept(
                                    new Progress(
                                            "Downloading " + dataset.name(),
                                            done,
                                            build.datasets().size()));
                            String path =
                                    "data/"
                                            + build.product()
                                            + "/"
                                            + build.version()
                                            + "/"
                                            + dataset.name()
                                            + ".json";
                            var reply =
                                    client.fetchEgLinkFile(build.revision(), path, token).join();
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
                });
    }

    public CompletableFuture<Snapshot> prepare(
            String id, Consumer<Progress> progress, Cancellation token) {
        return call(
                token,
                () -> {
                    var manifest = manifest(id);
                    verify(manifest, token);
                    Build build = build(manifest);
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
                                                sql.execute(
                                                        "PRAGMA application_id=" + APPLICATION_ID);
                                                sql.execute("PRAGMA user_version=1");
                                                sql.execute(
                                                        "CREATE TABLE eg_metadata(k TEXT PRIMARY KEY,v TEXT NOT NULL) WITHOUT ROWID");
                                                sql.execute(
                                                        "INSERT INTO eg_metadata VALUES('source',?)",
                                                        id);
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
                                                                                        dataset
                                                                                                        .name()
                                                                                                + ".json"),
                                                                        MAX_FILE_BYTES);
                                                        var object =
                                                                validateDataset(
                                                                        bytes, build, dataset);
                                                        for (var collection : object.entrySet()) {
                                                            if (collection
                                                                    .getKey()
                                                                    .equals("meta")) {
                                                                continue;
                                                            }
                                                            if (collection.getKey().length()
                                                                    > 128) {
                                                                throw new IOException(
                                                                        "Collection name exceeds bound");
                                                            }
                                                            if (collection.getValue()
                                                                    instanceof Map<?, ?> values) {
                                                                for (var entry :
                                                                        values.entrySet()) {
                                                                    token.check();
                                                                    insert(
                                                                            sql,
                                                                            dataset.name(),
                                                                            collection.getKey(),
                                                                            entry.getKey()
                                                                                    .toString(),
                                                                            entry.getValue());
                                                                    if (++indexed > 1_000_000) {
                                                                        throw new IOException(
                                                                                "Too many indexed EG Link records");
                                                                    }
                                                                }
                                                            } else if (collection.getValue()
                                                                    instanceof List<?> values) {
                                                                for (int i = 0;
                                                                        i < values.size();
                                                                        i++) {
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
                                                        throw new java.io.UncheckedIOException(
                                                                failure);
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
                });
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
