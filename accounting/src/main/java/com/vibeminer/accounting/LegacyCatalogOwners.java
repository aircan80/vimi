package com.vibeminer.accounting;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Explicit transfer of retained owner intent; does not copy the obsolete presence projection. */
public final class LegacyCatalogOwners {
    private LegacyCatalogOwners() {}

    public static CompletableFuture<Void> copy(
            Path legacyDatabase, LibraryCatalog destination, Cancellation token) {
        var result = new CompletableFuture<Void>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        token.check();
                        if (Files.isRegularFile(legacyDatabase)) {
                            try (var source =
                                    DerivedSqliteConnection.openReadOnly(legacyDatabase)) {
                                source.run(
                                        token,
                                        true,
                                        session -> {
                                            String after = null;
                                            while (true) {
                                                token.check();
                                                var page =
                                                        after == null
                                                                ? session.query(
                                                                        "SELECT id,global_owner FROM owners ORDER BY id LIMIT 256")
                                                                : session.query(
                                                                        "SELECT id,global_owner FROM owners WHERE id>? ORDER BY id LIMIT 256",
                                                                        after);
                                                if (page.isEmpty()) {
                                                    break;
                                                }
                                                for (Object[] row : page) {
                                                    token.check();
                                                    String owner = (String) row[0];
                                                    boolean global =
                                                            ((Number) row[1]).longValue() != 0;
                                                    destination
                                                            .retainOwner(
                                                                    owner,
                                                                    global
                                                                            ? LibraryCatalog.Scope
                                                                                    .GLOBAL
                                                                            : LibraryCatalog.Scope
                                                                                    .BUILD)
                                                            .join();
                                                    if (!global) {
                                                        // References are reindexed from
                                                        // authenticated ENCODING on load.
                                                        destination
                                                                .expect(
                                                                        owner,
                                                                        LibraryCatalog.Scope.BUILD,
                                                                        "encoding",
                                                                        LibraryCatalog.Role
                                                                                .ORIGINAL)
                                                                .join();
                                                    }
                                                    after = owner;
                                                }
                                            }
                                            return null;
                                        });
                            }
                        }
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }
}
