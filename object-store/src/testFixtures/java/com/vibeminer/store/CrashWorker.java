package com.vibeminer.store;

import java.nio.file.Path;
import java.util.List;
import com.vibeminer.store.StoreTypes.*;

/** Test-only executable. The parent kills this process while its writer is blocked. */
public final class CrashWorker {
    private CrashWorker() { }
    public static void main(String[] args) throws Exception {
        WritePoint requested = WritePoint.valueOf(args[1]); String operation = args[2];
        ObjectKey target = CrashFixture.targetKey(operation);
        ObjectStore store = ObjectStore.open(Path.of(args[0])).join();
        store.writeProbe = (point, key) -> {
            if (point != requested || !key.equals(target)) return;
            System.out.println("READY " + requested); System.out.flush();
            try { if (System.in.read() < 0) throw new IllegalStateException("Parent disconnected before termination"); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            throw new IllegalStateException("Parent should terminate the blocked writer");
        };
        switch (operation) {
            case "put" -> store.put(List.of(new PutObject(target, CrashFixture.target())), true, Cancellation.NONE).join();
            case "delete" -> store.delete(List.of(target), Cancellation.NONE).join();
            case "history" -> store.appendHistory(CrashFixture.observation(true), Cancellation.NONE).join();
            case "staged" -> {
                EncodedFixture f = CrashFixture.encoded();
                ObjectStaging stage = store.stage(f.key(), f.bytes().length, Origin.CDN, Cancellation.NONE).join();
                stage.append(f.bytes(), Cancellation.NONE).join(); stage.commit(Cancellation.NONE).join();
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        }
        store.closeAsync().join();
        throw new IllegalStateException("Requested crash boundary was not reached");
    }
}
