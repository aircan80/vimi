package com.vibeminer.store;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.vibeminer.store.StoreTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class GeneratedStoreTest {
    final Options generated = new Options(Kind.GENERATED, 300, Clock.systemUTC());
    @TempDir Path folder;

    @Test void chosenKeysSurviveReopenScanAndMissingIndexRebuild() throws Exception {
        ObjectKey key = new ObjectKey(7, new byte[16]); byte[] bytes = {8,7,6};
        ObjectStore store = ObjectStore.open(folder, generated).join();
        try {
            assertEquals(PutOutcome.STORED, store.put(List.of(new PutObject(key, bytes)), true, NONE).join().getFirst().outcome());
            assertArrayEquals(bytes, store.read(key,NONE).join().bytes());
            assertArrayEquals(new byte[]{7,6}, store.readRange(key,1,2,NONE).join().bytes());
            assertEquals(0,store.verify(true,NONE).join().corruptCount());
            assertEquals(1,store.scan(NONE).join().live());
        } finally { store.closeAsync().join(); }
        store = ObjectStore.open(folder, generated).join();
        try { assertTrue(store.has(key,NONE).join()); } finally { store.closeAsync().join(); }
        Files.delete(folder.resolve("objects.sqlite3"));
        store = ObjectStore.open(folder, generated).join();
        try {
            assertTrue(store.openReport().indexRebuilt()); assertArrayEquals(bytes,store.read(key,NONE).join().bytes());
            assertEquals(DeleteOutcome.DELETED,store.delete(List.of(key),NONE).join().getFirst().outcome());
            assertEquals(0,store.scan(NONE).join().live());
        } finally { store.closeAsync().join(); }
    }
    @Test void refusesCdnHistoryAndStaging() {
        ObjectStore store = ObjectStore.open(folder,generated).join();
        try {
            for (int type = 1; type <= 6; type++) {
                var r=store.put(List.of(new PutObject(new ObjectKey(type,new byte[16]),new byte[]{1})),true,NONE).join().getFirst();
                assertEquals("OBJECT_KEY_TYPE_UNSUPPORTED",r.code());
            }
            var observation = new History.Observation(1,"wow","us",java.time.Instant.EPOCH,"",new byte[]{1});
            assertTrue(store.appendHistory(observation,NONE).join().stream().allMatch(r -> "OBJECT_KEY_TYPE_UNSUPPORTED".equals(r.code())));
            assertTrue(store.readHistory(new History.Query(),NONE).join().isEmpty());
            assertInstanceOf(IllegalArgumentException.class, assertThrows(java.util.concurrent.CompletionException.class,
                () -> store.stage(EncodedFixture.sample().key(),100,Origin.CDN,NONE).join()).getCause());
        } finally { store.closeAsync().join(); }
    }
    @Test void wrongKindOpenDoesNotChangePackOrIndex() throws Exception {
        for (Kind kind : Kind.values()) {
            Path directory=folder.resolve(kind.name());
            Options options=new Options(kind,300,Clock.systemUTC());
            ObjectStore store=ObjectStore.open(directory,options).join();
            ObjectKey key=kind==Kind.GENERATED ? new ObjectKey(7,new byte[16]) : key(new byte[]{1});
            store.put(List.of(new PutObject(key,new byte[]{1})),true,NONE).join(); store.closeAsync().join();
            byte[] pack=Files.readAllBytes(directory.resolve("packs/pack-000001.vmp"));
            byte[] index=Files.readAllBytes(directory.resolve("objects.sqlite3"));
            Options wrong=new Options(kind==Kind.CDN ? Kind.GENERATED : Kind.CDN,300,Clock.systemUTC());
            failure(ObjectStore.open(directory,wrong),"OBJECT_STORE_KIND_MISMATCH");
            failure(ObjectStore.openReadOnly(directory,wrong),"OBJECT_STORE_KIND_MISMATCH");
            assertArrayEquals(pack,Files.readAllBytes(directory.resolve("packs/pack-000001.vmp")));
            assertArrayEquals(index,Files.readAllBytes(directory.resolve("objects.sqlite3")));
        }
    }
}
