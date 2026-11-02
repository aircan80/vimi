package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.builds.BuildOpener;
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
                (value, error) -> {
                    if (result.isCancelled()) token.cancel();
                });
        try {
            Thread.startVirtualThread(
                    () -> {
                        ObjectStore objects = null;
                        NetworkClients network = null;
                        CachingObjectSource source = null;
                        CachingObjectSource localSource = null;
                        Catalog catalog = null;
                        BuildOpener opener = null;
                        BuildOpening opening = null;
                        BuildPreparation preparation = null;
                        com.vibeminer.generated.DiskGeneratedStore generated = null;
                        BackendBuildService service = null;
                        try {
                            token.check();
                            objects = ObjectStore.open(directory.resolve("objects")).join();
                            token.check();
                            network = new NetworkClients();
                            source =
                                    new CachingObjectSource(
                                            objects,
                                            objects,
                                            objects,
                                            null,
                                            network,
                                            List.of(
                                                    ObservationAdapters.versions(network),
                                                    ObservationAdapters.listfile(network),
                                                    ObservationAdapters.definitions(network)));
                            localSource = CachingObjectSource.localOnly(objects, objects);
                            catalog =
                                    Catalog.open(
                                                    source,
                                                    directory.resolve("catalog.sqlite"),
                                                    new Catalog.Options(),
                                                    token)
                                            .join();
                            token.check();
                            opener = new BuildOpener(source);
                            opening = new BuildOpening(opener);
                            generated =
                                    com.vibeminer.generated.DiskGeneratedStore.open(
                                                    directory.resolve("generated"))
                                            .join();
                            var ownedGenerated = generated;
                            var preparationHistory = new PreparationHistory(generated);
                            preparationHistory.load();
                            var workspaceOpener = opener;
                            var workspaceSource = source;
                            var workspaceLocalSource = localSource;
                            var community =
                                    new com.vibeminer.files.names.CommunityFileNames(
                                            source,
                                            generated,
                                            cancellation -> {
                                                cancellation.check();
                                                return CompletableFuture.completedFuture(true);
                                            });
                            preparation =
                                    new BuildPreparation(
                                            (build, region, locale) ->
                                                    new NativePreparationWork(
                                                                    build,
                                                                    region,
                                                                    locale,
                                                                    workspaceOpener,
                                                                    workspaceSource,
                                                                    ownedGenerated,
                                                                    community,
                                                                    workspaceLocalSource,
                                                                    directory
                                                                            .resolve("derived")
                                                                            .resolve("grids"))
                                                            .withHistory(preparationHistory));
                            var ownedPreparation = preparation;
                            var ownedOpener = opener;
                            var ownedOpening = opening;
                            var ownedObjects = objects;
                            var ownedNetwork = network;
                            var ownedSource = source;
                            var ownedCatalog = catalog;
                            service =
                                    new BackendBuildService(
                                                    catalog,
                                                    cancellation ->
                                                            discoverProducts(
                                                                    ownedSource, cancellation),
                                                    () ->
                                                            closeAll(
                                                                    List.of(
                                                                            ownedPreparation
                                                                                    ::closeAsync,
                                                                            ownedOpening
                                                                                    ::closeAsync,
                                                                            ownedOpener::closeAsync,
                                                                            ownedCatalog
                                                                                    ::closeAsync,
                                                                            ownedSource::closeAsync,
                                                                            workspaceLocalSource
                                                                                    ::closeAsync,
                                                                            ownedNetwork
                                                                                    ::closeAsync,
                                                                            ownedGenerated
                                                                                    ::closeAsync,
                                                                            ownedObjects
                                                                                    ::closeAsync)))
                                            .withOpening(opening)
                                            .withPreparation(preparation)
                                            .withHistory(preparationHistory);
                            if (!result.complete(service)) service.closeAsync().join();
                        } catch (Throwable failure) {
                            try {
                                if (service != null) service.closeAsync().join();
                                else {
                                    List<Supplier<CompletableFuture<Void>>> close =
                                            new ArrayList<>();
                                    if (preparation != null) close.add(preparation::closeAsync);
                                    if (opening != null) close.add(opening::closeAsync);
                                    if (opener != null) close.add(opener::closeAsync);
                                    if (catalog != null) close.add(catalog::closeAsync);
                                    if (source != null) close.add(source::closeAsync);
                                    if (localSource != null) close.add(localSource::closeAsync);
                                    if (network != null) close.add(network::closeAsync);
                                    if (generated != null) close.add(generated::closeAsync);
                                    if (objects != null) close.add(objects::closeAsync);
                                    closeAll(close).join();
                                }
                            } catch (Throwable closeFailure) {
                                failure.addSuppressed(closeFailure);
                            }
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
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
