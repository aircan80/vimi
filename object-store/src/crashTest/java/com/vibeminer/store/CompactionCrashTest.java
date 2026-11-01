package com.vibeminer.store;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class CompactionCrashTest {
    @TempDir Path folder;
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"COMPACT_TARGET_LISTED","COMPACT_COPYING","COMPACT_COPIED","COMPACT_BEFORE_SWITCH","COMPACT_SWITCHING","COMPACT_SWITCHED","COMPACT_OLD_DELETED","COMPACT_SOURCE_UNLISTED"})
    void interruptedCompactionPreservesLiveBytesAndDeletionHistory(MaintenancePoint point) throws Exception {
        CompactionFixture.prepare(folder);CrashProcess.killJava(folder,point.name(),"compact");
        boolean switched=Set.of(MaintenancePoint.COMPACT_SWITCHED,MaintenancePoint.COMPACT_OLD_DELETED,MaintenancePoint.COMPACT_SOURCE_UNLISTED).contains(point);
        ObjectStore store=ObjectStore.open(folder).join();
        try {
            assertTrue(store.openReport().fullIndexCheck());
            assertEquals(switched?3:2,store.index.find(key(CompactionFixture.kept())).pack());
            assertEquals(!switched,Files.exists(folder.resolve("packs/pack-000002.vmp")));
            assertEquals(switched,Files.exists(folder.resolve("packs/pack-000003.vmp")));
            assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));verify(store);
            assertEquals(3,store.scan(NONE).join().live());verify(store);
        } finally {store.closeAsync().join();}
        store=ObjectStore.open(folder).join();
        try {assertFalse(store.openReport().fullIndexCheck());verify(store);}
        finally {store.closeAsync().join();}
    }
    private void verify(ObjectStore store) {
        assertArrayEquals(CompactionFixture.kept(),store.read(key(CompactionFixture.kept()),NONE).join().bytes());
        assertFalse(store.has(key(CrashFixture.baseline()),NONE).join());assertFalse(store.has(key(CompactionFixture.dead()),NONE).join());
        assertEquals(1,store.readHistory(new History.Query(),NONE).join().size());assertEquals(3,store.stats().join().objects());
        assertNull(store.scrub(true,NONE).join().indexProblem());
        assertEquals(0,store.verify(true,NONE).join().corruptCount());assertEquals(0,store.verify(true,NONE).join().lostCount());
    }
}
