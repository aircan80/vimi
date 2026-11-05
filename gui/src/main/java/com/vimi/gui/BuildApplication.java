package com.vimi.gui;

import com.vibeminer.activity.TransferObserver;
import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.builds.Catalog;
import com.vibeminer.builds.Products;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.History;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Native composition root. Startup, history scans and shutdown never perform I/O on the FX thread.
 */
public final class BuildApplication {
    private BuildApplication() {}

    public static Path defaultDataDirectory() {
        String configured = System.getProperty("vimi.dataDirectory");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath();
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return home.resolve("Library/Application Support/vimi");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return local == null || local.isBlank()
                    ? home.resolve("AppData/Local/vimi")
                    : Path.of(local).resolve("vimi");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg == null || xdg.isBlank()
                ? home.resolve(".local/share/vimi")
                : Path.of(xdg).resolve("vimi");
    }

    public static CompletableFuture<BackendBuildService> open() {
        return open(defaultDataDirectory());
    }

    public static CompletableFuture<BackendBuildService> open(Path directory) {
        return open(directory, null, TransferObserver.NONE);
    }

    static CompletableFuture<BackendBuildService> open(Path directory, com.vibeminer.network.TactKeyClient keyClient) {
        return open(directory, keyClient, TransferObserver.NONE);
    }

    static CompletableFuture<BackendBuildService> open(Path directory,
            com.vibeminer.network.TactKeyClient keyClient, TransferObserver observer) {
        java.util.Objects.requireNonNull(directory);
        java.util.Objects.requireNonNull(observer);
        // The data/library root names the account; World diagnostics redact it literally.
        com.vibeminer.rendering.bgfx.HelperStderrTail.addSensitiveRoot(directory);
        var result = new CompletableFuture<BackendBuildService>();
        var token = new Cancellation();
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled()) token.cancel();
                });
        Thread.startVirtualThread(
                () -> {
                    var cleanup = new ArrayList<Supplier<CompletableFuture<Void>>>();
                    try {
                        token.check();
                        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
                        cleanup.add(library::closeAsync);
                        var ownership =
                                com.vibeminer.store.LibraryCatalog.open(library.databasePath())
                                        .join();
                        cleanup.addFirst(ownership::closeAsync);
                        var migration =
                                PackLibraryMigration.open(directory, library, ownership).join();
                        cleanup.addFirst(
                                () -> {
                                    migration.close();
                                    return CompletableFuture.completedFuture(null);
                                });
                        var registry =
                                com.vibeminer.builds.BuildRegistry.open(library.databasePath())
                                        .join();
                        cleanup.addFirst(registry::closeAsync);
                        var saved = PreparationHistory.forLibrary(library.databasePath());
                        saved.load();
                        for (var receipt : saved.retainedReceipts()) {
                            ownership
                                    .retainOwner(
                                            receipt.identity(),
                                            com.vibeminer.store.LibraryCatalog.Scope.BUILD)
                                    .join();
                            if (registry.get(
                                                            receipt.identity(),
                                                            com.vibeminer.store.Cancellation.NONE)
                                                    .join()
                                            == null
                                    && receipt.cdnConfig() != null
                                    && receipt.region() != null) {
                                com.vibeminer.builds.BuildTypes.BuildRef savedRef;
                                try {
                                    savedRef =
                                            new com.vibeminer.builds.BuildTypes.BuildRef(
                                                    receipt.product(),
                                                    receipt.config(),
                                                    receipt.cdnConfig(),
                                                    receipt.region());
                                } catch (IllegalArgumentException invalidRoute) {
                                    System.getLogger(BuildApplication.class.getName())
                                            .log(
                                                    System.Logger.Level.WARNING,
                                                    "A saved build has no usable metadata route;"
                                                        + " its original receipt and ownership were"
                                                        + " retained");
                                    continue;
                                }
                                registry.observe(
                                                receipt.identity(),
                                                savedRef,
                                                "Saved build",
                                                receipt.locale(),
                                                com.vibeminer.store.Cancellation.NONE)
                                        .join();
                            }
                        }
                        var network = new NetworkClients(new com.vibeminer.network.NetworkOptions(), observer);
                        cleanup.addFirst(network::closeAsync);
                        var discovery =
                                library.forOwner(
                                        "library:discovery",
                                        com.vibeminer.store.LibraryCatalog.Scope.GLOBAL,
                                        "discovery",
                                        com.vibeminer.store.LibraryCatalog.Role.ORIGINAL);
                        var source =
                                new CachingObjectSource(
                                        discovery,
                                        discovery,
                                        discovery,
                                        migration.legacyObjects(),
                                        network,
                                        List.of(
                                                ObservationAdapters.versions(network),
                                                ObservationAdapters.listfile(network),
                                                ObservationAdapters.definitions(network)));
                        cleanup.addFirst(source::closeAsync);
                        var catalog =
                                Catalog.open(
                                                source,
                                                directory.resolve("catalog.sqlite"),
                                                new Catalog.Options(),
                                                token)
                                        .join();
                        cleanup.addFirst(catalog::closeAsync);
                        var accounting =
                                OverviewAccounting.openUnified(directory, ownership, library);
                        cleanup.addFirst(accounting::closeAsync);
                        var contexts =
                                new BuildMetadataContexts(
                                        library, ownership, network, migration.legacyObjects());
                        cleanup.addFirst(contexts::closeAsync);
                        var indexToken = new com.vibeminer.store.Cancellation();
                        var state =
                                new BuildLoadState(
                                        registry,
                                        accounting::indexBuild,
                                        owner -> ownership.figures(owner, indexToken),
                                        contexts::release);
                        cleanup.addFirst(state::closeAsync);
                        var hotfixImports = new ClientHotfixStore(directory.resolve("client-hotfix"));
                        cleanup.addFirst(hotfixImports::closeAsync);
                        var fullContexts =
                                new NativePreparationContexts(
                                        library,
                                        ownership,
                                        registry,
                                        network,
                                        migration.legacyObjects(),
                                        saved,
                                        accounting,
                                        directory.resolve("derived").resolve("grids"),
                                        state::invalidate).withHotfixImports(hotfixImports);
                        cleanup.addFirst(fullContexts::closeAsync);
                        var settings = SettingsService.open(library.databasePath()).join();
                        cleanup.addFirst(settings::closeAsync);
                        var tactOwner = library.forOwner("provider:tact-keys", LibraryCatalog.Scope.GLOBAL,
                                "source", LibraryCatalog.Role.ORIGINAL);
                        ownership.retainOwner("provider:tact-keys", LibraryCatalog.Scope.GLOBAL).join();
                        var dataSources = DataSourcesService.open(library.databasePath(), directory.resolve("questie"),
                                fullContexts.communityNames(), ownership, keyClient == null ? network : keyClient, tactOwner, tactOwner, network, network, observer);
                        cleanup.addFirst(dataSources::closeAsync);
                        fullContexts.withSources(dataSources);
                        var worldBookmarks = WorldBookmarkStore.forLibrary(library.databasePath());
                        cleanup.addFirst(() -> {
                            worldBookmarks.close();
                            return CompletableFuture.completedFuture(null);
                        });
                        Path comparisonWork = directory.resolve("derived").resolve("comparison-work");
                        ComparisonTemporaryFiles.cleanStale(comparisonWork);
                        var comparisons = new ComparisonCoordinator(
                                new LocalComparisonFactory(fullContexts, dataSources, comparisonWork), comparisonWork);
                        cleanup.addFirst(comparisons::closeAsync);
                        var preparation = new BuildPreparation(fullContexts);
                        cleanup.addFirst(preparation::closeAsync);
                        cleanup.addFirst(
                                () -> {
                                    accounting.cancelUpdates();
                                    indexToken.cancel();
                                    return CompletableFuture.completedFuture(null);
                                });
                        var downloads =
                                BuildDownloads.open(
                                        library.databasePath(),
                                        new NativeDownloadRunner(
                                                library,
                                                network,
                                                migration.legacyObjects(),
                                                accounting,
                                                directory));
                        cleanup.addFirst(downloads::closeAsync);
                        var opening = new BuildOpening(contexts::open, state);
                        cleanup.addFirst(opening::closeAsync);
                        token.check();
                        var service =
                                new BackendBuildService(
                                                catalog,
                                                cancellation ->
                                                        discoverProducts(source, cancellation),
                                                () -> closeAll(cleanup))
                                        .withOpening(opening)
                                        .withPreparation(preparation)
                                        .withComparisons(comparisons)
                                        .withDownloads(downloads)
                                        .withDataSources(dataSources)
                                        .withWorldBookmarks(worldBookmarks)
                                        .withHotfixImports(hotfixImports)
                                        .withSettings(settings)
                                        .withLoadState(state)
                                        .withHistory(saved);
                        if (!result.complete(service)) service.closeAsync().join();
                    } catch (Throwable failure) {
                        try {
                            closeAll(cleanup).join();
                        } catch (Throwable closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    static CompletableFuture<List<String>> discoverProducts(
            ObjectSource source, Cancellation token) {
        // Called on the adapter's private worker; retain bounded products, never historical bodies.
        TreeSet<String> found = new TreeSet<>(Products.NATURAL_ORDER);
        History.Entry after = null;
        for (; ; ) {
            token.check();
            var page =
                    source.readHistory(new History.Query(null, null, 1, after, 1000), token).join();
            for (var entry : page) {
                if (Products.isWow(entry.product()) && !Products.isKnown(entry.product())) {
                    found.add(entry.product());
                    if (found.size() > Products.MAX_UNREVIEWED) found.pollLast();
                }
            }
            if (page.size() < 1000) return CompletableFuture.completedFuture(List.copyOf(found));
            var next = page.getLast();
            if (next.equals(after))
                throw new IllegalStateException("History pagination did not advance");
            after = next;
        }
    }

    private static CompletableFuture<Void> closeAll(
            List<Supplier<CompletableFuture<Void>>> actions) {
        // Invoked only by a private startup/shutdown worker. Try every owner even after a failure.
        Throwable first = null;
        for (var action : actions) {
            try {
                action.get().join();
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        return first == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(first);
    }
}
