package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Skips a symlink check where this machine cannot create symbolic links: Windows without Developer
 * Mode or administrator rights fails with "A required privilege is not held by the client". CI
 * always has the privilege, so there an unavailable link fails instead of skipping.
 */
final class SymbolicLinks {
    private SymbolicLinks() {}

    /** Creates and removes a probe link in {@code directory}, or aborts the current test. */
    static void assumeAvailable(Path directory) throws IOException {
        Path target = Files.createTempFile(directory, "symlink-target", ".probe");
        Path link = directory.resolve(target.getFileName() + ".link");
        try {
            Files.createSymbolicLink(link, target);
            Files.delete(link);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            if (System.getenv("CI") != null) {
                throw unavailable;
            }
            // The reason only: a FileSystemException message also names local paths.
            String reason =
                    unavailable instanceof java.nio.file.FileSystemException failure
                                    && failure.getReason() != null
                            ? failure.getReason()
                            : unavailable.getClass().getSimpleName();
            Assumptions.abort(
                    "Symbolic links are unavailable on this machine (on Windows, enable Developer"
                            + " Mode or run as administrator): "
                            + reason);
        } finally {
            Files.delete(target);
        }
    }
}
