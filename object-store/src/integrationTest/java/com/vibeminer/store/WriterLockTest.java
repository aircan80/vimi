package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WriterLockTest {
    @TempDir Path folder;

    @Test
    void freshUnixLockHasOwnerReadWritePermissions() throws Exception {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"));
        Path path = folder.resolve("writer.lock");
        try (WriterLock lock = new WriterLock(path)) {
            assertNotNull(lock);
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
        }
    }

    @Test
    void existingLockCanBeReopenedAfterRelease() {
        Path path = folder.resolve("writer.lock");
        for (int i = 0; i < 3; i++) {
            try (WriterLock lock = new WriterLock(path)) {
                assertNotNull(lock);
            }
        }
    }

    @Test
    void heldLockExcludesAnotherWriterAndCanBeReacquired() {
        Path path = folder.resolve("writer.lock");
        try (WriterLock held = new WriterLock(path)) {
            assertNotNull(held);
            StoreException error = assertThrows(StoreException.class, () -> new WriterLock(path));
            assertEquals("OBJECT_STORE_BUSY", error.code());
        }
        try (WriterLock reacquired = new WriterLock(path)) {
            assertNotNull(reacquired);
        }
    }

    @Test
    void reopeningPreservesExistingFilePermissionsAndContent() throws Exception {
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"));
        Path path = folder.resolve("writer.lock");
        Files.writeString(path, "existing lock inode");
        Set<PosixFilePermission> permissions =
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(path, permissions);
        Object identity = Files.getAttribute(path, "unix:ino");
        try (WriterLock lock = new WriterLock(path)) {
            assertNotNull(lock);
            assertEquals(identity, Files.getAttribute(path, "unix:ino"));
            assertEquals(permissions, Files.getPosixFilePermissions(path));
            assertEquals("existing lock inode", Files.readString(path));
        }
    }
}
