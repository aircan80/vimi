package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Three bounded shared slots for one producer and one latest-frame consumer. Frames own detached
 * pixels; mappings never escape, and closing the file deterministically unmaps it before deletion.
 */
public final class GpuFrameFile implements AutoCloseable {
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 960;
    public static final int SLOTS = 3;
    public static final int FILE_HEADER_BYTES = 64;
    public static final int SLOT_HEADER_BYTES = 64;
    public static final int PIXEL_FORMAT_BGRA8_PRE_OPAQUE = 1;
    private static final long MAGIC = 0x56494d4946524d31L;
    private static final int VERSION = 1;
    private static final long MAX_SEQUENCE = Long.MAX_VALUE >>> 1;
    private static final ValueLayout.OfInt INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle STAMP = LONG.varHandle();
    private final FileChannel channel;
    private final Arena arena;
    private final MemorySegment mapping;
    private final int maxWidth, maxHeight, slotBytes;
    private final long sessionNonce;
    // Debug only (WorldFrameFileV2.VERIFY_PROPERTY): per-frame CRC32C on both sides. Torn reads
    // are always rejected by the slot stamp (seqlock).
    private volatile boolean verify = Boolean.getBoolean(WorldFrameFileV2.VERIFY_PROPERTY);
    private final ByteBuffer crcScratch =
            ByteBuffer.allocate(64 << 10).order(ByteOrder.LITTLE_ENDIAN);
    private boolean closed;
    private long lastSequence;

    /** Detached opaque premultiplied ARGB pixels, ready for one FX bulk upload without cloning. */
    public static final class Frame {
        private final long sequence, controlRevision;
        private final int width, height;
        private final int[] pixels;

        private Frame(long sequence, long controlRevision, int width, int height, int[] pixels) {
            this.sequence = sequence;
            this.controlRevision = controlRevision;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        public long sequence() {
            return sequence;
        }

        public long controlRevision() {
            return controlRevision;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public IntBuffer pixels() {
            return IntBuffer.wrap(pixels).asReadOnlyBuffer();
        }
    }

    private record Header(int width, int height, int slotBytes, long nonce) {}

    private record Candidate(long offset, long stamp) {}

    private GpuFrameFile(FileChannel channel, Arena arena, MemorySegment mapping, Header header) {
        this.channel = channel;
        this.arena = arena;
        this.mapping = mapping;
        maxWidth = header.width();
        maxHeight = header.height();
        slotBytes = header.slotBytes();
        sessionNonce = header.nonce();
        lastSequence = highestReservedSequence();
    }

    public static GpuFrameFile create(Path path, int maxWidth, int maxHeight) throws IOException {
        checkDimensions(maxWidth, maxHeight, MAX_WIDTH, MAX_HEIGHT);
        int slotBytes = slotBytes(maxWidth, maxHeight);
        long length = FILE_HEADER_BYTES + (long) SLOTS * slotBytes;
        var channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
        var arena = Arena.ofShared();
        try {
            channel.position(length - 1).write(ByteBuffer.wrap(new byte[1]));
            var mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, length, arena);
            long nonce;
            var random = new SecureRandom();
            do {
                nonce = random.nextLong();
            } while (nonce == 0);
            var header = ByteBuffer.allocate(FILE_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(MAGIC)
                    .putInt(VERSION)
                    .putInt(maxWidth)
                    .putInt(maxHeight)
                    .putInt(SLOTS)
                    .putInt(PIXEL_FORMAT_BGRA8_PRE_OPAQUE)
                    .putInt(slotBytes)
                    .putLong(nonce);
            mapping.asSlice(0, FILE_HEADER_BYTES).copyFrom(MemorySegment.ofArray(header.array()));
            return new GpuFrameFile(
                    channel, arena, mapping, new Header(maxWidth, maxHeight, slotBytes, nonce));
        } catch (IOException | RuntimeException | Error failure) {
            releaseFailed(channel, arena, failure);
            throw failure;
        }
    }

    public static GpuFrameFile open(Path path) throws IOException {
        return open(path, MAX_WIDTH, MAX_HEIGHT);
    }

    /**
     * Rejects an oversized mapping before allocation; narrower dimensions are checked before
     * return.
     */
    public static GpuFrameFile open(Path path, int maxWidth, int maxHeight) throws IOException {
        checkDimensions(maxWidth, maxHeight, MAX_WIDTH, MAX_HEIGHT);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GPU frame bridge must be a regular file");
        }
        var channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
        var arena = Arena.ofShared();
        try {
            long size = channel.size();
            long maximum = FILE_HEADER_BYTES + (long) SLOTS * slotBytes(maxWidth, maxHeight);
            if (size < FILE_HEADER_BYTES || size > maximum) {
                throw new IOException("GPU frame bridge size exceeds bounds");
            }
            var mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
            var header = readHeader(mapping, size);
            if (header.width > maxWidth || header.height > maxHeight) {
                throw new IOException("GPU frame bridge exceeds consumer dimensions");
            }
            return new GpuFrameFile(channel, arena, mapping, header);
        } catch (IOException | RuntimeException | Error failure) {
            releaseFailed(channel, arena, failure);
            throw failure;
        }
    }

    private static void releaseFailed(FileChannel channel, Arena arena, Throwable failure) {
        try {
            arena.close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
        try {
            channel.close();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static Header readHeader(MemorySegment mapping, long size) throws IOException {
        int width = mapping.get(INT, 12), height = mapping.get(INT, 16);
        int stride = mapping.get(INT, 28);
        long nonce = mapping.get(LONG, 32);
        if (mapping.get(LONG, 0) != MAGIC
                || mapping.get(INT, 8) != VERSION
                || width < 1
                || width > MAX_WIDTH
                || height < 1
                || height > MAX_HEIGHT
                || mapping.get(INT, 20) != SLOTS
                || mapping.get(INT, 24) != PIXEL_FORMAT_BGRA8_PRE_OPAQUE
                || stride != slotBytes(width, height)
                || nonce == 0
                || size != FILE_HEADER_BYTES + (long) SLOTS * stride
                || mapping.get(LONG, 40) != 0
                || mapping.get(LONG, 48) != 0
                || mapping.get(LONG, 56) != 0) {
            throw new IOException("Invalid GPU frame bridge header");
        }
        return new Header(width, height, stride, nonce);
    }

    private void checkOpenSession() throws IOException {
        if (closed) {
            throw new IllegalStateException("GPU frame bridge closed");
        }
        var header = readHeader(mapping, channel.size());
        if (header.width() != maxWidth
                || header.height() != maxHeight
                || header.slotBytes() != slotBytes
                || header.nonce() != sessionNonce) {
            throw new IOException("GPU frame bridge session changed");
        }
    }

    /**
     * Publishes one completed top-down, tightly packed BGRA8 opaque GPU readback. Caller retains a
     * stable source until this returns; its buffer position is unchanged. No GPU in-flight pointer
     * may reference a slot.
     */
    public synchronized void write(
            long sequence, long controlRevision, int width, int height, ByteBuffer bgra)
            throws IOException {
        checkOpenSession();
        checkDimensions(width, height, maxWidth, maxHeight);
        int length = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (sequence < 1
                || sequence > MAX_SEQUENCE
                || controlRevision < 1
                || sequence <= Math.max(lastSequence, highestReservedSequence())
                || bgra == null
                || bgra.remaining() != length) {
            throw new IllegalArgumentException("Invalid GPU frame publication");
        }
        long stamp = sequence << 1;
        long offset = FILE_HEADER_BYTES + ((sequence - 1) % SLOTS) * slotBytes;
        var metadata = ByteBuffer.allocate(SLOT_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        metadata.putLong(stamp)
                .putLong(controlRevision)
                .putInt(width)
                .putInt(height)
                .putInt(width * 4)
                .putInt(length)
                .putInt(0)
                .putInt(0)
                .putLong(sessionNonce);
        if (verify) {
            // 0 means "not checksummed": a verifying reader skips that slot's CRC.
            var crc = new CRC32C();
            crc.update(metadata.array());
            crc.update(bgra.duplicate());
            metadata.putInt(32, (int) crc.getValue());
        }
        lastSequence = sequence;
        STAMP.setRelease(mapping, offset, stamp | 1);
        // A seqlock writer's in-progress stamp must precede every mutable pixel/header write.
        VarHandle.fullFence();
        mapping.asSlice(offset + 8, SLOT_HEADER_BYTES - 8)
                .copyFrom(MemorySegment.ofArray(metadata.array()).asSlice(8));
        mapping.asSlice(offset + SLOT_HEADER_BYTES, length)
                .copyFrom(MemorySegment.ofBuffer(bgra.duplicate()));
        STAMP.setRelease(mapping, offset, stamp);
    }

    /** Fixed metadata selection, never a Source/presentation capability or callback under lock. */
    record GlobalSelection(long minimumRevision, long maximumRevision, int width, int height) {
        GlobalSelection {
            if (minimumRevision < 1
                    || maximumRevision < minimumRevision
                    || width < 1
                    || width > 800
                    || height < 1
                    || height > 500) {
                throw new IllegalArgumentException("Invalid global frame selection");
            }
        }

        boolean matches(long revision, int actualWidth, int actualHeight) {
            return revision >= minimumRevision
                    && revision <= maximumRevision
                    && actualWidth == width
                    && actualHeight == height;
        }
    }

    synchronized void requireGlobalSession(long expectedNonce) throws IOException {
        checkOpenSession();
        if (expectedNonce == 0
                || sessionNonce != expectedNonce
                || maxWidth != 800
                || maxHeight != 500) {
            throw new IOException("Global frame mapping identity differs");
        }
    }

    /** Filters fixed scalar metadata BEFORE detached pixel allocation. No arbitrary predicate. */
    synchronized Frame readLatestGlobal(long afterSequence, GlobalSelection selection)
            throws IOException {
        Objects.requireNonNull(selection);
        if (maxWidth != 800 || maxHeight != 500) {
            throw new IOException("Global frame mapping dimensions differ");
        }
        return latest(afterSequence, selection);
    }

    /** Returns the newest safely copied frame, or null for absent/stale/torn/corrupt slots. */
    public synchronized Frame readLatest(long afterSequence) throws IOException {
        return latest(afterSequence, null);
    }

    private Frame latest(long afterSequence, GlobalSelection selection) throws IOException {
        checkOpenSession();
        if (afterSequence < 0) {
            throw new IllegalArgumentException("Negative GPU sequence");
        }
        var candidates = new ArrayList<Candidate>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            long offset = FILE_HEADER_BYTES + (long) slot * slotBytes;
            long stamp = (long) STAMP.getAcquire(mapping, offset);
            if (stamp > 0 && (stamp & 1) == 0 && (stamp >>> 1) > afterSequence) {
                candidates.add(new Candidate(offset, stamp));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::stamp).reversed());
        for (var candidate : candidates) {
            var frame = read(candidate, selection);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    private Frame read(Candidate candidate, GlobalSelection selection) {
        long offset = candidate.offset();
        byte[] bytes = mapping.asSlice(offset, SLOT_HEADER_BYTES).toArray(ValueLayout.JAVA_BYTE);
        var metadata = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long stamp = metadata.getLong(0), revision = metadata.getLong(8);
        int width = metadata.getInt(16), height = metadata.getInt(20);
        int stride = metadata.getInt(24), length = metadata.getInt(28);
        int expectedCrc = metadata.getInt(32);
        if (stamp != candidate.stamp()
                || revision < 1
                || width < 1
                || width > maxWidth
                || height < 1
                || height > maxHeight
                || stride != width * 4
                || length != stride * height
                || metadata.getInt(36) != 0
                || metadata.getLong(40) != sessionNonce
                || metadata.getLong(48) != 0
                || metadata.getLong(56) != 0) {
            return null;
        }
        if (selection != null && !selection.matches(revision, width, height)) {
            return null;
        }
        // All sizes and framing are checked before allocating the detached image.
        int[] pixels = new int[width * height];
        mapping.asSlice(offset + SLOT_HEADER_BYTES, length)
                .asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
                .get(pixels);
        VarHandle.fullFence();
        long completed = (long) STAMP.getAcquire(mapping, offset);
        if (completed != stamp) {
            return null;
        }
        if (verify && expectedCrc != 0) {
            metadata.putInt(32, 0);
            var crc = new CRC32C();
            crc.update(bytes);
            crcScratch.clear();
            var ints = crcScratch.asIntBuffer();
            for (int first = 0; first < pixels.length; ) {
                int count = Math.min(ints.capacity(), pixels.length - first);
                ints.clear().put(pixels, first, count);
                crcScratch.clear().limit(count * Integer.BYTES);
                crc.update(crcScratch);
                first += count;
            }
            if ((int) crc.getValue() != expectedCrc) {
                return null;
            }
        }
        return new Frame(stamp >>> 1, revision, width, height, pixels);
    }

    private long highestReservedSequence() {
        long sequence = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            long stamp =
                    (long) STAMP.getAcquire(mapping, FILE_HEADER_BYTES + (long) slot * slotBytes);
            if (stamp > 0) {
                sequence = Math.max(sequence, stamp >>> 1);
            }
        }
        return sequence;
    }

    private static void checkDimensions(
            int width, int height, int maximumWidth, int maximumHeight) {
        if (width < 1 || width > maximumWidth || height < 1 || height > maximumHeight) {
            throw new IllegalArgumentException("GPU frame dimensions exceed bridge bounds");
        }
    }

    private static int slotBytes(int width, int height) {
        long bytes = SLOT_HEADER_BYTES + (long) width * height * 4;
        return Math.toIntExact((bytes + 63) & ~63L);
    }

    /** Tests only: overrides the property-selected verification mode of this instance. */
    void verify(boolean enabled) {
        verify = enabled;
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public long sessionNonce() {
        return sessionNonce;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            arena.close();
        } finally {
            channel.close();
        }
    }
}
