package com.vibeminer.accounting;

import com.vibeminer.store.Cancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Point-in-time file lengths, not filesystem allocated blocks or per-build reclaimable bytes. */
public final class LibraryUsage {
    private LibraryUsage() {}

    public enum Kind {
        ORIGINAL_PACKS,
        GENERATED_PACKS,
        DATABASES,
        WAL,
        SHARED_MEMORY,
        STAGING,
        OTHER
    }

    public record Totals(Map<Kind, Long> bytes, long files, boolean complete) {
        public Totals {
            bytes = Map.copyOf(bytes);
        }

        public long total() {
            long total = 0;
            for (long value : bytes.values()) {
                total = Math.addExact(total, value);
            }
            return total;
        }
    }

    public static Totals measure(Path root, Cancellation token) throws IOException {
        token.check();
        Map<Kind, Long> bytes = new EnumMap<>(Kind.class);
        long files = 0;
        boolean complete = true;
        if (!Files.exists(root)) {
            return new Totals(bytes, 0, true);
        }
        root = root.toRealPath();
        try (var walk = Files.walk(root)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                token.check();
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    complete = false;
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    String name = path.getFileName().toString();
                    Kind kind =
                            name.endsWith("-wal")
                                    ? Kind.WAL
                                    : name.endsWith("-shm")
                                            ? Kind.SHARED_MEMORY
                                            : relative.contains("staging/")
                                                    ? Kind.STAGING
                                                    : name.endsWith(".sqlite")
                                                                    || name.endsWith(".sqlite3")
                                                            ? Kind.DATABASES
                                                            : name.endsWith(".vmp")
                                                                            && relative.startsWith(
                                                                                    "objects/")
                                                                    ? Kind.ORIGINAL_PACKS
                                                                    : name.endsWith(".vmp")
                                                                                    && relative
                                                                                            .startsWith(
                                                                                                    "generated/")
                                                                            ? Kind.GENERATED_PACKS
                                                                            : Kind.OTHER;
                    bytes.merge(kind, Files.size(path), Math::addExact);
                    files++;
                } catch (java.nio.file.NoSuchFileException disappeared) {
                    complete = false;
                }
            }
        }
        return new Totals(bytes, files, complete);
    }
}
