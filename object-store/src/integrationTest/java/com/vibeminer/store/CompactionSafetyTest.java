package com.vibeminer.store;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import com.vibeminer.store.StoreTypes.*;
import static com.vibeminer.store.StoreAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompactionSafetyTest {
    @TempDir Path folder;
    ObjectStore store;
    @BeforeEach void open(){CompactionFixture.prepare(folder);store=ObjectStore.open(folder).join();}
    @AfterEach void close(){store.closeAsync().join();}
    Path source(){return folder.resolve("packs/pack-000002.vmp");}
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"COMPACT_TARGET_LISTED","COMPACT_COPYING","COMPACT_COPIED","COMPACT_BEFORE_SWITCH","COMPACT_SWITCHING"})
    void cancellationBeforeSwitchRemovesTargetAndKeepsSource(MaintenancePoint point) throws Exception {
        byte[] original=Files.readAllBytes(source());long digest=store.index.objectDigest;Cancellation canceled=new Cancellation();
        store.maintenanceProbe=p->{if(p==point)canceled.cancel();};
        assertThrows(CancellationException.class,()->store.compact(0,1,canceled).join());store.maintenanceProbe=null;
        assertArrayEquals(original,Files.readAllBytes(source()));assertEquals(digest,store.index.objectDigest);
        assertEquals(2,store.index.find(key(CompactionFixture.kept())).pack());assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));
        assertFalse(Files.exists(folder.resolve("packs/pack-000003.vmp")));assertNull(store.scrub(true,NONE).join().indexProblem());
        assertEquals(1,store.compact(0,1,NONE).join().packsCompacted());verify();
    }
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"COMPACT_SWITCHED","COMPACT_OLD_DELETED","COMPACT_SOURCE_UNLISTED"})
    void cancellationAfterDurableSwitchFinishesCleanup(MaintenancePoint point) throws Exception {
        Cancellation canceled=new Cancellation();store.maintenanceProbe=p->{if(p==point)canceled.cancel();};
        assertEquals(1,store.compact(0,1,canceled).join().packsCompacted());store.maintenanceProbe=null;
        assertFalse(Files.exists(source()));assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));verify();
    }
    @Test void corruptLiveEntryKeepsItsSourceAndDoesNotConsumeEveryFutureSlot() throws Exception {
        StoreIndex.Row row=store.index.find(key(CompactionFixture.kept()));
        try(FileChannel file=FileChannel.open(source(),StandardOpenOption.WRITE)){Format.write(file,row.offset()+96,new byte[]{99});file.force(true);}
        byte[] damaged=Files.readAllBytes(source());long digest=store.index.objectDigest;
        CompactReport report=store.compact(0,1,NONE).join();assertEquals(0,report.packsCompacted());assertEquals(1,report.skippedCorrupt());
        assertArrayEquals(damaged,Files.readAllBytes(source()));assertEquals(digest,store.index.objectDigest);
        assertEquals(ReadState.CORRUPT,store.read(row.key(),NONE).join().state());assertTrue(store.has(row.key(),NONE).join());
        assertFalse(Files.exists(folder.resolve("packs/pack-000003.vmp")));assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));
        report=store.compact(0,1,NONE).join();assertEquals(1,report.packsCompacted());assertEquals(0,report.skippedCorrupt());
        assertTrue(Files.exists(source()));assertEquals(2,store.index.find(row.key()).pack());
    }
    @Test void newerSourceHeaderAbortsAndCleansTarget() throws Exception {
        StoreIndex.Row row=store.index.find(key(CompactionFixture.kept()));
        try(FileChannel file=FileChannel.open(source(),StandardOpenOption.READ,StandardOpenOption.WRITE)) {
            byte[] header=Format.read(file,row.offset(),96);header[74]=1;Format.le(header).putInt(92,(int)Format.crc(header,0,92));Format.write(file,row.offset(),header);file.force(true);
        }
        byte[] original=Files.readAllBytes(source());failure(store.compact(0,1,NONE),"OBJECT_STORE_NEWER_FORMAT");
        assertArrayEquals(original,Files.readAllBytes(source()));assertFalse(Files.exists(folder.resolve("packs/pack-000003.vmp")));
        assertEquals(2,store.index.find(row.key()).pack());assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));
    }
    @Test void tornSourceHeaderAbortsWithoutDeletingItsRows() throws Exception {
        StoreIndex.Row row=store.index.find(key(CompactionFixture.kept()));
        try(FileChannel file=FileChannel.open(source(),StandardOpenOption.WRITE)){Format.write(file,row.offset(),new byte[]{0});file.force(true);}
        byte[] original=Files.readAllBytes(source());assertEquals(0,store.compact(0,1,NONE).join().packsCompacted());
        assertArrayEquals(original,Files.readAllBytes(source()));assertTrue(store.has(row.key(),NONE).join());
        assertEquals(ReadState.CORRUPT,store.read(row.key(),NONE).join().state());assertFalse(Files.exists(folder.resolve("packs/pack-000003.vmp")));
    }
    static java.util.stream.Stream<Arguments> readRaces() {
        return java.util.Arrays.stream(ReadPoint.values()).flatMap(point->java.util.stream.Stream.of(Arguments.of(point,false),Arguments.of(point,true)));
    }
    @ParameterizedTest @MethodSource("readRaces")
    void concurrentReadSurvivesSourceRemoval(ReadPoint point,boolean range) throws Exception {
        ObjectKey key=key(CompactionFixture.kept());CountDownLatch paused=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger lookups=new AtomicInteger();
        store.readProbe=(at,k)->{
            if(!k.equals(key))return;if(at==ReadPoint.ROW_FOUND)lookups.incrementAndGet();
            if(at!=point || (point==ReadPoint.ROW_FOUND && lookups.get()!=1))return;
            paused.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Read was not released");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        };
        CompletableFuture<ObjectRead> read=range ? store.readRange(key,65530,40,NONE) : store.read(key,NONE);
        try {
            assertTrue(paused.await(10,TimeUnit.SECONDS));assertEquals(1,store.compact(0,1,NONE).get(10,TimeUnit.SECONDS).packsCompacted());
            assertFalse(Files.exists(source()));release.countDown();
            ObjectRead result=read.get(10,TimeUnit.SECONDS);assertEquals(ReadState.PRESENT,result.state());assertArrayEquals(range ? java.util.Arrays.copyOfRange(CompactionFixture.kept(),65530,65570) : CompactionFixture.kept(),result.bytes());
            assertEquals(point==ReadPoint.ROW_FOUND?2:1,lookups.get());
        } finally {release.countDown();read.handle((value,error)->null).get(10,TimeUnit.SECONDS);store.readProbe=null;}
        verify();
    }
    @Test void switchUpdatesDigestAndPreservesHeadersHistoryAndTombstones() throws Exception {
        StoreIndex.Row row=store.index.find(key(CompactionFixture.kept()));byte[] header;
        try(FileChannel file=FileChannel.open(source(),StandardOpenOption.READ)){header=Format.read(file,row.offset(),96);}
        java.util.concurrent.atomic.AtomicBoolean full=new java.util.concurrent.atomic.AtomicBoolean();
        store.maintenanceProbe=point->{if(point==MaintenancePoint.COMPACT_SWITCHING){assertEquals(2,store.database.scalar("PRAGMA synchronous"));full.set(true);}};
        CompactReport result=store.compact(0,1,NONE).join();store.maintenanceProbe=null;
        assertTrue(full.get());assertEquals(1,store.database.scalar("PRAGMA synchronous"));assertEquals(1,result.packsCompacted());assertEquals(1,result.moved());assertTrue(result.bytesBefore()>result.bytesAfter());
        row=store.index.find(row.key());assertEquals(3,row.pack());
        try(FileChannel file=FileChannel.open(store.packPath(row.pack()),StandardOpenOption.READ)){assertArrayEquals(header,Format.read(file,row.offset(),96));}
        verify();assertEquals(3,store.scan(NONE).join().live());verify();
    }
    private void verify() {
        assertArrayEquals(CompactionFixture.kept(),store.read(key(CompactionFixture.kept()),NONE).join().bytes());
        assertFalse(store.has(key(CrashFixture.baseline()),NONE).join());assertFalse(store.has(key(CompactionFixture.dead()),NONE).join());
        assertEquals(1,store.readHistory(new History.Query(),NONE).join().size());assertNull(store.scrub(true,NONE).join().indexProblem());
        assertEquals(0,store.verify(true,NONE).join().corruptCount());assertEquals(0,store.verify(true,NONE).join().lostCount());
    }
}
