package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Verified newer definitions over layer2 history; no direct HTTP or store implementation calls. */
public final class FetchedDefinitions {
    public record Info(String revision, Instant fetchedAt) {}

    public record Definition(
            String tableId,
            String tableName,
            String sha256,
            long byteLength,
            String sourceUrl,
            String revision,
            Instant fetchedAt,
            Instant checkedAt,
            DbdDocument document) {
        public Info info() {
            return new Info(revision, fetchedAt);
        }
    }

    public record Outcome(Definition definition, DefinitionException failure, boolean asked) {}

    public record Check(Instant checkedAt, Info fetched, String failure, Instant nextCheckAfter) {}

    private record Failed(Instant at, DefinitionException failure) {}

    private record Known(String name, Definition definition) {}

    private record Run(ObjectKey document, History.Entry first, String revision) {}

    private final ObjectSource source;
    private final Clock clock;
    private final Duration refresh, gap;
    private final int page, pages;
    private final ConcurrentHashMap<String, Object> gates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Known> loaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Failed> failures = new ConcurrentHashMap<>();

    public FetchedDefinitions(ObjectSource source) {
        this(source, Clock.systemUTC(), Duration.ofHours(24), Duration.ofMinutes(15), 1000, 1000);
    }

    public FetchedDefinitions(
            ObjectSource source,
            Clock clock,
            Duration refresh,
            Duration minimumGap,
            int page,
            int pages) {
        this.source = java.util.Objects.requireNonNull(source);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.refresh = refresh;
        gap = minimumGap;
        this.page = page;
        this.pages = pages;
        if (page < 1 || pages < 1) {
            throw new IllegalArgumentException("Positive history bounds required");
        }
    }

    public CompletableFuture<Definition> cached(String id, String name, Cancellation token) {
        validate(id, name);
        return async(() -> cachedCore(id, name, token));
    }

    private Definition cachedCore(String id, String name, Cancellation token) {
        token.check();
        var known = loaded.get(id);
        if (known != null && known.name().equals(name)) {
            return known.definition();
        }
        var value = load(id, name, token);
        return loaded.compute(
                        id,
                        (k, current) ->
                                current != null && current.name().equals(name)
                                        ? current
                                        : new Known(name, value))
                .definition();
    }

    public CompletableFuture<Outcome> ensure(
            String id,
            String name,
            Predicate<DbdDocument> covers,
            Function<String, CompletableFuture<Void>> progress,
            Cancellation token,
            boolean deliberate) {
        validate(id, name);
        return async(
                () -> {
                    synchronized (gates.computeIfAbsent(id, k -> new Object())) {
                        token.check();
                        var cached = cachedCore(id, name, token);
                        Instant now = clock.instant();
                        var failed = failures.get(id);
                        if (!deliberate
                                && failed != null
                                && !failed.at().isAfter(now)
                                && Duration.between(failed.at(), now).compareTo(gap) < 0) {
                            return new Outcome(cached, failed.failure(), false);
                        }
                        if (!due(cached, covers, now)) {
                            return new Outcome(cached, null, false);
                        }
                        progress.apply("Checking WoWDBDefs for the newest " + name + " definition")
                                .join();
                        var result =
                                source.observe(
                                                new SourceTypes.ObservationRequest(
                                                        SourceTypes.ObservationKind.DEFINITION,
                                                        id,
                                                        "wowdbdefs",
                                                        name,
                                                        true),
                                                token)
                                        .join();
                        token.check();
                        try {
                            var fresh = accept(id, name, cached, result, token);
                            failures.remove(id);
                            return new Outcome(fresh, null, true);
                        } catch (DefinitionException failure) {
                            failures.put(id, new Failed(clock.instant(), failure));
                            var kept = loaded.get(id);
                            return new Outcome(
                                    kept != null && kept.name().equals(name)
                                            ? kept.definition()
                                            : cached,
                                    failure,
                                    true);
                        }
                    }
                });
    }

    public CompletableFuture<Boolean> refreshDue(
            String id, String name, Predicate<DbdDocument> covers, Cancellation token) {
        return cached(id, name, token)
                .thenApply(
                        cached -> {
                            var now = clock.instant();
                            var failure = failures.get(id);
                            return !(failure != null
                                            && !failure.at().isAfter(now)
                                            && Duration.between(failure.at(), now).compareTo(gap)
                                                    < 0)
                                    && due(cached, covers, now);
                        });
    }

    public CompletableFuture<Check> lastCheck(
            String id, String name, Predicate<DbdDocument> covers, Cancellation token) {
        return cached(id, name, token)
                .thenApply(
                        cached -> {
                            var failed = failures.get(id);
                            if (cached == null && failed == null) {
                                return null;
                            }
                            var at = failed != null ? failed.at() : cached.checkedAt();
                            Instant next =
                                    failed != null
                                            ? at.plus(gap)
                                            : at.isAfter(clock.instant())
                                                    ? clock.instant()
                                                    : at.plus(
                                                            covers.test(cached.document())
                                                                            && refresh.compareTo(
                                                                                            gap)
                                                                                    > 0
                                                                    ? refresh
                                                                    : gap);
                            return new Check(
                                    at,
                                    cached == null ? null : cached.info(),
                                    failed == null ? null : failed.failure().code(),
                                    next);
                        });
    }

    private boolean due(Definition cached, Predicate<DbdDocument> covers, Instant now) {
        if (cached == null || cached.checkedAt().isAfter(now)) {
            return true;
        }
        var age = Duration.between(cached.checkedAt(), now);
        return !(age.compareTo(refresh) < 0 && covers.test(cached.document())
                || age.compareTo(gap) < 0);
    }

    private Definition accept(
            String id,
            String name,
            Definition cached,
            SourceTypes.ObservationResult result,
            Cancellation token) {
        if (result.state() != SourceTypes.ObjectState.PRESENT || result.observation() == null) {
            String code =
                    switch (result.code() == null ? "" : result.code()) {
                        case "CDN_OFFLINE" -> "DEFINITION_FETCH_OFFLINE";
                        case "CDN_NOT_FOUND" -> "DEFINITION_FETCH_NOT_FOUND";
                        case "CDN_TOO_LARGE" -> "DEFINITION_FETCH_TOO_LARGE";
                        default -> "DEFINITION_FETCH_FAILED";
                    };
            throw new DefinitionException(
                    code,
                    result.message() == null ? "Definition fetch failed" : result.message(),
                    code.equals("DEFINITION_FETCH_OFFLINE")
                            || code.equals("DEFINITION_FETCH_FAILED"));
        }
        var o = result.observation();
        String revision = ObservationAdapters.definitionRevision(o.url().toString(), name);
        byte[] bytes = o.bytes();
        DbdDocument document;
        try {
            if (revision == null) {
                throw new DefinitionException(
                        "DEFINITION_FETCH_FAILED", "Unapproved definition address");
            }
            if (bytes.length > 1 << 20) {
                throw new DefinitionException(
                        "DEFINITION_FETCH_TOO_LARGE", "Definition exceeds1MiB");
            }
            try {
                document = DbdDefinitionReader.parse(bytes, new DbdTypes.Limits(), token);
            } catch (Db2FormatException e) {
                throw new DefinitionException("DEFINITION_FETCH_INVALID", e.code());
            }
        } catch (DefinitionException e) {
            if (result.recorded() && cached != null && o.observedAt().isAfter(cached.checkedAt())) {
                loaded.put(id, new Known(name, checked(cached, o.observedAt())));
            }
            throw e;
        }
        String sha = Json.sha256(bytes);
        var definition =
                cached != null && cached.sha256().equals(sha)
                        ? checked(
                                cached,
                                o.observedAt().isAfter(cached.checkedAt())
                                        ? o.observedAt()
                                        : cached.checkedAt())
                        : new Definition(
                                id,
                                name,
                                sha,
                                bytes.length,
                                o.url().toString(),
                                revision,
                                o.observedAt(),
                                o.observedAt(),
                                document);
        loaded.put(id, new Known(name, definition));
        return definition;
    }

    private static Definition checked(Definition d, Instant at) {
        return new Definition(
                d.tableId(),
                d.tableName(),
                d.sha256(),
                d.byteLength(),
                d.sourceUrl(),
                d.revision(),
                d.fetchedAt(),
                at,
                d.document());
    }

    private Definition load(String id, String name, Cancellation token) {
        var runs = new ArrayList<Run>();
        Instant newest = null;
        History.Entry after = null;
        for (int p = 0; ; p++) {
            if (p == pages) {
                return null;
            }
            var batch =
                    source.readHistory(new History.Query(id, "wowdbdefs", 5, after, page), token)
                            .join();
            for (var entry : batch) {
                token.check();
                if (entry.kind() != 5
                        || !id.equals(entry.product())
                        || !"wowdbdefs".equals(entry.region())) {
                    continue;
                }
                if (newest == null || entry.observedAt().isAfter(newest)) {
                    newest = entry.observedAt();
                }
                String revision =
                        entry.url() == null
                                ? null
                                : ObservationAdapters.definitionRevision(entry.url(), name);
                if (revision == null) {
                    continue;
                }
                if (!runs.isEmpty() && runs.getLast().document().equals(entry.document())) {
                    continue;
                }
                runs.add(new Run(entry.document(), entry, revision));
                if (runs.size() > 4096) {
                    runs.removeFirst();
                }
            }
            if (batch.size() < page) {
                break;
            }
            after = batch.getLast();
        }
        var documents = new HashMap<ObjectKey, byte[]>();
        var parsed = new HashMap<ObjectKey, DbdDocument>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            var run = runs.get(i);
            if (!read(run.document(), documents, parsed, token)) {
                continue;
            }
            Run start = run;
            for (int older = i - 1; older >= 0; older--) {
                var candidate = runs.get(older);
                if (candidate.document().equals(start.document())) {
                    start = candidate;
                    continue;
                }
                if (read(candidate.document(), documents, parsed, token)) {
                    break;
                }
            }
            byte[] bytes = documents.get(run.document());
            return new Definition(
                    id,
                    name,
                    Json.sha256(bytes),
                    bytes.length,
                    ObservationAdapters.definitionUrl(start.revision(), name),
                    start.revision(),
                    start.first().observedAt(),
                    newest,
                    parsed.get(run.document()));
        }
        return null;
    }

    private boolean read(
            ObjectKey key,
            Map<ObjectKey, byte[]> bytes,
            Map<ObjectKey, DbdDocument> parsed,
            Cancellation token) {
        if (bytes.containsKey(key)) {
            return parsed.containsKey(key);
        }
        var result =
                source.getKey(
                                new SourceTypes.ObjectRequest(key),
                                null,
                                SourceTypes.FetchPriority.INTERACTIVE,
                                token)
                        .join();
        byte[] value = result.bytes();
        bytes.put(key, value);
        if (result.state() == SourceTypes.ObjectState.PRESENT
                && value != null
                && value.length >= 1
                && value.length <= 1 << 20) {
            try {
                parsed.put(key, DbdDefinitionReader.parse(value, new DbdTypes.Limits(), token));
            } catch (Db2FormatException ignored) {
            }
        }
        return parsed.containsKey(key);
    }

    private static void validate(String id, String name) {
        if (id == null
                || !id.matches("[0-9a-f]{8}")
                || name == null
                || !name.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Pinned table identity and name required");
        }
    }

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, command -> Thread.ofVirtual().start(command));
    }
}
