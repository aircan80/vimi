package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildCatalog;
import com.vibeminer.builds.CatalogTypes;
import com.vibeminer.builds.Products;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Catalog-only GUI adapter. All lower calls run on a bounded serial virtual worker. */
public final class BackendBuildService implements BuildService {
    private final BuildCatalog catalog;
    private BuildLoadState loadState;
    public BackendBuildService withLoadState(BuildLoadState value) { loadState = Objects.requireNonNull(value); return this; }
    @Override public BuildLoadSnapshot loadSnapshot(BuildInfo build) { return loadState == null ? null : loadState.snapshot(build); }
    @Override public CompletableFuture<BuildLoadSnapshot> refreshLoadSnapshot(BuildInfo build) {
        return loadState == null ? CompletableFuture.completedFuture(null) : loadState.refresh(build);
    }
    private SettingsService settings;
    public BackendBuildService withSettings(SettingsService value) { settings=Objects.requireNonNull(value); return this; }
    @Override public SettingsService settings() { return settings; }
    private DataSourcesService dataSources;
    BackendBuildService withDataSources(DataSourcesService value) { dataSources=value; return this; }
    @Override public DataSourcesService dataSources() { return dataSources; }
    private WorldBookmarkStore worldBookmarks;
    BackendBuildService withWorldBookmarks(WorldBookmarkStore value) { worldBookmarks=value; return this; }
    @Override public WorldBookmarkStore worldBookmarks() { return worldBookmarks; }
    private BuildComparisons comparisons;
    BackendBuildService withComparisons(BuildComparisons value) { comparisons=value; return this; }
    @Override public BuildComparisons comparisons() { return comparisons; }
    private BuildDownloads downloads;
    BackendBuildService withDownloads(BuildDownloads value) { downloads=value; return this; }
    @Override public BuildDownloads downloads() { return downloads; }
    private BuildOpening opening;
    private PreparationHistory history;
    BackendBuildService withHistory(PreparationHistory value) {history=value;return this;}
    @Override public String historicalPreparation(BuildInfo build) {return history==null?null:history.summary(build);}
    private BuildPreparation preparation;
    public BackendBuildService withPreparation(BuildPreparation value) { preparation = value; return this; }
    @Override public BuildPreparation preparation() { return preparation; }
    public BackendBuildService withOpening(BuildOpening value) { opening = value; return this; }
    @Override public BuildOpening opening() { return opening; }
    private final Function<Cancellation, CompletableFuture<List<String>>> discovered;
    private final Supplier<CompletableFuture<Void>> cleanup;
    private final Cancellation token = new Cancellation();
    private final Object gate = new Object();
    private final java.util.Map<String, CatalogTypes.BuildList> lastGood = new java.util.HashMap<>();
    private final TreeSet<String> products = new TreeSet<>(Products.NATURAL_ORDER);
    private CompletableFuture<List<BuildInfo>> listing, refreshing;
    private Cancellation refreshToken;
    private boolean active, closed, cleaning;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile List<String> diagnostics = List.of();
    private volatile String status = "Local catalog · Refresh explicitly to observe Blizzard";

    public BackendBuildService(BuildCatalog catalog) {
        this(catalog, ignored -> CompletableFuture.completedFuture(List.of()),
                () -> CompletableFuture.completedFuture(null));
    }

    public BackendBuildService(BuildCatalog catalog,
            Function<Cancellation, CompletableFuture<List<String>>> discovered,
            Supplier<CompletableFuture<Void>> cleanup) {
        this.catalog = Objects.requireNonNull(catalog);
        this.discovered = Objects.requireNonNull(discovered);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    @Override public void cancelCatalogRefresh() {
        synchronized (gate) { if (refreshToken != null) refreshToken.cancel(); }
    }

    @Override public boolean mockMode() { return false; }
    @Override public String status() { return status; }
    @Override public List<String> problems() { return diagnostics; }
    @Override public CompletableFuture<List<BuildInfo>> listBuilds() { return request(false); }
    @Override public CompletableFuture<List<BuildInfo>> refreshBuilds() { return request(true); }

    private CompletableFuture<List<BuildInfo>> request(boolean refresh) {
        CompletableFuture<List<BuildInfo>> result;
        synchronized (gate) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Build service closed"));
            if (refreshing != null) return refreshing.copy();
            if (!refresh && listing != null) return listing.copy();
            result = new CompletableFuture<>();
            if (refresh) { refreshing = result; refreshToken = new Cancellation(); }
            else listing = result;
            if (active) return result.copy(); // At most one explicit refresh queued behind listing.
            active = true;
        }
        start(refresh, result);
        return result.copy();
    }

    @SuppressWarnings("try")
    private void start(boolean refresh, CompletableFuture<List<BuildInfo>> result) {
        final Cancellation request;
        synchronized (gate) { request = refresh ? refreshToken : new Cancellation(); }
        try {
            Thread.startVirtualThread(() -> {
                List<BuildInfo> value = null;
                Throwable failure = null;
                try (var registration = token.onCancel(request::cancel)) { value = load(refresh, request); request.check(); }
                catch (Throwable error) { failure = unwrap(error); }
                finish(refresh, result, value, failure);
            });
        } catch (Throwable error) {
            finish(refresh, result, null, error);
        }
    }

    private void finish(boolean refresh, CompletableFuture<List<BuildInfo>> result,
            List<BuildInfo> value, Throwable failure) {
        CompletableFuture<List<BuildInfo>> next;
        boolean clean;
        if (failure != null) {
            if (!(failure instanceof CancellationException)) {
                diagnostics = failure instanceof CatalogFailure unavailable ? unavailable.problems : List.of(describe(failure));
                status = "Catalog failed · " + diagnostics.size() + " issues";
            }
        }
        synchronized (gate) {
            if (refresh) { refreshing = null; refreshToken = null; }
            else listing = null;
            next = refreshing;
            active = next != null;
            clean = closed && !active && !cleaning;
            if (clean) cleaning = true;
        }
        if (next != null) start(true, next);
        if (clean) startCleanup();
        if (failure != null) result.completeExceptionally(failure);
        else result.complete(value);
    }

    private void remember(Iterable<String> discoveredProducts) {
        for (String product : discoveredProducts) {
            if (Products.isWow(product) && !Products.isKnown(product)) {
                products.add(product);
                if (products.size() > Products.MAX_UNREVIEWED) products.pollLast();
            }
        }
    }

    private List<BuildInfo> load(boolean refresh, Cancellation request) {
        request.check();
        List<String> warnings = new ArrayList<>();
        try { remember(discovered.apply(request).join()); }
        catch (Throwable error) { warning(warnings, "Local product discovery", error); }
        java.util.Set<String> eligible = null;
        if (refresh) {
            try {
                var summary = catalog.refreshSummary(request).join();
                remember(summary.newProducts());
                remember(summary.listed());
                observation(warnings, "Summary", summary.summary());
                problems(warnings, "Summary", summary.problems());
                if (summary.summary().state() == SourceTypes.ObjectState.PRESENT
                        && summary.summary().observation() != null && !summary.listed().isEmpty()
                        && summary.problems().isEmpty()) {
                    eligible = new java.util.HashSet<>();
                    for (String product : summary.listed()) {
                        if (Products.isWow(product) && !summary.withoutVersions().contains(product)) eligible.add(product);
                    }
                }
            } catch (Throwable error) { warning(warnings, "Summary refresh", error); }
        }
        // Retain only the reviewed fourteen plus the bounded natural-order discovery window.
        if (settings != null) remember(settings.enabledProducts());
        var listed = new ArrayList<>(Products.listed(products));
        if (settings != null) {
            for (String id : settings.enabledProducts()) {
                if (listed.stream().noneMatch(product -> product.id().equals(id)))
                    listed.add(new Products.Listed(id, Products.name(id), Products.isKnown(id)));
            }
            settings.rememberProducts(listed.stream().map(Products.Listed::id).toList()).join();
        }
        lastGood.keySet().retainAll(listed.stream().map(Products.Listed::id).toList());
        products.clear();
        listed.stream().filter(p -> !p.reviewed()).forEach(p -> products.add(p.id()));
        List<BuildInfo> result = new ArrayList<>();
        int enabledCount = 0;
        int readableCount = 0;
        for (var product : listed) {
            request.check();
            if (settings != null && !settings.enabled(product.id())) continue;
            enabledCount++;
            CatalogTypes.BuildList list = null;
            if (refresh && (eligible == null || eligible.contains(product.id()))) {
                try {
                    var updated = catalog.refresh(product.id(), request).join();
                    observation(warnings, product.id() + " versions", updated.versions());
                    observation(warnings, product.id() + " cdns", updated.cdns());
                    list = updated.list();
                } catch (Throwable error) { warning(warnings, product.id() + " refresh", error); }
            }
            if (list == null) {
                try { list = catalog.listBuilds(product.id(), request).join(); }
                catch (Throwable error) {
                    warning(warnings, product.id() + " catalog", error);
                    list = lastGood.get(product.id());
                    if (list == null) continue;
                    warnings.add(product.id() + ": showing previous catalog snapshot");
                }
            }
            readableCount++;
            lastGood.put(product.id(), list);
            problems(warnings, product.id(), list.problems());
            for (var build : list.builds()) result.add(project(build));
        }
        request.check();
        if (loadState != null) result = new ArrayList<>(loadState.observe(result, request::check));
        if (enabledCount > 0 && readableCount == 0) throw new CatalogFailure(warnings);
        diagnostics = List.copyOf(warnings);
        status = (enabledCount == 0 ? "No products enabled · Choose products in Settings"
                : refresh ? "Refresh complete" : "Local catalog · No network requests")
                + (warnings.isEmpty() ? "" : " · " + warnings.size() + " issues");
        return List.copyOf(result);
    }

    private static final class CatalogFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> problems;
        CatalogFailure(List<String> problems) {
            super("No product catalog could be read");
            this.problems = List.copyOf(problems);
        }
    }

    private static BuildInfo project(CatalogTypes.CatalogBuild build) {
        List<BuildInfo.RegionEvidence> evidence = build.regions().stream().map(region ->
                new BuildInfo.RegionEvidence(region.region(), region.cdnConfig(), region.current(),
                        region.firstSeen(), region.lastSeen())).toList();
        String regions = build.regions().stream().map(CatalogTypes.BuildRegion::region).distinct()
                .collect(java.util.stream.Collectors.joining(", "));
        return new BuildInfo(build.product(), Products.name(build.product()), build.versionsName(), regions,
                build.buildConfig(), build.current() ? BuildInfo.BuildSource.CURRENT : BuildInfo.BuildSource.SAVED,
                false, null, 0, build.id(), build.firstSeen(), build.lastSeen(), evidence);
    }

    private static void problems(List<String> warnings, String scope, List<CatalogTypes.CatalogProblem> problems) {
        for (var problem : problems) warnings.add(scope + ": " + problem.code() + " " + problem.message()
                + (problem.record() == null ? "" : " [record " + problem.record() + "]")
                + (problem.row() == null ? "" : " [row " + problem.row() + "]"));
    }

    private static void observation(List<String> warnings, String scope, SourceTypes.ObservationResult result) {
        if (result.state() != SourceTypes.ObjectState.PRESENT || result.code() != null || !result.recorded()) {
            warnings.add(scope + ": " + result.state() + (result.recorded() ? "" : " (not recorded)")
                    + (result.code() == null ? "" : " " + result.code())
                    + (result.message() == null ? "" : " " + result.message()));
        }
    }

    private void warning(List<String> warnings, String scope, Throwable error) {
        token.check();
        var cause = unwrap(error);
        if (cause instanceof CancellationException cancellation) throw cancellation;
        warnings.add(scope + ": " + describe(cause));
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    @Override public CompletableFuture<BuildInfo> openBuild(BuildInfo build) {
        Objects.requireNonNull(build);
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Build opening is not connected yet"));
    }

    @Override public CompletableFuture<Void> closeAsync() {
        boolean clean;
        synchronized (gate) {
            closed = true;
            clean = !active && !cleaning;
            if (clean) cleaning = true;
        }
        token.cancel();
        if (clean) startCleanup();
        return drained.copy();
    }

    private void startCleanup() {
        try {
            Thread.startVirtualThread(() -> {
                try { cleanup.get().join(); drained.complete(null); }
                catch (Throwable error) { drained.completeExceptionally(unwrap(error)); }
            });
        } catch (Throwable error) { drained.completeExceptionally(error); }
    }
}
