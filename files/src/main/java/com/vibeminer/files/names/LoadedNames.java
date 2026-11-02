package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Immutable snapshot; failed generated reads answer as absent and mark it damaged. */
public interface LoadedNames {
    CompletableFuture<String> nameOf(long id, Cancellation cancellation);

    CompletableFuture<List<Long>> idsOf(String path, Cancellation cancellation);

    CompletableFuture<List<NamesTypes.NameEntry>> from(
            long fromId, int limit, Cancellation cancellation);

    /** Stable content fingerprint, or null when the provider cannot identify its snapshot. */
    default String revision() {
        return null;
    }

    /** Saved generated index objects; empty means no inventory is exposed by this provider. */
    default List<com.vibeminer.generated.GeneratedKey> artifactKeys() {
        return List.of();
    }

    boolean isDamaged();
}
