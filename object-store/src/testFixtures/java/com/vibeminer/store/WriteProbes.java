package com.vibeminer.store;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Test-only access to the store's write observation points for suites in other modules. A probe
 * runs on the writer thread; throwing from it injects a failure at that point, and blocking in it
 * holds the writer there (for a parent process to kill).
 */
public final class WriteProbes {
    private WriteProbes() {}

    /** Point names in the order one entry passes them. */
    public static List<String> points() {
        return Arrays.stream(WritePoint.values()).map(Enum::name).toList();
    }

    /**
     * The field is not volatile: install before submitting the work it should observe (the
     * writer-queue submit publishes it).
     */
    public static void install(ObjectStore store, BiConsumer<String, ObjectKey> probe) {
        store.writeProbe = probe == null ? null : (point, key) -> probe.accept(point.name(), key);
    }

    /** Verification refuses each object for which {@code refusal} returns a code (or null). */
    public static void refuse(ObjectStore store, Function<ObjectKey, String> refusal) {
        store.refusalProbe = refusal;
    }
}
