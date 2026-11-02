package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Caller-owned admission for finite CPU work. Scopes never cover source/sink I/O. */
@FunctionalInterface
public interface GeometryArtifactWorkBudget {
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    CompletableFuture<Scope> enter(Cancellation cancellation);

    GeometryArtifactWorkBudget UNBUDGETED =
            token -> {
                token.check();
                return CompletableFuture.completedFuture(() -> {});
            };
}
