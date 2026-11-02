package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.History;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Community names: consent, resumable staging, verified history copies/deltas and rebuildable
 * generated indexes.
 */
public final class CommunityFileNames implements FileNames {
    public static final Duration REFRESH_INTERVAL = Duration.ofDays(7),
            MINIMUM_GAP = Duration.ofMinutes(15),
            UNREADABLE_RETRY = Duration.ofMinutes(1);
    private static final Map<GeneratedStore, ReentrantLock> BUILD_LOCKS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ObjectSource source;
    private final GeneratedStore store;
    private final FileNameSwitch consent;
    private final Clock clock;
    private final ReentrantLock loading = new ReentrantLock(), updating = new ReentrantLock();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private volatile Failure failure;
    private volatile Refusal refused;
    private volatile Instant unreadAt;
    private volatile boolean wasOn;

    private record Copy(ListfileStore index, ListfileRelease release, Instant fetchedAt) {}

    private record Snapshot(
            Copy copy, Instant checkedAt, ListfileRelease newest, String unreadable) {}

    private record Failure(Instant at, String code, String message, Instant observedAt) {}

    private record Refusal(String tag, String sha, ListfileFetchException error) {}

    private record Stored(
            ListfileRelease root,
            List<History.Entry> parts,
            List<byte[]> deltas,
            Instant fetchedAt) {}

    private record Staged(byte[] bytes, Instant at) {}

    private record Verification(
            int kind,
            List<Integer> parts,
            Instant fetchedAt,
            String detail) {} // 0usable1missing2badpart3hash4parse

    public CommunityFileNames(ObjectSource source, GeneratedStore store, FileNameSwitch consent) {
        this(source, store, consent, Clock.systemUTC());
    }

    public CommunityFileNames(
            ObjectSource source, GeneratedStore store, FileNameSwitch consent, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.store = store;
        this.consent = consent;
        this.clock = Objects.requireNonNull(clock);
    }

    public CompletableFuture<NamesTypes.FileNamesStatus> status(Cancellation c) {
        return NamesTasks.submit(
                c,
                () -> {
                    if (!on(c)) {
                        return off();
                    }
                    return status(loaded(c));
                });
    }

    public CompletableFuture<LoadedNames> current(Cancellation c) {
        return NamesTasks.submit(
                c,
                () -> {
                    if (!on(c)) {
                        return null;
                    }
                    Copy copy = loaded(c).copy;
                    return copy == null ? null : copy.index;
                });
    }

    public CompletableFuture<NamesTypes.FileNamesStatus> update(boolean force, Cancellation c) {
        return NamesTasks.submit(
                c,
                () -> {
                    if (!on(c)) {
                        return off();
                    }
                    lock(updating, c);
                    try {
                        unreadAt = null;
                        Snapshot s = loaded(c);
                        Instant now = clock.instant();
                        if (!force
                                && (fresh(s, now)
                                        || failure != null
                                                && recent(failure.at, now, MINIMUM_GAP))) {
                            return status(s);
                        }
                        try {
                            if (s.unreadable != null) {
                                throw fail("LISTFILE_FETCH_FAILED", s.unreadable, null);
                            }
                            if (!attempt(force, c)) {
                                return off();
                            }
                            failure = null;
                        } catch (RuntimeException e) {
                            c.check();
                            Throwable cause = NamesTasks.unwrap(e);
                            if (cause instanceof CancellationException) {
                                throw (CancellationException) cause;
                            }
                            if (cause instanceof ListfileFetchException f) {
                                failure =
                                        new Failure(clock.instant(), f.code, f.getMessage(), f.at);
                            } else {
                                failure =
                                        new Failure(
                                                clock.instant(),
                                                "LISTFILE_FETCH_FAILED",
                                                "Community names attempt failed ("
                                                        + cause.getClass().getSimpleName()
                                                        + ")",
                                                null);
                            }
                        }
                        return status(snapshot.get());
                    } finally {
                        updating.unlock();
                    }
                });
    }

    private synchronized boolean on(Cancellation c) {
        c.check();
        boolean enabled = false;
        if (store != null && consent != null) {
            try {
                CompletableFuture<Boolean> value = consent.enabled(c);
                enabled = value != null && Boolean.TRUE.equals(value.join());
            } catch (RuntimeException e) {
                c.check();
            }
        }
        if (enabled != wasOn) {
            unreadAt = null;
            wasOn = enabled;
        }
        return enabled;
    }

    private static NamesTypes.FileNamesStatus off() {
        return new NamesTypes.FileNamesStatus(NamesTypes.FileNamesState.OFF);
    }

    private NamesTypes.FileNamesStatus status(Snapshot s) {
        Copy copy = s == null ? null : s.copy;
        Failure f = failure;
        if (s != null && s.unreadable != null && f == null) {
            return new NamesTypes.FileNamesStatus(
                    NamesTypes.FileNamesState.NOT_READY,
                    null,
                    null,
                    null,
                    s.checkedAt,
                    0,
                    "LISTFILE_UNREADABLE",
                    s.unreadable,
                    null);
        }
        return new NamesTypes.FileNamesStatus(
                copy != null
                        ? NamesTypes.FileNamesState.READY
                        : f != null
                                ? NamesTypes.FileNamesState.FAILED
                                : NamesTypes.FileNamesState.NOT_READY,
                copy == null ? null : copy.release.tag(),
                copy == null ? null : copy.release.publishedAt(),
                copy == null ? null : copy.fetchedAt,
                s == null ? null : s.checkedAt,
                copy == null ? 0 : copy.index.count(),
                f == null ? null : f.code,
                f == null ? null : f.message,
                f == null ? null : f.observedAt);
    }

    private boolean fresh(Snapshot s, Instant now) {
        if (s.copy == null || s.newest == null || !recent(s.checkedAt, now, REFRESH_INTERVAL)) {
            return false;
        }
        return same(s.copy.release, s.newest)
                || refused != null
                        && refused.tag.equals(s.newest.tag())
                        && refused.sha.equals(s.newest.sha256());
    }

    private static boolean recent(Instant at, Instant now, Duration age) {
        return at != null && !at.isAfter(now) && Duration.between(at, now).compareTo(age) < 0;
    }

    private static boolean same(ListfileRelease a, ListfileRelease b) {
        return a.tag().equals(b.tag()) && a.sha256().equals(b.sha256());
    }

    private Snapshot loaded(Cancellation c) {
        Snapshot known = snapshot.get();
        if (known != null
                && (known.copy == null || !known.copy.index.isDamaged())
                && (known.unreadable == null
                        || recent(unreadAt, clock.instant(), UNREADABLE_RETRY))) {
            return known;
        }
        lock(loading, c);
        try {
            Snapshot before = snapshot.get();
            if (before != null
                    && (before.copy == null || !before.copy.index.isDamaged())
                    && (before.unreadable == null
                            || recent(unreadAt, clock.instant(), UNREADABLE_RETRY))) {
                return before;
            }
            Snapshot fresh;
            try {
                fresh = load(c);
            } catch (RuntimeException e) {
                c.check();
                unreadAt = clock.instant();
                fresh =
                        new Snapshot(
                                null,
                                null,
                                null,
                                "Stored community names could not be read ("
                                        + NamesTasks.unwrap(e).getClass().getSimpleName()
                                        + ")");
            }
            snapshot.compareAndSet(before, fresh);
            return snapshot.get();
        } finally {
            loading.unlock();
        }
    }

    private Snapshot load(Cancellation c) {
        List<History.Entry> entries =
                history("wowlistfile", "latest", ObservationKind.LISTFILE_RELEASE, c);
        Instant checked = entries.isEmpty() ? null : entries.getFirst().observedAt();
        List<ListfileRelease> releases = releases(entries, c);
        ListfileRelease newest = releases.isEmpty() ? null : releases.getFirst();
        Set<String> tags = new HashSet<>();
        for (ListfileRelease release : releases) {
            if (!tags.add(release.tag())) {
                continue;
            }
            if (tags.size() > 16) {
                break;
            }
            ListfileStore.Open open =
                    ListfileStore.openSync(
                            store, release.tag(), release.sha256(), c, ListfileStore::pathHash);
            Snapshot old = snapshot.get();
            boolean damaged =
                    old != null
                            && old.copy != null
                            && same(old.copy.release, release)
                            && old.copy.index.isDamaged();
            if (open.state() == ReadState.PRESENT && !damaged) {
                return new Snapshot(
                        new Copy(open.index(), release, open.index().release().fetchedAt()),
                        checked,
                        newest,
                        null);
            }
            Stored stored = resolve(release, releases, 0, new int[] {0}, c);
            if (stored == null) {
                continue;
            }
            try (InputStream input = copyStream(stored, c)) {
                ListfileStore index = build(release, stored.fetchedAt, input, true, c);
                return new Snapshot(
                        new Copy(index, release, stored.fetchedAt), checked, newest, null);
            } catch (ListfileFormatException | ListfileDeltaException | IOException e) {
                /* Try older intact release. */
            }
        }
        return new Snapshot(null, checked, newest, null);
    }

    private List<ListfileRelease> releases(List<History.Entry> entries, Cancellation c) {
        List<ListfileRelease> found = new ArrayList<>();
        for (History.Entry entry : entries) {
            byte[] bytes = held(entry, c);
            if (bytes != null) {
                try {
                    found.add(ListfileRelease.parse(bytes, entry.observedAt()));
                } catch (ListfileFetchException e) {
                    /* A bad release does not hide older copies. */
                }
            }
        }
        return found;
    }

    private List<History.Entry> history(
            String product, String region, ObservationKind kind, Cancellation c) {
        ArrayDeque<History.Entry> entries = new ArrayDeque<>();
        History.Entry after = null;
        for (int page = 0; page < 1000; page++) {
            c.check();
            List<History.Entry> batch =
                    source.readHistory(
                                    new History.Query(
                                            product, region, kind.historyKind(), after, 1000),
                                    c)
                            .join();
            for (History.Entry e : batch) {
                if (e.kind() == kind.historyKind()
                        && Objects.equals(e.product(), product)
                        && (region == null || Objects.equals(region, e.region()))) {
                    entries.addLast(e);
                    if (entries.size() > 4096) {
                        entries.removeFirst();
                    }
                }
            }
            if (batch.size() < 1000) {
                ArrayList<History.Entry> result = new ArrayList<>(entries);
                result.sort(Comparator.comparing(History.Entry::observedAt).reversed());
                return result;
            }
            after = batch.getLast();
        }
        throw fail("LISTFILE_FETCH_FAILED", "History walk exceeds bound", null);
    }

    private byte[] held(History.Entry entry, Cancellation c) {
        ObjectResult result =
                source.getKey(
                                new ObjectRequest(entry.document()),
                                null,
                                FetchPriority.INTERACTIVE,
                                c)
                        .join();
        c.check();
        if (result.state() == ObjectState.PRESENT) {
            return result.bytes();
        }
        if (result.state() == ObjectState.STORE_FAILED) {
            throw fail(
                    "LISTFILE_FETCH_FAILED",
                    "Stored names document unreadable",
                    result.observedAt());
        }
        return null;
    }

    private Stored resolve(
            ListfileRelease release,
            List<ListfileRelease> releases,
            int depth,
            int[] budget,
            Cancellation c) {
        if (depth > 16 || ++budget[0] > 128) {
            return null;
        }
        List<History.Entry> parts = history(release.tag(), null, ObservationKind.LISTFILE_PART, c);
        History.Entry[] chosen = new History.Entry[release.parts()];
        for (History.Entry e : parts) {
            Integer i = ObservationRequest.partIndex(e.region());
            if (i != null && i < chosen.length && chosen[i] == null) {
                chosen[i] = e;
            }
        }
        if (Arrays.stream(chosen).allMatch(Objects::nonNull)) {
            boolean okay = true;
            Instant at = Instant.MIN;
            for (int i = 0; i < chosen.length; i++) {
                byte[] bytes = held(chosen[i], c);
                if (bytes == null || bytes.length != release.partLength(i)) {
                    okay = false;
                    break;
                }
                if (chosen[i].observedAt().isAfter(at)) {
                    at = chosen[i].observedAt();
                }
            }
            if (okay) {
                return new Stored(release, List.of(chosen), List.of(), at);
            }
        }
        if (depth == 16) {
            return null;
        }
        int tried = 0;
        for (History.Entry e : history(release.tag(), "delta", ObservationKind.LISTFILE_DELTA, c)) {
            if (++tried > 8) {
                break;
            }
            byte[] bytes = held(e, c);
            if (bytes == null) {
                continue;
            }
            ListfileDelta.Header h;
            try {
                h = ListfileDelta.readHeader(bytes);
            } catch (ListfileDeltaException problem) {
                continue;
            }
            if (!h.targetSha256().equals(release.sha256()) || h.targetSize() != release.size()) {
                continue;
            }
            for (ListfileRelease base : releases) {
                if (!base.tag().equals(release.tag()) && base.sha256().equals(h.baseSha256())) {
                    Stored before = resolve(base, releases, depth + 1, budget, c);
                    if (before != null) {
                        List<byte[]> deltas = new ArrayList<>(before.deltas);
                        deltas.add(bytes);
                        return new Stored(
                                before.root, before.parts, List.copyOf(deltas), e.observedAt());
                    }
                }
            }
        }
        return null;
    }

    private InputStream copyStream(Stored stored, Cancellation c) {
        InputStream base =
                new PartStream(
                        stored.root.parts(),
                        i -> {
                            byte[] bytes = held(stored.parts.get(i), c);
                            if (bytes == null || bytes.length != stored.root.partLength(i)) {
                                throw fail(
                                        "LISTFILE_FETCH_FAILED", "Stored part disappeared", null);
                            }
                            return bytes;
                        },
                        c);
        return ListfileDelta.replay(base, stored.deltas, c);
    }

    private boolean attempt(boolean force, Cancellation c) {
        ObservationResult answer =
                source.observe(
                                new ObservationRequest(
                                        ObservationKind.LISTFILE_RELEASE, "wowlistfile", "latest"),
                                c)
                        .join();
        c.check();
        Observation o = present(answer);
        if (answer.recorded()) {
            snapshot.updateAndGet(
                    s -> new Snapshot(s.copy, later(s.checkedAt, o.observedAt()), s.newest, null));
        }
        ListfileRelease release = ListfileRelease.parse(o.bytes(), o.observedAt());
        if (answer.recorded()) {
            snapshot.updateAndGet(s -> new Snapshot(s.copy, s.checkedAt, release, null));
        }
        Snapshot s = snapshot.get();
        if (s.copy != null && same(s.copy.release, release)) {
            return true;
        }
        if (!force
                && refused != null
                && refused.tag.equals(release.tag())
                && refused.sha.equals(release.sha256())) {
            throw refused.error;
        }
        // Identical bytes under a new tag need only a zero-op delta, never another download.
        if (s.copy != null
                && !s.copy.release.tag().equals(release.tag())
                && s.copy.release.sha256().equals(release.sha256())
                && s.copy.release.size() == release.size()) {
            List<ListfileRelease> catalog =
                    releases(
                            history("wowlistfile", "latest", ObservationKind.LISTFILE_RELEASE, c),
                            c);
            Stored base = resolve(s.copy.release, catalog, 0, new int[] {0}, c);
            if (base != null && base.deltas.size() < 16) {
                try (InputStream a = copyStream(base, c);
                        InputStream b = copyStream(base, c)) {
                    byte[] delta =
                            ListfileDelta.encode(
                                    a,
                                    b,
                                    release.sha256(),
                                    release.sha256(),
                                    release.size(),
                                    16 << 20,
                                    c);
                    if (delta != null) {
                        record(
                                new Observation(
                                        ObservationKind.LISTFILE_DELTA,
                                        release.tag(),
                                        "delta",
                                        SourceUrls.asset(release.tag()),
                                        s.copy.fetchedAt,
                                        delta),
                                c);
                        try (InputStream input = copyStream(base, c)) {
                            swap(
                                    build(release, s.copy.fetchedAt, input, false, c),
                                    release,
                                    s.copy.fetchedAt,
                                    c);
                            return true;
                        }
                    }
                } catch (ListfileFormatException | ListfileDeltaException | IOException e) {
                    /* Fall back to ordinary part download. */
                }
            }
        }
        Set<Integer> fetched = new HashSet<>(), bad = new HashSet<>();
        boolean rehashed = false;
        Instant fetchedAt;
        for (; ; ) {
            Verification v = verify(release, c);
            if (v.kind == 0) {
                fetchedAt = v.fetchedAt;
                break;
            }
            if (v.kind == 4) {
                ListfileFetchException error = fail("LISTFILE_INVALID", v.detail, o.observedAt());
                refused = new Refusal(release.tag(), release.sha256(), error);
                throw error;
            }
            if (v.kind == 3) {
                if (rehashed) {
                    ListfileFetchException error =
                            fail("LISTFILE_INVALID", v.detail, o.observedAt());
                    refused = new Refusal(release.tag(), release.sha256(), error);
                    throw error;
                }
                rehashed = true;
                for (int i = 0; i < release.parts(); i++) {
                    fetched.add(i);
                    if (!fetch(release, i, c)) {
                        return false;
                    }
                }
            } else {
                for (int i : v.parts) {
                    if (v.kind == 2 ? !bad.add(i) : !fetched.add(i)) {
                        ListfileFetchException error =
                                fail(
                                        v.kind == 2 ? "LISTFILE_INVALID" : "LISTFILE_FETCH_FAILED",
                                        v.detail,
                                        o.observedAt());
                        if (v.kind == 2) {
                            refused = new Refusal(release.tag(), release.sha256(), error);
                        }
                        throw error;
                    }
                    fetched.add(i);
                    if (!fetch(release, i, c)) {
                        return false;
                    }
                }
            }
        }
        keep(release, fetchedAt, c);
        try (InputStream input = stagedStream(release, c)) {
            ListfileStore built = build(release, fetchedAt, input, false, c);
            swap(built, release, fetchedAt, c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    private static Instant later(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    private boolean fetch(ListfileRelease release, int part, Cancellation c) {
        if (!on(c)) {
            return false;
        }
        ObservationResult answer =
                source.observe(
                                new ObservationRequest(
                                        ObservationKind.LISTFILE_PART,
                                        release.tag(),
                                        ObservationRequest.partRegion(part),
                                        null,
                                        false),
                                c)
                        .join();
        c.check();
        Observation o = present(answer);
        byte[] bytes = o.bytes();
        ByteBuffer b =
                ByteBuffer.allocate(8 + bytes.length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(ticks(o.observedAt()))
                        .put(bytes);
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            GeneratedKey key = stagedKey(release, part);
            store.delete(List.of(key), token.value).join();
            store.put(List.of(new GeneratedTypes.Put(key, b.array())), token.value).join();
        }
        return true;
    }

    private Verification verify(ListfileRelease release, Cancellation c) {
        List<Integer> missing = new ArrayList<>();
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            for (int i = 0; i < release.parts(); i++) {
                if (!store.has(stagedKey(release, i), token.value).join()) {
                    missing.add(i);
                }
            }
        }
        if (!missing.isEmpty()) {
            return new Verification(1, missing, null, "Missing staged part");
        }
        MessageDigest sha = ListfileStore.digest();
        ListfileParser parser = new ListfileParser((id, name) -> {});
        String invalid = null;
        Instant fetched = Instant.MIN;
        for (int i = 0; i < release.parts(); i++) {
            Staged part = staged(release, i, c);
            if (part == null) {
                return new Verification(1, List.of(i), null, "Unreadable staged part");
            }
            if (part.bytes.length != release.partLength(i)) {
                return new Verification(2, List.of(i), null, "Wrong staged part length");
            }
            fetched = later(fetched, part.at);
            sha.update(part.bytes);
            if (invalid == null) {
                try {
                    parser.feed(part.bytes);
                } catch (ListfileFormatException e) {
                    invalid = e.getMessage();
                }
            }
        }
        if (!HexFormat.of().formatHex(sha.digest()).equals(release.sha256())) {
            return new Verification(3, List.of(), null, "Asset SHA-256 differs");
        }
        if (invalid == null) {
            try {
                parser.finish();
            } catch (ListfileFormatException e) {
                invalid = e.getMessage();
            }
        }
        return new Verification(invalid == null ? 0 : 4, List.of(), fetched, invalid);
    }

    private Staged staged(ListfileRelease release, int part, Cancellation c) {
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            GeneratedKey key = stagedKey(release, part);
            GeneratedTypes.Read read = store.read(key, token.value).join();
            if (read.state() == ReadState.ABSENT) {
                return null;
            }
            byte[] bytes = read.bytes();
            if (read.state() != ReadState.PRESENT || bytes == null || bytes.length < 8) {
                store.delete(List.of(key), token.value).join();
                return null;
            }
            long t = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (t < 0 || t > 3155378975999999999L) {
                store.delete(List.of(key), token.value).join();
                return null;
            }
            return new Staged(Arrays.copyOfRange(bytes, 8, bytes.length), instant(t));
        }
    }

    static GeneratedKey stagedKey(ListfileRelease r, int i) {
        return GeneratedKey.hash(
                ("wowlistfile-staging-v1|" + r.tag() + "|" + r.sha256() + "|" + i)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private InputStream stagedStream(ListfileRelease r, Cancellation c) {
        return new PartStream(
                r.parts(),
                i -> {
                    Staged part = staged(r, i, c);
                    if (part == null) {
                        throw fail("LISTFILE_FETCH_FAILED", "Staged part disappeared", null);
                    }
                    return part.bytes;
                },
                c);
    }

    private void keep(ListfileRelease release, Instant at, Cancellation c) {
        Snapshot s = snapshot.get();
        if (s.copy != null && !s.copy.release.tag().equals(release.tag())) {
            List<ListfileRelease> catalog =
                    releases(
                            history("wowlistfile", "latest", ObservationKind.LISTFILE_RELEASE, c),
                            c);
            Stored base = resolve(s.copy.release, catalog, 0, new int[] {0}, c);
            if (base != null && base.deltas.size() < 16) {
                try (InputStream a = copyStream(base, c);
                        InputStream b = stagedStream(release, c)) {
                    byte[] delta =
                            ListfileDelta.encode(
                                    a,
                                    b,
                                    s.copy.release.sha256(),
                                    release.sha256(),
                                    release.size(),
                                    16 << 20,
                                    c);
                    if (delta != null) {
                        record(
                                new Observation(
                                        ObservationKind.LISTFILE_DELTA,
                                        release.tag(),
                                        "delta",
                                        SourceUrls.asset(release.tag()),
                                        at,
                                        delta),
                                c);
                        return;
                    }
                } catch (ListfileFormatException | ListfileDeltaException | IOException e) {
                    /* Full copy when no bounded usable delta. */
                }
            }
        }
        for (int i = 0; i < release.parts(); i++) {
            Staged part = staged(release, i, c);
            if (part == null) {
                throw fail("LISTFILE_FETCH_FAILED", "Staged part disappeared", null);
            }
            record(
                    new Observation(
                            ObservationKind.LISTFILE_PART,
                            release.tag(),
                            ObservationRequest.partRegion(i),
                            SourceUrls.asset(release.tag()),
                            part.at,
                            part.bytes),
                    c);
        }
    }

    private void record(Observation o, Cancellation c) {
        ObservationResult result = source.record(o, c).join();
        c.check();
        if (result.state() != ObjectState.PRESENT || !result.recorded()) {
            throw fail(
                    "LISTFILE_FETCH_FAILED",
                    "Verified release could not be kept",
                    result.observedAt());
        }
    }

    private ListfileStore build(
            ListfileRelease release,
            Instant at,
            InputStream input,
            boolean rebuild,
            Cancellation c) {
        ReentrantLock gate;
        synchronized (BUILD_LOCKS) {
            gate = BUILD_LOCKS.computeIfAbsent(store, k -> new ReentrantLock());
        }
        lock(gate, c);
        try {
            return ListfileStore.buildSync(
                    store,
                    new ListfileStore.Release(
                            release.tag(), release.sha256(), release.publishedAt(), at),
                    input,
                    rebuild,
                    c,
                    ListfileStore::pathHash);
        } finally {
            gate.unlock();
        }
    }

    private void swap(ListfileStore index, ListfileRelease release, Instant at, Cancellation c) {
        snapshot.updateAndGet(
                s -> new Snapshot(new Copy(index, release, at), s.checkedAt, s.newest, null));
        refused = null;
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            List<GeneratedKey> keys = new ArrayList<>();
            for (int i = 0; i < release.parts(); i++) {
                keys.add(stagedKey(release, i));
            }
            store.delete(keys, token.value).join();
            for (int round = 0; round < 4; round++) {
                if (store.compact(0.5, 4, token.value).join().packsCompacted() == 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            c
                    .check(); /* Cleanup failure only costs space. Do not blindly delete shared segments. */
        }
    }

    private static Observation present(ObservationResult answer) {
        if (answer.state() != ObjectState.PRESENT || answer.observation() == null) {
            throw fail(
                    answer.state() == ObjectState.NOT_FOUND
                            ? "LISTFILE_NOT_FOUND"
                            : "LISTFILE_FETCH_FAILED",
                    answer.message() == null ? "Source did not return names" : answer.message(),
                    answer.observedAt());
        }
        return answer.observation();
    }

    private static ListfileFetchException fail(String code, String message, Instant at) {
        return ListfileRelease.fail(code, message, at);
    }

    private static void lock(ReentrantLock gate, Cancellation c) {
        for (; ; ) {
            c.check();
            try {
                if (gate.tryLock(25, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
        }
    }

    private static long ticks(Instant at) {
        return Math.addExact(
                621355968000000000L,
                Math.addExact(
                        Math.multiplyExact(at.getEpochSecond(), 10_000_000L), at.getNano() / 100));
    }

    private static Instant instant(long ticks) {
        long t = ticks - 621355968000000000L;
        return Instant.ofEpochSecond(
                Math.floorDiv(t, 10_000_000), Math.floorMod(t, 10_000_000) * 100);
    }

    private static final class PartStream extends InputStream {
        final int count;
        final java.util.function.IntFunction<byte[]> parts;
        final Cancellation c;
        int part;
        ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);

        PartStream(int count, java.util.function.IntFunction<byte[]> parts, Cancellation c) {
            this.count = count;
            this.parts = parts;
            this.c = c;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] out, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, out.length);
            if (length == 0) {
                return 0;
            }
            c.check();
            while (current.available() == 0) {
                if (part == count) {
                    return -1;
                }
                current = new ByteArrayInputStream(parts.apply(part++));
            }
            return current.read(out, offset, length);
        }
    }
}
