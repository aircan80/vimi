package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureProgress;
import com.vibeminer.files.FileTypes.EnsureResult;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.EnsureSelectionKind;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileSegment;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.FileTypes.FilesOpenResult;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.files.names.FileNames;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Independent decoded-files fake: no BLTE, build resolver, source or real handle delegation. */
public final class MemoryFiles implements Files {
    private record Identity(BuildRef build, String locale) {}

    private static final class Entry {
        final BuildFile file;
        final byte[] bytes;
        final List<FileHole> holes;
        volatile boolean stored;
        volatile FileRead failure;
        volatile List<String> chunkKeys;

        Entry(BuildFile file, byte[] bytes, List<FileHole> holes) {
            this.file = file;
            this.bytes = bytes.clone();
            this.holes = List.copyOf(holes);
            stored = file.storage() == FileStorage.STORED;
            chunkKeys = holes.stream().map(FileHole::keyName).distinct().sorted().toList();
        }
    }

    private final Map<Identity, Map<Long, Entry>> builds = new ConcurrentHashMap<>();

    public MemoryFiles add(
            BuildRef build, String locale, BuildFile file, byte[] bytes, List<FileHole> holes) {
        FileLocales.bitOf(locale);
        builds.computeIfAbsent(new Identity(build, locale), ignored -> new ConcurrentHashMap<>())
                .put(file.fileDataId(), new Entry(file, bytes, holes));
        return this;
    }

    /** Script a typed failure for whole/range/open and streams already opened; null clears it. */
    public MemoryFiles failure(BuildRef build, String locale, long id, FileRead failure) {
        Entry entry = builds.get(new Identity(build, locale)).get(id);
        if (failure != null
                && (failure.state() == FileReadState.COMPLETE
                        || failure.state() == FileReadState.PARTIAL
                        || failure.state() == FileReadState.PROTECTED)) {
            throw new IllegalArgumentException("Failure must carry no bytes");
        }
        entry.failure = failure;
        return this;
    }

    /** Encryption provenance also includes known keys, which do not make holes. */
    public MemoryFiles chunkKeys(BuildRef build, String locale, long id, List<String> keys) {
        builds.get(new Identity(build, locale)).get(id).chunkKeys =
                keys.stream().distinct().sorted().toList();
        return this;
    }

    @Override
    public FileNames names() {
        return FileNames.OFF;
    }

    @Override
    public CompletableFuture<FilesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        FileLocales.bitOf(locale);
        token.check();
        var entries = builds.get(new Identity(build, locale));
        if (entries == null) {
            boolean any = builds.keySet().stream().anyMatch(k -> k.build().equals(build));
            return CompletableFuture.completedFuture(
                    new FilesOpenResult(
                            build,
                            locale,
                            any ? FilesOpenState.LOCALE_NOT_IN_BUILD : FilesOpenState.NEEDS_NETWORK,
                            null,
                            null,
                            null,
                            null));
        }
        return CompletableFuture.completedFuture(
                new FilesOpenResult(
                        build,
                        locale,
                        FilesOpenState.OPEN,
                        new Handle(build, locale, entries),
                        null,
                        null,
                        null));
    }

    private static BuildFile current(Entry entry) {
        var f = entry.file;
        return new BuildFile(
                f.fileDataId(),
                f.path(),
                f.pathSource(),
                f.size(),
                f.contentKey(),
                f.format(),
                f.formatSource(),
                f.storage() == FileStorage.NO_ENCODED_COPY
                        ? f.storage()
                        : entry.stored ? FileStorage.STORED : FileStorage.NOT_STORED,
                f.encrypted());
    }

    private static FileRead decoded(Entry entry, long offset, int length) {
        long start = Math.min(offset, entry.bytes.length),
                end = Math.min(entry.bytes.length, start + length);
        List<FileHole> holes = new ArrayList<>();
        List<FileSegment> segments = new ArrayList<>();
        long at = start;
        for (var hole : entry.holes) {
            long a = Math.max(start, hole.offset()),
                    b = Math.min(end, hole.offset() + hole.length());
            if (a >= b) {
                continue;
            }
            if (at < a) {
                segments.add(
                        new FileSegment(
                                at, java.util.Arrays.copyOfRange(entry.bytes, (int) at, (int) a)));
            }
            holes.add(new FileHole(a, b - a, hole.keyName()));
            at = b;
        }
        if (at < end) {
            segments.add(
                    new FileSegment(
                            at, java.util.Arrays.copyOfRange(entry.bytes, (int) at, (int) end)));
        }
        return new FileRead(
                holes.isEmpty()
                        ? FileReadState.COMPLETE
                        : segments.isEmpty() ? FileReadState.PROTECTED : FileReadState.PARTIAL,
                start,
                end - start,
                segments,
                holes,
                null,
                null,
                null);
    }

    private final class Handle implements BuildFiles {
        final BuildRef build;
        final String locale;
        final Map<Long, Entry> entries;
        boolean closed;

        Handle(BuildRef build, String locale, Map<Long, Entry> entries) {
            this.build = build;
            this.locale = locale;
            this.entries = entries;
        }

        void check(Cancellation token) {
            if (closed) {
                throw new IllegalStateException("Handle closed");
            }
            token.check();
        }

        Entry find(FileRef ref) {
            if (ref.fileDataId() != null) {
                return entries.get(ref.fileDataId());
            }
            return entries.values().stream()
                    .filter(
                            e ->
                                    e.file.path() != null
                                            && BuildFilesHandle.fold(e.file.path())
                                                    .equals(BuildFilesHandle.fold(ref.path())))
                    .max(java.util.Comparator.comparingLong(e -> e.file.fileDataId()))
                    .orElse(null);
        }

        @Override
        public BuildRef build() {
            return build;
        }

        @Override
        public String locale() {
            return locale;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public CompletableFuture<Boolean> exists(FileRef ref, Cancellation token) {
            check(token);
            return CompletableFuture.completedFuture(find(ref) != null);
        }

        @Override
        public CompletableFuture<FileStat> stat(FileRef ref, Cancellation token) {
            check(token);
            var e = find(ref);
            return CompletableFuture.completedFuture(
                    new FileStat(
                            e == null
                                    ? FileLookupState.UNKNOWN_FILE
                                    : e.file.storage() == FileStorage.NO_ENCODED_COPY
                                            ? FileLookupState.NOT_IN_ENCODING
                                            : FileLookupState.FOUND,
                            e == null ? null : current(e)));
        }

        private FileRead failure(Entry e, long offset) {
            return e == null
                    ? FileRead.failed(
                            FileReadState.UNKNOWN_FILE, offset, null, "Unknown file", null)
                    : e.file.storage() == FileStorage.NO_ENCODED_COPY
                            ? FileRead.failed(
                                    FileReadState.NOT_IN_ENCODING,
                                    offset,
                                    null,
                                    "No encoding",
                                    null)
                            : e.failure == null
                                    ? null
                                    : FileRead.failed(
                                            e.failure.state(),
                                            offset,
                                            e.failure.code(),
                                            e.failure.message(),
                                            e.failure.observedAt());
        }

        @Override
        public CompletableFuture<FileRead> get(FileRef ref, Cancellation token) {
            check(token);
            var e = find(ref);
            FileRead failed = failure(e, 0);
            if (failed != null) {
                return CompletableFuture.completedFuture(failed);
            }
            e.stored = true;
            return CompletableFuture.completedFuture(decoded(e, 0, e.bytes.length));
        }

        @Override
        public CompletableFuture<FileRead> readRange(
                FileRef ref, long offset, int length, Cancellation token) {
            check(token);
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("Invalid range");
            }
            var e = find(ref);
            FileRead failed = failure(e, offset);
            if (failed != null) {
                return CompletableFuture.completedFuture(failed);
            }
            if (offset < e.bytes.length && length > 0) {
                e.stored = true;
            }
            return CompletableFuture.completedFuture(decoded(e, offset, length));
        }

        @Override
        public CompletableFuture<FileOpen> openRead(FileRef ref, Cancellation token) {
            check(token);
            var e = find(ref);
            FileRead failed = failure(e, 0);
            if (failed != null) {
                return CompletableFuture.completedFuture(
                        new FileOpen(
                                failed.state(),
                                null,
                                failed.code(),
                                failed.message(),
                                failed.observedAt()));
            }
            e.stored = true;
            var state = decoded(e, 0, e.bytes.length).state();
            return CompletableFuture.completedFuture(
                    new FileOpen(state, new FakeStream(e), null, null, null));
        }

        @Override
        public AsyncCursor<BuildFile> list(FileFilter filter, Cancellation token) {
            return listing(filter, token, false);
        }

        @Override
        public AsyncCursor<BuildFile> listMetadata(FileFilter filter, Cancellation token) {
            return listing(filter, token, true);
        }

        private AsyncCursor<BuildFile> listing(
                FileFilter filter, Cancellation token, boolean metadataOnly) {
            check(token);
            var snapshot =
                    entries.values().stream()
                            .sorted(java.util.Comparator.comparingLong(e -> e.file.fileDataId()))
                            .toList();
            return new AsyncCursor<>() {
                int at;
                boolean ended;

                @Override
                public CompletableFuture<List<BuildFile>> next(int limit, Cancellation call) {
                    if (limit < 1) {
                        throw new IllegalArgumentException("Invalid limit");
                    }
                    if (ended) {
                        throw new IllegalStateException("Cursor closed");
                    }
                    token.check();
                    call.check();
                    List<BuildFile> out = new ArrayList<>();
                    while (at < snapshot.size() && out.size() < limit) {
                        var f = current(snapshot.get(at++));
                        if (metadataOnly) {
                            var type = FormatDetection.detect(null, f.path());
                            f =
                                    new BuildFile(
                                            f.fileDataId(),
                                            f.path(),
                                            f.pathSource(),
                                            f.size(),
                                            f.contentKey(),
                                            type.format(),
                                            type.source(),
                                            f.storage(),
                                            f.encrypted());
                        }
                        if (f.fileDataId() < filter.fromId()
                                || f.fileDataId() > filter.toId()
                                || filter.pathPrefix() != null
                                        && (f.path() == null
                                                || !BuildFilesHandle.fold(f.path())
                                                        .startsWith(
                                                                BuildFilesHandle.fold(
                                                                        filter.pathPrefix())))
                                || filter.formats() != null
                                        && !filter.formats().contains(f.format())
                                || filter.storage() != null
                                        && !filter.storage().contains(f.storage())
                                || filter.encrypted() != null
                                        && filter.encrypted() != f.encrypted()) {
                            continue;
                        }
                        out.add(f);
                    }
                    return CompletableFuture.completedFuture(out);
                }

                @Override
                public void close() {
                    ended = true;
                }
            };
        }

        @Override
        public CompletableFuture<EnsureResult> ensure(
                EnsureSelection selection,
                EnsurePriority priority,
                Consumer<EnsureProgress> progress,
                Cancellation token) {
            if (closed) {
                throw new IllegalStateException("Handle closed");
            }
            return Io.async(
                    () -> {
                        EnsureTally tally =
                                new EnsureTally(
                                        progress, Clock.systemUTC(), Duration.ofMillis(100));
                        List<FileRef> refs = selection.files();
                        if (selection.kind() == EnsureSelectionKind.MATCHING) {
                            refs = new ArrayList<>();
                            try (var cursor = list(selection.filter(), token)) {
                                for (; ; ) {
                                    var page = cursor.next(4096, token).join();
                                    if (page.isEmpty()) {
                                        break;
                                    }
                                    for (var file : page) {
                                        refs.add(FileRef.id(file.fileDataId()));
                                    }
                                }
                            }
                        }
                        Set<String> seen = new LinkedHashSet<>();
                        boolean canceled = token.isCanceled();
                        for (var ref : refs) {
                            var e = find(ref);
                            String key =
                                    e == null
                                            ? ref.fileDataId() != null
                                                    ? "id" + ref.fileDataId()
                                                    : "path" + BuildFilesHandle.fold(ref.path())
                                            : "id" + e.file.fileDataId();
                            if (!seen.add(key)) {
                                continue;
                            }
                            long size = e == null || e.file.size() == null ? 0 : e.file.size();
                            int index =
                                    tally.add(ref, e == null ? null : e.file.fileDataId(), size);
                            if (e == null) {
                                tally.settle(index, FileEnsureState.UNKNOWN_FILE, null, 0);
                            } else if (e.file.storage() == FileStorage.NO_ENCODED_COPY) {
                                tally.settle(index, FileEnsureState.NOT_IN_ENCODING, null, 0);
                            } else if (!token.isCanceled()) {
                                boolean before = e.stored;
                                e.stored = true;
                                tally.settle(
                                        index,
                                        before
                                                ? FileEnsureState.ALREADY_STORED
                                                : FileEnsureState.STORED,
                                        null,
                                        size);
                            } else {
                                canceled = true;
                            }
                            tally.offer();
                        }
                        tally.listed();
                        return tally.finish(canceled);
                    });
        }
    }

    private static final class FakeStream extends FileReadStream {
        final Entry entry;
        long at;
        boolean closed;

        FakeStream(Entry entry) {
            this.entry = entry;
        }

        void check() {
            if (closed) {
                throw new IllegalStateException("Stream closed");
            }
        }

        @Override
        public long length() {
            check();
            return entry.bytes.length;
        }

        @Override
        public long position() {
            check();
            return at;
        }

        @Override
        public void seek(long position) {
            check();
            if (position < 0) {
                throw new IllegalArgumentException("Negative position");
            }
            at = position;
        }

        @Override
        public List<FileHole> holes() {
            return entry.holes;
        }

        @Override
        public List<String> chunkKeys() {
            return entry.chunkKeys;
        }

        @Override
        public CompletableFuture<Integer> read(ByteBuffer to, Cancellation token) {
            return Io.async(
                    () -> {
                        check();
                        token.check();
                        FileRead failure = entry.failure;
                        if (failure != null) {
                            throw new FileReadException(
                                    failure.state(),
                                    failure.code(),
                                    failure.message(),
                                    failure.observedAt());
                        }
                        if (!to.hasRemaining()) {
                            return 0;
                        }
                        if (at >= entry.bytes.length) {
                            return -1;
                        }
                        long stop = entry.bytes.length;
                        for (var hole : entry.holes) {
                            if (at >= hole.offset() && at < hole.offset() + hole.length()) {
                                throw new FileHoleException(hole);
                            }
                            if (hole.offset() > at) {
                                stop = hole.offset();
                                break;
                            }
                        }
                        int n = (int) Math.min(to.remaining(), stop - at);
                        to.put(entry.bytes, (int) at, n);
                        at += n;
                        return n;
                    });
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
