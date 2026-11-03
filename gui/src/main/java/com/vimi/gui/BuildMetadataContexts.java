package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildOpener;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectStore;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Per-open ownership is immutable; metadata ledgers live until their accounting import drains. */
final class BuildMetadataContexts {
    private final ObjectStore library;
    private final NetworkClients network;
    private final LibraryCatalog ownership;
    private final ObjectReader legacy;
    private final Set<Context> contexts = new HashSet<>();
    private final Map<Build, Context> opened = new IdentityHashMap<>();
    private boolean closing;

    private record Context(CachingObjectSource source, BuildOpener opener) {
        CompletableFuture<Void> closeAsync() {
            return opener.closeAsync().thenCompose(ignored -> source.closeAsync());
        }
    }

    BuildMetadataContexts(
            ObjectStore library,
            LibraryCatalog ownership,
            NetworkClients network,
            ObjectReader legacy) {
        this.library = library;
        this.ownership = ownership;
        this.network = network;
        this.legacy = legacy;
    }

    synchronized CompletableFuture<BuildTypes.BuildOpenResult> open(
            BuildInfo info, BuildTypes.BuildRef ref, Cancellation token) {
        if (closing || contexts.size() >= 16) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            closing
                                    ? "Build metadata contexts closed"
                                    : "Build accounting is still using metadata; retry after it"
                                            + " completes"));
        }
        var owner =
                library.forOwner(
                        info.identity(),
                        LibraryCatalog.Scope.BUILD,
                        "metadata",
                        LibraryCatalog.Role.ORIGINAL);
        var source = new CachingObjectSource(owner, owner, owner, legacy, network, List.of());
        var context = new Context(source, new BuildOpener(source));
        contexts.add(context);
        var result = new CompletableFuture<BuildTypes.BuildOpenResult>();
        ownership
                .expect(
                        info.identity(),
                        LibraryCatalog.Scope.BUILD,
                        "encoding",
                        LibraryCatalog.Role.ORIGINAL)
                .thenCompose(ignored -> context.opener().openMetadataBuild(ref, token))
                .whenComplete(
                        (value, failure) -> {
                            if (failure == null
                                    && value.state() == BuildTypes.BuildOpenState.OPEN) {
                                synchronized (BuildMetadataContexts.this) {
                                    opened.put(value.opened(), context);
                                }
                                result.complete(value);
                            } else {
                                remove(context)
                                        .whenComplete(
                                                (ignored, cleanup) -> {
                                                    if (failure != null) {
                                                        if (cleanup != null)
                                                            failure.addSuppressed(cleanup);
                                                        result.completeExceptionally(failure);
                                                    } else if (cleanup != null)
                                                        result.completeExceptionally(cleanup);
                                                    else result.complete(value);
                                                });
                            }
                        });
        return result;
    }

    synchronized CompletableFuture<Void> release(Build model) {
        Context context = opened.remove(model);
        return context == null ? CompletableFuture.completedFuture(null) : remove(context);
    }

    private synchronized CompletableFuture<Void> remove(Context context) {
        return context.closeAsync()
                .whenComplete(
                        (ignored, failure) -> {
                            synchronized (BuildMetadataContexts.this) {
                                contexts.remove(context);
                            }
                        });
    }

    synchronized CompletableFuture<Void> closeAsync() {
        closing = true;
        return CompletableFuture.allOf(
                contexts.stream().map(Context::closeAsync).toArray(CompletableFuture[]::new));
    }
}
