package com.vibeminer.questie.data;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.generated.DerivedDatabase;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.Progress;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import com.vibeminer.questie.QuestieTypes.Stage;
import com.vibeminer.questie.data.QuestieDataTypes.PreparedVersion;
import com.vibeminer.questie.internal.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Versioned private derived SQLite projections. Accepts jobs independently of WoW builds. */
public final class QuestieDataRepository implements AutoCloseable {
    private final Path root;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofVirtual().name("questie-prepare-", 0).factory());
    private final List<QuestieDataHandle> handles = new ArrayList<>();
    private CompletableFuture<Void> closing;

    public QuestieDataRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public CompletableFuture<PreparedVersion> prepare(
            SourceSnapshot source, Consumer<Progress> progress, Cancellation token) {
        java.util.Objects.requireNonNull(source);
        java.util.Objects.requireNonNull(progress);
        java.util.Objects.requireNonNull(token);
        return submit(
                token,
                () -> {
                    String id = versionId(source);
                    Path path = path(id);
                    try {
                        Files.createDirectories(root);
                        if (Files.isRegularFile(path)) {
                            var retained = readVersion(path, token);
                            if (retained != null) {
                                if (!retained.id().equals(id)
                                        || !retained.sourceId().equals(source.id())
                                        || !retained.sourceSha256().equals(source.sha256())
                                        || retained.flavor() != source.flavor()
                                        || !retained.producerCommit()
                                                .equals(source.producerCommit())) {
                                    throw new IllegalArgumentException(
                                            "Retained Questie source identity mismatch");
                                }
                                progress.accept(
                                        new Progress(
                                                Stage.READY,
                                                retained.entityCount(),
                                                retained.entityCount(),
                                                "entities",
                                                "Retained Questie version ready"));
                                return retained;
                            }
                        }
                        progress.accept(
                                new Progress(
                                        Stage.VERIFYING,
                                        0L,
                                        null,
                                        "bytes",
                                        "Checking retained package"));
                        var archive = new BakedArchive(source, token);
                        var database = DerivedDatabase.open(path, token).join();
                        try {
                            PreparedVersion result =
                                    database.transaction(
                                                    session -> {
                                                        create(session);
                                                        session.execute("DELETE FROM metadata");
                                                        session.execute("DELETE FROM entities");
                                                        session.execute("DELETE FROM translations");
                                                        session.execute(
                                                                "DELETE FROM entity_references");
                                                        session.execute("DELETE FROM item_sources");
                                                        session.execute(
                                                                "DELETE FROM unknown_metadata");
                                                        var importer =
                                                                new Importer(
                                                                        archive, session, token,
                                                                        progress);
                                                        importer.run();
                                                        token.check();
                                                        var prepared =
                                                                new PreparedVersion(
                                                                        id,
                                                                        source.id(),
                                                                        source.sha256(),
                                                                        source.releaseTag(),
                                                                        source.producerCommit(),
                                                                        source.flavor(),
                                                                        Schema.PROFILE,
                                                                        Instant.now(),
                                                                        importer.entityCount,
                                                                        importer.locales,
                                                                        importer.diagnostics);
                                                        session.execute(
                                                                "INSERT INTO metadata(key,value) VALUES('ready',?)",
                                                                encodeVersion(prepared));
                                                        return prepared;
                                                    },
                                                    token)
                                            .join();
                            progress.accept(
                                    new Progress(
                                            Stage.READY,
                                            result.entityCount(),
                                            result.entityCount(),
                                            "entities",
                                            "Static Questie snapshot ready"));
                            return result;
                        } finally {
                            database.close();
                        }
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    public CompletableFuture<List<PreparedVersion>> listVersions(Cancellation token) {
        return submit(
                token,
                () -> {
                    if (!Files.isDirectory(root)) {
                        return List.of();
                    }
                    try (var files = Files.list(root)) {
                        var result = new ArrayList<PreparedVersion>();
                        for (Path file :
                                files.filter(
                                                p ->
                                                        p.getFileName()
                                                                .toString()
                                                                .matches("[0-9a-f]{64}\\.sqlite"))
                                        .sorted()
                                        .toList()) {
                            token.check();
                            var version = readVersion(file, token);
                            if (version != null) {
                                result.add(version);
                            }
                        }
                        return List.copyOf(result);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    public CompletableFuture<QuestieDataHandle> open(String id, Cancellation token) {
        checkId(id);
        return submit(
                token,
                () -> {
                    Path file = path(id);
                    if (!Files.isRegularFile(file)) {
                        throw new IllegalArgumentException(
                                "Unknown prepared Questie version: " + id);
                    }
                    var database = DerivedDatabase.open(file, token).join();
                    try {
                        var version =
                                database.read(QuestieDataRepository::readVersion, token).join();
                        if (version == null || !version.id().equals(id)) {
                            throw new IllegalStateException("Questie version is not ready");
                        }
                        var handle = new QuestieDataHandle(database, version, this::forget);
                        synchronized (this) {
                            handles.add(handle);
                        }
                        return handle;
                    } catch (RuntimeException | Error error) {
                        database.close();
                        throw error;
                    }
                });
    }

    private synchronized void forget(QuestieDataHandle handle) {
        handles.remove(handle);
    }

    private PreparedVersion readVersion(Path path, Cancellation token) {
        var database = DerivedDatabase.open(path, token).join();
        try {
            return database.read(QuestieDataRepository::readVersion, token).join();
        } finally {
            database.close();
        }
    }

    private static PreparedVersion readVersion(DerivedDatabase.Session session) {
        if (session.query("SELECT name FROM sqlite_master WHERE type='table' AND name='metadata'")
                .isEmpty()) {
            return null;
        }
        var rows = session.query("SELECT value FROM metadata WHERE key='ready'");
        if (rows.isEmpty()) {
            return null;
        }
        var m =
                Json.object(
                        Json.parse(
                                ((String) rows.getFirst()[0]).getBytes(StandardCharsets.UTF_8),
                                1 << 20));
        String profile = Json.string(m.get("schemaProfile"));
        if (!Schema.PROFILE.equals(profile)) {
            throw new IllegalArgumentException(
                    "Unsupported retained Questie projection: " + profile);
        }
        String expectedId =
                HexFormat.of()
                        .formatHex(
                                BakedArchive.sha256()
                                        .digest(
                                                (Json.string(m.get("sourceId"))
                                                                + "\n"
                                                                + Json.string(m.get("sourceSha256"))
                                                                + "\n"
                                                                + Flavor.parse(
                                                                                Json.string(
                                                                                        m.get(
                                                                                                "flavor")))
                                                                        .name()
                                                                + "\n"
                                                                + profile)
                                                        .getBytes(StandardCharsets.UTF_8)));
        if (!expectedId.equals(Json.string(m.get("id")))) {
            throw new IllegalArgumentException("Retained Questie identity mismatch");
        }
        return new PreparedVersion(
                Json.string(m.get("id")),
                Json.string(m.get("sourceId")),
                Json.string(m.get("sourceSha256")),
                Json.string(m.get("releaseTag")),
                Json.string(m.get("producerCommit")),
                Flavor.parse(Json.string(m.get("flavor"))),
                profile,
                Instant.parse(Json.string(m.get("preparedAt"))),
                Json.number(m.get("entityCount")),
                strings(m.get("locales")),
                strings(m.get("diagnostics")));
    }

    private static List<String> strings(Object value) {
        return Json.array(value).stream().map(Json::string).toList();
    }

    private static String encodeVersion(PreparedVersion v) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", v.id());
        m.put("sourceId", v.sourceId());
        m.put("sourceSha256", v.sourceSha256());
        m.put("releaseTag", v.releaseTag());
        m.put("producerCommit", v.producerCommit());
        m.put("flavor", v.flavor().name());
        m.put("schemaProfile", v.schemaProfile());
        m.put("preparedAt", v.preparedAt().toString());
        m.put("entityCount", v.entityCount());
        m.put("locales", v.locales());
        m.put("diagnostics", v.diagnostics());
        return new String(Json.bytes(m), StandardCharsets.UTF_8);
    }

    static String versionId(SourceSnapshot source) {
        return HexFormat.of()
                .formatHex(
                        BakedArchive.sha256()
                                .digest(
                                        (source.id()
                                                        + "\n"
                                                        + source.sha256()
                                                        + "\n"
                                                        + source.flavor().name()
                                                        + "\n"
                                                        + Schema.PROFILE)
                                                .getBytes(StandardCharsets.UTF_8)));
    }

    private Path path(String id) {
        checkId(id);
        return root.resolve(id + ".sqlite");
    }

    private static void checkId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid prepared version ID");
        }
    }

    private static void create(DerivedDatabase.Session s) {
        s.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        s.execute(
                "CREATE TABLE IF NOT EXISTS entities(kind TEXT NOT NULL,id INTEGER NOT NULL,name TEXT,fields BLOB NOT NULL,raw_fields BLOB NOT NULL,provenance TEXT NOT NULL,PRIMARY KEY(kind,id)) WITHOUT ROWID");
        s.execute(
                "CREATE TABLE IF NOT EXISTS translations(locale TEXT NOT NULL,kind TEXT NOT NULL,id INTEGER NOT NULL,name TEXT,fields BLOB NOT NULL,PRIMARY KEY(locale,kind,id)) WITHOUT ROWID");
        s.execute(
                "CREATE TABLE IF NOT EXISTS entity_references(from_kind TEXT NOT NULL,from_id INTEGER NOT NULL,field TEXT NOT NULL,to_kind TEXT NOT NULL,to_id INTEGER NOT NULL)");
        s.execute(
                "CREATE TABLE IF NOT EXISTS item_sources(item_id INTEGER NOT NULL,kind TEXT NOT NULL,entity_id INTEGER NOT NULL,relation TEXT NOT NULL,evidence TEXT NOT NULL,field TEXT NOT NULL,PRIMARY KEY(item_id,kind,entity_id,relation,evidence,field)) WITHOUT ROWID");
        s.execute(
                "CREATE TABLE IF NOT EXISTS unknown_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        s.execute(
                "CREATE INDEX IF NOT EXISTS entity_names ON entities(kind,name COLLATE NOCASE,id)");
        s.execute(
                "CREATE INDEX IF NOT EXISTS reference_targets ON entity_references(to_kind,to_id)");
    }

    private synchronized <T> CompletableFuture<T> submit(
            Cancellation token, Supplier<T> operation) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Questie data repository closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        try {
                            token.check();
                            result.complete(operation.get());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Questie data queue full", full));
        }
        return result.copy();
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        worker.shutdown();
        var result = closing;
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                            /* Drain accepted jobs. */
                        }
                        List<QuestieDataHandle> retained;
                        synchronized (this) {
                            retained = List.copyOf(handles);
                        }
                        CompletableFuture.allOf(
                                        retained.stream()
                                                .map(QuestieDataHandle::closeAsync)
                                                .toArray(CompletableFuture[]::new))
                                .join();
                        result.complete(null);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
        return result.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
