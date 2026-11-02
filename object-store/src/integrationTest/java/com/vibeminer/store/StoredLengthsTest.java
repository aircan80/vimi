package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StoredLengthsTest {
    @Test
    void reportsLocalPayloadLengthsAbsentAndDeletedWithoutReturningPayloads(@TempDir Path folder) {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            byte[] bytes = {1, 2, 3};
            var key = StoreAssertions.key(bytes);
            var absent = StoreAssertions.key(new byte[] {4, 5});
            store.put(List.of(new StoreTypes.PutObject(key, bytes)), true, Cancellation.NONE)
                    .join();
            assertEquals(
                    List.of(3L, -1L, 3L),
                    store.storedLengths(List.of(key, absent, key), Cancellation.NONE).join());
            store.delete(List.of(key), Cancellation.NONE).join();
            assertEquals(List.of(-1L), store.storedLengths(List.of(key), Cancellation.NONE).join());
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> store.storedLengths(List.of(key), canceled).join());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            store.storedLengths(
                                            java.util.Collections.nCopies(4097, key),
                                            Cancellation.NONE)
                                    .join());
        } finally {
            store.closeAsync().join();
        }
    }
}
