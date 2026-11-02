package com.vibeminer.accounting;

import com.vibeminer.store.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Reporting boundary; inventories and local metadata are supplied by application adapters. */
public interface StorageAccounting {
    CompletableFuture<Void> retain(
            String owner, boolean global, String source, UsageIndex.Role role);

    CompletableFuture<Boolean> current(String owner, String source, String version);

    CompletableFuture<Void> replace(
            String owner,
            String source,
            String version,
            UsageIndex.Inventory inventory,
            UsageIndex.Presence presence,
            Cancellation token);

    CompletableFuture<Void> refresh(
            String storage, UsageIndex.Presence presence, Cancellation token);

    CompletableFuture<Void> update(
            String storage, List<String> keys, UsageIndex.Presence presence, Cancellation token);

    CompletableFuture<UsageIndex.Figures> figures(String owner);

    CompletableFuture<UsageIndex.Coverage> coverage(String owner, String scope, String revision);

    CompletableFuture<Void> saveCoverage(
            String owner,
            String scope,
            String revision,
            long files,
            long named,
            Cancellation token);

    CompletableFuture<Void> forget(String owner);

    CompletableFuture<Void> closeAsync();
}
