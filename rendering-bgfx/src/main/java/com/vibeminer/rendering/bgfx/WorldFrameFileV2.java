package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.WorldFrameLimits;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Separate World v2 mapping codec. No renderer, control channel or GUI is wired to it. */
final class WorldFrameFileV2 implements AutoCloseable {
    static final int WIDTH = WorldFrameLimits.MAX_WIDTH, HEIGHT = WorldFrameLimits.MAX_HEIGHT;
    static final int SLOTS = 3, HEADER_BYTES = 64;
    static final int PAYLOAD_BYTES = WIDTH * HEIGHT * 4;
    static final int SLOT_BYTES = HEADER_BYTES + PAYLOAD_BYTES;
    static final long FILE_BYTES = HEADER_BYTES + (long) SLOTS * SLOT_BYTES;
    static final long MAX_SEQUENCE = Long.MAX_VALUE >>> 1;
    private static final byte[] MAGIC =
            "VIMIWSF2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final ValueLayout.OfLong LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle STAMP = LONG.varHandle();

    /**
     * Debug only: {@code -Dvimi.frameVerify=true} restores the per-frame CRC32C and opaque alpha
     * checks on both sides. Torn reads are always rejected by the slot stamp (seqlock).
     */
    static final String VERIFY_PROPERTY = "vimi.frameVerify";

    /** Immutable acknowledged presentation bounds; checked before any detached pixel allocation. */
    record Barrier(
            long policy,
            int width,
            int height,
            long cameraFloor,
            long cameraCeiling,
            long contentFloor,
            long contentCeiling) {
        Barrier {
            dimensions(width, height);
            if (policy <= 0
                    || cameraFloor < 1
                    || cameraCeiling < cameraFloor
                    || contentFloor < 0
                    || contentCeiling < contentFloor) {
                throw new IllegalArgumentException("Invalid World frame barrier");
            }
        }

        boolean accepts(WorldStreamFrameGate.Frame frame) {
            return frame.policy() == policy
                    && frame.width() == width
                    && frame.height() == height
                    && frame.camera() >= cameraFloor
                    && frame.camera() <= cameraCeiling
                    && frame.content() >= contentFloor
                    && frame.content() <= contentCeiling;
        }
    }

    /**
     * Detached pixels survive unmapping; consumers get a read-only view without a clone. The pixel
     * array is pooled: the one owner calls {@link #release()} once it no longer reads the pixels,
     * after which a later frame may overwrite them. An unreleased frame is ordinary garbage.
     */
    static final class Frame {
        private final WorldStreamFrameGate.Frame metadata;
        private final int[] pixels;
        private final Pool pool;
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Frame(WorldStreamFrameGate.Frame metadata, int[] pixels, Pool pool) {
            this.metadata = metadata;
            this.pixels = pixels;
            this.pool = pool;
        }

        WorldStreamFrameGate.Frame metadata() {
            return metadata;
        }

        IntBuffer pixels() {
            if (released.get()) {
                throw new IllegalStateException("World frame pixels released");
            }
            return IntBuffer.wrap(pixels, 0, metadata.width() * metadata.height())
                    .slice()
                    .asReadOnlyBuffer();
        }

        /** Idempotent; returns the pixel array for reuse by a later read. */
        void release() {
            if (released.compareAndSet(false, true)) {
                pool.recycle(pixels);
            }
        }

        int[] array() { // Tests only.
            return pixels;
        }
    }

    /**
     * Bounded frame-size pixel arrays. Up to three frames are live at once (the read in progress,
     * the transport's latest and the presentation), so three retained arrays keep the steady state
     * allocation-free.
     */
    static final class Pool {
        private final Object lock = new Object();
        private final java.util.ArrayDeque<int[]> free = new java.util.ArrayDeque<>(SLOTS);
        private long allocations;

        /**
         * An array of at least {@code pixels} ints. Arrays are sized to the frame, not the cap, so
         * a small viewport holds no 2560x1600 arrays; ones too small after a resize are dropped.
         */
        int[] take(int pixels) {
            synchronized (lock) {
                int[] reused;
                while ((reused = free.pollFirst()) != null) {
                    if (reused.length >= pixels) {
                        return reused;
                    }
                }
                allocations++;
            }
            return new int[pixels];
        }

        void recycle(int[] pixels) {
            synchronized (lock) {
                if (pixels.length <= WIDTH * HEIGHT
                        && free.size() < SLOTS
                        && free.stream().noneMatch(retained -> retained == pixels)) {
                    free.addFirst(pixels);
                }
            }
        }

        long allocations() {
            synchronized (lock) {
                return allocations;
            }
        }

        int retained() {
            synchronized (lock) {
                return free.size();
            }
        }
    }

    private record Candidate(long offset, long stamp) {}

    private final FileChannel channel;
    private final Arena arena;
    private final MemorySegment mapping;
    private final WorldStreamState.Nonce nonce;
    private final byte[] scope;
    private final ByteBuffer scratch = ByteBuffer.allocate(64 << 10).order(ByteOrder.LITTLE_ENDIAN);
    private final boolean verify;
    private final Pool pool = new Pool();
    // Test seam: runs after a slot's pixels are copied and before its stamp is re-checked.
    volatile Runnable afterCopy;
    private boolean closed;
    private long lastSequence;

    private WorldFrameFileV2(
            FileChannel channel,
            Arena arena,
            MemorySegment mapping,
            WorldStreamState.Nonce nonce,
            byte[] scope,
            boolean verify) {
        this.verify = verify;
        this.channel = channel;
        this.arena = arena;
        this.mapping = mapping;
        this.nonce = nonce;
        this.scope = scope;
        lastSequence = highestReserved();
    }

    static WorldFrameFileV2 create(Path path, WorldStreamState.Nonce nonce, String scope)
            throws IOException {
        return open(path, nonce, scope, true, Boolean.getBoolean(VERIFY_PROPERTY));
    }

    static WorldFrameFileV2 open(Path path, WorldStreamState.Nonce nonce, String scope)
            throws IOException {
        return open(path, nonce, scope, false, Boolean.getBoolean(VERIFY_PROPERTY));
    }

    /** Explicit verification mode, independent of the system property. */
    static WorldFrameFileV2 open(
            Path path, WorldStreamState.Nonce nonce, String scope, boolean create, boolean verify)
            throws IOException {
        Objects.requireNonNull(nonce);
        if (scope == null || !scope.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid World scope digest");
        }
        byte[] digest = HexFormat.of().parseHex(scope);
        var channel =
                create
                        ? FileChannel.open(
                                path,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)
                        : FileChannel.open(
                                path,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
        var arena = Arena.ofShared();
        try {
            if (create) {
                channel.position(FILE_BYTES - 1).write(ByteBuffer.wrap(new byte[1]));
            }
            if (channel.size() != FILE_BYTES) {
                throw new IOException("Invalid World frame file size");
            }
            var mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_BYTES, arena);
            if (create) {
                var header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                header.put(MAGIC)
                        .putInt(2)
                        .putInt(HEADER_BYTES)
                        .putLong(nonce.high())
                        .putLong(nonce.low())
                        .put(digest);
                mapping.asSlice(0, HEADER_BYTES).copyFrom(MemorySegment.ofArray(header.array()));
            }
            var result = new WorldFrameFileV2(channel, arena, mapping, nonce, digest, verify);
            result.checkOpen();
            return result;
        } catch (IOException | RuntimeException | Error error) {
            try {
                arena.close();
            } catch (Throwable close) {
                error.addSuppressed(close);
            }
            try {
                channel.close();
            } catch (Throwable close) {
                error.addSuppressed(close);
            }
            throw error;
        }
    }

    synchronized boolean freshFor(WorldStreamEnvelope.Session session) throws IOException {
        checkOpen();
        return nonce.equals(session.nonce())
                && Arrays.equals(scope, HexFormat.of().parseHex(session.scope()))
                && highestReserved() == 0;
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IllegalStateException("World frame file closed");
        }
        if (channel.size() != FILE_BYTES
                || mapping.get(INT, 8) != 2
                || mapping.get(INT, 12) != HEADER_BYTES
                || mapping.get(LONG, 16) != nonce.high()
                || mapping.get(LONG, 24) != nonce.low()
                || !Arrays.equals(MAGIC, mapping.asSlice(0, 8).toArray(ValueLayout.JAVA_BYTE))
                || !Arrays.equals(scope, mapping.asSlice(32, 32).toArray(ValueLayout.JAVA_BYTE))) {
            throw new IOException("World frame session changed");
        }
    }

    /**
     * Caller owns a stable completed BGRA readback until this returns; its position is unchanged.
     */
    synchronized void write(WorldStreamFrameGate.Frame ticket, ByteBuffer bgra) throws IOException {
        checkOpen();
        Objects.requireNonNull(ticket);
        dimensions(ticket.width(), ticket.height());
        int length = ticket.width() * ticket.height() * 4;
        if (!nonce.equals(ticket.nonce())
                || ticket.sequence() < 1
                || ticket.sequence() > MAX_SEQUENCE
                || ticket.sequence() <= Math.max(lastSequence, highestReserved())
                || ticket.camera() < 1
                || ticket.content() < 0
                || ticket.policy() < 1
                || bgra == null
                || bgra.remaining() != length) {
            throw new IllegalArgumentException("Invalid World frame publication");
        }
        if (verify) {
            for (int i = bgra.position() + 3; i < bgra.limit(); i += 4) {
                if (bgra.get(i) != (byte) 255) {
                    throw new IllegalArgumentException("Nonopaque World pixels");
                }
            }
        } else {
            // Cheap tripwire: a renderer change that writes alpha below 255 fails the helper
            // loudly instead of showing a see-through viewport.
            int pixels = length / 4, stride = alphaStride(pixels);
            for (int i = 0; i < pixels; i += stride) {
                if (bgra.get(bgra.position() + i * 4 + 3) != (byte) 255) {
                    throw new IllegalArgumentException("Nonopaque World pixels");
                }
            }
            if (bgra.get(bgra.limit() - 1) != (byte) 255) {
                throw new IllegalArgumentException("Nonopaque World pixels");
            }
        }
        long stamp = ticket.sequence() << 1;
        long offset = HEADER_BYTES + ((ticket.sequence() - 1) % SLOTS) * SLOT_BYTES;
        var header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(stamp)
                .putLong(nonce.high())
                .putLong(nonce.low())
                .putLong(ticket.camera())
                .putLong(ticket.content())
                .putLong(ticket.policy())
                .putInt(ticket.width())
                .putInt(ticket.height())
                .putInt(0)
                .putInt(length);
        if (verify) {
            // 0 means "not checksummed": a verifying reader skips that slot's CRC.
            var crc = metadataCrc(header.array());
            crc.update(bgra.duplicate());
            header.putInt(56, (int) crc.getValue());
        }
        lastSequence = ticket.sequence();
        STAMP.setRelease(mapping, offset, stamp | 1);
        VarHandle.fullFence();
        mapping.asSlice(offset + 8, HEADER_BYTES - 8)
                .copyFrom(MemorySegment.ofArray(header.array()).asSlice(8));
        mapping.asSlice(offset + HEADER_BYTES, length)
                .copyFrom(MemorySegment.ofBuffer(bgra.duplicate()));
        STAMP.setRelease(mapping, offset, stamp);
    }

    synchronized Frame readLatest(long afterSequence, Barrier barrier) throws IOException {
        checkOpen();
        Objects.requireNonNull(barrier);
        if (afterSequence < 0 || afterSequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Invalid World frame sequence");
        }
        var candidates = new ArrayList<Candidate>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            long offset = HEADER_BYTES + (long) slot * SLOT_BYTES;
            long stamp = (long) STAMP.getAcquire(mapping, offset);
            if (stamp > 0 && (stamp & 1) == 0 && (stamp >>> 1) > afterSequence) {
                candidates.add(new Candidate(offset, stamp));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::stamp).reversed());
        for (var candidate : candidates) {
            var frame = read(candidate, barrier);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    private Frame read(Candidate candidate, Barrier barrier) throws IOException {
        long offset = candidate.offset();
        byte[] bytes = mapping.asSlice(offset, HEADER_BYTES).toArray(ValueLayout.JAVA_BYTE);
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getLong(0) != candidate.stamp()
                || header.getLong(8) != nonce.high()
                || header.getLong(16) != nonce.low()) {
            return null;
        }
        int width = header.getInt(48), height = header.getInt(52), length = header.getInt(60);
        if (width < 1
                || width > WIDTH
                || height < 1
                || height > HEIGHT
                || length != width * height * 4) {
            return null;
        }
        var metadata =
                new WorldStreamFrameGate.Frame(
                        nonce,
                        candidate.stamp() >>> 1,
                        header.getLong(24),
                        header.getLong(32),
                        header.getLong(40),
                        width,
                        height);
        if (!barrier.accepts(metadata)) {
            return null;
        }
        int count = width * height;
        int[] pixels = pool.take(count);
        mapping.asSlice(offset + HEADER_BYTES, length)
                .asByteBuffer()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
                .get(pixels, 0, count);
        var seam = afterCopy;
        if (seam != null) {
            seam.run();
        }
        VarHandle.fullFence();
        // Seqlock: an unchanged even stamp means no writer touched the slot during the copy.
        int expectedCrc = header.getInt(56);
        if ((long) STAMP.getAcquire(mapping, offset) != candidate.stamp()
                || verify && expectedCrc != 0 && !verified(bytes, pixels, count, expectedCrc)) {
            pool.recycle(pixels);
            return null;
        }
        // The copy is consistent, so sampled nonopaque pixels are a real renderer fault: loud.
        boolean opaque = pixels[count - 1] >>> 24 == 255;
        for (int i = 0, stride = alphaStride(count); opaque && i < count; i += stride) {
            opaque = pixels[i] >>> 24 == 255;
        }
        if (!opaque) {
            pool.recycle(pixels);
            throw new NonopaqueFrameException(metadata.sequence());
        }
        return new Frame(metadata, pixels, pool);
    }

    /** About 64 evenly spaced alpha samples per frame, plus the last pixel. */
    static int alphaStride(int pixels) {
        return Math.max(1, pixels / 64);
    }

    /** A published, consistent frame has nonopaque pixels: a renderer contract violation. */
    static final class NonopaqueFrameException extends IOException {
        private static final long serialVersionUID = 1L;

        NonopaqueFrameException(long sequence) {
            super("World frame " + sequence + " has nonopaque pixels");
        }
    }

    private boolean verified(byte[] header, int[] pixels, int count, int expectedCrc) {
        var crc = metadataCrc(header);
        scratch.clear();
        var ints = scratch.asIntBuffer();
        for (int first = 0; first < count; ) {
            int chunk = Math.min(ints.capacity(), count - first);
            for (int i = first; i < first + chunk; i++) {
                if ((pixels[i] >>> 24) != 255) {
                    return false;
                }
            }
            ints.clear().put(pixels, first, chunk);
            scratch.clear().limit(chunk * 4);
            crc.update(scratch);
            first += chunk;
        }
        return (int) crc.getValue() == expectedCrc;
    }

    Pool pool() { // Tests only.
        return pool;
    }

    private static CRC32C metadataCrc(byte[] header) {
        var crc = new CRC32C();
        crc.update(header, 8, 48);
        crc.update(header, 60, 4);
        return crc;
    }

    private long highestReserved() {
        long result = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            long stamp = (long) STAMP.getAcquire(mapping, HEADER_BYTES + (long) slot * SLOT_BYTES);
            if (stamp > 0) {
                result = Math.max(result, stamp >>> 1);
            }
        }
        return result;
    }

    private static void dimensions(int width, int height) {
        if (width < 1 || width > WIDTH || height < 1 || height > HEIGHT) {
            throw new IllegalArgumentException("World frame dimensions exceed fixed caps");
        }
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
