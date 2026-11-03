package com.vibeminer.concepts.mounts;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only catalogue borrowing one build/locale table and file workspace. */
public interface BuildMounts extends AutoCloseable {
    CompletableFuture<MountTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<MountTypes.Overview> overview(long mountId, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
