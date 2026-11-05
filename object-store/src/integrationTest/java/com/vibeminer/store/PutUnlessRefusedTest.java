package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A refused object stops the whole put, so no later object commits without it. */
@Timeout(30)
final class PutUnlessRefusedTest {
    @TempDir Path folder;

    private static PutObject object(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return new PutObject(key(bytes), bytes);
    }

    /** Declares one byte more than it holds, which verification refuses. */
    private static PutObject mismatched(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return new PutObject(key(bytes), bytes.length + 1, bytes, StoreTypes.Origin.CDN);
    }

    private static List<PutOutcome> outcomes(List<PutResult> results) {
        return results.stream().map(PutResult::outcome).toList();
    }

    @Test
    void aRefusedObjectStoresNothingOfItsPut() {
        var store = ObjectStore.open(folder.resolve("store")).join();
        try {
            var first = object("first");
            var bad = mismatched("bad");
            var last = object("last");
            var results =
                    store.putUnlessRefused(List.of(first, bad, last), true, Cancellation.NONE)
                            .join();
            assertEquals(
                    List.of(PutOutcome.REFUSED, PutOutcome.REFUSED, PutOutcome.REFUSED),
                    outcomes(results));
            assertEquals("OBJECT_SIZE_MISMATCH", results.get(1).code());
            assertEquals("OBJECT_BATCH_REFUSED", results.get(0).code());
            assertEquals("OBJECT_BATCH_REFUSED", results.get(2).code());
            assertFalse(store.has(first.key()).join());
            assertFalse(store.has(last.key()).join());

            // The ordinary put keeps storing the others, as before.
            var plain = store.put(List.of(first, bad, last), true, Cancellation.NONE).join();
            assertEquals(
                    List.of(PutOutcome.STORED, PutOutcome.REFUSED, PutOutcome.STORED),
                    outcomes(plain));
            assertEquals(
                    List.of(PutOutcome.ALREADY_PRESENT, PutOutcome.STORED),
                    outcomes(
                            store.putUnlessRefused(
                                            List.of(first, object("fresh")),
                                            true,
                                            Cancellation.NONE)
                                    .join()));
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void anOwnedLibraryPutIsRefusedWholeToo() {
        var library = ObjectStore.openLibrary(folder.resolve("library")).join();
        var catalog = LibraryCatalog.open(library.databasePath()).join();
        try {
            var owner =
                    library.forOwner(
                            "fixture",
                            LibraryCatalog.Scope.GLOBAL,
                            "generated",
                            LibraryCatalog.Role.DERIVED);
            var first = object("owned first");
            var probe = object("owned probe");
            WriteProbes.refuse(
                    library, k -> k.equals(probe.key()) ? "OBJECT_FIXTURE_REFUSED" : null);
            var results =
                    owner.putUnlessRefused(List.of(first, probe), true, Cancellation.NONE).join();
            assertEquals(List.of(PutOutcome.REFUSED, PutOutcome.REFUSED), outcomes(results));
            assertEquals("OBJECT_FIXTURE_REFUSED", results.get(1).code());
            assertFalse(owner.has(first.key(), Cancellation.NONE).join());
            WriteProbes.refuse(library, null);
            assertEquals(
                    List.of(PutOutcome.STORED, PutOutcome.STORED),
                    outcomes(
                            owner.putUnlessRefused(List.of(first, probe), true, Cancellation.NONE)
                                    .join()));
            assertTrue(owner.has(probe.key(), Cancellation.NONE).join());
        } finally {
            catalog.closeAsync().join();
            library.closeAsync().join();
        }
    }
}
