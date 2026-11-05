package com.vimi.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Test cleanup that never hides the failure that ended a test body. */
final class TestCleanup {
    private TestCleanup() {}

    interface Step {
        void run() throws Exception;
    }

    /**
     * Runs {@code cleanup} after a test body. If the body already failed ({@code primary} is not
     * null), a cleanup failure is attached to it as suppressed instead of replacing it. This matters
     * on Windows, where a store that a failed body left open keeps its files locked, so deleting
     * the test directory fails exactly when the real cause is needed.
     */
    static void after(Throwable primary, Step cleanup) throws Exception {
        // Throwable: an AssertionError or other Error from cleanup must not replace the cause.
        try {
            cleanup.run();
        } catch (Throwable failure) {
            if (primary == null) {
                throw failure;
            }
            primary.addSuppressed(failure);
        }
    }

    /** Deletes a test directory tree; see {@link #after(Throwable, Step)}. */
    static void deleteTree(Throwable primary, Path directory) throws Exception {
        after(primary, () -> deleteTree(directory));
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
