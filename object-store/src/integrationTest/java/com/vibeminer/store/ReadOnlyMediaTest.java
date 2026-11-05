package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.security.auth.module.NTSystem;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try") // Permission scope restores filesystem state on every exit.
final class ReadOnlyMediaTest {
    @TempDir Path folder;
    private final byte[] bytes = {1, 2, 3, 4};

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanClosedStoreReadsWithoutCreatingSidecarsOnReadOnlyMedia(boolean emptyWal)
            throws Exception {
        createClosedStore();
        if (emptyWal) {
            Files.createFile(folder.resolve("objects.sqlite3-wal"));
        }
        try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(folder)) {
            ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
            try {
                assertArrayEquals(bytes, reader.read(key(bytes), NONE).join().bytes());
                assertEquals(emptyWal, Files.exists(folder.resolve("objects.sqlite3-wal")));
                if (emptyWal) {
                    assertEquals(0, Files.size(folder.resolve("objects.sqlite3-wal")));
                }
                assertFalse(Files.exists(folder.resolve("objects.sqlite3-shm")));
            } finally {
                reader.closeAsync().join();
            }
        }
    }

    @Test
    void immutableFallbackRejectsUncleanMainFile() throws Exception {
        createClosedStore();
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
            db.exec("UPDATE store SET clean_close=0");
            db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(folder)) {
            failure(
                    ObjectStore.openReadOnly(folder, new Options()),
                    "OBJECT_STORE_INDEX_UNREADABLE");
        }
    }

    @Test
    void inaccessibleWalCommitsNeverFallBackToMainFile() throws Exception {
        Path source = folder.resolve("source");
        ObjectStore writer = ObjectStore.open(source).join();
        Path copy = folder.resolve("copy");
        try {
            writer.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
            Files.createDirectories(copy.resolve("packs"));
            Files.copy(source.resolve("objects.sqlite3"), copy.resolve("objects.sqlite3"));
            Files.copy(source.resolve("objects.sqlite3-wal"), copy.resolve("objects.sqlite3-wal"));
            try (var packs = Files.newDirectoryStream(source.resolve("packs"))) {
                for (Path pack : packs) {
                    Files.copy(pack, copy.resolve("packs").resolve(pack.getFileName()));
                }
            }
            assertTrue(Files.size(copy.resolve("objects.sqlite3-wal")) > 0);
            try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(copy)) {
                failure(
                        ObjectStore.openReadOnly(copy, new Options()),
                        "OBJECT_STORE_INDEX_UNREADABLE");
            }
        } finally {
            writer.closeAsync().join();
        }
    }

    @Test
    void secondaryReaderSeesLaterCommitsAndNeverTakesWriterOwnership() {
        createClosedStore();
        ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            ObjectStore writer = ObjectStore.open(folder).join();
            try {
                byte[] later = {5, 6, 7, 8};
                assertFalse(reader.has(key(later), NONE).join());
                writer.put(List.of(new PutObject(key(later), later)), true, NONE).join();
                assertArrayEquals(later, reader.read(key(later), NONE).join().bytes());
                failure(ObjectStore.open(folder), "OBJECT_STORE_BUSY");
                writer.delete(List.of(key(later)), NONE).join();
                assertFalse(reader.has(key(later), NONE).join());
            } finally {
                writer.closeAsync().join();
            }
        } finally {
            reader.closeAsync().join();
        }
    }

    private void createClosedStore() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            store.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        } finally {
            store.closeAsync().join();
        }
    }

    /**
     * Changes only this test's files and always restores them, including on assumption skips. POSIX
     * file systems get owner read-only modes. Windows (NTFS/ReFS) gets an explicit per-file deny
     * entry for the current user that blocks writing, creating, deleting and attribute changes
     * while leaving read, traversal and ACL ownership rights intact.
     */
    private static final class ReadOnlyPermissions implements AutoCloseable {
        private static final Set<AclEntryPermission> DENIED_WRITES =
                EnumSet.of(
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.DELETE,
                        AclEntryPermission.DELETE_CHILD);
        private final Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();
        private final Map<Path, List<AclEntry>> originalAcls = new LinkedHashMap<>();

        ReadOnlyPermissions(Path root) throws IOException {
            FileStore store = Files.getFileStore(root);
            boolean posix = store.supportsFileAttributeView(PosixFileAttributeView.class);
            assumeTrue(
                    posix || store.supportsFileAttributeView(AclFileAttributeView.class),
                    "Requires POSIX permissions or native ACLs");
            try {
                AclEntry deny = posix ? null : denyWrites(root);
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.toList()) {
                        if (posix) {
                            original.put(path, Files.getPosixFilePermissions(path));
                            Files.setPosixFilePermissions(
                                    path,
                                    Files.isDirectory(path)
                                            ? Set.of(
                                                    PosixFilePermission.OWNER_READ,
                                                    PosixFilePermission.OWNER_EXECUTE)
                                            : Set.of(PosixFilePermission.OWNER_READ));
                        } else {
                            AclFileAttributeView view =
                                    Files.getFileAttributeView(path, AclFileAttributeView.class);
                            List<AclEntry> acl = view.getAcl();
                            originalAcls.put(path, acl);
                            List<AclEntry> denied = new ArrayList<>(acl.size() + 1);
                            denied.add(deny);
                            denied.addAll(acl);
                            view.setAcl(denied);
                        }
                    }
                }
                if (posix) {
                    assumeTrue(
                            !Files.isWritable(root),
                            "Requires an unprivileged permission-enforcing user");
                } else {
                    // A deny for the wrong account would silently disable every case, so the ACL
                    // branch fails rather than skips when enforcement is missing.
                    assertFalse(
                            Files.isWritable(root),
                            "Deny entry for " + deny.principal() + " left the store writable");
                    assertWriteDenied(root);
                }
            } catch (IOException | RuntimeException | Error error) {
                try {
                    close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private static AclEntry denyWrites(Path root) throws IOException {
            // Resolve the account from the process token, qualified by its domain. A bare user
            // name can resolve to another account on domain-joined machines or on name clashes.
            NTSystem token = new NTSystem();
            UserPrincipal user =
                    root.getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(token.getDomain() + "\\" + token.getName());
            return AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(user)
                    .setPermissions(DENIED_WRITES)
                    .build();
        }

        /** Proves enforcement directly instead of trusting the access-check helper alone. */
        private static void assertWriteDenied(Path root) {
            Path probe = root.resolve("read-only-probe");
            assertThrows(AccessDeniedException.class, () -> Files.createFile(probe));
            assertFalse(Files.exists(probe));
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (var entry : originalAcls.entrySet()) {
                try {
                    Files.getFileAttributeView(entry.getKey(), AclFileAttributeView.class)
                            .setAcl(entry.getValue());
                } catch (IOException restore) {
                    if (failure == null) {
                        failure = restore;
                    } else {
                        failure.addSuppressed(restore);
                    }
                }
            }
            for (var entry : original.entrySet()) {
                Files.setPosixFilePermissions(entry.getKey(), entry.getValue());
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
