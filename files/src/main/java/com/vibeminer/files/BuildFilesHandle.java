package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureProgress;
import com.vibeminer.files.FileTypes.EnsureResult;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.EnsureSelectionKind;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.names.LoadedNames;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class BuildFilesHandle implements BuildFiles {
    private volatile Build opened;
    private final BuildRef build;
    private final String locale;
    private final long bit;
    private final ObjectSource objects;
    private final FileSystemOptions options;
    private final FileReader.LayoutCache layouts = new FileReader.LayoutCache();
    private final ConcurrentHashMap<Long, FileFormat> formats = new ConcurrentHashMap<>();
    private final AtomicLong decoded = new AtomicLong();

    BuildFilesHandle(
            Build opened,
            String locale,
            long bit,
            ObjectSource objects,
            FileSystemOptions options) {
        this.opened = opened;
        build = opened.build();
        this.locale = locale;
        this.bit = bit;
        this.objects = objects;
        this.options = options;
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
        opened = null;
    }

    private Build opened() {
        Build value = opened;
        if (value == null) {
            throw new IllegalStateException("Files handle closed");
        }
        return value;
    }

    long decodedChunks() {
        return decoded.get();
    }

    static FileVariant pick(List<FileVariant> variants, long bit) {
        FileVariant best = null;
        int rank = 4;
        for (var variant : variants) {
            if ((variant.localeFlags() & bit) == 0) {
                continue;
            }
            int next =
                    variant.encodedKey() == null
                            ? 3
                            : (variant.contentFlags() & 128) != 0
                                    ? 2
                                    : (variant.contentFlags() & 1) != 0 ? 1 : 0;
            if (next < rank) {
                best = variant;
                rank = next;
            }
            if (rank == 0) {
                break;
            }
        }
        return best;
    }

    record Found(FileResolution resolution, boolean root) {}

    private Found find(Build built, FileRef ref, LoadedNames names, Cancellation token) {
        Objects.requireNonNull(ref);
        token.check();
        if (ref.fileDataId() != null) {
            return new Found(built.resolve(ref.fileDataId()), false);
        }
        var byRoot = built.resolve(ref.path());
        if (byRoot.state() != FileResolutionState.UNKNOWN_FILE) {
            return new Found(byRoot, true);
        }
        FileResolution listed = null, inLocale = null;
        if (names != null) {
            for (long id : names.idsOf(ref.path(), token).join()) {
                var resolved = built.resolve(id);
                if (resolved.state() == FileResolutionState.UNKNOWN_FILE) {
                    continue;
                }
                listed = resolved;
                if (pick(resolved.variants(), bit) != null) {
                    inLocale = resolved;
                }
            }
        }
        return new Found(inLocale != null ? inLocale : listed != null ? listed : byRoot, false);
    }

    private Found find(Build built, FileRef ref, Cancellation token) {
        if (ref.fileDataId() != null) {
            return find(built, ref, null, token);
        }
        var root = built.resolve(ref.path());
        if (root.state() != FileResolutionState.UNKNOWN_FILE) {
            return new Found(root, true);
        }
        return find(built, ref, options.names().current(token).join(), token);
    }

    @Override
    public CompletableFuture<Boolean> exists(FileRef file, Cancellation token) {
        Build built = opened();
        return Io.async(
                () -> {
                    var found = find(built, file, token).resolution();
                    return found.state() != FileResolutionState.UNKNOWN_FILE
                            && pick(found.variants(), bit) != null;
                });
    }

    private List<FileStorage> storage(List<FileVariant> variants, Cancellation token) {
        List<com.vibeminer.store.ObjectKey> keys = new ArrayList<>();
        for (var variant : variants) {
            if (variant.encodedKey() != null) {
                keys.add(FileReader.keyOf(variant));
            }
        }
        List<Boolean> got = keys.isEmpty() ? List.of() : objects.hasKeys(keys, token).join();
        if (got.size() != keys.size()) {
            throw new IllegalStateException("Layer two presence count differs");
        }
        List<FileStorage> result = new ArrayList<>();
        int at = 0;
        for (var variant : variants) {
            result.add(
                    variant.encodedKey() == null
                            ? FileStorage.NO_ENCODED_COPY
                            : got.get(at++) ? FileStorage.STORED : FileStorage.NOT_STORED);
        }
        return result;
    }

    private BuildFile toFile(
            long id, FileVariant variant, FileStorage storage, String path, FilePathSource source) {
        return new BuildFile(
                id,
                path,
                source,
                variant.encodedKey() == null ? null : variant.decodedSize(),
                variant.contentKey().toString(),
                FileFormat.UNKNOWN,
                FileFormatSource.NONE,
                storage,
                (variant.contentFlags() & 0x8000000) != 0);
    }

    private FileReader reader(FileVariant variant, Build built, boolean local) {
        return new FileReader(
                objects,
                variant,
                local ? null : built.cdn(),
                options.keys(),
                layouts,
                options.maxReadBytes(),
                decoded::incrementAndGet);
    }

    private BuildFile format(BuildFile file, FileVariant variant, Build built, Cancellation token) {
        FileFormat known = formats.get(file.fileDataId());
        byte[] head = null;
        if (known == null
                && file.storage() == FileStorage.STORED
                && variant.decodedSize() != null) {
            try {
                if (variant.decodedSize() == 0) {
                    head = new byte[0];
                } else {
                    var reader = reader(variant, built, true);
                    var layout = reader.layout(token);
                    if (!layout.chunks().isEmpty()
                            && layout.chunks().getFirst().encodedSize() <= 1 << 20
                            && layout.chunks().getFirst().decodedSize() <= 1 << 20) {
                        head =
                                head(
                                        reader.read(
                                                0,
                                                (int) Math.min(64, variant.decodedSize()),
                                                token));
                    }
                }
            } catch (RuntimeException e) {
                token.check();
            }
            if (head != null) {
                known = remember(file.fileDataId(), head, variant.decodedSize());
            }
        }
        var detected =
                known != null && known != FileFormat.UNKNOWN
                        ? new FormatDetection.Detected(known, FileFormatSource.MAGIC)
                        : FormatDetection.detect(null, file.path());
        return formatted(file, detected);
    }

    private static BuildFile formatted(BuildFile file, FormatDetection.Detected detected) {
        return new BuildFile(
                file.fileDataId(),
                file.path(),
                file.pathSource(),
                file.size(),
                file.contentKey(),
                detected.format(),
                detected.source(),
                file.storage(),
                file.encrypted());
    }

    private FileFormat remember(long id, byte[] head, long size) {
        FileFormat found = FormatDetection.magic(head), old = formats.get(id);
        if (found == FileFormat.UNKNOWN
                && (head.length < Math.min(64, size) || old != null && old != FileFormat.UNKNOWN)) {
            return old == null ? FileFormat.UNKNOWN : old;
        }
        if (formats.size() < 1000000 || old != null) {
            formats.put(id, found);
        }
        return found;
    }

    static byte[] head(FileRead read) {
        if (read.offset() != 0
                || read.state() != FileReadState.COMPLETE
                        && read.state() != FileReadState.PARTIAL) {
            return null;
        }
        if (read.segments().isEmpty()) {
            return read.length() == 0 ? new byte[0] : null;
        }
        return read.segments().getFirst().offset() == 0 ? read.segments().getFirst().bytes() : null;
    }

    @Override
    public CompletableFuture<FileStat> stat(FileRef file, Cancellation token) {
        Build built = opened();
        return Io.async(
                () -> {
                    token.check();
                    LoadedNames names = options.names().current(token).join();
                    var found = find(built, file, names, token);
                    var resolved = found.resolution();
                    if (resolved.state() == FileResolutionState.UNKNOWN_FILE
                            || resolved.fileDataId() == null) {
                        return new FileStat(FileLookupState.UNKNOWN_FILE, null);
                    }
                    var variant = pick(resolved.variants(), bit);
                    if (variant == null) {
                        return new FileStat(FileLookupState.NOT_IN_LOCALE, null);
                    }
                    String path =
                            names == null
                                    ? null
                                    : names.nameOf(resolved.fileDataId(), token).join();
                    FilePathSource source =
                            path != null
                                    ? FilePathSource.LISTFILE
                                    : found.root() ? FilePathSource.ROOT_HASH : FilePathSource.NONE;
                    if (path == null && found.root()) {
                        path = file.path();
                    }
                    var result =
                            toFile(
                                    resolved.fileDataId(),
                                    variant,
                                    storage(List.of(variant), token).getFirst(),
                                    path,
                                    source);
                    return new FileStat(
                            variant.encodedKey() == null
                                    ? FileLookupState.NOT_IN_ENCODING
                                    : FileLookupState.FOUND,
                            format(result, variant, built, token));
                });
    }

    private FileReader readerFor(Build built, FileRef file, long offset, Cancellation token) {
        return readerFor(built, find(built, file, token), offset);
    }

    private FileReader readerFor(Build built, Found found, long offset) {
        var resolved = found.resolution();
        if (resolved.state() == FileResolutionState.UNKNOWN_FILE || resolved.fileDataId() == null) {
            throw new FileReader.Failure(FileReadState.UNKNOWN_FILE, null, "Unknown file", null);
        }
        var variant = pick(resolved.variants(), bit);
        if (variant == null) {
            throw new FileReader.Failure(FileReadState.NOT_IN_LOCALE, null, "Not in locale", null);
        }
        if (variant.encodedKey() == null) {
            throw new FileReader.Failure(
                    FileReadState.NOT_IN_ENCODING, null, "Not in encoding", null);
        }
        if (variant.decodedSize() == null || variant.decodedSize() < 0) {
            throw new FileReader.Failure(
                    FileReadState.CORRUPT, "FILES_NO_DECLARED_SIZE", "Missing decoded size", null);
        }
        return reader(variant, built, false);
    }

    @Override
    public CompletableFuture<FileRead> get(FileRef file, Cancellation token) {
        Build built = opened();
        return Io.async(
                () -> {
                    try {
                        token.check();
                        var found = find(built, file, token);
                        var reader = readerFor(built, found, 0);
                        if (reader.size > options.maxReadBytes()) {
                            return FileRead.failed(
                                    FileReadState.TOO_LARGE,
                                    0,
                                    "FILES_READ_TOO_LARGE",
                                    "Whole read exceeds configured limit",
                                    null);
                        }
                        FileRead read =
                                reader.size == 0
                                        ? empty(0)
                                        : reader.read(0, (int) reader.size, token);
                        if (read.state() == FileReadState.COMPLETE
                                && !reader.matches(read.bytes())) {
                            return FileRead.failed(
                                    FileReadState.CORRUPT,
                                    0,
                                    "BLTE_INTEGRITY",
                                    "Content key differs",
                                    null);
                        }
                        byte[] head = head(read);
                        if (head != null) {
                            remember(found.resolution().fileDataId(), head, reader.size);
                        }
                        return read;
                    } catch (FileReader.Failure e) {
                        return FileRead.failed(e.state, 0, e.code, e.getMessage(), e.at);
                    } catch (BlteChunks.Failure e) {
                        return FileRead.failed(e.state, 0, e.code, e.getMessage(), null);
                    }
                });
    }

    private static FileRead empty(long at) {
        return new FileRead(FileReadState.COMPLETE, at, 0, List.of(), List.of(), null, null, null);
    }

    @Override
    public CompletableFuture<FileRead> readRange(
            FileRef file, long offset, int length, Cancellation token) {
        Build built = opened();
        if (offset < 0 || length < 0 || length > options.maxReadBytes()) {
            throw new IllegalArgumentException("Invalid range");
        }
        return Io.async(
                () -> {
                    try {
                        token.check();
                        var reader = readerFor(built, file, offset, token);
                        long start = Math.min(offset, reader.size);
                        int n = (int) Math.min(length, reader.size - start);
                        return n == 0 ? empty(start) : reader.read(start, n, token);
                    } catch (FileReader.Failure e) {
                        return FileRead.failed(e.state, offset, e.code, e.getMessage(), e.at);
                    } catch (BlteChunks.Failure e) {
                        return FileRead.failed(e.state, offset, e.code, e.getMessage(), null);
                    }
                });
    }

    @Override
    public CompletableFuture<FileOpen> openRead(FileRef file, Cancellation token) {
        Build built = opened();
        return Io.async(
                () -> {
                    try {
                        token.check();
                        var reader = readerFor(built, file, 0, token);
                        if (reader.size == 0 && !reader.matches(new byte[0])) {
                            throw BlteChunks.integrity();
                        }
                        return DecodedFileStream.open(reader, token);
                    } catch (FileReader.Failure e) {
                        return new FileOpen(e.state, null, e.code, e.getMessage(), e.at);
                    } catch (BlteChunks.Failure e) {
                        return new FileOpen(e.state, null, e.code, e.getMessage(), null);
                    }
                });
    }

    @Override
    public AsyncCursor<BuildFile> list(FileFilter filter, Cancellation token) {
        return new Listing(opened(), Objects.requireNonNull(filter), token, true);
    }

    @Override
    public AsyncCursor<BuildFile> listMetadata(FileFilter filter, Cancellation token) {
        return new Listing(opened(), Objects.requireNonNull(filter), token, false, true);
    }

    @Override
    public AsyncCursor<FileTypes.ContentFile> listContentInventory(
            FileTypes.FileIdRange range, Cancellation token) {
        return new ContentListing(
                opened(), Objects.requireNonNull(range), Objects.requireNonNull(token));
    }

    /** Frozen content indexes only: intentionally does not call names(), storage() or format(). */
    private final class ContentListing implements AsyncCursor<FileTypes.ContentFile> {
        private final Build built;
        private final FileTypes.FileIdRange range;
        private final Cancellation token;
        private final Iterator<Long> ids;
        private final AtomicBoolean reading = new AtomicBoolean();
        private volatile boolean closed;
        private boolean ended;

        ContentListing(Build built, FileTypes.FileIdRange range, Cancellation token) {
            this.built = built;
            this.range = range;
            this.token = token;
            ids = built.fileDataIds(locale, range.fromId()).iterator();
            ended = range.fromId() > range.toId();
        }

        @Override
        public CompletableFuture<List<FileTypes.ContentFile>> next(int limit, Cancellation call) {
            Objects.requireNonNull(call);
            if (limit < 1 || limit > 65536) {
                throw new IllegalArgumentException("Invalid page limit");
            }
            if (closed) {
                throw new IllegalStateException("Cursor closed");
            }
            if (!reading.compareAndSet(false, true)) {
                throw new IllegalStateException("Concurrent next");
            }
            return Io.async(
                            () -> {
                                var result =
                                        new ArrayList<FileTypes.ContentFile>(Math.min(limit, 4096));
                                while (!ended && result.size() < limit) {
                                    token.check();
                                    call.check();
                                    if (closed) {
                                        throw new IllegalStateException("Cursor closed");
                                    }
                                    if (!ids.hasNext()) {
                                        ended = true;
                                        break;
                                    }
                                    long id = ids.next();
                                    if (id > range.toId()) {
                                        ended = true;
                                        break;
                                    }
                                    var variant = pick(built.resolve(id).variants(), bit);
                                    if (variant != null) {
                                        result.add(
                                                new FileTypes.ContentFile(
                                                        id,
                                                        variant.encodedKey() == null
                                                                ? null
                                                                : variant.decodedSize(),
                                                        variant.contentKey().toString()));
                                    }
                                }
                                token.check();
                                call.check();
                                return List.copyOf(result);
                            })
                    .whenComplete((value, failure) -> reading.set(false));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private record Entry(long id, FileVariant variant, String path) {}

    private final class Listing implements AsyncCursor<BuildFile> {
        final Build built;
        final FileFilter filter;
        final Cancellation token;
        final boolean detect;
        final boolean metadataOnly;
        final Iterator<Long> ids;
        final ArrayDeque<BuildFile> pending = new ArrayDeque<>();
        boolean closed, ended;
        final AtomicBoolean reading = new AtomicBoolean();
        LoadedNames walked;
        final ArrayDeque<com.vibeminer.files.names.NamesTypes.NameEntry> namePage =
                new ArrayDeque<>();
        long namesFrom;
        boolean namesEnded;

        private String nameOf(LoadedNames current, long id, Cancellation cancellation) {
            if (current == null) {
                walked = null;
                namePage.clear();
                return null;
            }
            if (current != walked) {
                walked = current;
                namePage.clear();
                namesFrom = id;
                namesEnded = false;
            }
            for (; ; ) {
                if (namePage.isEmpty() && !namesEnded) {
                    var page =
                            current.from(namesFrom, options.presenceBatch(), cancellation).join();
                    if (page.isEmpty()) {
                        namesEnded = true;
                    } else {
                        namePage.addAll(page);
                        long last = page.getLast().id();
                        namesEnded = last == 0xffffffffL;
                        namesFrom = last + 1;
                    }
                }
                if (namePage.isEmpty()) {
                    return null;
                }
                var next = namePage.getFirst();
                if (next.id() < id) {
                    namePage.removeFirst();
                    continue;
                }
                return next.id() == id ? next.name() : null;
            }
        }

        Listing(Build built, FileFilter filter, Cancellation token, boolean detect) {
            this(built, filter, token, detect, false);
        }

        Listing(
                Build built,
                FileFilter filter,
                Cancellation token,
                boolean detect,
                boolean metadataOnly) {
            this.built = built;
            this.filter = filter;
            this.token = token;
            this.detect = detect;
            this.metadataOnly = metadataOnly;
            ids = built.fileDataIds(locale, filter.fromId()).iterator();
            if (filter.fromId() > filter.toId()
                    || filter.formats() != null && filter.formats().isEmpty()) {
                ended = true;
            }
        }

        @Override
        public CompletableFuture<List<BuildFile>> next(int limit, Cancellation call) {
            if (limit < 1 || limit > 65536) {
                throw new IllegalArgumentException("Invalid page limit");
            }
            if (closed) {
                throw new IllegalStateException("Cursor closed");
            }
            if (!reading.compareAndSet(false, true)) {
                throw new IllegalStateException("Concurrent next");
            }
            Cancellation operation = new Cancellation();
            var parentRegistration = token.onCancel(operation::cancel);
            var callRegistration = call.onCancel(operation::cancel);
            CompletableFuture<List<BuildFile>> answer =
                    Io.async(
                            () -> {
                                try {
                                    List<BuildFile> result = new ArrayList<>();
                                    while (result.size() < limit) {
                                        token.check();
                                        call.check();
                                        if (closed) {
                                            throw new IllegalStateException("Cursor closed");
                                        }
                                        while (!pending.isEmpty() && result.size() < limit) {
                                            result.add(pending.removeFirst());
                                        }
                                        if (result.size() == limit || ended) {
                                            break;
                                        }
                                        fill(operation);
                                    }
                                    return List.copyOf(result);
                                } finally {
                                    parentRegistration.close();
                                    callRegistration.close();
                                    reading.set(false);
                                }
                            });
            answer.whenComplete(
                    (value, error) -> {
                        parentRegistration.close();
                        callRegistration.close();
                        reading.set(false);
                    });
            return answer;
        }

        private void fill(Cancellation call) {
            LoadedNames names = options.names().current(call).join();
            if (filter.pathPrefix() != null && names == null) {
                ended = true;
                return;
            }
            List<Entry> batch = new ArrayList<>();
            while (ids.hasNext() && batch.size() < options.presenceBatch()) {
                token.check();
                call.check();
                long id = ids.next();
                if (id > filter.toId()) {
                    ended = true;
                    break;
                }
                var variant = pick(built.resolve(id).variants(), bit);
                if (variant == null) {
                    continue;
                }
                if (filter.encrypted() != null
                        && filter.encrypted() != ((variant.contentFlags() & 0x8000000) != 0)) {
                    continue;
                }
                String path = nameOf(names, id, call);
                if (names != null && names.isDamaged() && filter.pathPrefix() != null) {
                    ended = true;
                    break;
                }
                if (filter.pathPrefix() != null
                        && (path == null || !fold(path).startsWith(fold(filter.pathPrefix())))) {
                    continue;
                }
                batch.add(new Entry(id, variant, path));
            }
            if (!ids.hasNext()) {
                ended = true;
            }
            var states = storage(batch.stream().map(Entry::variant).toList(), call);
            List<CompletableFuture<BuildFile>> jobs = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                var entry = batch.get(i);
                FileStorage state = states.get(i);
                if (filter.storage() != null && !filter.storage().contains(state)) {
                    continue;
                }
                var file =
                        toFile(
                                entry.id(),
                                entry.variant(),
                                state,
                                entry.path(),
                                entry.path() == null
                                        ? FilePathSource.NONE
                                        : FilePathSource.LISTFILE);
                if (metadataOnly) {
                    var projected = formatted(file, FormatDetection.detect(null, file.path()));
                    if (filter.formats() == null || filter.formats().contains(projected.format())) {
                        pending.add(projected);
                    }
                } else if (detect || filter.formats() != null) {
                    jobs.add(Io.async(() -> format(file, entry.variant(), built, call)));
                    if (jobs.size() == 8) {
                        drain(jobs);
                    }
                } else {
                    pending.add(file);
                }
            }
            drain(jobs);
        }

        private void drain(List<CompletableFuture<BuildFile>> jobs) {
            for (var job : jobs) {
                var file = job.join();
                if (filter.formats() == null || filter.formats().contains(file.format())) {
                    pending.add(file);
                }
            }
            jobs.clear();
        }

        @Override
        public void close() {
            closed = true;
            pending.clear();
        }
    }

    static String fold(String path) {
        StringBuilder b = new StringBuilder(path.length());
        for (char c : path.toCharArray()) {
            b.append(c == '\\' ? '/' : c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    @Override
    public CompletableFuture<EnsureResult> ensure(
            EnsureSelection selection,
            EnsurePriority priority,
            Consumer<EnsureProgress> progress,
            Cancellation token) {
        Build built = opened();
        Objects.requireNonNull(selection);
        Objects.requireNonNull(priority);
        return Io.async(() -> ensureCore(built, selection, priority, progress, token));
    }

    private record EnsureEntry(FileRef ref, Long id, FileVariant variant, int tally) {}

    private EnsureResult ensureCore(
            Build built,
            EnsureSelection selection,
            EnsurePriority priority,
            Consumer<EnsureProgress> progress,
            Cancellation token) {
        EnsureTally tally = new EnsureTally(progress, options.clock(), options.progressInterval());
        Set<String> seen = new HashSet<>();
        List<EnsureEntry> batch = new ArrayList<>();
        boolean canceled = false;
        try {
            if (selection.kind() == EnsureSelectionKind.MATCHING) {
                try (var cursor = new Listing(built, selection.filter(), token, false)) {
                    for (; ; ) {
                        var files = cursor.next(options.presenceBatch(), token).join();
                        if (files.isEmpty()) {
                            break;
                        }
                        for (var file : files) {
                            var ref = FileRef.id(file.fileDataId());
                            var variant = pick(built.resolve(file.fileDataId()).variants(), bit);
                            int n = tally.add(ref, file.fileDataId(), encodedSize(variant));
                            if (variant.encodedKey() == null) {
                                tally.settle(n, FileEnsureState.NOT_IN_ENCODING, null, 0);
                            } else {
                                batch.add(new EnsureEntry(ref, file.fileDataId(), variant, n));
                            }
                        }
                        ensureBatch(batch, built, priority, tally, token);
                        batch.clear();
                    }
                }
            } else {
                List<EnsureEntry> selected = new ArrayList<>();
                LoadedNames names =
                        selection.files().stream().anyMatch(r -> r.path() != null)
                                ? options.names().current(token).join()
                                : null;
                for (var ref : selection.files()) {
                    var found =
                            find(built, ref, names, ref.path() == null ? Cancellation.NONE : token)
                                    .resolution();
                    Long id = found.fileDataId();
                    String unique =
                            id != null
                                    ? "id:" + id
                                    : ref.fileDataId() != null
                                            ? "id:" + ref.fileDataId()
                                            : "path:" + fold(ref.path());
                    if (!seen.add(unique)) {
                        continue;
                    }
                    FileVariant variant = pick(found.variants(), bit);
                    int n = tally.add(ref, id, encodedSize(variant));
                    var entry = new EnsureEntry(ref, id, variant, n);
                    if (found.state() == FileResolutionState.UNKNOWN_FILE || id == null) {
                        tally.settle(n, FileEnsureState.UNKNOWN_FILE, null, 0);
                    } else if (variant == null) {
                        tally.settle(n, FileEnsureState.NOT_IN_LOCALE, null, 0);
                    } else if (variant.encodedKey() == null) {
                        tally.settle(n, FileEnsureState.NOT_IN_ENCODING, null, 0);
                    } else {
                        selected.add(entry);
                    }
                }
                tally.listed();
                tally.offer();
                for (int from = 0; from < selected.size(); from += options.presenceBatch()) {
                    batch.clear();
                    batch.addAll(
                            selected.subList(
                                    from,
                                    Math.min(selected.size(), from + options.presenceBatch())));
                    ensureBatch(batch, built, priority, tally, token);
                }
            }
            tally.listed();
        } catch (RuntimeException error) {
            if (!token.isCanceled()
                    || !(Io.unwrap(error) instanceof java.util.concurrent.CancellationException)) {
                throw error;
            }
            canceled = true;
            reconcile(batch, tally);
        }
        return tally.finish(canceled);
    }

    private static long encodedSize(FileVariant variant) {
        return variant == null || variant.encodedSize() == null ? 0 : variant.encodedSize();
    }

    private void reconcile(List<EnsureEntry> batch, EnsureTally tally) {
        List<EnsureEntry> unresolved =
                batch.stream()
                        .filter(
                                e ->
                                        e.variant() != null
                                                && e.variant().encodedKey() != null
                                                && !tally.settled(e.tally()))
                        .toList();
        if (unresolved.isEmpty()) {
            return;
        }
        var present =
                objects.hasKeys(
                                unresolved.stream()
                                        .map(e -> FileReader.keyOf(e.variant()))
                                        .toList(),
                                Cancellation.NONE)
                        .join();
        for (int i = 0; i < unresolved.size(); i++) {
            if (present.get(i)) {
                var entry = unresolved.get(i);
                tally.settle(
                        entry.tally(), FileEnsureState.STORED, null, encodedSize(entry.variant()));
            }
        }
    }

    private void ensureBatch(
            List<EnsureEntry> batch,
            Build built,
            EnsurePriority priority,
            EnsureTally tally,
            Cancellation token) {
        if (batch.isEmpty()) {
            return;
        }
        token.check();
        List<EnsureEntry> valid =
                batch.stream()
                        .filter(e -> e.variant() != null && e.variant().encodedKey() != null)
                        .toList();
        var states = storage(valid.stream().map(EnsureEntry::variant).toList(), token);
        java.util.LinkedHashMap<com.vibeminer.store.ObjectKey, EnsureEntry> missing =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < valid.size(); i++) {
            var entry = valid.get(i);
            if (states.get(i) == FileStorage.STORED) {
                tally.settle(
                        entry.tally(),
                        FileEnsureState.ALREADY_STORED,
                        null,
                        encodedSize(entry.variant()));
            } else {
                missing.putIfAbsent(FileReader.keyOf(entry.variant()), entry);
            }
        }
        tally.offer();
        if (missing.isEmpty()) {
            return;
        }
        List<com.vibeminer.access.SourceTypes.ObjectResult> fetched = new ArrayList<>();
        var unique = new ArrayList<>(missing.values());
        for (int from = 0; from < unique.size(); from += 4096) {
            var slice = unique.subList(from, Math.min(unique.size(), from + 4096));
            int call = tally.beginCall();
            fetched.addAll(
                    objects.getKeys(
                                    slice.stream()
                                            .map(e -> FileReader.requestOf(e.variant()))
                                            .toList(),
                                    built.cdn(),
                                    priority == EnsurePriority.INTERACTIVE
                                            ? FetchPriority.INTERACTIVE
                                            : FetchPriority.BACKGROUND,
                                    p -> tally.cdn(call, p.cdnBytes()),
                                    token)
                            .join());
            token.check();
        }
        java.util.Map<com.vibeminer.store.ObjectKey, com.vibeminer.access.SourceTypes.ObjectResult>
                byKey = new java.util.HashMap<>();
        for (var result : fetched) {
            byKey.put(result.key(), result);
        }
        for (var entry : valid) {
            if (tally.settled(entry.tally())) {
                continue;
            }
            var got = byKey.get(FileReader.keyOf(entry.variant()));
            if (got == null) {
                throw new IllegalStateException("Missing ensure result");
            }
            FileEnsureState state =
                    switch (got.state()) {
                        case PRESENT -> FileEnsureState.STORED;
                        case NOT_FOUND -> FileEnsureState.MISSING;
                        case NEEDS_NETWORK -> FileEnsureState.NEEDS_NETWORK;
                        case UNAVAILABLE -> FileEnsureState.UNAVAILABLE;
                        case REFUSED -> FileEnsureState.REFUSED;
                        case UNSUPPORTED -> FileEnsureState.UNSUPPORTED;
                        case STORE_FAILED -> FileEnsureState.STORE_FAILED;
                    };
            tally.settle(entry.tally(), state, got.code(), encodedSize(entry.variant()));
        }
        tally.offer();
    }
}
