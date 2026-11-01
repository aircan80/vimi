package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Path;
import java.util.List;

/** Test-only executable. The parent kills this process while its writer is blocked. */
public final class CrashWorker {
    private CrashWorker() {}

    public static void main(String[] args) throws Exception {
        String operation = args[2];
        if (operation.equals("rebuild")) {
            ObjectStore.open(
                            Path.of(args[0]),
                            new Options(),
                            point -> {
                                if (point.name().equals(args[1])) {
                                    stopAt(args[1]);
                                }
                            })
                    .join();
            throw new IllegalStateException("Requested rebuild boundary was not reached");
        }
        if (operation.equals("scan") || operation.equals("compact")) {
            ObjectStore scanning = ObjectStore.open(Path.of(args[0])).join();
            scanning.maintenanceProbe =
                    point -> {
                        if (point.name().equals(args[1])) {
                            stopAt(args[1]);
                        }
                    };
            if (operation.equals("compact")) {
                scanning.compact(0, 1, Cancellation.NONE).join();
            } else {
                scanning.scan(Cancellation.NONE).join();
            }
            throw new IllegalStateException("Requested scan boundary was not reached");
        }
        WritePoint requested = WritePoint.valueOf(args[1]);
        ObjectKey target = CrashFixture.targetKey(operation);
        ObjectStore store = ObjectStore.open(Path.of(args[0])).join();
        store.writeProbe =
                (point, key) -> {
                    if (point != requested || !key.equals(target)) {
                        return;
                    }
                    stopAt(requested.name());
                };
        switch (operation) {
            case "put" ->
                    store.put(
                                    List.of(new PutObject(target, CrashFixture.target())),
                                    true,
                                    Cancellation.NONE)
                            .join();
            case "delete" -> store.delete(List.of(target), Cancellation.NONE).join();
            case "history" ->
                    store.appendHistory(CrashFixture.observation(true), Cancellation.NONE).join();
            case "staged" -> {
                EncodedFixture f = CrashFixture.encoded();
                ObjectStaging stage =
                        store.stage(f.key(), f.bytes().length, Origin.CDN, Cancellation.NONE)
                                .join();
                stage.append(f.bytes(), Cancellation.NONE).join();
                stage.commit(Cancellation.NONE).join();
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        }
        store.closeAsync().join();
        throw new IllegalStateException("Requested crash boundary was not reached");
    }

    private static void stopAt(String requested) {
        System.out.println("READY " + requested);
        System.out.flush();
        try {
            if (System.in.read() < 0) {
                throw new IllegalStateException("Parent disconnected before termination");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        throw new IllegalStateException("Parent should terminate the blocked writer");
    }
}
