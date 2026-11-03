package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildCatalog;
import com.vibeminer.builds.CatalogTypes;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Offline persisted settings and catalog visibility contracts, independent of the JavaFX runtime.
 */
public final class SettingsServiceTest {
    private SettingsServiceTest() {}

    public static void main(String[] args) throws Exception {
        defaultsPersistAndKeepPayloads();
        disabledProductsAreNotReadOrRefreshed();
        refusesFutureSchema();
        System.out.println(
                "Settings: defaults, persistence, discovered products, catalog filtering and schema"
                        + " guards passed");
    }

    private static void defaultsPersistAndKeepPayloads() throws Exception {
        Path directory = Files.createTempDirectory("vimi-settings-");
        var library = ObjectStore.openLibrary(directory).join();
        try (var ownership = LibraryCatalog.open(library.databasePath()).join()) {
            var payload = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
            library.forOwner(
                            "build-owner",
                            LibraryCatalog.Scope.BUILD,
                            "fixture",
                            LibraryCatalog.Role.DERIVED)
                    .put(List.of(new StoreTypes.PutObject(payload, new byte[] {1, 2, 3})))
                    .join();
            require(
                    ownership
                                    .figures("build-owner", com.vibeminer.store.Cancellation.NONE)
                                    .join()
                                    .derived()
                                    .storedBytes()
                            == 3,
                    "Fixture has retained ownership before settings changes");
            var settings = await(SettingsService.open(library.databasePath()));
            try {
                require(
                        Set.copyOf(settings.enabledProducts())
                                .equals(SettingsService.DEFAULT_PRODUCTS),
                        "Fresh settings use reviewed default channels only");
                require(
                        !settings.enabled("wowt") && !settings.enabled("wow_classic"),
                        "PTR/progression hidden by default");
                await(settings.rememberProducts(List.of("wow_future", "other", "wow_future")));
                require(
                        settings.snapshot().products().stream()
                                .anyMatch(p -> p.id().equals("wow_future") && !p.enabled()),
                        "New discovery is available but disabled");
                long revision = settings.snapshot().revision();
                await(settings.setEnabled("wow", false));
                await(settings.setEnabled("wow_future", true));
                require(
                        settings.snapshot().revision() > revision,
                        "Settings revision updates after commit");
                require(
                        library.stats().join().objects() == 1
                                && library.read(payload).join().state()
                                        == StoreTypes.ReadState.PRESENT,
                        "Hiding products preserves stored data");
                require(
                        settings.setEnabled("not-a-wow-product", true).isCompletedExceptionally(),
                        "Invalid IDs refused without persistence");
            } finally {
                await(settings.closeAsync());
            }
            var reopened = await(SettingsService.open(library.databasePath()));
            require(
                    !reopened.enabled("wow") && reopened.enabled("wow_future"),
                    "Explicit choices survive restart");
            await(reopened.resetProducts());
            require(
                    reopened.enabled("wow") && !reopened.enabled("wow_future"),
                    "Restore defaults preserves discovery and clears overrides");
            await(
                    reopened.setEnabled("wow_beta", false)
                            .thenCompose(ignored -> reopened.closeAsync()));
            require(
                    reopened.setEnabled("wow_beta", true).isCompletedExceptionally(),
                    "Close rejects changes and can be awaited from callback");
        } finally {
            library.closeAsync().join();
            delete(directory);
        }
    }

    private static void disabledProductsAreNotReadOrRefreshed() throws Exception {
        Path directory = Files.createTempDirectory("vimi-settings-catalog-");
        var library = ObjectStore.openLibrary(directory).join();
        var settings = await(SettingsService.open(library.databasePath()));
        var catalog = new CatalogFixture();
        var backend =
                new BackendBuildService(
                                catalog,
                                ignored -> CompletableFuture.completedFuture(List.of("wow_future")),
                                () -> CompletableFuture.completedFuture(null))
                        .withSettings(settings);
        try {
            var initial = await(backend.listBuilds());
            require(
                    initial.size() == SettingsService.DEFAULT_PRODUCTS.size(),
                    "Catalog contains enabled products only");
            require(
                    !catalog.listed.contains("wow_classic")
                            && !catalog.listed.contains("wow_future"),
                    "Disabled products are not read");
            for (String id : SettingsService.DEFAULT_PRODUCTS)
                await(settings.setEnabled(id, false));
            await(settings.setEnabled("wow_future", true));
            catalog.failed.add("wow_future");
            try {
                await(backend.listBuilds());
                throw new AssertionError(
                        "A failed enabled catalog cannot become an empty snapshot");
            } catch (java.util.concurrent.ExecutionException expected) {
                require(
                        backend.problems().stream()
                                .anyMatch(problem -> problem.contains("catalog read failed")),
                        "Hidden cached products cannot mask failure of the enabled scope");
            }
            catalog.failed.clear();
            var refreshed = await(backend.refreshBuilds());
            require(
                    refreshed.stream().noneMatch(b -> b.product().equals("wow"))
                            && refreshed.stream().anyMatch(b -> b.product().equals("wow_future")),
                    "Committed choices control next catalog snapshot");
            require(
                    !catalog.refreshed.contains("wow") && catalog.refreshed.contains("wow_future"),
                    "Disabled product refresh is skipped");
            await(settings.setEnabled("wow_zz_custom", true));
            require(
                    await(backend.listBuilds()).stream()
                            .anyMatch(b -> b.product().equals("wow_zz_custom")),
                    "Explicit custom products work before discovery");
            for (String id : List.copyOf(settings.enabledProducts()))
                await(settings.setEnabled(id, false));
            catalog.listed.clear();
            catalog.refreshed.clear();
            require(
                    await(backend.refreshBuilds()).isEmpty(),
                    "All products may be disabled without catalog error");
            require(
                    catalog.listed.isEmpty() && catalog.refreshed.isEmpty(),
                    "All disabled means no per-product access");
            require(
                    backend.status().contains("No products enabled"),
                    "Empty preference scope is explained");
        } finally {
            await(backend.closeAsync());
            await(settings.closeAsync());
            library.closeAsync().join();
            delete(directory);
        }
    }

    private static void refusesFutureSchema() throws Exception {
        Path directory = Files.createTempDirectory("vimi-settings-future-");
        var library = ObjectStore.openLibrary(directory).join();
        try {
            var settings = await(SettingsService.open(library.databasePath()));
            await(settings.closeAsync());
            try (var sql = new DerivedSqliteConnection(library.databasePath())) {
                sql.run(
                        com.vibeminer.store.Cancellation.NONE,
                        true,
                        session -> {
                            session.execute("UPDATE settings_metadata SET version=99");
                            return null;
                        });
            }
            try {
                await(SettingsService.open(library.databasePath()));
                throw new AssertionError("Future schema must fail");
            } catch (java.util.concurrent.ExecutionException expected) {
                require(
                        expected.getCause() instanceof IllegalArgumentException,
                        "Version refusal is explicit");
            }
            try (var sql = DerivedSqliteConnection.openReadOnly(library.databasePath())) {
                long version =
                        sql.run(
                                com.vibeminer.store.Cancellation.NONE,
                                false,
                                session ->
                                        ((Number)
                                                        session.query(
                                                                        "SELECT version FROM"
                                                                            + " settings_metadata")
                                                                .getFirst()[0])
                                                .longValue());
                require(version == 99, "Unsupported metadata remains unchanged");
            }
        } finally {
            library.closeAsync().join();
            delete(directory);
        }
    }

    private static final class CatalogFixture implements BuildCatalog {
        final List<String> listed = new ArrayList<>(), refreshed = new ArrayList<>();
        final Set<String> failed = new java.util.HashSet<>();

        @Override
        public CompletableFuture<CatalogTypes.BuildList> listBuilds(
                String product, Cancellation token) {
            listed.add(product);
            if (failed.contains(product))
                return CompletableFuture.failedFuture(
                        new IllegalStateException("catalog read failed"));
            return CompletableFuture.completedFuture(list(product));
        }

        @Override
        public CompletableFuture<CatalogTypes.CatalogRefresh> refresh(
                String product, Cancellation token) {
            refreshed.add(product);
            return CompletableFuture.completedFuture(
                    new CatalogTypes.CatalogRefresh(
                            product, observed(), observed(), list(product)));
        }

        @Override
        public CompletableFuture<CatalogTypes.SummaryRefresh> refreshSummary(Cancellation token) {
            return CompletableFuture.completedFuture(
                    new CatalogTypes.SummaryRefresh(
                            observed(), List.of(), List.of(), List.of(), "fixture", List.of()));
        }

        private static CatalogTypes.BuildList list(String product) {
            var now = Instant.parse("2026-01-01T00:00:00Z");
            var build =
                    new CatalogTypes.CatalogBuild(
                            product,
                            "identity:" + product,
                            "fixture",
                            "1".repeat(32),
                            "2".repeat(32),
                            Map.of(),
                            now,
                            now,
                            true,
                            List.of(
                                    new CatalogTypes.BuildRegion(
                                            "us", "3".repeat(32), now, now, true, null)));
            return new CatalogTypes.BuildList(product, List.of(build), List.of(), now);
        }

        private static SourceTypes.ObservationResult observed() {
            return new SourceTypes.ObservationResult(
                    SourceTypes.ObjectState.PRESENT,
                    null,
                    true,
                    null,
                    null,
                    Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    private static <T> T await(CompletableFuture<T> work) throws Exception {
        return work.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void delete(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
