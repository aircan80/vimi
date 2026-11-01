package com.vibeminer.store;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.vibeminer.store.StoreTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class ChunkedStagingTest {
    @TempDir Path folder;
    ObjectStore store;
    @BeforeEach void open() { store=ObjectStore.open(folder).join(); }
    @AfterEach void close() { store.closeAsync().join(); }
    Path stagedPath(ObjectKey key) { return folder.resolve("staging/"+HexFormat.of().formatHex(key.indexBytes())+".part"); }

    @Test void resumesOnlyWholeProvenChunksAcrossStoreReopen() {
        EncodedFixture f=EncodedFixture.sample();
        ObjectStaging staged=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join();
        staged.append(Arrays.copyOf(f.bytes(),f.firstChunkEnd()+2),NONE).join(); staged.closeAsync().join();
        store.closeAsync().join(); store=ObjectStore.open(folder).join();
        staged=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join();
        assertEquals(f.firstChunkEnd(),staged.length());
        staged.append(Arrays.copyOfRange(f.bytes(),f.firstChunkEnd(),f.bytes().length),NONE).join();
        assertEquals(PutOutcome.STORED,staged.commit(NONE).join().outcome());
        assertArrayEquals(f.bytes(),store.read(f.key(),NONE).join().bytes()); assertFalse(Files.exists(stagedPath(f.key())));
    }
    @Test void failedChunkRetainsProofAndRequiresNewHandle() {
        EncodedFixture f=EncodedFixture.sample(); byte[] corrupt=f.bytes().clone(); corrupt[corrupt.length-1]^=1;
        ObjectStaging staged=store.stage(f.key(),corrupt.length,Origin.CDN,NONE).join();
        failure(staged.append(corrupt,NONE),"OBJECT_STAGING_REJECTED"); assertEquals(f.firstChunkEnd(),staged.length());
        failure(staged.append(new byte[]{1},NONE),"OBJECT_STAGING_REJECTED");
        staged.closeAsync().join();
        ObjectStaging resumed=store.stage(f.key(),corrupt.length,Origin.CDN,NONE).join();
        assertEquals(f.firstChunkEnd(),resumed.length()); resumed.abandon().join();
    }
    @Test void incompleteCommitKeepsHandleUsable() {
        EncodedFixture f=EncodedFixture.sample(); ObjectStaging staged=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join();
        staged.append(Arrays.copyOf(f.bytes(),f.headerLength()),NONE).join();
        assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,()->staged.commit(NONE).join()).getCause());
        staged.append(Arrays.copyOfRange(f.bytes(),f.headerLength(),f.bytes().length),NONE).join();
        assertEquals(PutOutcome.STORED,staged.commit(NONE).join().outcome());
    }
    @Test void commitRechecksExternalDamageAndRefusesOnlyStagedObject() throws Exception {
        EncodedFixture f=EncodedFixture.sample(); ObjectStaging staged=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join();
        staged.append(f.bytes(),NONE).join(); byte[] bad=f.bytes().clone(); bad[bad.length-1]^=1; Files.write(stagedPath(f.key()),bad);
        assertEquals("OBJECT_BLTE_INVALID",staged.commit(NONE).join().code());
        assertFalse(store.has(f.key(),NONE).join()); assertFalse(Files.exists(stagedPath(f.key())));
        assertEquals(PutOutcome.STORED,store.put(List.of(new PutObject(key(new byte[]{1}),new byte[]{1})),true,NONE).join().getFirst().outcome());
    }
    @Test void missingStagedFileIsPerObjectRefusal() throws Exception {
        EncodedFixture f=EncodedFixture.sample(); ObjectStaging staged=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join();
        staged.append(f.bytes(),NONE).join(); Files.delete(stagedPath(f.key()));
        assertEquals("OBJECT_STAGING_REJECTED",staged.commit(NONE).join().code());
    }
    @Test void staleHandleCannotRemoveReplacementStaging() {
        EncodedFixture f=EncodedFixture.sample(); ObjectStaging old=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join(); old.closeAsync().join();
        ObjectStaging replacement=store.stage(f.key(),f.bytes().length,Origin.CDN,NONE).join(); replacement.append(f.bytes(),NONE).join(); old.abandon().join();
        assertEquals(PutOutcome.STORED,replacement.commit(NONE).join().outcome());
    }
    @Test void stagingBudgetsAreReleased() {
        List<ObjectStaging> handles=new ArrayList<>();
        for (int i=0;i<4;i++) { byte[] key=new byte[16];key[0]=(byte)i;handles.add(store.stage(new ObjectKey(4,key),Format.MAX_PAYLOAD,Origin.CDN,NONE).join()); }
        assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,
            ()->store.stage(new ObjectKey(4,new byte[16]),1,Origin.CDN,NONE).join()).getCause());
        byte[] extra=new byte[16];extra[0]=5; failure(store.stage(new ObjectKey(4,extra),1,Origin.CDN,NONE),"OBJECT_STORE_LIMIT");
        for (ObjectStaging h:handles) h.abandon().join();
        for (int i=0;i<16;i++) { byte[] key=new byte[16];key[0]=(byte)i;handles.add(store.stage(new ObjectKey(4,key),1,Origin.CDN,NONE).join()); }
        extra[0]=17;failure(store.stage(new ObjectKey(4,extra),1,Origin.CDN,NONE),"OBJECT_STORE_LIMIT");
        for (ObjectStaging h:handles) h.abandon().join();
    }
}
