package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2FileSource;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Persisted completed inventory, referencing the existing durable decoded table graph. */
@SuppressWarnings("try")
public final class PreparedTablesCache {
    public static final String FORMAT = "vimi-prepared-table-inventory-v1";
    private static final int MAX_BYTES = 16 << 20, MAX_TABLES = 65536;
    private final GeneratedStore store;

    public PreparedTablesCache(GeneratedStore store) {
        this.store = java.util.Objects.requireNonNull(store);
    }

    public static GeneratedKey key(String identity) {
        return GeneratedKey.hash((FORMAT + "\n" + identity).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Restores the completed assessment with one inventory read, without opening every table. The
     * identity must cover build/locale, definitions, reader policy and keys. This is a saved
     * assessment, not a disk scrub: individual table accesses still validate their generated graph
     * and repair missing/corrupt derived data through the normal table pipeline.
     */
    public CompletableFuture<TableTypes.PreloadResult> restore(
            String identity, Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        return restore(identity, null, progress, token);
    }

    /**
     * Explicit verification path. The handle must be local-only: a cache check must not acquire
     * missing inputs. Normal reopening uses the inventory-only overload instead.
     */
    public CompletableFuture<TableTypes.PreloadResult> restore(
            String identity,
            BuildTables local,
            Consumer<TableTypes.PreloadProgress> progress,
            Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var generated = new com.vibeminer.generated.Cancellation();
                    try (var registration = token.onCancel(generated::cancel)) {
                        token.check();
                        var read = store.read(key(identity), generated).join();
                        if (read.state() != GeneratedTypes.ReadState.PRESENT) {
                            return null;
                        }
                        List<TableTypes.Entry> entries;
                        try {
                            var root = Json.object(Json.parse(read.bytes(), MAX_BYTES));
                            if (!FORMAT.equals(root.get("Format"))
                                    || !identity.equals(root.get("Identity"))) {
                                return null;
                            }
                            var values = Json.array(root.get("Tables"));
                            if (values.size() > MAX_TABLES) {
                                return null;
                            }
                            entries = new ArrayList<>();
                            long previous = 0;
                            for (var value : values) {
                                var entry = decode(Json.object(value));
                                if (entry.fileDataId() <= previous
                                        || !entry.checked()
                                        || entry.state() == TableTypes.State.NOT_CHECKED
                                        || entry.state() == TableTypes.State.STORE_FAILED) {
                                    return null;
                                }
                                previous = entry.fileDataId();
                                entries.add(entry);
                            }
                        } catch (RuntimeException invalid) {
                            return null;
                        }
                        int total =
                                (int) entries.stream().filter(PreparedTablesCache::present).count();
                        int done = 0;
                        if (progress != null) {
                            progress.accept(report(0, total, 0));
                        }
                        var verified = new ArrayList<TableTypes.Entry>();
                        for (var entry : entries) {
                            token.check();
                            if (local != null && TableQueries.isReadable(entry.state())) {
                                var current =
                                        local.check(
                                                        TableTypes.Ref.id(entry.fileDataId()),
                                                        TableTypes.Priority.BACKGROUND,
                                                        token)
                                                .join();
                                if (!TableQueries.isReadable(current.state())
                                        || !java.util.Objects.equals(
                                                current.contentKey(), entry.contentKey())
                                        || !java.util.Objects.equals(
                                                current.counts(), entry.counts())) {
                                    return null;
                                }
                                verified.add(current);
                            } else {
                                verified.add(entry);
                            }
                            if (present(entry)) {
                                done++;
                            }
                            if (local != null && progress != null) {
                                progress.accept(report(done, total, 0));
                            }
                        }
                        token.check();
                        int failed =
                                (int)
                                        verified.stream()
                                                .filter(PreparedTablesCache::present)
                                                .filter(
                                                        entry ->
                                                                !TableQueries.isReadable(
                                                                        entry.state()))
                                                .count();
                        var complete = report(total, total, failed);
                        if (progress != null) {
                            progress.accept(complete);
                        }
                        return new TableTypes.PreloadResult(
                                TableTypes.PreloadOutcome.COMPLETED,
                                complete,
                                verified,
                                com.vibeminer.files.FileTypes.FilesOpenState.OPEN,
                                "TABLES_RESTORED",
                                "Restored prepared local table inventory");
                    }
                },
                command -> Thread.startVirtualThread(command));
    }

    public CompletableFuture<Void> save(
            String identity, TableTypes.PreloadResult result, Cancellation token) {
        return CompletableFuture.runAsync(
                () -> {
                    token.check();
                    if (result.outcome() != TableTypes.PreloadOutcome.COMPLETED) {
                        throw new IllegalArgumentException(
                                "Only complete assessments can be saved");
                    }
                    if (result.tables().size() > MAX_TABLES) {
                        throw new IllegalArgumentException("Table inventory exceeds bound");
                    }
                    var root = new LinkedHashMap<String, Object>();
                    root.put("Format", FORMAT);
                    root.put("Identity", identity);
                    root.put(
                            "Tables",
                            result.tables().stream().map(PreparedTablesCache::encode).toList());
                    byte[] bytes = Json.bytes(root);
                    if (bytes.length > MAX_BYTES) {
                        throw new IllegalArgumentException("Table inventory exceeds byte bound");
                    }
                    token.check();
                    // Derived-store objects are immutable. Replace only this small inventory
                    // pointer after rows are durable.
                    // A crash/store failure between these calls is a cache miss, never a false
                    // ready flag; row graphs survive.
                    store.delete(List.of(key(identity))).join();
                    store.put(List.of(new GeneratedTypes.Put(key(identity), bytes))).join();
                },
                command -> Thread.startVirtualThread(command));
    }

    private static boolean present(TableTypes.Entry entry) {
        return entry.state() != TableTypes.State.NOT_IN_BUILD
                && entry.state() != TableTypes.State.NOT_IN_LOCALE;
    }

    private static TableTypes.PreloadProgress report(int done, int total, int failed) {
        return new TableTypes.PreloadProgress(
                TableTypes.PreloadStage.RESTORING, 0, 0, 0, 0, 0, 0, done, total, failed);
    }

    private static Map<String, Object> encode(TableTypes.Entry entry) {
        if (!entry.checked()
                || entry.state() == TableTypes.State.NOT_CHECKED
                || entry.state() == TableTypes.State.STORE_FAILED) {
            throw new IllegalArgumentException(
                    "Unchecked/system-failed tables cannot establish saved preparation");
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("Id", entry.fileDataId());
        value.put("Name", entry.name());
        value.put("State", entry.state().name());
        value.put("Counts", entry.counts());
        value.put("ContentKey", entry.contentKey());
        var reason = entry.reason();
        if (reason == null) {
            value.put("Reason", null);
        } else {
            var r = new LinkedHashMap<String, Object>();
            r.put("Code", reason.code());
            r.put("Message", reason.message());
            r.put(
                    "ObservedAt",
                    reason.observedAt() == null ? null : reason.observedAt().toString());
            r.put(
                    "Definition",
                    reason.definitionState() == null ? null : reason.definitionState().name());
            r.put("Db2", reason.db2OpenState() == null ? null : reason.db2OpenState().name());
            r.put("Keys", reason.keyNames());
            value.put("Reason", r);
        }
        return value;
    }

    private static TableTypes.Entry decode(Map<String, Object> value) {
        var r = value.get("Reason") == null ? null : Json.object(value.get("Reason"));
        TableTypes.Reason reason =
                r == null
                        ? null
                        : new TableTypes.Reason(
                                (String) r.get("Code"),
                                (String) r.get("Message"),
                                r.get("ObservedAt") == null
                                        ? null
                                        : Instant.parse(Json.string(r.get("ObservedAt"))),
                                r.get("Definition") == null
                                        ? null
                                        : TableDefinitions.State.valueOf(
                                                Json.string(r.get("Definition"))),
                                r.get("Db2") == null
                                        ? null
                                        : Db2FileSource.OpenState.valueOf(
                                                Json.string(r.get("Db2"))),
                                Json.array(r.get("Keys")).stream().map(Json::string).toList());
        return new TableTypes.Entry(
                Json.number(value.get("Id")),
                (String) value.get("Name"),
                TableTypes.State.valueOf(Json.string(value.get("State"))),
                true,
                reason,
                value.get("Counts") == null
                        ? null
                        : Json.convert(value.get("Counts"), TableTypes.Counts.class),
                (String) value.get("ContentKey"));
    }
}
