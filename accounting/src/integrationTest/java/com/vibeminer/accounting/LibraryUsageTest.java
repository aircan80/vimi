package com.vibeminer.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.Cancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LibraryUsageTest {
    @Test
    void separatesContainerAndSidecarLengthsWithoutChangingFiles(@TempDir Path folder)
            throws Exception {
        write(folder, "objects/packs/pack-000001.vmp", 100);
        write(folder, "generated/packs/pack-000001.vmp", 70);
        write(folder, "derived/items.sqlite", 30);
        write(folder, "derived/items.sqlite-wal", 10);
        write(folder, "derived/items.sqlite-shm", 2);
        write(folder, "objects/staging/object.part", 4);
        write(folder, "unknown.bin", 3);
        var usage = LibraryUsage.measure(folder, Cancellation.NONE);
        assertEquals(219, usage.total());
        assertEquals(7, usage.files());
        assertTrue(usage.complete());
        assertEquals(100, usage.bytes().get(LibraryUsage.Kind.ORIGINAL_PACKS));
        assertEquals(70, usage.bytes().get(LibraryUsage.Kind.GENERATED_PACKS));
        assertEquals(30, usage.bytes().get(LibraryUsage.Kind.DATABASES));
        assertEquals(10, usage.bytes().get(LibraryUsage.Kind.WAL));
        assertEquals(4, usage.bytes().get(LibraryUsage.Kind.STAGING));
        assertEquals(10, Files.size(folder.resolve("derived/items.sqlite-wal")));
    }

    private static void write(Path root, String relative, int length) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[length]);
    }
}
