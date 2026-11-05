package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileSegment;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * All blocking joins run only on dedicated virtual operation threads, never lower layer callbacks.
 */
final class FileReader {
    static final int MAX_RANGE = 4 << 20;

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final FileReadState state;
        final String code;
        final Instant at;

        Failure(FileReadState state, String code, String message, Instant at) {
            super(message);
            this.state = state;
            this.code = code;
            this.at = at;
        }
    }

    static final class LayoutCache {
        private final LinkedHashMap<CdnKey, BlteChunks.Layout> values =
                new LinkedHashMap<>(16, .75f, true);
        private long chunks;

        synchronized BlteChunks.Layout find(CdnKey key) {
            return values.get(key);
        }

        synchronized void put(CdnKey key, BlteChunks.Layout layout) {
            var old = values.put(key, layout);
            if (old != null) {
                chunks -= old.chunks().size();
            }
            chunks += layout.chunks().size();
            while (values.size() > 1 && (values.size() > 64 || chunks > 262144)) {
                var entry = values.entrySet().iterator();
                var first = entry.next();
                chunks -= first.getValue().chunks().size();
                entry.remove();
            }
        }
    }

    final long size;
    final CdnKey ekey, ckey;
    final ObjectRequest request;
    final TactKeySource keys;
    final long max;
    private final ObjectSource objects;
    private final CdnContext cdn;
    private final String unrouted;
    private final LayoutCache cache;
    private final Runnable decoded;

    FileReader(
            ObjectSource objects,
            FileVariant variant,
            CdnContext cdn,
            TactKeySource keys,
            LayoutCache cache,
            long max,
            Runnable decoded) {
        this(objects, variant, cdn, null, keys, cache, max, decoded);
    }

    /** {@code unrouted} names why a build has no CDN route, replacing the generic message. */
    FileReader(
            ObjectSource objects,
            FileVariant variant,
            CdnContext cdn,
            String unrouted,
            TactKeySource keys,
            LayoutCache cache,
            long max,
            Runnable decoded) {
        this.objects = objects;
        this.cdn = cdn;
        this.unrouted = unrouted;
        this.keys = keys;
        this.cache = cache;
        this.max = max;
        this.decoded = decoded;
        ekey = variant.encodedKey();
        ckey = variant.contentKey();
        size = variant.decodedSize();
        request = requestOf(variant);
    }

    static ObjectKey keyOf(FileVariant variant) {
        return new ObjectKey(ObjectKey.ENCODED, variant.encodedKey().bytes());
    }

    static ObjectRequest requestOf(FileVariant variant) {
        return new ObjectRequest(
                keyOf(variant),
                variant.encodedSize(),
                variant.location() == null ? List.of() : List.of(variant.location()),
                0);
    }

    BlteChunks.Layout layout(Cancellation token) {
        var existing = cache.find(ekey);
        if (existing != null) {
            return checked(existing);
        }
        if (request.declaredLength() != null && request.declaredLength() < 8) {
            throw BlteChunks.invalid("Encoded copy shorter than BLTE prefix");
        }
        ObjectResult got;
        try {
            got = range(0, 8, token);
        } catch (IllegalArgumentException failure) {
            if (request.declaredLength() == null) {
                throw BlteChunks.invalid("Encoded copy shorter than BLTE prefix");
            }
            throw failure;
        }
        Long length = got.length() != null ? got.length() : request.declaredLength();
        if (length == null) {
            throw new Failure(
                    FileReadState.CORRUPT,
                    "FILES_ENCODED_LENGTH_UNKNOWN",
                    "Encoded length unknown",
                    null);
        }
        byte[] prefix = got.bytes();
        int n = BlteChunks.headerLength(prefix, length);
        byte[] header = n == 0 ? prefix : new byte[n];
        if (n > 0) {
            System.arraycopy(prefix, 0, header, 0, 8);
            byte[] tail = encoded(8, n - 8, token);
            System.arraycopy(tail, 0, header, 8, tail.length);
        }
        var layout = BlteChunks.parse(header, ekey, length, size);
        cache.put(ekey, layout);
        return checked(layout);
    }

    private BlteChunks.Layout checked(BlteChunks.Layout layout) {
        if (layout.decodedLength() != size
                || request.declaredLength() != null
                        && layout.encodedLength() != request.declaredLength()) {
            throw BlteChunks.invalid("Cached layout differs from declared lengths");
        }
        return layout;
    }

    private ObjectResult range(long offset, int length, Cancellation token) {
        token.check();
        var got =
                objects.getRange(request, offset, length, cdn, FetchPriority.INTERACTIVE, token)
                        .join();
        if (!got.isPresent()) {
            throw new Failure(
                    switch (got.state()) {
                        case NOT_FOUND -> FileReadState.MISSING;
                        case NEEDS_NETWORK -> FileReadState.NEEDS_NETWORK;
                        case UNAVAILABLE -> FileReadState.UNAVAILABLE;
                        case STORE_FAILED -> FileReadState.STORE_FAILED;
                        case REFUSED -> FileReadState.CORRUPT;
                        case UNSUPPORTED -> FileReadState.UNSUPPORTED;
                        default -> throw new IllegalStateException("Present without bytes");
                    },
                    got.code(),
                    cdn == null
                                    && unrouted != null
                                    && "OBJECT_ACCESS_NO_CDN_CONTEXT".equals(got.code())
                            ? unrouted
                            : got.message(),
                    got.observedAt());
        }
        if (got.bytes() == null || got.bytes().length != length) {
            throw BlteChunks.invalid("Layer two range length differs");
        }
        return got;
    }

    byte[] encoded(long offset, int length, Cancellation token) {
        byte[] answer = new byte[length];
        for (int at = 0; at < length; ) {
            int n = Math.min(MAX_RANGE, length - at);
            byte[] piece = range(offset + at, n, token).bytes();
            System.arraycopy(piece, 0, answer, at, n);
            at += n;
        }
        return answer;
    }

    void checkChunk(BlteChunks.Layout layout, BlteChunks.Chunk chunk) {
        if (layout.headerless() && (chunk.decodedSize() > max || chunk.encodedSize() > max)) {
            throw new Failure(
                    FileReadState.TOO_LARGE,
                    "FILES_CHUNK_TOO_LARGE",
                    "Headerless chunk exceeds read limit",
                    null);
        }
    }

    BlteChunks.Decoded decode(BlteChunks.Layout layout, BlteChunks.Chunk chunk, byte[] encoded) {
        decoded.run();
        return BlteChunks.decode(layout, chunk, encoded, ekey, keys);
    }

    FileRead read(long offset, int length, Cancellation token) {
        var layout = layout(token);
        long end = offset + length;
        int first = layout.indexAt(offset), last = layout.indexAt(end - 1);
        for (int i = first; i <= last; i++) {
            checkChunk(layout, layout.chunks().get(i));
        }
        List<FileSegment> segments = new ArrayList<>();
        List<FileHole> holes = new ArrayList<>();
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        long segmentAt = offset;
        for (int i = first; i <= last; ) {
            token.check();
            int runEnd = i, runBytes = layout.chunks().get(i).encodedSize();
            while (runEnd < last
                    && runBytes + layout.chunks().get(runEnd + 1).encodedSize() <= MAX_RANGE) {
                runBytes += layout.chunks().get(++runEnd).encodedSize();
            }
            byte[] run = encoded(layout.chunks().get(i).encodedOffset(), runBytes, token);
            int at = 0;
            try {
                for (int c = i; c <= runEnd; c++) {
                    var chunk = layout.chunks().get(c);
                    byte[] bytes = Arrays.copyOfRange(run, at, at + chunk.encodedSize());
                    at += chunk.encodedSize();
                    if (chunk.decodedSize() == 0) {
                        continue;
                    }
                    var result = decode(layout, chunk, bytes);
                    long start = Math.max(offset, chunk.decodedOffset()),
                            stop = Math.min(end, chunk.decodedOffset() + chunk.decodedSize());
                    if (result.hole() != null) {
                        if (segment.size() > 0) {
                            segments.add(new FileSegment(segmentAt, segment.toByteArray()));
                            segment.reset();
                        }
                        mergeHole(holes, start, stop - start, result.hole());
                    } else {
                        if (segment.size() == 0) {
                            segmentAt = start;
                        }
                        segment.write(
                                result.bytes(),
                                (int) (start - chunk.decodedOffset()),
                                (int) (stop - start));
                        Arrays.fill(result.bytes(), (byte) 0);
                    }
                }
            } finally {
                Arrays.fill(run, (byte) 0);
            }
            i = runEnd + 1;
        }
        if (segment.size() > 0) {
            segments.add(new FileSegment(segmentAt, segment.toByteArray()));
        }
        return new FileRead(
                holes.isEmpty()
                        ? FileReadState.COMPLETE
                        : segments.isEmpty() ? FileReadState.PROTECTED : FileReadState.PARTIAL,
                offset,
                length,
                segments,
                holes,
                null,
                null,
                null);
    }

    static void mergeHole(List<FileHole> holes, long at, long length, String key) {
        if (length == 0) {
            return;
        }
        if (!holes.isEmpty()) {
            var old = holes.getLast();
            if (old.offset() + old.length() == at && old.keyName().equals(key)) {
                holes.set(holes.size() - 1, new FileHole(old.offset(), old.length() + length, key));
                return;
            }
        }
        holes.add(new FileHole(at, length, key));
    }

    boolean matches(byte[] bytes) {
        return java.security.MessageDigest.isEqual(BlteChunks.md5().digest(bytes), ckey.bytes());
    }
}
