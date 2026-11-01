package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.check;
import static com.vibeminer.store.StoreAssertions.key;

import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Pack 2 has a live object, dead bytes and tombstones protecting entries in pack 1. */
public final class CompactionFixture {
    private CompactionFixture() {}

    public static byte[] kept() {
        byte[] bytes = new byte[2 * 65536 + 13];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    public static byte[] dead() {
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    public static void prepare(Path folder) {
        CrashFixture.prepare(folder);
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            store.scan(NONE).join();
            store.put(
                            List.of(
                                    new PutObject(key(kept()), kept()),
                                    new PutObject(key(dead()), dead())),
                            true,
                            NONE)
                    .join();
            store.delete(List.of(key(CrashFixture.baseline()), key(dead())), NONE).join();
            store.scan(NONE).join();
            check(store.index.find(key(kept())).pack() == 2);
        } finally {
            store.closeAsync().join();
        }
    }
}
