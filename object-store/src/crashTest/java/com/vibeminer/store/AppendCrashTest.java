package com.vibeminer.store;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import com.vibeminer.store.StoreTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class AppendCrashTest {
    @TempDir Path folder;
    @ParameterizedTest @EnumSource(WritePoint.class)
    void putSurvivesOrTruncatesAtEachBoundary(WritePoint point) throws Exception { exercise(point,"put"); }
    @ParameterizedTest @EnumSource(value=WritePoint.class, names="PAYLOAD_PARTIAL", mode=EnumSource.Mode.EXCLUDE)
    void deleteCannotResurrectAfterDurableTombstone(WritePoint point) throws Exception { exercise(point,"delete"); }
    @ParameterizedTest @EnumSource(WritePoint.class)
    void historyRecoveryPreservesDocumentAndPriorObservations(WritePoint point) throws Exception { exercise(point,"history"); }
    @ParameterizedTest @EnumSource(WritePoint.class)
    void stagedCopySurvivesOrTruncatesAtEachBoundary(WritePoint point) throws Exception { exercise(point,"staged"); }

    void exercise(WritePoint point,String operation) throws Exception {
        CrashFixture.prepare(folder); CrashProcess.killJava(folder,point.name(),operation);
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"),true)) {
            assertEquals(0,db.scalar("SELECT clean_close FROM store"));
        }
        boolean complete = Set.of(WritePoint.HEADER_WRITTEN,WritePoint.PACK_SYNCED,WritePoint.INDEX_UPDATED,WritePoint.INDEX_COMMITTED).contains(point);
        ObjectKey target = CrashFixture.targetKey(operation);
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertTrue(store.openReport().fullIndexCheck());
            assertEquals(complete && point != WritePoint.INDEX_COMMITTED ? 1 : 0,store.openReport().recoveredEntries());
            if (point == WritePoint.PAYLOAD_PARTIAL || point == WritePoint.PAYLOAD_WRITTEN || point == WritePoint.TABLE_WRITTEN)
                assertTrue(store.openReport().truncatedBytes() > 0 || operation.equals("delete"));
            assertEquals(operation.equals("delete") ? !complete : complete,store.has(target,NONE).join());
            if (!operation.equals("delete")) assertArrayEquals(CrashFixture.baseline(),store.read(key(CrashFixture.baseline()),NONE).join().bytes());
            assertEquals(operation.equals("history") && complete ? 2 : 1,store.readHistory(new History.Query(),NONE).join().size());
            assertEquals(0,store.verify(true,NONE).join().corruptCount());
            assertEquals(0,store.verify(true,NONE).join().lostCount());
            assertNull(store.scrub(true,NONE).join().indexProblem());
            assertEquals(operation.equals("delete") ? (complete ? 2 : 3) : (complete ? 4 : 3),store.stats().join().objects());
            for (Object[] row : store.database.query("SELECT seq,committed_length FROM packs"))
                assertEquals((long)row[1],Files.size(store.packPath(Math.toIntExact((long)row[0]))));
            byte[] following = "after recovery".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertEquals(PutOutcome.STORED,store.put(List.of(new PutObject(key(following),following)),true,NONE).join().getFirst().outcome());
            assertEquals(0,store.scan(NONE).join().corrupt());
            assertEquals(operation.equals("delete") ? !complete : complete,store.has(target,NONE).join());
        } finally { store.closeAsync().join(); }
        // The first recovery must converge; a second clean open never replays the same tail.
        store = ObjectStore.open(folder).join();
        try {
            assertFalse(store.openReport().fullIndexCheck());assertEquals(0,store.openReport().recoveredEntries());assertEquals(0,store.openReport().truncatedBytes());
            assertEquals(operation.equals("delete") ? !complete : complete,store.has(target,NONE).join());
        } finally { store.closeAsync().join(); }
    }
}
