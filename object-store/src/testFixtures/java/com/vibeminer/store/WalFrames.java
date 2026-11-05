package com.vibeminer.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test-only view of a SQLite WAL file, for simulating what the OS keeps of unsynced WAL writes
 * after a power loss. With {@code synchronous=NORMAL} a commit writes its frames without an fsync,
 * so the OS may lose any of the frames written after the last sync point. A frame is a 24-byte
 * header (page number, database size after a commit or 0, salts, checksums) and one page.
 */
public final class WalFrames {
    private static final int HEADER = 32, FRAME_HEADER = 24;

    private final Path wal;
    private final int pageSize;

    private WalFrames(Path wal, int pageSize) {
        this.wal = wal;
        this.pageSize = pageSize;
    }

    /** Reads the WAL header of {@code database}'s {@code -wal} sidecar. */
    public static WalFrames of(Path database) throws IOException {
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        byte[] header = new byte[HEADER];
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.READ)) {
            if (file.read(ByteBuffer.wrap(header), 0) != HEADER) {
                throw new IOException("WAL has no header: " + wal);
            }
        }
        var b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int magic = b.getInt(0);
        if (magic != 0x377f0682 && magic != 0x377f0683) {
            throw new IOException("Not a WAL file: " + wal);
        }
        return new WalFrames(wal, b.getInt(8));
    }

    public Path path() {
        return wal;
    }

    public long frameBytes() {
        return FRAME_HEADER + (long) pageSize;
    }

    /** Whole frames in the file; a trailing partial frame is not counted. */
    public int frames() throws IOException {
        long size = Files.size(wal);
        return size < HEADER ? 0 : Math.toIntExact((size - HEADER) / frameBytes());
    }

    /** The frame count that ends at {@code bytes}, which must be a frame boundary. */
    public int framesAt(long bytes) {
        if (bytes == 0) {
            return 0;
        }
        long frames = (bytes - HEADER) / frameBytes();
        if (bytes < HEADER || HEADER + frames * frameBytes() != bytes) {
            throw new IllegalArgumentException("Not a frame boundary: " + bytes);
        }
        return Math.toIntExact(frames);
    }

    /** The 32-byte header, whose salts change whenever a checkpoint restarts the WAL. */
    public byte[] header() throws IOException {
        byte[] header = new byte[HEADER];
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.READ)) {
            file.read(ByteBuffer.wrap(header), 0);
        }
        return header;
    }

    /** Frame counts after each commit frame, ascending: the commit boundaries in the file. */
    public List<Integer> commits() throws IOException {
        var commits = new ArrayList<Integer>();
        int frames = frames();
        byte[] header = new byte[FRAME_HEADER];
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.READ)) {
            for (int i = 0; i < frames; i++) {
                Arrays.fill(header, (byte) 0);
                file.read(ByteBuffer.wrap(header), HEADER + i * frameBytes());
                if (ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(4) != 0) {
                    commits.add(i + 1);
                }
            }
        }
        return commits;
    }

    /** The OS kept only the first {@code frames} frames: every later write is lost. */
    public void keep(int frames) throws IOException {
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            file.truncate(HEADER + frames * frameBytes());
        }
    }

    /**
     * The OS lost frame {@code index} (0-based) but kept the frames written after it: the
     * writeback reordered. The lost frame reads back as stale garbage.
     */
    public void lose(int index) throws IOException {
        byte[] garbage = new byte[Math.toIntExact(frameBytes())];
        Arrays.fill(garbage, (byte) 0x5a);
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(garbage), HEADER + index * frameBytes());
        }
    }
}
