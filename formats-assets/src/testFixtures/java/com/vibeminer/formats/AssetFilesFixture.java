package com.vibeminer.formats;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileHoleException;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureProgress;
import com.vibeminer.files.FileTypes.EnsureResult;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileSegment;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.files.FileTypes.FileStorage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Independent deterministic decoded-files boundary fixture; no production Files reader is invoked.
 */
public class AssetFilesFixture implements BuildFiles {
    private record Entry(
            BuildFile file, byte[] bytes, List<FileHole> holes, List<String> chunkKeys) {}

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Long, FileOpen> failures = new ConcurrentHashMap<>();
    private final Map<Long, Throwable> readFailures = new ConcurrentHashMap<>();
    private final Map<Long, RuntimeException> statFailures = new ConcurrentHashMap<>();
    private final BuildRef build;
    private final String locale;
    private boolean closed;
    public final AtomicInteger stats = new AtomicInteger(),
            opens = new AtomicInteger(),
            ranges = new AtomicInteger(),
            reads = new AtomicInteger(),
            streamCloses = new AtomicInteger(),
            bytesRead = new AtomicInteger();

    public AssetFilesFixture() {
        this(new BuildRef("wow", "1".repeat(32), "2".repeat(32), "us"), "enUS");
    }

    public AssetFilesFixture(BuildRef build, String locale) {
        this.build = build;
        this.locale = locale;
    }

    public AssetFilesFixture add(long id, String content, byte[] bytes) {
        return add(
                new BuildFile(
                        id,
                        null,
                        FilePathSource.NONE,
                        (long) bytes.length,
                        content,
                        FileFormat.UNKNOWN,
                        FileFormatSource.NONE,
                        FileStorage.STORED,
                        false),
                bytes,
                List.of(),
                List.of());
    }

    public AssetFilesFixture add(
            BuildFile file, byte[] bytes, List<FileHole> holes, List<String> chunkKeys) {
        entries.put(
                file.fileDataId(),
                new Entry(file, bytes.clone(), List.copyOf(holes), List.copyOf(chunkKeys)));
        return this;
    }

    public void failOpen(long id, FileOpen failure) {
        failures.put(id, failure);
    }

    public void failRead(long id, Throwable failure) {
        readFailures.put(id, failure);
    }

    public void failStat(long id, RuntimeException failure) {
        statFailures.put(id, failure);
    }

    public BuildRef build() {
        return build;
    }

    public String locale() {
        return locale;
    }

    private void check(Cancellation token) {
        token.check();
        if (closed) {
            throw new IllegalStateException("FILES_CLOSED");
        }
    }

    private Entry entry(FileRef file) {
        if (file.fileDataId() != null) {
            return entries.get(file.fileDataId());
        }
        return entries.values().stream()
                .filter(e -> file.path().equals(e.file.path()))
                .findFirst()
                .orElse(null);
    }

    public CompletableFuture<FileStat> stat(FileRef file, Cancellation token) {
        try {
            check(token);
            stats.incrementAndGet();
            if (file.fileDataId() != null && statFailures.containsKey(file.fileDataId())) {
                throw statFailures.get(file.fileDataId());
            }
            Entry e = entry(file);
            return CompletableFuture.completedFuture(
                    e == null
                            ? new FileStat(FileLookupState.UNKNOWN_FILE, null)
                            : new FileStat(
                                    e.file.storage() == FileStorage.NO_ENCODED_COPY
                                            ? FileLookupState.NOT_IN_ENCODING
                                            : FileLookupState.FOUND,
                                    e.file));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Boolean> exists(FileRef file, Cancellation token) {
        return stat(file, token).thenApply(s -> s.state() == FileLookupState.FOUND);
    }

    public CompletableFuture<FileRead> get(FileRef file, Cancellation token) {
        Entry e = entry(file);
        return e == null
                ? CompletableFuture.completedFuture(
                        FileRead.failed(FileReadState.UNKNOWN_FILE, 0, null, null, null))
                : readRange(file, 0, e.bytes.length, token);
    }

    public CompletableFuture<FileRead> readRange(
            FileRef file, long offset, int count, Cancellation token) {
        try {
            check(token);
            ranges.incrementAndGet();
            Entry e = entry(file);
            if (e == null) {
                return CompletableFuture.completedFuture(
                        FileRead.failed(FileReadState.UNKNOWN_FILE, offset, null, null, null));
            }
            if (readFailures.containsKey(e.file.fileDataId())) {
                return CompletableFuture.failedFuture(readFailures.get(e.file.fileDataId()));
            }
            if (offset < 0 || count < 0 || offset > e.bytes.length - count) {
                throw new IllegalArgumentException("Range outside source");
            }
            List<FileHole> holes =
                    e.holes.stream()
                            .filter(
                                    h ->
                                            h.offset() < offset + count
                                                    && offset < h.offset() + h.length())
                            .map(
                                    h ->
                                            new FileHole(
                                                    Math.max(offset, h.offset()),
                                                    Math.min(
                                                                    offset + count,
                                                                    h.offset() + h.length())
                                                            - Math.max(offset, h.offset()),
                                                    h.keyName()))
                            .toList();
            if (holes.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new FileRead(
                                FileReadState.COMPLETE,
                                offset,
                                count,
                                List.of(
                                        new FileSegment(
                                                offset,
                                                Arrays.copyOfRange(
                                                        e.bytes,
                                                        (int) offset,
                                                        (int) offset + count))),
                                List.of(),
                                null,
                                null,
                                null));
            }
            List<FileSegment> segments = new ArrayList<>();
            long at = offset;
            for (FileHole hole : holes) {
                if (at < hole.offset()) {
                    segments.add(
                            new FileSegment(
                                    at,
                                    Arrays.copyOfRange(e.bytes, (int) at, (int) hole.offset())));
                }
                at = hole.offset() + hole.length();
            }
            if (at < offset + count) {
                segments.add(
                        new FileSegment(
                                at, Arrays.copyOfRange(e.bytes, (int) at, (int) offset + count)));
            }
            return CompletableFuture.completedFuture(
                    new FileRead(
                            segments.isEmpty() ? FileReadState.PROTECTED : FileReadState.PARTIAL,
                            offset,
                            count,
                            segments,
                            holes,
                            null,
                            null,
                            null));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<FileOpen> openRead(FileRef file, Cancellation token) {
        try {
            check(token);
            opens.incrementAndGet();
            Entry e = entry(file);
            if (e == null) {
                return CompletableFuture.completedFuture(
                        new FileOpen(FileReadState.UNKNOWN_FILE, null, null, null, null));
            }
            FileOpen failure = failures.get(e.file.fileDataId());
            if (failure != null) {
                return CompletableFuture.completedFuture(failure);
            }
            FileReadStream stream =
                    new FileReadStream() {
                        long position;
                        boolean streamClosed;

                        public long length() {
                            return e.bytes.length;
                        }

                        public long position() {
                            return position;
                        }

                        public void seek(long at) {
                            if (at < 0) {
                                throw new IllegalArgumentException();
                            }
                            position = at;
                        }

                        public List<FileHole> holes() {
                            return e.holes;
                        }

                        public List<String> chunkKeys() {
                            return e.chunkKeys;
                        }

                        public CompletableFuture<Integer> read(
                                ByteBuffer destination, Cancellation cancel) {
                            try {
                                cancel.check();
                                if (streamClosed) {
                                    throw new IllegalStateException("STREAM_CLOSED");
                                }
                                reads.incrementAndGet();
                                Throwable failed = readFailures.get(e.file.fileDataId());
                                if (failed != null) {
                                    return CompletableFuture.failedFuture(failed);
                                }
                                if (!destination.hasRemaining()) {
                                    return CompletableFuture.completedFuture(0);
                                }
                                if (position >= e.bytes.length) {
                                    return CompletableFuture.completedFuture(-1);
                                }
                                long end = e.bytes.length;
                                for (FileHole hole : e.holes) {
                                    if (hole.offset() <= position
                                            && position < hole.offset() + hole.length()) {
                                        return CompletableFuture.failedFuture(
                                                new FileHoleException(hole));
                                    }
                                    if (hole.offset() > position) {
                                        end = Math.min(end, hole.offset());
                                    }
                                }
                                int count = (int) Math.min(destination.remaining(), end - position);
                                destination.put(e.bytes, (int) position, count);
                                position += count;
                                bytesRead.addAndGet(count);
                                return CompletableFuture.completedFuture(count);
                            } catch (RuntimeException error) {
                                return CompletableFuture.failedFuture(error);
                            }
                        }

                        public void close() {
                            if (!streamClosed) {
                                streamClosed = true;
                                streamCloses.incrementAndGet();
                            }
                        }
                    };
            return CompletableFuture.completedFuture(
                    new FileOpen(
                            e.holes.isEmpty() ? FileReadState.COMPLETE : FileReadState.PARTIAL,
                            stream,
                            null,
                            null,
                            null));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public AsyncCursor<BuildFile> list(FileFilter filter, Cancellation token) {
        throw new UnsupportedOperationException("Asset consumers do not enumerate files");
    }

    public CompletableFuture<EnsureResult> ensure(
            EnsureSelection selection,
            EnsurePriority priority,
            Consumer<EnsureProgress> progress,
            Cancellation token) {
        throw new UnsupportedOperationException("Asset consumers do not ensure files");
    }

    public void close() {
        closed = true;
    }
}
