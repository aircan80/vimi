package com.vibeminer.generated;

import com.vibeminer.generated.GeneratedTypes.Read;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import com.vibeminer.generated.RecordFormat.Block;
import com.vibeminer.generated.RecordFormat.Chunk;
import com.vibeminer.generated.RecordFormat.Frame;
import com.vibeminer.generated.RecordFormat.Manifest;
import com.vibeminer.generated.RecordFormat.Segment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable manifest; pages and ID lookups read only the required compressed ranges. */
public final class RecordFile {
    public static final int MAX_PAGE_RECORDS = 1 << 16;
    static final int RUN_BYTES = 256 << 10;

    public record Record(long ordinal, long id, byte[] bytes) {
        public Record {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Open(ReadState state, RecordFile file, String problem) {}

    private final GeneratedStore store;
    private final Manifest manifest;
    private final List<GeneratedKey> keys;

    private RecordFile(GeneratedStore store, GeneratedKey key, Manifest manifest) {
        this.store = store;
        this.manifest = manifest;
        LinkedHashSet<GeneratedKey> all = new LinkedHashSet<>();
        all.add(key);
        for (Segment segment : manifest.segments()) {
            all.add(segment.key());
        }
        keys = List.copyOf(all);
    }

    public long count() {
        return manifest.count();
    }

    public byte[] metadata() {
        return manifest.metadata().clone();
    }

    /** Shared segments must not be deleted while another live manifest references them. */
    public List<GeneratedKey> keys() {
        return keys;
    }

    List<Long> blockFirstOrdinals() {
        return manifest.blocks().stream().map(Block::first).toList();
    }

    public static CompletableFuture<Open> open(GeneratedStore store, GeneratedKey key) {
        return open(store, key, Cancellation.NONE);
    }

    public static CompletableFuture<Open> open(
            GeneratedStore store, GeneratedKey key, Cancellation cancellation) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(key);
        Objects.requireNonNull(cancellation);
        return RecordTasks.submit(
                () -> {
                    cancellation.check();
                    Read read = store.read(key, cancellation).join();
                    if (read.state() == ReadState.ABSENT) {
                        return new Open(ReadState.ABSENT, null, null);
                    }
                    byte[] bytes = read.bytes();
                    if (read.state() != ReadState.PRESENT || bytes == null) {
                        return new Open(ReadState.CORRUPT, null, read.problem());
                    }
                    try {
                        return new Open(
                                ReadState.PRESENT,
                                new RecordFile(store, key, RecordFormat.decode(bytes)),
                                null);
                    } catch (GeneratedStoreException e) {
                        if (!e.code().equals(GeneratedTypes.CORRUPT)) {
                            throw e;
                        }
                        return new Open(ReadState.CORRUPT, null, e.getMessage());
                    }
                });
    }

    public CompletableFuture<List<Record>> read(long firstOrdinal, int count) {
        return read(firstOrdinal, count, Cancellation.NONE);
    }

    public CompletableFuture<List<Record>> read(
            long firstOrdinal, int count, Cancellation cancellation) {
        return RecordTasks.submit(
                () -> {
                    position(firstOrdinal);
                    cancellation.check();
                    if (count < 0 || count > MAX_PAGE_RECORDS) {
                        throw new IllegalArgumentException("Invalid page size");
                    }
                    int n = (int) Math.min(count, manifest.count() - firstOrdinal);
                    List<Record> result = new ArrayList<>(n);
                    if (n == 0) {
                        return List.of();
                    }
                    for (int b = blockOf(firstOrdinal); result.size() < n; b++) {
                        cancellation.check();
                        Block block = manifest.blocks().get(b);
                        int skip = (int) (firstOrdinal + result.size() - block.first()),
                                take = (int) Math.min(n - result.size(), recordsIn(b) - skip);
                        result.addAll(
                                parseBlock(b, frame(block.frame(), cancellation), skip, take));
                    }
                    return List.copyOf(result);
                });
    }

    public CompletableFuture<Record> find(long id) {
        return find(id, Cancellation.NONE);
    }

    public CompletableFuture<Record> find(long id, Cancellation cancellation) {
        return lookup(id, false, cancellation);
    }

    public CompletableFuture<Record> findAtOrAfter(long id) {
        return findAtOrAfter(id, Cancellation.NONE);
    }

    public CompletableFuture<Record> findAtOrAfter(long id, Cancellation cancellation) {
        return lookup(id, true, cancellation);
    }

    private CompletableFuture<Record> lookup(long id, boolean ceiling, Cancellation cancellation) {
        return RecordTasks.submit(
                () -> {
                    RecordFormat.id(id);
                    cancellation.check();
                    if (manifest.chunks().isEmpty()) {
                        return null;
                    }
                    int lo = 0, hi = manifest.chunks().size() - 1, c = ceiling ? 0 : -1;
                    while (lo <= hi) {
                        int mid = (lo + hi) >>> 1;
                        if (manifest.chunks().get(mid).firstId() <= id) {
                            c = mid;
                            lo = mid + 1;
                        } else {
                            hi = mid - 1;
                        }
                    }
                    if (c < 0) {
                        return null;
                    }
                    long[] found = findOrdinal(c, id, cancellation);
                    if (found == null && ceiling && c + 1 < manifest.chunks().size()) {
                        found =
                                findOrdinal(
                                        c + 1,
                                        manifest.chunks().get(c + 1).firstId(),
                                        cancellation);
                    }
                    if (found == null || (!ceiling && found[1] != id)) {
                        return null;
                    }
                    int b = blockOf(found[0]);
                    Record record =
                            parseBlock(
                                            b,
                                            frame(manifest.blocks().get(b).frame(), cancellation),
                                            (int) (found[0] - manifest.blocks().get(b).first()),
                                            1)
                                    .getFirst();
                    if (record.id() != found[1]) {
                        throw RecordFormat.corrupt("Id index points to a different record");
                    }
                    return record;
                });
    }

    /** Sequential asynchronous cursor. Each next() returns one block; an empty batch means EOF. */
    public Cursor readFrom(long firstOrdinal, Cancellation cancellation) {
        position(firstOrdinal);
        return new Cursor(firstOrdinal, cancellation);
    }

    public Cursor readAll() {
        return readFrom(0, Cancellation.NONE);
    }

    public final class Cursor implements AutoCloseable {
        private final Cancellation cancellation;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile boolean closed;
        private int block, skip, runEnd;
        private byte[] run;
        private int runOffset;

        private Cursor(long first, Cancellation cancellation) {
            this.cancellation = Objects.requireNonNull(cancellation);
            block = first == count() ? manifest.blocks().size() : blockOf(first);
            skip =
                    block < manifest.blocks().size()
                            ? (int) (first - manifest.blocks().get(block).first())
                            : 0;
        }

        public CompletableFuture<List<Record>> next() {
            if (!busy.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Cursor already in use"));
            }
            CompletableFuture<List<Record>> result =
                    RecordTasks.submit(
                            () -> {
                                try {
                                    if (closed) {
                                        throw new IllegalStateException("Cursor closed");
                                    }
                                    cancellation.check();
                                    if (block == manifest.blocks().size()) {
                                        run = null;
                                        return List.of();
                                    }
                                    Frame first = manifest.blocks().get(block).frame();
                                    if (run == null || block >= runEnd) {
                                        runEnd = block + 1;
                                        long size = first.compressed();
                                        while (runEnd < manifest.blocks().size()) {
                                            Frame
                                                    previous =
                                                            manifest.blocks()
                                                                    .get(runEnd - 1)
                                                                    .frame(),
                                                    next = manifest.blocks().get(runEnd).frame();
                                            if (next.segment() != first.segment()
                                                    || next.offset()
                                                            != (long) previous.offset()
                                                                    + previous.compressed()
                                                    || size + next.compressed() > RUN_BYTES) {
                                                break;
                                            }
                                            size += next.compressed();
                                            runEnd++;
                                        }
                                        run =
                                                segment(
                                                        first.segment(),
                                                        first.offset(),
                                                        (int) size,
                                                        cancellation);
                                        runOffset = first.offset();
                                    }
                                    byte[] compressed =
                                            Arrays.copyOfRange(
                                                    run,
                                                    first.offset() - runOffset,
                                                    first.offset()
                                                            - runOffset
                                                            + first.compressed());
                                    List<Record> records =
                                            parseBlock(
                                                    block,
                                                    RecordFormat.decompress(
                                                            compressed, first.length()),
                                                    skip,
                                                    (int) (recordsIn(block) - skip));
                                    block++;
                                    skip = 0;
                                    if (block == runEnd) {
                                        run = null;
                                    }
                                    return records;
                                } finally {
                                    if (closed) {
                                        run = null;
                                    }
                                }
                            });
            return result.whenComplete(
                    (value, error) -> {
                        if (closed) {
                            run = null;
                        }
                        busy.set(false);
                    });
        }

        @Override
        public void close() {
            closed = true;
            if (!busy.get()) {
                run = null;
            }
        }
    }

    private void position(long first) {
        if (first < 0 || first > count()) {
            throw new IllegalArgumentException("Ordinal outside file");
        }
    }

    private int blockOf(long ordinal) {
        int lo = 0, hi = manifest.blocks().size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (manifest.blocks().get(mid).first() <= ordinal) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private long recordsIn(int b) {
        return (b + 1 < manifest.blocks().size() ? manifest.blocks().get(b + 1).first() : count())
                - manifest.blocks().get(b).first();
    }

    private List<Record> parseBlock(int b, byte[] body, int skip, int take) {
        long n = recordsIn(b);
        if (n > body.length / 2 || skip < 0 || take < 0 || (long) skip + take > n) {
            throw RecordFormat.corrupt("Impossible block record count");
        }
        ByteBuffer in = RecordFormat.buffer(body);
        List<Record> result = new ArrayList<>(take);
        for (int i = 0; i < n; i++) {
            long id = RecordFormat.read32(in), length = RecordFormat.read32(in);
            if (length > in.remaining()) {
                throw RecordFormat.corrupt("Record extends beyond block");
            }
            if (i >= skip && i < skip + take) {
                byte[] bytes = new byte[(int) length];
                in.get(bytes);
                result.add(new Record(manifest.blocks().get(b).first() + i, id, bytes));
            } else {
                in.position(in.position() + (int) length);
            }
        }
        if (in.hasRemaining()) {
            throw RecordFormat.corrupt("Trailing block bytes");
        }
        return List.copyOf(result);
    }

    private long[] findOrdinal(int c, long id, Cancellation cancellation) {
        Chunk chunk = manifest.chunks().get(c);
        ByteBuffer body = RecordFormat.buffer(frame(chunk.frame(), cancellation));
        long limit =
                c + 1 < manifest.chunks().size()
                        ? manifest.chunks().get(c + 1).firstId()
                        : 0x1_0000_0000L;
        long current = chunk.firstId(), ordinal = 0;
        long[] found = null;
        for (int i = 0; i < chunk.entries(); i++) {
            long delta = RecordFormat.read32(body);
            if ((i == 0) != (delta == 0)) {
                throw RecordFormat.corrupt("Id chunk is not strictly sorted");
            }
            current += delta;
            long encoded = RecordFormat.readVar(body), change = (encoded >>> 1) ^ -(encoded & 1);
            try {
                ordinal = Math.addExact(ordinal, change);
            } catch (ArithmeticException e) {
                throw RecordFormat.corrupt("Ordinal overflow");
            }
            if (current >= limit || ordinal < 0 || ordinal >= count()) {
                throw RecordFormat.corrupt("Id chunk overlaps or points outside file");
            }
            if (found == null && current >= id) {
                found = new long[] {ordinal, current};
            }
        }
        if (body.hasRemaining()) {
            throw RecordFormat.corrupt("Trailing id chunk bytes");
        }
        return found;
    }

    private byte[] frame(Frame frame, Cancellation cancellation) {
        return RecordFormat.decompress(
                segment(frame.segment(), frame.offset(), frame.compressed(), cancellation),
                frame.length());
    }

    private byte[] segment(int index, int offset, int length, Cancellation cancellation) {
        Segment segment = manifest.segments().get(index);
        Read read;
        try {
            read = store.readRange(segment.key(), offset, length, cancellation).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                throw RecordFormat.corrupt("Segment shorter than directory");
            }
            throw e;
        }
        byte[] bytes = read.bytes();
        if (read.state() != ReadState.PRESENT
                || read.length() != segment.length()
                || bytes == null
                || bytes.length != length) {
            throw RecordFormat.corrupt("Segment missing, corrupt or has a different length");
        }
        return bytes;
    }
}
