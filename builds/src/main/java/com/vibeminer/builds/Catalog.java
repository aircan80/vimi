package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.builds.CatalogTypes.BuildList;
import com.vibeminer.builds.CatalogTypes.CatalogProblem;
import com.vibeminer.builds.CatalogTypes.CatalogRefresh;
import com.vibeminer.builds.CatalogTypes.SummaryRefresh;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * History-derived catalog with a disposable SQLite projection; listing never invokes the network.
 */
public final class Catalog implements BuildCatalog {
    public record Options(String sourceRegion, int pageSize) {
        public Options {
            if (!BuildIdentity.isValidRegion(sourceRegion) || pageSize < 1 || pageSize > 10000) {
                throw new IllegalArgumentException("Invalid catalog options");
            }
        }

        public Options() {
            this("us", 1000);
        }
    }

    static final int MAX_FAILED = 1000, MAX_LIST_PROBLEMS = 50;
    static final Duration RESUME_LAG = Duration.ofMinutes(10);
    private final ObjectSource source;
    private final CatalogStore store;
    private final Options options;
    private final CatalogTasks tasks;
    private final Map<ObjectKey, History.Entry> failed = new HashMap<>();
    private boolean rescan = true;
    private History.Entry resumeAfter;

    private Catalog(ObjectSource source, CatalogStore store, Options options) {
        this.source = source;
        this.store = store;
        this.options = options;
        tasks = new CatalogTasks("catalog", 1, 64, store::close);
    }

    public static CompletableFuture<Catalog> open(ObjectSource source, Path path) {
        return open(source, path, new Options(), Cancellation.NONE);
    }

    public static CompletableFuture<Catalog> open(
            ObjectSource source, Path path, Options options, Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(path);
        Objects.requireNonNull(options);
        return CatalogTasks.open(
                token, () -> new Catalog(source, CatalogStore.open(path, token), options));
    }

    @Override
    public CompletableFuture<BuildList> listBuilds(String product, Cancellation token) {
        validate(product);
        return tasks.submit(token, () -> list(product, token));
    }

    @Override
    public CompletableFuture<CatalogRefresh> refresh(String product, Cancellation token) {
        validate(product);
        return tasks.submit(
                token,
                () -> {
                    var versions = observe(ObservationKind.VERSIONS, product, token);
                    var cdns = observe(ObservationKind.CDNS, product, token);
                    return new CatalogRefresh(product, versions, cdns, list(product, token));
                });
    }

    @Override
    public CompletableFuture<SummaryRefresh> refreshSummary(Cancellation token) {
        return tasks.submit(
                token, () -> Summaries.read(observe(ObservationKind.SUMMARY, "", token)));
    }

    private ObservationResult observe(ObservationKind kind, String product, Cancellation token) {
        try {
            return source.observe(
                            new ObservationRequest(kind, product, options.sourceRegion()), token)
                    .join();
        } catch (RuntimeException error) {
            Throwable e = CatalogTasks.unwrap(error);
            if (e instanceof CancellationException canceled) {
                throw canceled;
            }
            return new ObservationResult(
                    ObjectState.UNAVAILABLE,
                    null,
                    false,
                    "CATALOG_OBSERVE_FAILED",
                    "Observing " + kind + " failed (" + e.getClass().getSimpleName() + ")",
                    null);
        }
    }

    private BuildList list(String product, Cancellation token) {
        List<Transient> transientProblems = sync(token);
        BuildList listed = store.list(product, token);
        List<CatalogProblem> mine = new ArrayList<>();
        for (Transient transientProblem : transientProblems) {
            if (transientProblem.product().equals(product)) {
                mine.add(transientProblem.problem());
            }
        }
        if (mine.size() > MAX_LIST_PROBLEMS) {
            int omitted = mine.size() - MAX_LIST_PROBLEMS;
            mine = new ArrayList<>(mine.subList(0, MAX_LIST_PROBLEMS));
            mine.add(
                    new CatalogProblem(
                            "CATALOG_PROBLEMS_TRUNCATED",
                            omitted + " more history entries cannot be read now"));
        }
        if (mine.isEmpty()) {
            return listed;
        }
        List<CatalogProblem> all = new ArrayList<>(listed.problems());
        all.addAll(mine);
        return new BuildList(product, listed.builds(), all, listed.lastObservedAt());
    }

    private record Transient(String product, CatalogProblem problem) {}

    private List<Transient> sync(Cancellation token) {
        List<Transient> problems = new ArrayList<>();
        Map<ObjectKey, History.Entry> nowFailed = new HashMap<>();
        boolean[] overflow = {false};
        java.util.function.Consumer<History.Entry> read =
                entry -> {
                    CatalogProblem problem = tryAdd(entry, token);
                    if (problem == null) {
                        return;
                    }
                    problems.add(new Transient(entry.product(), problem));
                    if (nowFailed.size() < MAX_FAILED) {
                        nowFailed.put(entry.record(), entry);
                    } else {
                        overflow[0] = true;
                    }
                };
        for (History.Entry entry : new ArrayList<>(failed.values())) {
            read.accept(entry);
        }
        History.Entry after = rescan ? null : resumeAfter, anchor = after;
        ArrayDeque<History.Entry> recent = new ArrayDeque<>();
        Instant newest = null;
        while (true) {
            token.check();
            List<History.Entry> page =
                    source.readHistory(
                                    new History.Query(null, null, 1, after, options.pageSize()),
                                    token)
                            .join();
            for (History.Entry entry : page) {
                token.check();
                if (newest == null || entry.observedAt().isAfter(newest)) {
                    newest = entry.observedAt();
                }
                recent.add(entry);
                while (!recent.isEmpty()
                        && !recent.peek().observedAt().isAfter(newest.minus(RESUME_LAG))) {
                    anchor = recent.remove();
                }
                if (failed.containsKey(entry.record())
                        || nowFailed.containsKey(entry.record())
                        || store.has(entry.record(), token)) {
                    continue;
                }
                read.accept(entry);
            }
            if (page.size() < options.pageSize()) {
                break;
            }
            after = page.getLast();
        }
        failed.clear();
        failed.putAll(nowFailed);
        rescan = overflow[0];
        resumeAfter = overflow[0] ? null : anchor;
        return problems;
    }

    private CatalogProblem tryAdd(History.Entry entry, Cancellation token) {
        ObjectResult got =
                source.getKey(
                                new ObjectRequest(entry.document()),
                                null,
                                FetchPriority.INTERACTIVE,
                                token)
                        .join();
        byte[] bytes = got.bytes();
        if (got.isPresent() && bytes != null) {
            store.add(entry, VersionsReply.read(entry, bytes), token);
            return null;
        }
        return unreadable(entry, got);
    }

    static CatalogProblem unreadable(History.Entry entry, ObjectResult got) {
        String code =
                switch (got.state()) {
                    case PRESENT -> "CATALOG_DOCUMENT_TOO_LARGE";
                    case REFUSED -> "CATALOG_DOCUMENT_REFUSED";
                    case UNSUPPORTED -> "CATALOG_DOCUMENT_UNSUPPORTED";
                    case STORE_FAILED -> "CATALOG_DOCUMENT_STORE_FAILED";
                    default -> "CATALOG_DOCUMENT_UNAVAILABLE";
                };
        return new CatalogProblem(
                code,
                "The versions reply cannot be read now ("
                        + got.state()
                        + (got.code() == null ? "" : ", " + got.code())
                        + ")",
                entry.record());
    }

    static void validate(String product) {
        if (!Products.isWow(product)) {
            throw new IllegalArgumentException("Not a WoW product id");
        }
    }

    public CompletableFuture<Void> closeAsync() {
        return tasks.closeAsync();
    }
}
