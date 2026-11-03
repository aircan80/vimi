package com.vibeminer.polestar;

import com.vibeminer.network.Cancellation;
import com.vibeminer.network.PolestarClient;
import com.vibeminer.polestar.PolestarTypes.Catalog;
import com.vibeminer.polestar.PolestarTypes.Page;
import com.vibeminer.polestar.PolestarTypes.Progress;
import com.vibeminer.polestar.PolestarTypes.Record;
import com.vibeminer.polestar.PolestarTypes.Snapshot;
import com.vibeminer.polestar.PolestarTypes.Table;
import com.vibeminer.store.DerivedSqliteConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

/** Independent GitHub snapshots and indexed local records. GUI never owns provider SQL. */
public final class PolestarRepository implements AutoCloseable {
    public static final int MAX_FILE_BYTES = 16 << 20, MAX_SNAPSHOT_BYTES = 64 << 20;
    private static final int APPLICATION_ID = 0x50535431;
    private static final List<String> NOTICES =
            List.of("NOTICE.md", "README.md", "LICENSE", "LICENSE-CC-BY-4.0.txt");
    private final Path root;
    private final PolestarClient client;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("vimi-polestar").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private boolean closing;
    private CompletableFuture<Void> closed;

    public PolestarRepository(Path directory, PolestarClient client) throws IOException {
        root = directory.toAbsolutePath().normalize();
        this.client = java.util.Objects.requireNonNull(client);
        Files.createDirectories(root.resolve("sources"));
        Files.createDirectories(root.resolve("derived"));
    }

    public CompletableFuture<Optional<Catalog>> catalog(Cancellation token) {
        return call(
                token,
                () -> {
                    Path path = root.resolve("catalog.json");
                    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                            ? Optional.of(catalog(read(path, 3 << 20)))
                            : Optional.empty();
                });
    }

    public CompletableFuture<Catalog> check(Cancellation token) {
        return call(
                token,
                () -> {
                    var head = client.fetchPolestarHead(token).join();
                    String revision =
                            Json.string(
                                    Json.object(
                                                    Json.object(Json.parse(head.bytes(), 64 << 10))
                                                            .get("object"))
                                            .get("sha"));
                    if (!revision.matches("[0-9a-f]{40}")) {
                        throw new IOException("Expected immutable Polestar commit");
                    }
                    var index = client.fetchPolestarFile(revision, "index.json", token).join();
                    var pack = client.fetchPolestarFile(revision, "datapackage.json", token).join();
                    var value =
                            Manifest.parse(
                                    revision, pack.observedAt(), index.bytes(), pack.bytes());
                    token.check();
                    atomic(
                            root.resolve("catalog.json"),
                            envelope(value, index.bytes(), pack.bytes()));
                    return value;
                });
    }

    public static Catalog inspect(String revision, Instant checkedAt, byte[] index, byte[] pack) {
        return Manifest.parse(revision, checkedAt, index, pack);
    }

    public CompletableFuture<Snapshot> download(
            Catalog requested, Consumer<Progress> progress, Cancellation token) {
        return call(
                token,
                () -> {
                    checkId(requested.revision());
                    var current = catalog(read(root.resolve("catalog.json"), 3 << 20));
                    if (!current.equals(requested)) {
                        throw new IllegalArgumentException(
                                "Choose the catalog checked by this repository");
                    }
                    Path destination = source(current.revision());
                    if (Files.exists(
                            destination.resolve("snapshot.json"), LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            var existing = snapshot(destination);
                            if (!samePublication(existing.catalog(), current)) {
                                throw new IOException("Retained package manifest differs");
                            }
                            verifyOriginals(destination, current, token);
                            return existing;
                        } catch (IOException | IllegalArgumentException invalid) {
                            // Retain the invalid copy until a complete verified replacement exists.
                        }
                    }
                    Path staging =
                            root.resolve("sources").resolve(".download-" + UUID.randomUUID());
                    Files.createDirectory(staging);
                    try {
                        byte[] stored = read(root.resolve("catalog.json"), 3 << 20);
                        var metadata = Json.object(Json.parse(stored, 3 << 20));
                        Files.write(
                                staging.resolve("index.json"),
                                java.util.Base64.getDecoder()
                                        .decode(Json.string(metadata.get("indexBytes"))));
                        Files.write(
                                staging.resolve("datapackage.json"),
                                java.util.Base64.getDecoder()
                                        .decode(Json.string(metadata.get("packageBytes"))));
                        var hashes = new LinkedHashMap<String, Object>();
                        long done = 0;
                        for (var resource : current.resources()) {
                            token.check();
                            String name = resource.table().wire();
                            String path = "data/" + name + ".jsonl";
                            byte[] bytes =
                                    client.fetchPolestarFile(current.revision(), path, token)
                                            .join()
                                            .bytes();
                            if (bytes.length != resource.bytes()
                                    || !hash(bytes).equals(resource.sha256())) {
                                throw new IOException(
                                        "Published resource checksum differs: " + name);
                            }
                            Files.write(
                                    staging.resolve(name + ".jsonl"),
                                    bytes,
                                    StandardOpenOption.CREATE_NEW);
                            byte[] schema =
                                    client.fetchPolestarFile(
                                                    current.revision(),
                                                    "schema/" + name + ".schema.json",
                                                    token)
                                            .join()
                                            .bytes();
                            new RowSchema(schema, name);
                            Files.write(
                                    staging.resolve(name + ".schema.json"),
                                    schema,
                                    StandardOpenOption.CREATE_NEW);
                            hashes.put(name + ".schema.json", hash(schema));
                            done += bytes.length;
                            progress.accept(
                                    new Progress("Downloaded " + name, done, current.bytes()));
                        }
                        for (String notice : NOTICES) {
                            byte[] bytes =
                                    client.fetchPolestarFile(current.revision(), notice, token)
                                            .join()
                                            .bytes();
                            if (bytes.length == 0 || bytes.length > 1 << 20) {
                                throw new IOException("Invalid source notice");
                            }
                            Files.write(
                                    staging.resolve(notice), bytes, StandardOpenOption.CREATE_NEW);
                            hashes.put(notice, hash(bytes));
                        }
                        hashes.put(
                                "index.json", hash(read(staging.resolve("index.json"), 1 << 20)));
                        hashes.put(
                                "datapackage.json",
                                hash(read(staging.resolve("datapackage.json"), 1 << 20)));
                        var completed = new LinkedHashMap<String, Object>();
                        completed.put("revision", current.revision());
                        completed.put("downloadedAt", Instant.now().toString());
                        completed.put("catalog", Json.parse(stored, 3 << 20));
                        completed.put("hashes", hashes);
                        token.check();
                        Files.write(
                                staging.resolve("snapshot.json"),
                                Json.bytes(completed),
                                StandardOpenOption.CREATE_NEW);
                        Path previous = null;
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            previous =
                                    root.resolve("sources")
                                            .resolve(
                                                    ".replaced-"
                                                            + current.revision()
                                                            + "-"
                                                            + UUID.randomUUID());
                            Files.move(destination, previous, StandardCopyOption.ATOMIC_MOVE);
                        }
                        try {
                            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException failure) {
                            if (previous != null
                                    && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                                Files.move(previous, destination, StandardCopyOption.ATOMIC_MOVE);
                            }
                            throw failure;
                        }
                        return snapshot(destination);
                    } finally {
                        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                            remove(staging);
                        }
                    }
                });
    }

    /** Check the pinned publication, reuse complete local artifacts, and prepare missing data. */
    public CompletableFuture<Snapshot> sync(Consumer<Progress> progress, Cancellation token) {
        progress.accept(new Progress("Checking Polestar GitHub publication", 0, 0));
        return check(token)
                .thenCompose(value -> download(value, progress, token))
                .thenCompose(value -> prepare(value.id(), progress, token));
    }

    public CompletableFuture<List<Snapshot>> snapshots(Cancellation token) {
        return call(
                token,
                () -> {
                    var result = new ArrayList<Snapshot>();
                    try (var entries = Files.list(root.resolve("sources"))) {
                        for (var path :
                                entries.filter(
                                                p ->
                                                        p.getFileName()
                                                                .toString()
                                                                .matches("[0-9a-f]{40}"))
                                        .limit(129)
                                        .toList()) {
                            token.check();
                            if (result.size() == 128) {
                                throw new IOException("Too many retained versions");
                            }
                            result.add(snapshot(path));
                        }
                    }
                    return result.stream()
                            .sorted(
                                    java.util.Comparator.comparing(Snapshot::downloadedAt)
                                            .reversed())
                            .toList();
                });
    }

    public CompletableFuture<Snapshot> prepare(
            String id, Consumer<Progress> progress, Cancellation token) {
        return call(
                token,
                () -> {
                    var source = snapshot(source(id));
                    if (source.preparedAt() != null) {
                        return source;
                    }
                    Path temporary =
                            root.resolve("derived")
                                    .resolve(".prepare-" + UUID.randomUUID() + ".sqlite");
                    try {
                        try (var connection = new DerivedSqliteConnection(temporary)) {
                            connection.run(
                                    com.vibeminer.store.Cancellation.NONE,
                                    false,
                                    sql -> {
                                        sql.execute("PRAGMA application_id=" + APPLICATION_ID);
                                        sql.execute("PRAGMA user_version=1");
                                        sql.execute(
                                                "CREATE TABLE metadata(k TEXT PRIMARY KEY,v TEXT NOT NULL) WITHOUT ROWID");
                                        sql.execute(
                                                "CREATE TABLE resources(dataset TEXT PRIMARY KEY,license TEXT NOT NULL,rows INTEGER NOT NULL) WITHOUT ROWID");
                                        sql.execute(
                                                "CREATE TABLE records(dataset TEXT NOT NULL,ordinal INTEGER NOT NULL,record_key TEXT NOT NULL,name TEXT NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(dataset,ordinal)) WITHOUT ROWID");
                                        sql.execute(
                                                "CREATE INDEX records_key ON records(dataset,record_key)");
                                        sql.execute(
                                                "CREATE UNIQUE INDEX unique_entity ON records(dataset,record_key) WHERE dataset IN ('quest','npc','object','item','ui_map','step','note')");
                                        sql.execute(
                                                "CREATE INDEX records_name ON records(dataset,name COLLATE NOCASE)");
                                        return null;
                                    });
                            long total = 0;
                            for (var resource : source.catalog().resources()) {
                                token.check();
                                String name = resource.table().wire();
                                byte[] schema = verifiedAux(source(id), name + ".schema.json");
                                var validator = new RowSchema(schema, name);
                                Path path = source(id).resolve(name + ".jsonl");
                                byte[] bytes = read(path, MAX_FILE_BYTES);
                                if (bytes.length != resource.bytes()
                                        || !hash(bytes).equals(resource.sha256())) {
                                    throw new IOException(
                                            "Retained resource checksum differs: " + name);
                                }
                                final long[] count = {0};
                                try (var reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        new java.io.ByteArrayInputStream(bytes),
                                                        StandardCharsets.UTF_8
                                                                .newDecoder()
                                                                .onMalformedInput(
                                                                        java.nio.charset
                                                                                .CodingErrorAction
                                                                                .REPORT)))) {
                                    connection.run(
                                            com.vibeminer.store.Cancellation.NONE,
                                            true,
                                            sql -> {
                                                sql.execute(
                                                        "INSERT INTO resources VALUES(?,?,?)",
                                                        name,
                                                        resource.license(),
                                                        resource.rows());
                                                try {
                                                    String line;
                                                    while ((line = reader.readLine()) != null) {
                                                        token.check();
                                                        if (line.isBlank()) {
                                                            continue;
                                                        }
                                                        byte[] rowBytes =
                                                                line.getBytes(
                                                                        StandardCharsets.UTF_8);
                                                        var fields =
                                                                Json.object(
                                                                        Json.parse(
                                                                                rowBytes, 1 << 20));
                                                        validator.validate(fields);
                                                        if (++count[0] > resource.rows()) {
                                                            throw new IllegalArgumentException(
                                                                    "Resource exceeds declared row count");
                                                        }
                                                        String key = key(fields, count[0]);
                                                        String title = text(fields);
                                                        sql.execute(
                                                                "INSERT INTO records VALUES(?,?,?,?,?)",
                                                                name,
                                                                count[0],
                                                                key,
                                                                title,
                                                                rowBytes);
                                                    }
                                                } catch (IOException failure) {
                                                    throw new java.io.UncheckedIOException(failure);
                                                }
                                                return null;
                                            });
                                }
                                if (count[0] != resource.rows()) {
                                    throw new IOException("Resource row count differs: " + name);
                                }
                                total += count[0];
                                progress.accept(
                                        new Progress(
                                                "Prepared " + name,
                                                total,
                                                source.catalog().rows()));
                            }
                            token.check();
                            connection.run(
                                    com.vibeminer.store.Cancellation.NONE,
                                    true,
                                    sql -> {
                                        sql.execute("INSERT INTO metadata VALUES('source',?)", id);
                                        sql.execute(
                                                "INSERT INTO metadata VALUES('preparedAt',?)",
                                                Instant.now().toString());
                                        sql.execute(
                                                "INSERT INTO metadata VALUES('profile','polestar-jsonl-schema1-v1')");
                                        return null;
                                    });
                            connection.run(
                                    com.vibeminer.store.Cancellation.NONE,
                                    false,
                                    sql -> {
                                        sql.query("PRAGMA wal_checkpoint(TRUNCATE)");
                                        return null;
                                    });
                        }
                        token.check();
                        Files.deleteIfExists(Path.of(database(id) + "-wal"));
                        Files.deleteIfExists(Path.of(database(id) + "-shm"));
                        Files.move(
                                temporary,
                                database(id),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        return snapshot(source(id));
                    } finally {
                        Files.deleteIfExists(temporary);
                        Files.deleteIfExists(Path.of(temporary + "-wal"));
                        Files.deleteIfExists(Path.of(temporary + "-shm"));
                    }
                });
    }

    public CompletableFuture<Page> search(
            String sourceId, Table table, String query, int offset, int limit, Cancellation token) {
        if (query == null || query.length() > 512 || offset < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Invalid record query");
        }
        return call(token, () -> query(sourceId, table, null, query, offset, limit, token));
    }

    public CompletableFuture<List<Record>> lookup(
            String sourceId, Table table, String key, Cancellation token) {
        if (key == null || key.length() > 256) {
            throw new IllegalArgumentException("Invalid record key");
        }
        return call(token, () -> query(sourceId, table, key, "", 0, 500, token).records());
    }

    private Page query(
            String id,
            Table table,
            String key,
            String query,
            int offset,
            int limit,
            Cancellation token)
            throws Exception {
        var snapshot = snapshot(source(id));
        if (snapshot.preparedAt() == null) {
            throw new IllegalStateException("Prepare this version first");
        }
        try (var connection = DerivedSqliteConnection.openReadOnly(database(id))) {
            return connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    true,
                    sql -> {
                        token.check();
                        String condition =
                                key == null
                                        ? "dataset=? AND name LIKE ? ESCAPE '\\'"
                                        : "dataset=? AND record_key=?";
                        String pattern =
                                key == null
                                        ? "%"
                                                + query.replace("\\", "\\\\")
                                                        .replace("%", "\\%")
                                                        .replace("_", "\\_")
                                                + "%"
                                        : key;
                        long total =
                                ((Number)
                                                sql.query(
                                                                "SELECT count(*) FROM records WHERE "
                                                                        + condition,
                                                                table.wire(),
                                                                pattern)
                                                        .getFirst()[0])
                                        .longValue();
                        var records = new ArrayList<Record>();
                        String license =
                                snapshot.catalog().resources().stream()
                                        .filter(r -> r.table() == table)
                                        .findFirst()
                                        .orElseThrow()
                                        .license();
                        for (var row :
                                sql.query(
                                        "SELECT ordinal,record_key,name,payload FROM records WHERE "
                                                + condition
                                                + " ORDER BY ordinal LIMIT ? OFFSET ?",
                                        table.wire(),
                                        pattern,
                                        limit,
                                        offset)) {
                            token.check();
                            records.add(
                                    new Record(
                                            table,
                                            ((Number) row[0]).longValue(),
                                            (String) row[1],
                                            (String) row[2],
                                            Json.object(Json.parse((byte[]) row[3], 1 << 20)),
                                            id,
                                            snapshot.catalog().clientBuild(),
                                            license));
                        }
                        return new Page(records, total, offset, limit);
                    });
        }
    }

    private Snapshot snapshot(Path directory) throws Exception {
        checkDirectory(directory);
        var metadata =
                Json.object(Json.parse(read(directory.resolve("snapshot.json"), 3 << 20), 3 << 20));
        var catalog = catalog(Json.bytes(metadata.get("catalog")));
        if (!catalog.revision().equals(directory.getFileName().toString())
                || !catalog.revision().equals(metadata.get("revision"))) {
            throw new IOException("Snapshot identity differs");
        }
        long original = 0;
        for (var resource : catalog.resources()) {
            Path data = directory.resolve(resource.table().wire() + ".jsonl");
            if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(data) != resource.bytes()) {
                throw new IOException("Retained source resource is missing or incomplete");
            }
            original += resource.bytes();
            original += verifiedAux(directory, resource.table().wire() + ".schema.json").length;
        }
        for (String notice : NOTICES) {
            original += verifiedAux(directory, notice).length;
        }
        original += read(directory.resolve("snapshot.json"), 3 << 20).length;
        original += verifiedAux(directory, "index.json").length;
        original += verifiedAux(directory, "datapackage.json").length;
        var issues = new ArrayList<String>();
        Instant prepared = null;
        long size = 0, rows = 0;
        Path db = database(catalog.revision());
        if (Files.exists(db, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (!Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid derived database path");
                }
                try (var connection = DerivedSqliteConnection.openReadOnly(db)) {
                    prepared =
                            connection.run(
                                    com.vibeminer.store.Cancellation.NONE,
                                    false,
                                    sql -> {
                                        if (((Number)
                                                                        sql.query(
                                                                                        "PRAGMA application_id")
                                                                                .getFirst()[0])
                                                                .intValue()
                                                        != APPLICATION_ID
                                                || ((Number)
                                                                        sql.query(
                                                                                        "PRAGMA user_version")
                                                                                .getFirst()[0])
                                                                .intValue()
                                                        != 1
                                                || !catalog.revision()
                                                        .equals(
                                                                sql.query(
                                                                                "SELECT v FROM metadata WHERE k='source'")
                                                                        .getFirst()[0])
                                                || !"polestar-jsonl-schema1-v1"
                                                        .equals(
                                                                sql.query(
                                                                                "SELECT v FROM metadata WHERE k='profile'")
                                                                        .getFirst()[0])) {
                                            throw new IllegalArgumentException(
                                                    "Unsupported prepared database");
                                        }
                                        return Instant.parse(
                                                (String)
                                                        sql.query(
                                                                        "SELECT v FROM metadata WHERE k='preparedAt'")
                                                                .getFirst()[0]);
                                    });
                }
                size = Files.size(db);
                rows = catalog.rows();
            } catch (Exception invalid) {
                prepared = null;
                rows = 0;
                size = 0;
                issues.add("Prepared database unavailable; prepare again from retained originals");
            }
        }
        return new Snapshot(
                catalog.revision(),
                catalog,
                Instant.parse(Json.string(metadata.get("downloadedAt"))),
                prepared,
                original,
                size,
                rows,
                issues);
    }

    private static boolean samePublication(Catalog left, Catalog right) {
        return left.revision().equals(right.revision())
                && left.version().equals(right.version())
                && left.clientBuild().equals(right.clientBuild())
                && left.producerCommit().equals(right.producerCommit())
                && left.resources().equals(right.resources())
                && left.coverage().equals(right.coverage());
    }

    private static void verifyOriginals(Path directory, Catalog catalog, Cancellation token)
            throws IOException {
        for (var resource : catalog.resources()) {
            token.check();
            byte[] bytes =
                    read(directory.resolve(resource.table().wire() + ".jsonl"), MAX_FILE_BYTES);
            if (bytes.length != resource.bytes() || !hash(bytes).equals(resource.sha256())) {
                throw new IOException("Retained resource checksum differs");
            }
        }
    }

    private byte[] verifiedAux(Path directory, String name) throws IOException {
        var metadata =
                Json.object(Json.parse(read(directory.resolve("snapshot.json"), 3 << 20), 3 << 20));
        var hashes = Json.object(metadata.get("hashes"));
        byte[] bytes = read(directory.resolve(name), 1 << 20);
        if (!hash(bytes).equals(hashes.get(name))) {
            throw new IOException("Retained metadata checksum differs");
        }
        return bytes;
    }

    private static String key(Map<String, Object> row, long ordinal) {
        Object value =
                row.containsKey("id")
                        ? row.get("id")
                        : row.containsKey("quest")
                                ? row.get("quest")
                                : row.getOrDefault("ui_map", ordinal);
        String result = String.valueOf(value);
        if (result.length() > 256) {
            throw new IllegalArgumentException("Record key exceeds bound");
        }
        return result;
    }

    private static String text(Map<String, Object> row) {
        Object value =
                row.getOrDefault("name", row.getOrDefault("title", row.getOrDefault("text", "")));
        return value instanceof String text ? text : "";
    }

    private static byte[] envelope(Catalog catalog, byte[] index, byte[] pack) {
        return Json.bytes(
                Map.of(
                        "revision",
                        catalog.revision(),
                        "checkedAt",
                        catalog.checkedAt().toString(),
                        "indexBytes",
                        java.util.Base64.getEncoder().encodeToString(index),
                        "packageBytes",
                        java.util.Base64.getEncoder().encodeToString(pack)));
    }

    private static Catalog catalog(byte[] bytes) {
        var value = Json.object(Json.parse(bytes, 3 << 20));
        return Manifest.parse(
                Json.string(value.get("revision")),
                Instant.parse(Json.string(value.get("checkedAt"))),
                java.util.Base64.getDecoder().decode(Json.string(value.get("indexBytes"))),
                java.util.Base64.getDecoder().decode(Json.string(value.get("packageBytes"))));
    }

    private Path source(String id) {
        checkId(id);
        return root.resolve("sources").resolve(id);
    }

    private Path database(String id) {
        checkId(id);
        return root.resolve("derived").resolve(id + ".sqlite");
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid Polestar snapshot id");
        }
    }

    private static void checkDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Snapshot directory unavailable");
        }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
            throw new IOException("Local source file unavailable or exceeds bound");
        }
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Local source limit exceeded");
            }
            return bytes;
        }
    }

    private static void atomic(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling(".metadata-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void remove(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private synchronized <T> CompletableFuture<T> call(Cancellation token, Work<T> work) {
        if (closing) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Polestar repository closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        try {
                            token.check();
                            result.complete(work.run());
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Polestar request queue is full", full));
        }
        return result.copy();
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed != null) {
            return closed.copy();
        }
        closing = true;
        closed = new CompletableFuture<>();
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        closed.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closed.completeExceptionally(e);
                    }
                });
        return closed.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
