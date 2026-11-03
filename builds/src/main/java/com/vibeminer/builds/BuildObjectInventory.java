package com.vibeminer.builds;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.LibraryCatalog;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Publishes the immutable ENCODING reference inventory without loading tables or payloads. */
public final class BuildObjectInventory {
    private BuildObjectInventory() {}

    /**
     * The catalog owns worker/drain lifetime; the opened build must remain alive until completion.
     */
    public static CompletableFuture<Void> index(
            LibraryCatalog catalog, String identity, Build opened, Cancellation token) {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(opened);
        Objects.requireNonNull(token);
        String version = opened.metadata().encodingContentKey().hex();
        return catalog.expect(
                        identity,
                        LibraryCatalog.Scope.BUILD,
                        "encoding",
                        LibraryCatalog.Role.ORIGINAL)
                .thenCompose(ignored -> catalog.current(identity, "encoding", version))
                .thenCompose(
                        current -> {
                            token.check();
                            if (current) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return catalog.replace(
                                    identity,
                                    "encoding",
                                    version,
                                    new LibraryCatalog.Inventory() {
                                        private int offset;

                                        @Override
                                        public List<LibraryCatalog.Reference> next(
                                                int limit, Cancellation cancellation) {
                                            cancellation.check();
                                            List<BuildContent.Entry> page =
                                                    opened.encodedContentPage(offset, limit);
                                            offset = Math.addExact(offset, page.size());
                                            return page.stream()
                                                    .map(
                                                            entry ->
                                                                    new LibraryCatalog.Reference(
                                                                            entry.key(),
                                                                            entry.publishedBytes()))
                                                    .toList();
                                        }
                                    },
                                    token);
                        });
    }
}
