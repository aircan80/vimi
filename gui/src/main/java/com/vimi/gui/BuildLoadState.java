package com.vimi.gui;

import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.LibraryCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Registry-backed load status with independent, coalesced object-accounting refreshes. */
public final class BuildLoadState implements BuildOpening.Observer {
    private final BuildRegistry registry;
    private final BiFunction<BuildInfo, Build, CompletableFuture<Void>> index;
    private final Function<String, CompletableFuture<LibraryCatalog.Figures>> figures;
    private final Function<Build, CompletableFuture<Void>> release;
    private long duplicateSequence;
    private final Map<String, BuildLoadSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, BuildRegistry.LoadAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> active = new HashMap<>();
    private boolean closing;

    public BuildLoadState(
            BuildRegistry registry,
            BiFunction<BuildInfo, Build, CompletableFuture<Void>> index,
            Function<String, CompletableFuture<LibraryCatalog.Figures>> figures) {
        this(registry, index, figures, build -> CompletableFuture.completedFuture(null));
    }

    public BuildLoadState(
            BuildRegistry registry,
            BiFunction<BuildInfo, Build, CompletableFuture<Void>> index,
            Function<String, CompletableFuture<LibraryCatalog.Figures>> figures,
            Function<Build, CompletableFuture<Void>> release) {
        this.registry = Objects.requireNonNull(registry);
        this.index = Objects.requireNonNull(index);
        this.figures = Objects.requireNonNull(figures);
        this.release = Objects.requireNonNull(release);
    }

    public BuildLoadSnapshot snapshot(BuildInfo build) {
        return snapshots.get(build.identity());
    }

    /** Called by the catalog's worker, never a JavaFX event handler. */
    List<BuildInfo> observe(List<BuildInfo> discovered) {
        return observe(discovered, () -> {});
    }

    List<BuildInfo> observe(List<BuildInfo> discovered, Runnable check) {
        check.run();
        List<BuildRegistry.Entry> persisted = new ArrayList<>();
        String after = null;
        while (true) {
            check.run();
            var page = registry.list(after, BuildRegistry.PAGE_LIMIT, Cancellation.NONE).join();
            persisted.addAll(page);
            if (page.isEmpty()) break;
            after = page.getLast().identity();
        }
        var known = new HashMap<String, BuildRegistry.Entry>();
        persisted.forEach(
                entry -> {
                    known.put(entry.identity(), entry);
                    cache(entry);
                });
        var result = new ArrayList<>(discovered);
        for (var build : discovered) {
            check.run();
            if (build.observations().isEmpty()) continue;
            var evidence = build.observations().getFirst();
            var ref =
                    new BuildTypes.BuildRef(
                            build.product(),
                            build.config(),
                            evidence.cdnConfig(),
                            evidence.region());
            BuildRegistry.Entry prior = known.remove(build.identity());
            if (prior == null
                    || !prior.reference().equals(ref)
                    || !Objects.equals(prior.version(), build.version())) {
                cache(
                        registry.observe(
                                        build.identity(),
                                        ref,
                                        build.version(),
                                        "enUS",
                                        Cancellation.NONE)
                                .join());
            }
        }
        for (var entry : known.values()) {
            check.run();
            var ref = entry.reference();
            result.add(
                    new BuildInfo(
                            ref.product(),
                            com.vibeminer.builds.Products.name(ref.product()),
                            entry.version(),
                            ref.region(),
                            ref.buildConfig(),
                            BuildInfo.BuildSource.SAVED,
                            false,
                            null,
                            0,
                            entry.identity(),
                            entry.updatedAt(),
                            entry.updatedAt(),
                            List.of(
                                    new BuildInfo.RegionEvidence(
                                            ref.region(),
                                            ref.cdnConfig(),
                                            false,
                                            entry.updatedAt(),
                                            entry.updatedAt()))));
        }
        return List.copyOf(result);
    }

    @Override
    public CompletableFuture<Void> started(BuildInfo build, BuildTypes.BuildRef ref) {
        return registry.beginLoad(build.identity(), ref, build.version(), "enUS", Cancellation.NONE)
                .thenCompose(
                        attempt -> {
                            attempts.put(build.identity(), attempt);
                            return registry.get(build.identity(), Cancellation.NONE);
                        })
                .thenAccept(this::cache);
    }

    @Override
    public CompletableFuture<Void> finished(
            BuildInfo build, BuildTypes.BuildOpenResult result, Throwable failure) {
        Build model = result == null ? null : result.opened();
        BuildRegistry.LoadAttempt attempt = attempts.remove(build.identity());
        if (attempt == null) {
            return model == null
                    ? CompletableFuture.completedFuture(null)
                    : withRelease(CompletableFuture.completedFuture(null), model);
        }
        boolean loaded =
                failure == null
                        && result != null
                        && result.state() == BuildTypes.BuildOpenState.OPEN;
        var handedOff = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<Void> reporting;
        try {
            CompletableFuture<Boolean> persisted;
            Throwable cause = unwrap(failure);
            if (loaded) {
                persisted = registry.loaded(attempt, Cancellation.NONE);
            } else if (cause instanceof java.util.concurrent.CancellationException) {
                persisted = registry.interrupted(attempt, Cancellation.NONE);
            } else {
                String code = result == null ? "BUILD_LOAD_FAILED" : result.code();
                String message =
                        cause != null
                                ? describe(cause)
                                : result == null ? "No build result" : result.message();
                persisted =
                        registry.failed(
                                attempt,
                                code == null ? "BUILD_LOAD_FAILED" : code,
                                message == null ? "Build could not be loaded" : message,
                                Cancellation.NONE);
            }
            reporting =
                    persisted
                            .thenCompose(
                                    applied -> registry.get(build.identity(), Cancellation.NONE))
                            .thenAccept(
                                    entry -> {
                                        cache(entry);
                                        if (loaded) handedOff.set(index(build, model));
                                    });
        } catch (RuntimeException | Error error) {
            reporting = CompletableFuture.failedFuture(error);
        }
        return reporting
                .handle(
                        (ignored, error) -> {
                            CompletableFuture<Void> outcome =
                                    error == null
                                            ? CompletableFuture.completedFuture(null)
                                            : CompletableFuture.failedFuture(unwrap(error));
                            return model != null && !handedOff.get()
                                    ? withRelease(outcome, model)
                                    : outcome;
                        })
                .thenCompose(work -> work);
    }

    private synchronized boolean index(BuildInfo info, Build opened) {
        if (closing) return false;
        String key = "index:" + info.identity();
        if (active.containsKey(key)) {
            releaseDuplicate(info, opened, active.get(key));
            return true;
        }
        var before = snapshots.get(info.identity());
        snapshots.put(
                info.identity(),
                new BuildLoadSnapshot(
                        before == null ? null : before.entry(),
                        before == null ? null : before.usage(),
                        true,
                        null));
        CompletableFuture<Void> work;
        try {
            work = index.apply(info, opened);
        } catch (RuntimeException | Error failure) {
            work = CompletableFuture.failedFuture(failure);
        }
        work = withRelease(work, opened);
        active.put(key, work);
        work.whenComplete(
                (ignored, failure) -> {
                    synchronized (BuildLoadState.this) {
                        active.remove(key);
                    }
                    if (failure != null) problem(info.identity(), failure);
                    else {
                        CompletableFuture<?> previous;
                        synchronized (BuildLoadState.this) {
                            previous = active.get("figures:" + info.identity());
                        }
                        if (previous == null) refresh(info);
                        else previous.whenComplete((value, error) -> refresh(info));
                    }
                });
        return true;
    }

    private void releaseDuplicate(BuildInfo info, Build opened, CompletableFuture<?> existing) {
        String key = "release:" + duplicateSequence++;
        CompletableFuture<Void> result = withRelease(existing.thenApply(ignored -> null), opened);
        active.put(key, result);
        result.whenComplete(
                (ignored, failure) -> {
                    synchronized (BuildLoadState.this) {
                        active.remove(key);
                    }
                    if (failure != null) problem(info.identity(), failure);
                });
    }

    private <T> CompletableFuture<T> withRelease(CompletableFuture<T> work, Build opened) {
        return work.handle(
                        (value, failure) -> {
                            CompletableFuture<Void> closing;
                            try {
                                closing = Objects.requireNonNull(release.apply(opened));
                            } catch (RuntimeException | Error error) {
                                closing = CompletableFuture.failedFuture(error);
                            }
                            return closing.handle(
                                    (ignored, cleanup) -> {
                                        Throwable problem = unwrap(failure);
                                        if (problem != null) {
                                            if (cleanup != null)
                                                problem.addSuppressed(unwrap(cleanup));
                                            throw new CompletionException(problem);
                                        }
                                        if (cleanup != null)
                                            throw new CompletionException(unwrap(cleanup));
                                        return value;
                                    });
                        })
                .thenCompose(result -> result);
    }

    public synchronized CompletableFuture<BuildLoadSnapshot> refresh(BuildInfo build) {
        if (closing)
            return CompletableFuture.failedFuture(new IllegalStateException("Build state closed"));
        String key = "figures:" + build.identity();
        @SuppressWarnings("unchecked")
        CompletableFuture<BuildLoadSnapshot> existing =
                (CompletableFuture<BuildLoadSnapshot>) active.get(key);
        if (existing != null) return existing.copy();
        var result = new CompletableFuture<BuildLoadSnapshot>();
        active.put(key, result);
        registry.get(build.identity(), Cancellation.NONE)
                .thenCompose(
                        entry -> {
                            cache(entry);
                            if (entry == null
                                    || entry.status() == BuildRegistry.Status.DISCOVERED) {
                                return CompletableFuture.completedFuture(snapshot(build));
                            }
                            return figures.apply(build.identity())
                                    .thenApply(
                                            usage -> {
                                                BuildLoadSnapshot prior =
                                                        snapshots.get(build.identity());
                                                boolean indexing;
                                                synchronized (BuildLoadState.this) {
                                                    indexing =
                                                            active.containsKey(
                                                                    "index:" + build.identity());
                                                }
                                                var current =
                                                        new BuildLoadSnapshot(
                                                                prior == null
                                                                        ? entry
                                                                        : prior.entry(),
                                                                usage,
                                                                indexing,
                                                                null);
                                                snapshots.put(build.identity(), current);
                                                return current;
                                            });
                        })
                .whenComplete(
                        (value, failure) -> {
                            synchronized (BuildLoadState.this) {
                                active.remove(key);
                            }
                            if (failure == null) result.complete(value);
                            else {
                                problem(build.identity(), failure);
                                result.completeExceptionally(unwrap(failure));
                            }
                        });
        return result.copy();
    }

    private void cache(BuildRegistry.Entry entry) {
        if (entry == null) return;
        snapshots.compute(
                entry.identity(),
                (identity, before) -> {
                    if (before != null && before.entry() != null) {
                        var prior = before.entry();
                        if (prior.attempt() > entry.attempt()
                                || prior.attempt() == entry.attempt()
                                        && prior.status() != BuildRegistry.Status.LOADING
                                        && entry.status() == BuildRegistry.Status.LOADING)
                            return before;
                    }
                    return new BuildLoadSnapshot(
                            entry,
                            before == null ? null : before.usage(),
                            before != null && before.measuring(),
                            before == null ? null : before.problem());
                });
    }

    private void problem(String identity, Throwable failure) {
        snapshots.compute(
                identity,
                (key, before) ->
                        new BuildLoadSnapshot(
                                before == null ? null : before.entry(),
                                before == null ? null : before.usage(),
                                false,
                                describe(unwrap(failure))));
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        closing = true;
        return CompletableFuture.allOf(active.values().toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
