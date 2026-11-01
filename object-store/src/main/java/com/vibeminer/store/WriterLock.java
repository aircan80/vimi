package com.vibeminer.store;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Locale;
import static java.lang.foreign.ValueLayout.*;

/** Uses flock on Unix: Java FileLock/fcntl would not exclude the .NET reference writer. */
final class WriterLock implements AutoCloseable {
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private int fd = -1;
    private MemorySegment handle;
    private MethodHandle release;
    WriterLock(Path path) {
        SymbolLookup lookup = windows ? SymbolLookup.libraryLookup("kernel32.dll", Arena.global()) : Linker.nativeLinker().defaultLookup();
        try (Arena arena = Arena.ofConfined()) {
            if (windows) {
                MethodHandle create = NativeSqlite.function(lookup, "CreateFileW", ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
                byte[] name = (path.toString() + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                MemorySegment wide = arena.allocate(name.length, 2); wide.copyFrom(MemorySegment.ofArray(name));
                handle = (MemorySegment) NativeSqlite.call(create, wide, 0xC0000000, 0, MemorySegment.NULL, 4, 0x80, MemorySegment.NULL);
                release = NativeSqlite.function(lookup, "CloseHandle", JAVA_INT, ADDRESS);
                if (handle.address() == -1) throw busy();
            } else {
                MethodHandle open = NativeSqlite.function(lookup, "open", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
                MethodHandle flock = NativeSqlite.function(lookup, "flock", JAVA_INT, JAVA_INT, JAVA_INT);
                release = NativeSqlite.function(lookup, "close", JAVA_INT, JAVA_INT);
                boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
                fd = (int) NativeSqlite.call(open, arena.allocateFrom(path.toString()), 2 | (mac ? 0x200 : 0x40), 0600);
                if (fd < 0) throw new StoreException("OBJECT_STORE_WRITE_FAILED", "Cannot open writer lock");
                if ((int) NativeSqlite.call(flock, fd, 2 | 4) != 0) { close(); throw busy(); }
            }
        }
    }
    private StoreException busy() { return new StoreException("OBJECT_STORE_BUSY", "Another writer owns this store"); }
    @Override public void close() {
        if (windows && handle != null && handle.address() != -1) { NativeSqlite.call(release, handle); handle = null; }
        else if (!windows && fd >= 0) { NativeSqlite.call(release, fd); fd = -1; }
    }
}
