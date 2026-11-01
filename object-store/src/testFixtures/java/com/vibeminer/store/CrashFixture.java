package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Known content shared by child-process and cross-language crash checks. */
public final class CrashFixture {
    private CrashFixture() {}

    public static byte[] baseline() {
        return "committed baseline".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] target() {
        return "crash target".getBytes(StandardCharsets.US_ASCII);
    }

    public static History.Observation observation(boolean next) {
        return new History.Observation(
                1,
                "wow",
                "us",
                next ? Instant.EPOCH.plusSeconds(1) : Instant.EPOCH,
                "",
                baseline());
    }

    public static EncodedFixture encoded() {
        byte[] chunk = new byte[Format.BLOCK + 13];
        chunk[0] = 'N';
        return EncodedFixture.chunks(chunk, new byte[] {'E', 4, 5});
    }

    public static ObjectKey targetKey(String operation) {
        return switch (operation) {
            case "put" -> StoreAssertions.key(target());
            case "delete" -> StoreAssertions.key(baseline());
            case "history" -> History.objects(observation(true)).getLast().key();
            case "staged" -> encoded().key();
            default -> throw new IllegalArgumentException("Unknown operation");
        };
    }

    public static void prepare(java.nio.file.Path folder) {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            store.put(
                            List.of(new PutObject(StoreAssertions.key(baseline()), baseline())),
                            true,
                            Cancellation.NONE)
                    .join();
            store.appendHistory(observation(false), Cancellation.NONE).join();
        } finally {
            store.closeAsync().join();
        }
    }
}
