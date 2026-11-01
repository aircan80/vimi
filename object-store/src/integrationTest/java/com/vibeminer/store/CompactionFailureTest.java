package com.vibeminer.store;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class CompactionFailureTest {
    @TempDir Path folder;
    ObjectStore store;
    @BeforeEach void open(){CompactionFixture.prepare(folder);store=ObjectStore.open(folder).join();}
    @AfterEach void close() throws Exception {
        store.io.fault=null;store.database.statementProbe=null;store.closeAsync().handle((v,e)->null).get(5,TimeUnit.SECONDS);
    }
    void armSqlFailure(String mode) {
        AtomicBoolean switching=new AtomicBoolean(),once=new AtomicBoolean();
        store.maintenanceProbe=point->{if(point==MaintenancePoint.COMPACT_SWITCHING)switching.set(true);};
        store.database.statementProbe=sql->{
            if(!switching.get())return;
            if(mode.equals("unknown") && (sql.equals("COMMIT")||sql.equals("ROLLBACK")))throw new NativeSqlite.Failure(10,"Injected failed switch and rollback");
            if(sql.equals(mode) && once.compareAndSet(false,true))throw new NativeSqlite.Failure(10,"Injected transaction I/O failure");
        };
    }
    @Test void failedModeResetAfterCommittedSwitchKeepsBothFilesForRecovery() throws Exception {
        armSqlFailure("PRAGMA synchronous=NORMAL");failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");
        assertTrue(Files.exists(store.packPath(2)));assertTrue(Files.exists(store.packPath(3)));
        assertEquals(3,store.index.find(key(CompactionFixture.kept())).pack());assertArrayEquals(CompactionFixture.kept(),store.read(key(CompactionFixture.kept()),NONE).join().bytes());
        store.closeAsync().get(5,TimeUnit.SECONDS);reopen();assertFalse(Files.exists(store.packPath(2)));assertTrue(Files.exists(store.packPath(3)));verify();
    }
    @Test void uncertainSwitchKeepsFilesAndStopsWritesUntilReopen() throws Exception {
        armSqlFailure("unknown");failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");
        assertTrue(Files.exists(store.packPath(2)));assertTrue(Files.exists(store.packPath(3)));
        failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");store.closeAsync().get(5,TimeUnit.SECONDS);reopen();
        assertTrue(Files.exists(store.packPath(2)));assertFalse(Files.exists(store.packPath(3)));verify();
    }
    @Test void failedSwitchCommitDeletesOnlyUncommittedTarget() {
        armSqlFailure("COMMIT");failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");
        assertTrue(Files.exists(store.packPath(2)));assertFalse(Files.exists(store.packPath(3)));assertEquals(2,store.index.find(key(CompactionFixture.kept())).pack());
        store.database.statementProbe=null;store.maintenanceProbe=null;verify();assertEquals(1,store.compact(0,1,NONE).join().packsCompacted());verify();
    }
    @ParameterizedTest @EnumSource(value=StoreIo.Point.class,names={"PACK_WRITE","PACK_SYNC","DIRECTORY_SYNC"})
    void copyFailuresLeaveOriginalIndexedAndRetryable(StoreIo.Point point) {
        AtomicBoolean once=new AtomicBoolean();store.io.fault=(at,path)->{if(at==point && once.compareAndSet(false,true))throw new IOException("Injected compaction I/O failure");};
        failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");assertTrue(Files.exists(store.packPath(2)));assertFalse(Files.exists(store.packPath(3)));
        assertEquals(2,store.index.find(key(CompactionFixture.kept())).pack());store.io.fault=null;verify();assertEquals(1,store.compact(0,1,NONE).join().packsCompacted());verify();
    }
    @Test void failedSourceDeleteKeepsDurablySwitchedTarget() {
        store.io.fault=(point,path)->{if(point==StoreIo.Point.FILE_DELETE && path.equals(store.packPath(2)))throw new IOException("Injected source unlink error");};
        failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");assertTrue(Files.exists(store.packPath(2)));assertTrue(Files.exists(store.packPath(3)));
        assertEquals(3,store.index.find(key(CompactionFixture.kept())).pack());verify();store.closeAsync().join();reopen();
        assertFalse(Files.exists(store.packPath(2)));verify();
    }
    @Test void failedTargetCleanupLeavesRecoverableCompactingRow() {
        store.maintenanceProbe=point->{if(point==MaintenancePoint.COMPACT_COPYING)throw new StoreException("OBJECT_STORE_WRITE_FAILED","Injected copy abort");};
        store.io.fault=(point,path)->{if(point==StoreIo.Point.FILE_DELETE && path.equals(store.packPath(3)))throw new IOException("Injected target unlink error");};
        failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");assertTrue(Files.exists(store.packPath(2)));assertTrue(Files.exists(store.packPath(3)));
        assertEquals(2,store.index.find(key(CompactionFixture.kept())).pack());verify();store.closeAsync().join();reopen();
        assertFalse(Files.exists(store.packPath(3)));verify();
    }
    @Test void cleanupFailurePreservesPrimaryDiskFullCode() {
        store.io.fault=(point,path)->{
            if(point==StoreIo.Point.PACK_WRITE)throw new IOException("No space left on device");
            if(point==StoreIo.Point.FILE_DELETE)throw new IOException("Injected unlink error");
        };
        failure(store.compact(0,1,NONE),"OBJECT_STORE_DISK_FULL");store.io.fault=null;verify();store.closeAsync().join();reopen();verify();
    }
    @Test void failedMetadataReloadStopsWriterUntilReopen() {
        AtomicBoolean switching=new AtomicBoolean();store.maintenanceProbe=point->{if(point==MaintenancePoint.COMPACT_SWITCHING)switching.set(true);};
        store.database.statementProbe=sql->{if(switching.get() && (sql.equals("COMMIT")||sql.equals("SELECT * FROM store")))throw new NativeSqlite.Failure(10,"Injected commit/reload failure");};
        failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");failure(store.compact(0,1,NONE),"OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().join();reopen();assertTrue(store.openReport().fullIndexCheck());verify();
    }
    void reopen(){store=ObjectStore.open(folder).join();}
    void verify(){
        assertArrayEquals(CompactionFixture.kept(),store.read(key(CompactionFixture.kept()),NONE).join().bytes());
        assertFalse(store.has(key(CrashFixture.baseline()),NONE).join());assertFalse(store.has(key(CompactionFixture.dead()),NONE).join());
        assertEquals(1,store.readHistory(new History.Query(),NONE).join().size());assertNull(store.scrub(true,NONE).join().indexProblem());
    }
}
