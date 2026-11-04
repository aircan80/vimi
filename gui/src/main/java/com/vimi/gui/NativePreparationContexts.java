package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildObjectInventory;
import com.vibeminer.builds.BuildOpener;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.builds.Builds;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.generated.PackGeneratedStore;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Owns immutable per-workspace storage capabilities and their full preparation lifetime. */
final class NativePreparationContexts implements BuildPreparation.Factory {
    private final com.vibeminer.builds.BuildResolutionCache resolutionCache = new com.vibeminer.builds.BuildResolutionCache();
    private final ObjectStore library;
    private final LibraryCatalog ownership;
    private final BuildRegistry registry;
    private final com.vibeminer.network.CdnClient network;
    private final ObjectReader legacy;
    private final PreparationHistory history;
    private final OverviewAccounting accounting;
    private final Path grids;
    private final Consumer<BuildInfo> statusChanged;
    private final CachingObjectSource nameSource;
    private final CommunityFileNames names;
    private final Set<Context> contexts = new HashSet<>();
    // All builds share one bounded pool; retiring a workspace never closes this owner.
    private final com.vibeminer.concepts.world.TerrainAtlasPool terrainAtlases =
            new com.vibeminer.concepts.world.TerrainAtlasPool();
    private boolean closing;
    private CompletableFuture<Void> closed;

    NativePreparationContexts(
            ObjectStore library,
            LibraryCatalog ownership,
            BuildRegistry registry,
            NetworkClients network,
            ObjectReader legacy,
            PreparationHistory history,
            OverviewAccounting accounting,
            Path grids,
            Consumer<BuildInfo> statusChanged) {
        this(library, ownership, registry, network, network, legacy, history, accounting, grids, statusChanged);
    }

    NativePreparationContexts(ObjectStore library, LibraryCatalog ownership, BuildRegistry registry,
            com.vibeminer.network.CdnClient network, com.vibeminer.network.ListfileClient listfile,
            ObjectReader legacy, PreparationHistory history, OverviewAccounting accounting,
            Path grids, Consumer<BuildInfo> statusChanged) {
        this.library = library;
        this.ownership = ownership;
        this.registry = registry;
        this.network = network;
        this.legacy = legacy;
        this.history = history;
        this.accounting = accounting;
        this.grids = grids;
        this.statusChanged = statusChanged;
        var originalNames =
                library.forOwner(
                        "provider:community-listfile",
                        LibraryCatalog.Scope.GLOBAL,
                        "source",
                        LibraryCatalog.Role.ORIGINAL);
        nameSource =
                new CachingObjectSource(
                        originalNames,
                        originalNames,
                        originalNames,
                        legacy,
                        network,
                        List.of(ObservationAdapters.listfile(listfile)));
        var generatedNames =
                new PackGeneratedStore(
                        library,
                        library.forOwner(
                                "provider:community-listfile",
                                LibraryCatalog.Scope.GLOBAL,
                                "index",
                                LibraryCatalog.Role.DERIVED));
        names =
                new CommunityFileNames(
                        nameSource,
                        generatedNames,
                        token -> {
                            token.check();
                            return CompletableFuture.completedFuture(true);
                        });
    }

    private DataSourcesService sourcePolicies;
    private ClientHotfixStore hotfixImports;
    NativePreparationContexts withHotfixImports(ClientHotfixStore value) {
        hotfixImports = java.util.Objects.requireNonNull(value); return this;
    }
    NativePreparationContexts withSources(DataSourcesService sources) { sourcePolicies = sources; return this; }
    CommunityFileNames communityNames() { return names; }

    List<PreparationHistory.Receipt> comparisonReceipts() {
        return history == null ? List.of() : history.retainedReceipts();
    }

    @Override
    public synchronized NativePreparationWork create(
            BuildInfo info, BuildInfo.RegionEvidence evidence, String locale) {
        return createWork(info, evidence, locale, false, true,
                com.vibeminer.files.keys.TactKeys.EMPTY);
    }

    synchronized NativePreparationWork createLocalComparison(BuildInfo info,
            BuildInfo.RegionEvidence evidence, String locale, boolean definitions,
            com.vibeminer.files.keys.TactKeys pinnedKeys) {
        return createWork(info, evidence, locale, true, definitions, pinnedKeys);
    }

    private NativePreparationWork createWork(BuildInfo info, BuildInfo.RegionEvidence evidence,
            String locale, boolean localOnly, boolean definitions,
            com.vibeminer.files.keys.TactKeys pinnedKeys) {
        if (closing) {
            throw new IllegalStateException("Preparation contexts closed");
        }
        var context = new Context(info, evidence, locale, localOnly);
        try {
            var generated =
                    new PackGeneratedStore(
                            library,
                            library.forOwner(
                                    info.identity(),
                                    LibraryCatalog.Scope.BUILD,
                                    "workspace-derived",
                                    LibraryCatalog.Role.DERIVED));
            NativePreparationWork work =
                    new NativePreparationWork(
                                    info,
                                    evidence,
                                    locale,
                                    context,
                                    context.source,
                                    generated,
                                    names,
                                    context.localSource,
                                    grids,
                                    terrainAtlases)
                            .withLifecycle(() -> context.closeAsync().join());
            if (localOnly) {
                work.withSourcePolicy(false, definitions).withPinnedCommunityKeys(pinnedKeys);
            } else {
                if (hotfixImports != null) work.withHotfixImports(hotfixImports);
                work.withSourcePolicies(sourcePolicies).withHistory(history).withAccounting(accounting)
                        .withAssessment(ignored -> statusChanged.accept(info));
            }
            contexts.add(context);
            return work;
        } catch (Throwable failure) {
            context.closeAsync()
                    .whenComplete(
                            (ignored, cleanup) -> {
                                if (cleanup != null) {
                                    failure.addSuppressed(cleanup);
                                }
                            });
            throw failure;
        }
    }

    private final class Context implements Builds {
        private final BuildInfo info;
        private final BuildTypes.BuildRef ref;
        private final String locale;
        private final boolean localOnly;
        private final CachingObjectSource source;
        private final CachingObjectSource localSource;
        private final BuildOpener opener;
        private CompletableFuture<Void> inventory = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> close;

        Context(BuildInfo info, BuildInfo.RegionEvidence evidence, String locale, boolean localOnly) {
            this.info = info;
            this.locale = locale;
            this.localOnly = localOnly;
            ref =
                    new BuildTypes.BuildRef(
                            info.product(), info.config(), evidence.cdnConfig(), evidence.region());
            var original =
                    library.forOwner(
                            info.identity(),
                            LibraryCatalog.Scope.BUILD,
                            "workspace-original",
                            LibraryCatalog.Role.ORIGINAL);
            localSource = CachingObjectSource.localOnly(original, original);
            source = localOnly ? CachingObjectSource.localOnly(original, original) :
                    new CachingObjectSource(original, original, original, legacy, network, List.of());
            var indexes = library.forOwner(info.identity(), LibraryCatalog.Scope.BUILD,
                    "build-indexes", LibraryCatalog.Role.DERIVED);
            opener = new BuildOpener(source, new BuildOpener.Options(), resolutionCache,
                    new com.vibeminer.builds.BuildResolutionCache.Storage(indexes, indexes));
        }

        @Override
        public CompletableFuture<BuildTypes.MetadataOpenResult> metadata(
                BuildTypes.BuildRef requested, Cancellation token) {
            if (localOnly) {
                try {
                    requireRef(requested);
                    token.check();
                    return ownership.expect(info.identity(), LibraryCatalog.Scope.BUILD,
                                    "encoding", LibraryCatalog.Role.ORIGINAL)
                            .thenCompose(ignored -> opener.openMetadataBuild(ref, token)).thenApply(result ->
                            new BuildTypes.MetadataOpenResult(result.build(), result.state(),
                                    result.opened() == null ? null : result.opened().metadata(),
                                    result.part(), result.code(), result.message(), result.observedAt()));
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            return background(
                    () -> {
                        requireRef(requested);
                        token.check();
                        var attempt =
                                registry.beginLoad(
                                                info.identity(),
                                                ref,
                                                info.version(),
                                                locale,
                                                com.vibeminer.store.Cancellation.NONE)
                                        .join();
                        try {
                            ownership
                                    .expect(
                                            info.identity(),
                                            LibraryCatalog.Scope.BUILD,
                                            "encoding",
                                            LibraryCatalog.Role.ORIGINAL)
                                    .join();
                            changed();
                            var result = opener.openMetadataBuild(ref, token).join();
                            token.check();
                            if (result.state() == BuildTypes.BuildOpenState.OPEN
                                    && result.opened() != null) {
                                registry.loaded(attempt, com.vibeminer.store.Cancellation.NONE)
                                        .join();
                                startInventory(result.opened());
                            } else {
                                registry.failed(
                                                attempt,
                                                result.code() == null
                                                        ? "BUILD_METADATA_FAILED"
                                                        : result.code(),
                                                result.message() == null
                                                        ? "Build metadata could not be loaded"
                                                        : result.message(),
                                                com.vibeminer.store.Cancellation.NONE)
                                        .join();
                            }
                            changed();
                            return new BuildTypes.MetadataOpenResult(
                                    result.build(),
                                    result.state(),
                                    result.opened() == null ? null : result.opened().metadata(),
                                    result.part(),
                                    result.code(),
                                    result.message(),
                                    result.observedAt());
                        } catch (Throwable failure) {
                            Throwable cause = unwrap(failure);
                            try {
                                if (cause instanceof java.util.concurrent.CancellationException) {
                                    registry.interrupted(
                                                    attempt, com.vibeminer.store.Cancellation.NONE)
                                            .join();
                                } else {
                                    registry.failed(
                                                    attempt,
                                                    "BUILD_METADATA_FAILED",
                                                    "Build metadata could not be loaded",
                                                    com.vibeminer.store.Cancellation.NONE)
                                            .join();
                                }
                            } catch (Throwable reporting) {
                                cause.addSuppressed(unwrap(reporting));
                            }
                            changed();
                            throw cause instanceof RuntimeException runtime
                                    ? runtime
                                    : new CompletionException(cause);
                        }
                    });
        }

        private synchronized void startInventory(Build opened) {
            try {
                inventory =
                        accounting == null
                                ? BuildObjectInventory.index(
                                        ownership,
                                        info.identity(),
                                        opened,
                                        com.vibeminer.store.Cancellation.NONE)
                                : accounting.indexBuild(info, opened);
            } catch (RuntimeException failure) {
                inventory = CompletableFuture.failedFuture(failure);
            }
            inventory.whenComplete((ignored, failure) -> changed());
        }

        private void changed() {
            try {
                statusChanged.accept(info);
            } catch (RuntimeException closedService) {
                // The durable status is already saved; a closing view needs no notification.
            }
        }

        @Override
        public CompletableFuture<BuildTypes.BuildOpenResult> open(
                BuildTypes.BuildRef requested, Cancellation token) {
            requireRef(requested);
            return opener.open(ref, token);
        }

        private void requireRef(BuildTypes.BuildRef requested) {
            if (!ref.equals(requested)) {
                throw new IllegalArgumentException("Different preparation build context");
            }
        }

        synchronized CompletableFuture<Void> closeAsync() {
            if (close != null) {
                return close.copy();
            }
            close =
                    inventory
                            .handle((ignored, failure) -> null)
                            .thenCompose(ignored -> opener.closeAsync())
                            .handle((ignored, failure) -> finishSources(failure))
                            .thenCompose(work -> work)
                            .whenComplete(
                                    (ignored, failure) -> {
                                        synchronized (NativePreparationContexts.this) {
                                            contexts.remove(this);
                                        }
                                    });
            return close.copy();
        }

        private CompletableFuture<Void> finishSources(Throwable prior) {
            return CompletableFuture.allOf(localSource.closeAsync(), source.closeAsync())
                    .handle(
                            (ignored, failure) -> {
                                if (prior != null) {
                                    if (failure != null) {
                                        prior.addSuppressed(failure);
                                    }
                                    throw new CompletionException(unwrap(prior));
                                }
                                if (failure != null) {
                                    throw new CompletionException(unwrap(failure));
                                }
                                return null;
                            });
        }
    }

    CompletableFuture<Void> closeAsync() {
        final List<Context> retiring;
        final CompletableFuture<Void> result;
        synchronized (this) {
            if (closed != null) {
                return closed.copy();
            }
            closing = true;
            retiring = List.copyOf(contexts);
            closed = new CompletableFuture<>();
            result = closed;
        }
        var draining = new ArrayList<CompletableFuture<Void>>();
        for (Context context : retiring) {
            draining.add(context.closeAsync());
        }
        CompletableFuture.allOf(draining.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> resolutionCache.close())
                .handle(
                        (ignored, failure) ->
                                CompletableFuture.allOf(
                                                nameSource.closeAsync(), terrainAtlases.closeAsync())
                                        .handle(
                                                (done, nameFailure) -> {
                                                    if (failure != null) {
                                                        if (nameFailure != null) {
                                                            failure.addSuppressed(nameFailure);
                                                        }
                                                        throw new CompletionException(
                                                                unwrap(failure));
                                                    }
                                                    if (nameFailure != null) {
                                                        throw new CompletionException(
                                                                unwrap(nameFailure));
                                                    }
                                                    return (Void) null;
                                                }))
                .thenCompose(work -> work)
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure == null) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(unwrap(failure));
                            }
                        });
        return result.copy();
    }

    @FunctionalInterface
    private interface Task<T> {
        T run();
    }

    private static <T> CompletableFuture<T> background(Task<T> task) {
        var result = new CompletableFuture<T>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        result.complete(task.run());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
