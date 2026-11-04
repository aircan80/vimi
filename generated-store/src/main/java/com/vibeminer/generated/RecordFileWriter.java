package com.vibeminer.generated;

import static com.vibeminer.generated.RecordFormat.CHUNK_ENTRIES;

import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.RecordFormat.Block;
import com.vibeminer.generated.RecordFormat.Chunk;
import com.vibeminer.generated.RecordFormat.Frame;
import com.vibeminer.generated.RecordFormat.Manifest;
import com.vibeminer.generated.RecordFormat.Segment;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One writer, one call at a time. Segments commit first; the manifest is the final commit point.
 */
public final class RecordFileWriter implements AutoCloseable {
    private final GeneratedStore store;
    private final RecordFileOptions options;
    private final ByteArrayOutputStream block = new ByteArrayOutputStream(),
            segment = new ByteArrayOutputStream();
    private final List<Segment> segments = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private int[] ids = new int[256];
    private Set<Integer> seen;
    private int count, blockFirst;
    private long uncompressed, compressed, idBytes;
    private final AtomicInteger state = new AtomicInteger(); // 0 open, 1 busy, 2 finished

    public RecordFileWriter(GeneratedStore store) {
        this(store, new RecordFileOptions());
    }

    public RecordFileWriter(GeneratedStore store, RecordFileOptions options) {
        this.store = Objects.requireNonNull(store);
        this.options = Objects.requireNonNull(options);
    }

    public CompletableFuture<Void> add(long id, byte[] record) {
        return add(id, record, Cancellation.NONE);
    }

    public CompletableFuture<Void> add(long id, byte[] record, Cancellation cancellation) {
        return addAll(new long[] {id}, new byte[][] {record}, 1, cancellation);
    }

    /**
     * Appends {@code ids[0..count)} with their records in one worker call, producing exactly the
     * bytes and outcomes of the same {@link #add} calls made in order. The arrays may be reused
     * once this returns. A rejected record (bad id, null, size or count bound) or a duplicate id
     * fails the call after the records before it are appended and leaves the writer usable, as the
     * individual {@code add} for that record would have; other failures finish the writer.
     */
    public CompletableFuture<Void> addAll(
            long[] ids, byte[][] records, int count, Cancellation cancellation) {
        Objects.checkFromIndexSize(0, count, ids.length);
        Objects.checkFromIndexSize(0, count, records.length);
        long[] ownedIds = Arrays.copyOf(ids, count);
        byte[][] owned = new byte[count][];
        RuntimeException[] rejected = new RuntimeException[1];
        return operation(
                () -> {
                    cancellation.check();
                    for (int i = 0; i < count; i++) {
                        try {
                            RecordFormat.id(ownedIds[i]);
                            Objects.requireNonNull(records[i]);
                            if (this.count + i >= options.maxRecords()
                                    || records[i].length > options.maxRecordBytes()) {
                                throw RecordFormat.limit(
                                        "Record count or size exceeds configured bound");
                            }
                        } catch (RuntimeException e) {
                            if (i == 0) {
                                throw e;
                            }
                            // Records before it are still appended, as by individual adds.
                            rejected[0] = e;
                            break;
                        }
                        owned[i] = records[i].clone();
                    }
                },
                () -> {
                    // Validation failures leave the writer usable; failures after mutation finish
                    // it.
                    for (int i = 0; i < count && owned[i] != null; i++) {
                        if (i > 0) {
                            try {
                                // Where the next individual add would have checked admission.
                                cancellation.check();
                            } catch (RuntimeException e) {
                                throw new Rejected(e);
                            }
                        }
                        append(ownedIds[i], owned[i], cancellation);
                    }
                    if (rejected[0] != null) {
                        throw new Rejected(rejected[0]);
                    }
                    return null;
                },
                false,
                cancellation);
    }

    private void append(long id, byte[] record, Cancellation cancellation) {
        if (duplicate((int) id)) {
            throw new Rejected(new IllegalArgumentException("Duplicate record id"));
        }
        cancellation.check();
        if (block.size() > 0 && record.length >= options.blockBytes()) {
            closeBlock(cancellation);
        }
        if (count == ids.length) {
            ids = Arrays.copyOf(ids, Math.min(ids.length * 2, options.maxRecords()));
        }
        ids[count++] = (int) id;
        if (seen != null) {
            seen.add((int) id);
        }
        RecordFormat.writeVar(block, id);
        RecordFormat.writeVar(block, record.length);
        block.writeBytes(record);
        if (block.size() >= options.blockBytes()) {
            closeBlock(cancellation);
        }
    }

    public CompletableFuture<RecordFileInfo> complete(GeneratedKey key, byte[] metadata) {
        return complete(key, metadata, Cancellation.NONE);
    }

    public CompletableFuture<RecordFileInfo> complete(
            GeneratedKey key, byte[] metadata, Cancellation cancellation) {
        byte[][] owned = new byte[1][];
        return operation(
                () -> {
                    cancellation.check();
                    Objects.requireNonNull(key);
                    Objects.requireNonNull(metadata);
                    if (metadata.length > options.maxMetadataBytes()) {
                        throw RecordFormat.limit("Metadata exceeds configured bound");
                    }
                    owned[0] = metadata.clone();
                },
                () -> {
                    if (block.size() > 0) {
                        closeBlock(cancellation);
                    }
                    writeIndex(cancellation);
                    flushSegment(cancellation);
                    byte[] manifest =
                            RecordFormat.encode(
                                    new Manifest(count, segments, blocks, chunks, owned[0]),
                                    options.compressionLevel());
                    cancellation.check();
                    PutOutcome outcome =
                            store.put(List.of(new Put(key, manifest)), cancellation)
                                    .join()
                                    .getFirst();
                    return new RecordFileInfo(
                            key,
                            count,
                            uncompressed,
                            compressed,
                            idBytes,
                            manifest.length,
                            blocks.size(),
                            chunks.size(),
                            segments.size(),
                            outcome == PutOutcome.ALREADY_PRESENT);
                },
                true,
                cancellation);
    }

    private <T> CompletableFuture<T> operation(
            Runnable validate, Supplier<T> action, boolean finish, Cancellation cancellation) {
        if (!state.compareAndSet(0, 1)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Writer is busy, finished or failed"));
        }
        try {
            validate.run();
        } catch (RuntimeException e) {
            state.compareAndSet(1, 0);
            return CompletableFuture.failedFuture(e);
        }
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<T> result =
                RecordTasks.submit(
                        () -> {
                            started.set(true);
                            try {
                                cancellation.check();
                            } catch (RuntimeException e) {
                                state.compareAndSet(1, 0);
                                throw e;
                            }
                            try {
                                T value = action.get();
                                if (finish) {
                                    state.set(2);
                                } else {
                                    state.compareAndSet(1, 0);
                                }
                                return value;
                            } catch (Rejected e) {
                                state.compareAndSet(1, 0);
                                throw e.reason;
                            } catch (Throwable e) {
                                state.set(2);
                                throw e;
                            }
                        });
        // Admission refusal happens before mutation and permits retry.
        return result.whenComplete(
                (value, failure) -> {
                    if (failure != null && !started.get()) {
                        state.compareAndSet(1, 0);
                    }
                });
    }

    /** A record refused before it changed the writer; the writer stays usable. */
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient RuntimeException reason;

        Rejected(RuntimeException reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    private boolean duplicate(int id) {
        if (seen == null) {
            if (count == 0 || Integer.compareUnsigned(id, ids[count - 1]) > 0) {
                return false;
            }
            seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                seen.add(ids[i]);
            }
        }
        return seen.contains(id);
    }

    private void closeBlock(Cancellation cancellation) {
        int length = block.size();
        Frame frame = append(block.toByteArray(), cancellation);
        blocks.add(new Block(frame, blockFirst));
        uncompressed += length;
        compressed += frame.compressed();
        blockFirst = count;
        block.reset();
    }

    private Frame append(byte[] body, Cancellation cancellation) {
        cancellation.check();
        byte[] frame = RecordFormat.compress(body, options.compressionLevel());
        if (segment.size() > 0 && (long) segment.size() + frame.length > options.segmentBytes()) {
            flushSegment(cancellation);
        }
        Frame ref = new Frame(segments.size(), segment.size(), frame.length, body.length);
        segment.writeBytes(frame);
        return ref;
    }

    private void flushSegment(Cancellation cancellation) {
        if (segment.size() == 0) {
            return;
        }
        byte[] bytes = segment.toByteArray();
        GeneratedKey key = GeneratedKey.hash(bytes);
        store.put(List.of(new Put(key, bytes)), cancellation).join();
        segments.add(new Segment(key, bytes.length));
        segment.reset();
    }

    private void writeIndex(Cancellation cancellation) {
        long[] pairs = new long[count];
        // Flip the sign bit so signed sort has unsigned (id,ordinal) order.
        for (int i = 0; i < count; i++) {
            pairs[i] = (((long) ids[i] << 32) | Integer.toUnsignedLong(i)) ^ Long.MIN_VALUE;
        }
        ids = new int[0];
        seen = null;
        Arrays.sort(pairs);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        for (int start = 0; start < count; start += CHUNK_ENTRIES) {
            cancellation.check();
            chunk.reset();
            int n = Math.min(CHUNK_ENTRIES, count - start);
            long first = (pairs[start] ^ Long.MIN_VALUE) >>> 32,
                    previous = first,
                    previousOrdinal = 0;
            for (int i = start; i < start + n; i++) {
                long pair = pairs[i] ^ Long.MIN_VALUE,
                        id = pair >>> 32,
                        ordinal = pair & 0xffff_ffffL,
                        delta = ordinal - previousOrdinal;
                RecordFormat.writeVar(chunk, id - previous);
                RecordFormat.writeVar(chunk, (delta << 1) ^ (delta >> 63));
                previous = id;
                previousOrdinal = ordinal;
            }
            Frame frame = append(chunk.toByteArray(), cancellation);
            chunks.add(new Chunk(frame, n, first));
            idBytes += frame.compressed();
        }
    }

    @Override
    public void close() {
        state.set(2);
    }
}
