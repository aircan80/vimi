package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class GroupCommitCrashTest {
    @TempDir Path folder;

    @ParameterizedTest
    @EnumSource(WritePoint.class)
    void interruptedMultiObjectGroupRecoversOnlyCompleteEntries(WritePoint point) throws Exception {
        CrashFixture.prepare(folder);
        CrashProcess.killJava(folder, point.name(), "group");
        boolean secondComplete =
                Set.of(
                                WritePoint.HEADER_WRITTEN,
                                WritePoint.PACK_SYNCED,
                                WritePoint.INDEX_UPDATED,
                                WritePoint.INDEX_COMMITTED)
                        .contains(point);
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertTrue(store.has(key(CrashFixture.baseline())).join());
            assertTrue(store.has(key(CrashFixture.groupFirst())).join());
            assertEquals(secondComplete, store.has(key(CrashFixture.target())).join());
            assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
            assertEquals(0, store.verify(true, Cancellation.NONE).join().lostCount());
            store.scan(Cancellation.NONE).join();
            assertEquals(secondComplete, store.has(key(CrashFixture.target())).join());
        } finally {
            store.closeAsync().join();
        }
        store = ObjectStore.open(folder).join();
        try {
            assertTrue(store.has(key(CrashFixture.groupFirst())).join());
            assertEquals(secondComplete, store.has(key(CrashFixture.target())).join());
        } finally {
            store.closeAsync().join();
        }
    }
}
