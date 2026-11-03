package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.builds.Catalog;
import com.vibeminer.builds.Products;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.History;
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
        java.util.Objects.requireNonNull(directory);
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
                        var saved =
                                new PreparationHistory(
                                        new com.vibeminer.generated.PackGeneratedStore(
                                                library,
                                                library.forOwner(
                                                        "library:saved-builds",
                                                        com.vibeminer.store.LibraryCatalog.Scope
                                                                .GLOBAL,
                                                        "receipts",
                                                        com.vibeminer.store.LibraryCatalog.Role
                                                                .DERIVED)));
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
                        var network = new NetworkClients();
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
                        var contexts =
                                new BuildMetadataContexts(
                                        library, ownership, network, migration.legacyObjects());
                        cleanup.addFirst(contexts::closeAsync);
                        var indexToken = new com.vibeminer.store.Cancellation();
                        var state =
                                new BuildLoadState(
                                        registry,
                                        (info, opened) ->
                                                com.vibeminer.builds.BuildObjectInventory.index(
                                                        ownership,
                                                        info.identity(),
                                                        opened,
                                                        indexToken),
                                        owner -> ownership.figures(owner, indexToken),
                                        contexts::release);
                        cleanup.addFirst(state::closeAsync);
                        cleanup.addFirst(
                                () -> {
                                    indexToken.cancel();
                                    return CompletableFuture.completedFuture(null);
                                });
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
