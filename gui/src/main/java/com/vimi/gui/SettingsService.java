package com.vimi.gui;

import com.vibeminer.builds.Products;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Cached settings and bounded asynchronous commands; independent of provider and build lifetimes.
 */
public final class SettingsService {
    public static final Set<String> DEFAULT_PRODUCTS =
            Set.of("wow", "wow_beta", "wow_anniversary", "wowf", "wow_classic_beta", "wow_cn_beta");

    public record Product(
            String id, String name, boolean enabled, boolean defaultEnabled, boolean reviewed) {}

    public record Snapshot(List<Product> products, long revision) {
        public Snapshot {
            products = List.copyOf(products);
        }
    }

    private final SettingsPreferences preferences;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(64),
                    Thread.ofPlatform().daemon().name("vimi-settings").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private volatile Snapshot snapshot;
    private volatile SettingsPreferences.Stored stored;
    private final Object gate = new Object();
    private CompletableFuture<Void> closing;

    public static CompletableFuture<SettingsService> open(Path database) {
        var result = new CompletableFuture<SettingsService>();
        Thread.startVirtualThread(
                () -> {
                    SettingsService service = null;
                    try {
                        service = new SettingsService(new SettingsPreferences(database));
                        if (!result.complete(service)) service.closeAsync();
                    } catch (Throwable error) {
                        if (service != null) service.closeAsync();
                        result.completeExceptionally(error);
                    }
                });
        return result;
    }

    private SettingsService(SettingsPreferences preferences) throws Exception {
        this.preferences = preferences;
        refresh();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean enabled(String product) {
        return stored.overrides().getOrDefault(product, DEFAULT_PRODUCTS.contains(product));
    }

    public List<String> enabledProducts() {
        return snapshot.products().stream().filter(Product::enabled).map(Product::id).toList();
    }

    public CompletableFuture<Void> setEnabled(String product, boolean enabled) {
        if (!Products.isWow(product))
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid WoW product ID"));
        return submit(() -> preferences.setEnabled(product, enabled));
    }

    /** Remember discovery for the Settings list; newly discovered products remain disabled. */
    public CompletableFuture<Void> rememberProducts(Collection<String> products) {
        List<String> owned;
        try {
            owned =
                    products.stream()
                            .filter(Products::isWow)
                            .distinct()
                            .sorted(Products.NATURAL_ORDER)
                            .toList();
            if (owned.size() > SettingsPreferences.PRODUCT_LIMIT)
                throw new IllegalArgumentException("Too many products");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        synchronized (gate) {
            if (closing != null)
                return CompletableFuture.failedFuture(new IllegalStateException("Settings closed"));
            if (stored.known().containsAll(owned)) return CompletableFuture.completedFuture(null);
        }
        return submit(() -> preferences.remember(owned));
    }

    public CompletableFuture<Void> resetProducts() {
        return submit(preferences::reset);
    }

    private void refresh() throws Exception {
        stored = preferences.load();
        var known = new TreeSet<String>(Products.NATURAL_ORDER);
        known.addAll(Products.IDS);
        known.addAll(stored.known());
        var choices = new ArrayList<Product>();
        for (String id : known)
            choices.add(
                    new Product(
                            id,
                            Products.name(id),
                            enabled(id),
                            DEFAULT_PRODUCTS.contains(id),
                            Products.isKnown(id)));
        choices.sort(
                Comparator.comparing((Product p) -> !p.defaultEnabled())
                        .thenComparing(Product::id, Products.NATURAL_ORDER));
        snapshot = new Snapshot(choices, snapshot == null ? 1 : snapshot.revision() + 1);
    }

    @FunctionalInterface
    private interface Change {
        void run() throws Exception;
    }

    private CompletableFuture<Void> submit(Change change) {
        var accepted = new CompletableFuture<Void>();
        synchronized (gate) {
            if (closing != null)
                return CompletableFuture.failedFuture(new IllegalStateException("Settings closed"));
            try {
                worker.execute(
                        () -> {
                            try {
                                change.run();
                                refresh();
                                accepted.complete(null);
                            } catch (Throwable failure) {
                                accepted.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                accepted.completeExceptionally(new IllegalStateException("Settings busy", failure));
            }
        }
        var published = new CompletableFuture<Void>();
        accepted.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) published.complete(null);
                                    else published.completeExceptionally(failure);
                                }));
        return published;
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closing != null) return closing.copy();
            closing = new CompletableFuture<>();
            worker.shutdown();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                            closing.complete(null);
                        } catch (Throwable failure) {
                            closing.completeExceptionally(failure);
                        }
                    });
            return closing.copy();
        }
    }
}
