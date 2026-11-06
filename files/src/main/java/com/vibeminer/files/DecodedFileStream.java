package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FileReadState;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps only the last decoded chunk; envelopes are scanned without decoding at open. When the open
 * scan fetched the whole encoded body in one run (small chunks, at most {@link #RETAIN} bytes), the
 * stream keeps that run and decodes later reads from it instead of fetching the chunks again.
 */
final class DecodedFileStream extends FileReadStream {
    /** Largest encoded run the open scan fetches, and so the most a stream retains. */
    static final int RETAIN = 1 << 20;

    private final FileReader reader;
    private final BlteChunks.Layout layout;
    private final List<FileHole> holes;
    private final List<String> keys;
    private long position;
    private boolean closed;
    private byte[] cached;
    private int cachedIndex = -1;
    // The whole encoded body from encodedOffset of the first chunk, or null.
    private byte[] retained;
    private final AtomicBoolean reading = new AtomicBoolean();

    private DecodedFileStream(
            FileReader reader,
            BlteChunks.Layout layout,
            List<FileHole> holes,
            List<String> keys,
            byte[] retained) {
        this.reader = reader;
        this.layout = layout;
        this.holes = List.copyOf(holes);
        this.keys = List.copyOf(keys);
        this.retained = retained;
    }

    static FileOpen open(FileReader reader, Cancellation token) {
        if (reader.size == 0) {
            return new FileOpen(
                    FileReadState.COMPLETE,
                    new DecodedFileStream(
                            reader,
                            new BlteChunks.Layout(true, 0, 0, 0, List.of()),
                            List.of(),
                            List.of(),
                            null),
                    null,
                    null,
                    null);
        }
        var layout = reader.layout(token);
        List<FileHole> holes = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        byte[] retained = null;
        for (int i = 0; i < layout.chunks().size(); ) {
            var chunk = layout.chunks().get(i);
            reader.checkChunk(layout, chunk);
            int last = i, n = Math.min(chunk.encodedSize(), BlteChunks.ENVELOPE);
            if (chunk.encodedSize() <= 65536) {
                n = chunk.encodedSize();
                while (last + 1 < layout.chunks().size()
                        && layout.chunks().get(last + 1).encodedSize() <= 65536
                        && n + layout.chunks().get(last + 1).encodedSize() <= RETAIN) {
                    n += layout.chunks().get(++last).encodedSize();
                }
            }
            byte[] run = reader.encoded(chunk.encodedOffset(), n, token);
            int at = 0;
            for (int c = i; c <= last; c++) {
                var next = layout.chunks().get(c);
                reader.checkChunk(layout, next);
                if (next.decodedSize() > 0) {
                    Long name =
                            BlteChunks.peek(
                                    Arrays.copyOfRange(
                                            run,
                                            at,
                                            at
                                                    + Math.min(
                                                            next.encodedSize(),
                                                            BlteChunks.ENVELOPE)));
                    if (name != null) {
                        String key = String.format(java.util.Locale.ROOT, "%016X", name);
                        keys.add(key);
                        if (reader.keys.find(name) == null) {
                            FileReader.mergeHole(
                                    holes, next.decodedOffset(), next.decodedSize(), key);
                        }
                    }
                }
                at += next.encodedSize();
            }
            if (i == 0 && last == layout.chunks().size() - 1 && n == run.length) {
                retained = run; // One run covered the whole body; reads reuse it.
            }
            i = last + 1;
        }
        long missing = holes.stream().mapToLong(FileHole::length).sum();
        return new FileOpen(
                missing == 0
                        ? FileReadState.COMPLETE
                        : missing == reader.size ? FileReadState.PROTECTED : FileReadState.PARTIAL,
                new DecodedFileStream(reader, layout, holes, new ArrayList<>(keys), retained),
                null,
                null,
                null);
    }

    private void check() {
        if (closed) {
            throw new IllegalStateException("Stream closed");
        }
    }

    @Override
    public synchronized long length() {
        check();
        return layout.decodedLength();
    }

    @Override
    public synchronized long position() {
        check();
        return position;
    }

    @Override
    public synchronized void seek(long target) {
        check();
        if (target < 0) {
            throw new IllegalArgumentException("Negative seek");
        }
        if (reading.get()) {
            throw new IllegalStateException("Read in progress");
        }
        position = target;
    }

    @Override
    public List<FileHole> holes() {
        return holes;
    }

    @Override
    public List<String> chunkKeys() {
        return keys;
    }

    @Override
    public CompletableFuture<Integer> read(ByteBuffer destination, Cancellation token) {
        synchronized (this) {
            check();
            if (destination.isReadOnly()) {
                throw new IllegalArgumentException("Read-only buffer");
            }
            if (!reading.compareAndSet(false, true)) {
                throw new IllegalStateException("Concurrent stream read");
            }
        }
        CompletableFuture<Integer> answer =
                Io.async(
                        () -> {
                            try {
                                synchronized (this) {
                                    check();
                                    token.check();
                                    if (!destination.hasRemaining()) {
                                        return 0;
                                    }
                                    if (position >= layout.decodedLength()) {
                                        return -1;
                                    }
                                    for (var hole : holes) {
                                        if (position >= hole.offset()
                                                && position - hole.offset() < hole.length()) {
                                            throw new FileHoleException(hole);
                                        }
                                    }
                                    int index = layout.indexAt(position);
                                    var chunk = layout.chunks().get(index);
                                    if (index != cachedIndex) {
                                        byte[] encoded = encoded(chunk, token);
                                        if (cached != null) {
                                            Arrays.fill(cached, (byte) 0);
                                        }
                                        cached = null;
                                        cachedIndex = -1;
                                        var got = reader.decode(layout, chunk, encoded);
                                        if (got.hole() != null) {
                                            throw new FileHoleException(
                                                    new FileHole(
                                                            chunk.decodedOffset(),
                                                            chunk.decodedSize(),
                                                            got.hole()));
                                        }
                                        cached = got.bytes();
                                        cachedIndex = index;
                                    }
                                    int at = (int) (position - chunk.decodedOffset()),
                                            n =
                                                    Math.min(
                                                            destination.remaining(),
                                                            chunk.decodedSize() - at);
                                    destination.put(cached, at, n);
                                    position += n;
                                    return n;
                                }
                            } catch (FileReader.Failure e) {
                                throw new FileReadException(e.state, e.code, e.getMessage(), e.at);
                            } catch (BlteChunks.Failure e) {
                                throw new FileReadException(e.state, e.code, e.getMessage(), null);
                            } finally {
                                reading.set(false);
                            }
                        });
        answer.whenComplete((value, error) -> reading.set(false));
        return answer;
    }

    private byte[] encoded(BlteChunks.Chunk chunk, Cancellation token) {
        if (retained == null) {
            return reader.encoded(chunk.encodedOffset(), chunk.encodedSize(), token);
        }
        int from =
                Math.toIntExact(chunk.encodedOffset() - layout.chunks().getFirst().encodedOffset());
        return Arrays.copyOfRange(retained, from, from + chunk.encodedSize());
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (cached != null) {
            Arrays.fill(cached, (byte) 0);
        }
        cached = null;
        cachedIndex = -1;
        if (retained != null) {
            Arrays.fill(retained, (byte) 0);
        }
        retained = null;
    }
}
