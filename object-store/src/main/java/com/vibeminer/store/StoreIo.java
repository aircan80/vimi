package com.vibeminer.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Per-store file operations with a package-private, checked-error test seam. */
final class StoreIo {
    enum Point { PACK_WRITE, PACK_SYNC, PACK_TRUNCATE, FILE_DELETE, DIRECTORY_SYNC, INDEX_REPLACE,
                 STAGING_WRITE, STAGING_TRUNCATE, STAGING_DELETE }
    @FunctionalInterface interface Fault { void before(Point point, Path path) throws IOException; }
    Fault fault;
    private void before(Point point, Path path) throws IOException { if (fault != null) fault.before(point, path); }
    private boolean staged(Path path) { return path.getFileName().toString().endsWith(".part"); }
    void write(FileChannel file, Path path, long offset, byte[] bytes) throws IOException {
        before(staged(path) ? Point.STAGING_WRITE : Point.PACK_WRITE, path); Format.write(file, offset, bytes);
    }
    void sync(FileChannel file, Path path) throws IOException { before(Point.PACK_SYNC, path); file.force(true); }
    void truncate(FileChannel file, Path path, long length) throws IOException {
        before(staged(path) ? Point.STAGING_TRUNCATE : Point.PACK_TRUNCATE, path); file.truncate(length);
    }
    void delete(Path path) throws IOException {
        before(staged(path) ? Point.STAGING_DELETE : Point.FILE_DELETE, path); Files.deleteIfExists(path);
    }
    void syncDirectory(Path path) throws IOException { before(Point.DIRECTORY_SYNC, path); Format.syncDirectory(path); }
    void replace(Path source, Path target) throws IOException {
        before(Point.INDEX_REPLACE, target); Files.move(source,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
