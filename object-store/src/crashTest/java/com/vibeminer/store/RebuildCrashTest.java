package com.vibeminer.store;

import java.nio.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import com.vibeminer.store.StoreTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class RebuildCrashTest {
    @TempDir Path folder;
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"REBUILD_CREATED","REPLAY_ENTRY","REBUILD_REPLAYED","REBUILD_CHECKPOINTED","REBUILD_BEFORE_REPLACE","REBUILD_REPLACED"})
    void interruptedTemporaryRebuildPreservesAndRecoversOriginalData(MaintenancePoint point) throws Exception {
        CrashFixture.prepare(folder);
        try (NativeSqlite db=new NativeSqlite(folder.resolve("objects.sqlite3"),false)) { db.exec("UPDATE store SET row_check=row_check+1"); }
        byte[] original=Files.readAllBytes(folder.resolve("objects.sqlite3"));
        CrashProcess.killJava(folder,point.name(),"rebuild");
        assertTrue(Files.exists(folder.resolve("objects.sqlite3")),"Main index must exist throughout replacement");
        if(point!=MaintenancePoint.REBUILD_REPLACED) assertArrayEquals(original,Files.readAllBytes(folder.resolve("objects.sqlite3")));
        ObjectStore store=ObjectStore.open(folder).join();
        try { verify(store);assertFalse(Files.exists(folder.resolve("objects.sqlite3.rebuild"))); }
        finally { store.closeAsync().join(); }
        store=ObjectStore.open(folder).join();
        try { assertFalse(store.openReport().indexRebuilt());assertFalse(store.openReport().fullIndexCheck());verify(store); }
        finally { store.closeAsync().join(); }
    }
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"SCAN_SEALED","SCAN_CLEARED","REPLAY_ENTRY","SCAN_REPLAYED","SCAN_COMMITTED","SCAN_CHECKPOINTED"})
    void interruptedLiveScanRollsBackOrCommitsOneCompleteIndex(MaintenancePoint point) throws Exception {
        CrashFixture.prepare(folder);CrashProcess.killJava(folder,point.name(),"scan");
        ObjectStore store=ObjectStore.open(folder).join();
        try {assertTrue(store.openReport().fullIndexCheck());verify(store);assertEquals(3,store.scan(NONE).join().live());}
        finally {store.closeAsync().join();}
        store=ObjectStore.open(folder).join();
        try {assertFalse(store.openReport().fullIndexCheck());verify(store);}
        finally {store.closeAsync().join();}
    }
    private void verify(ObjectStore store) {
        assertArrayEquals(CrashFixture.baseline(),store.read(key(CrashFixture.baseline()),NONE).join().bytes());
        assertEquals(3,store.stats().join().objects());assertEquals(1,store.readHistory(new History.Query(),NONE).join().size());
        assertNull(store.scrub(true,NONE).join().indexProblem());
        assertEquals(0,store.verify(true,NONE).join().corruptCount());assertEquals(0,store.verify(true,NONE).join().lostCount());
    }
}
