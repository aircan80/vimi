package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import com.vibeminer.formats.hotfix.HotfixRowDecoder;
import com.vibeminer.formats.internal.Json;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bounded ordinary view only. Raw captures and native preparation remain the actual base DB2. */
public final class HotfixBuildTables implements BuildTables {
    // Byte ceilings come from the shared HotfixMemory accountant. A real Forever cache charges
    // about 27 MiB for ItemSparse alone, and item warmup pins several such tables at once.
    private static final int CACHE_TABLES = 8;

    private enum Kind {
        ADD,
        DELETE,
        PROTECTED,
        UNKNOWN
    }

    private record Change(
            HotfixCacheReader.Entry entry, Kind kind, Db2Types.Row row, String reason) {
        private Change(HotfixCacheReader.Entry entry, Kind kind, Db2Types.Row row) {
            this(entry, kind, row, kind.name());
        }
    }

    private static final class View {
        final ClientBuildTables.HotfixMetadata metadata;
        final TableTypes.Entry entry;
        final String identity;
        final Map<Long, Change> changes;
        final long[] excluded;
        final List<Db2Types.Row> additions;
        final Map<Long, Integer> additionIndex;
        final long bytes;
        final int added, replaced, deleted, protectedRows, unknownRows, ignored;

        View(
                ClientBuildTables.HotfixMetadata metadata,
                Map<Long, Change> changes,
                long[] excluded,
                List<Db2Types.Row> additions,
                Map<Long, Integer> additionIndex,
                long bytes,
                int added,
                int replaced,
                int deleted,
                int protectedRows,
                int unknownRows,
                int ignored,
                String input) {
            this.metadata = metadata;
            this.changes = Map.copyOf(changes);
            this.excluded = excluded;
            this.additions = List.copyOf(additions);
            this.additionIndex = Map.copyOf(additionIndex);
            this.bytes = bytes;
            this.added = added;
            this.replaced = replaced;
            this.deleted = deleted;
            this.protectedRows = protectedRows;
            this.unknownRows = unknownRows;
            this.ignored = ignored;
            var base = metadata.entry();
            entry =
                    new TableTypes.Entry(
                            base.fileDataId(),
                            base.name(),
                            protectedRows + unknownRows > 0
                                    ? TableTypes.State.PARTIAL
                                    : base.state(),
                            base.checked(),
                            base.reason(),
                            base.counts(),
                            base.contentKey());
            identity =
                    Json.sha256(
                            String.join(
                                            "\n",
                                            HotfixBinding.POLICY,
                                            HotfixRowDecoder.REVISION,
                                            input,
                                            metadata.identity(),
                                            metadata.header().tableHash(),
                                            metadata.header().layoutHash())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        long baseRows() {
            return metadata.rows() - excluded.length;
        }

        long rows() {
            return baseRows() + additions.size();
        }

        int excludedBefore(long raw) {
            int at = Arrays.binarySearch(excluded, raw);
            return at >= 0 ? at : -at - 1;
        }

        long raw(long effective) {
            long low = effective, high = effective + excluded.length;
            while (low < high) {
                long mid = (low + high) >>> 1;
                int excludedThrough = excludedBefore(mid + 1);
                if (mid - excludedThrough < effective) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    /** A table whose overlay exceeded a bound; it reads base rows with a visible reason. */
    private record Unavailable(
            String identity, String name, long rows, String reason, long generation) {}

    private static final class OverBudget extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String reason;
        final long generation;

        OverBudget(String reason, long generation) {
            super("Hotfix " + reason, null, false, false);
            this.reason = reason;
            this.generation = generation;
        }
    }

    private OverBudget over(String reason) {
        return new OverBudget(reason, memory.generation());
    }

    /** Bytes this handle could free for a new view without touching the current operation. */
    private long ownUnpinnedBytes() {
        long bytes = 0;
        for (var entry : views.entrySet()) {
            if (!pinned.contains(entry.getKey())) {
                bytes += entry.getValue().bytes;
            }
        }
        return bytes;
    }

    /**
     * Refuses before any eviction when even evicting every unpinned view could not make room.
     * {@code generation} is the release generation read before the free memory, so a release that
     * lands after the check is never missed by the retry rule.
     */
    private void requireRoom(long bytes, long generation) {
        if (bytes > memory.tableBytes()) {
            throw new OverBudget(HotfixViewInfo.TABLE_BUDGET, generation);
        }
        if (bytes > memory.available() + ownUnpinnedBytes()) {
            throw new OverBudget(HotfixViewInfo.RETAINED_BUDGET, generation);
        }
    }

    private void evictOldestUnpinned() {
        for (var key : views.keySet()) {
            if (!pinned.contains(key)) {
                charge.release(views.remove(key).bytes);
                return;
            }
        }
        throw new IllegalStateException("Hotfix view eviction found only pinned views");
    }

    private static final class Job<T> {
        final Cancellation token = new Cancellation();
        final CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> cancelDrain;
        boolean sealed;
    }

    private final ClientBuildTables base;
    private final TablesOptions options;
    private final HotfixBinding binding;
    private final LinkedHashMap<Long, View> views = new LinkedHashMap<>(8, .75f, true);
    private final Set<Long> pinned = new HashSet<>();
    private final Map<Long, Unavailable> unavailable = new HashMap<>();
    // Raw-column fit results per base identity; survive view eviction (insertion order).
    private final Map<String, Set<Integer>> rawFits = new LinkedHashMap<>();
    // Base identity each table had when it was last asked for; hotfixInfo reports a refusal only
    // while it still describes that base.
    private final Map<Long, String> observed = new HashMap<>();
    // Tables refused during the current operation: one operation never mixes base and overlaid
    // rows of a table, and never recompiles a refused table per read.
    private final Set<Long> operationSkipped = new HashSet<>();
    // Overlay compilations that started decoding; observed by tests only.
    int compiles;
    // Test seam over the bulk base-ordinal result; production keeps it as is.
    java.util.function.UnaryOperator<Map<Long, Long>> ordinalsProbe = found -> found;
    // Test seam over each base page of the raw-column fit scan; production keeps it as is.
    java.util.function.UnaryOperator<TableTypes.Page> rawScanProbe = page -> page;
    private final Set<Job<?>> jobs = new HashSet<>();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    r -> {
                        var t = new Thread(r, "hotfix-tables");
                        t.setDaemon(true);
                        return t;
                    });
    private final ThreadPoolExecutor cancels =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(64),
                    r -> {
                        var t = new Thread(r, "hotfix-cancel");
                        t.setDaemon(true);
                        return t;
                    });
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closed;
    private Throwable cancellationFailure;
    private final HotfixMemory memory;
    private final HotfixMemory.Charge charge;

    HotfixBuildTables(ClientBuildTables base, TablesOptions options) {
        this.base = Objects.requireNonNull(base);
        this.options = options;
        binding = Objects.requireNonNull(options.hotfix());
        memory = options.hotfixMemory();
        charge = memory.charge(this);
        if (!binding.build().equals(base.build()) || !binding.locale().equals(base.locale())) {
            throw new IllegalArgumentException("Hotfix workspace mismatch");
        }
    }

    @Override
    public BuildRef build() {
        return base.build();
    }

    @Override
    public String locale() {
        return base.locale();
    }

    private void stop(Job<?> job) {
        CompletableFuture<Void> completion;
        synchronized (job) {
            if (job.sealed || job.cancelDrain != null) {
                return;
            }
            completion = new CompletableFuture<>();
            job.cancelDrain = completion;
        }
        try {
            cancels.execute(
                    () -> {
                        Throwable failure = null;
                        try {
                            job.token.cancel();
                        } catch (RuntimeException | Error error) {
                            failure = error;
                            synchronized (this) {
                                if (cancellationFailure == null) {
                                    cancellationFailure = error;
                                }
                            }
                        }
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    });
        } catch (RuntimeException | Error error) {
            synchronized (this) {
                if (cancellationFailure == null) {
                    cancellationFailure = error;
                }
            }
            completion.completeExceptionally(error);
        }
    }

    private <T> CompletableFuture<T> operation(
            Cancellation caller, Function<Cancellation, T> action) {
        Objects.requireNonNull(caller);
        var job = new Job<T>();
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Hotfix view closed"));
            }
            if (jobs.size() >= 33) {
                return CompletableFuture.failedFuture(
                        new java.util.concurrent.RejectedExecutionException(
                                "Hotfix request capacity"));
            }
            if (cancellationFailure != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Hotfix cancellation failed", cancellationFailure));
            }
            jobs.add(job);
        }
        var registration = caller.onCancel(() -> stop(job));
        job.result.whenComplete(
                (v, e) -> {
                    if (job.result.isCancelled()) {
                        stop(job);
                    }
                });
        try {
            worker.execute(
                    () -> {
                        T value = null;
                        Throwable failure = null;
                        try {
                            job.token.check();
                            pinned.clear();
                            operationSkipped.clear();
                            value = action.apply(job.token);
                            job.token.check();
                        } catch (RuntimeException | Error e) {
                            failure = e;
                        } finally {
                            registration.close();
                            CompletableFuture<Void> cancelDrain;
                            synchronized (job) {
                                job.sealed = true;
                                cancelDrain = job.cancelDrain;
                            }
                            if (cancelDrain != null) {
                                try {
                                    await(cancelDrain);
                                } catch (RuntimeException | Error cleanup) {
                                    if (failure == null) {
                                        failure = cleanup;
                                    } else if (failure != cleanup) {
                                        failure.addSuppressed(cleanup);
                                    }
                                }
                            }
                            pinned.clear();
                            operationSkipped.clear();
                        }
                        var delivery = job.result;
                        T delivered = value;
                        Throwable terminal = failure;
                        // No source/payload work remains. Caller continuations must not block
                        // the single source lane or its actual close drain.
                        CompletableFuture.runAsync(
                                () -> {
                                    synchronized (this) {
                                        jobs.remove(job);
                                    }
                                    if (terminal == null) {
                                        delivery.complete(delivered);
                                    } else {
                                        delivery.completeExceptionally(terminal);
                                    }
                                });
                    });
        } catch (RuntimeException failure) {
            registration.close();
            synchronized (job) {
                job.sealed = true;
            }
            synchronized (this) {
                jobs.remove(job);
            }
            job.result.completeExceptionally(failure);
        }
        return job.result;
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.handle(
                        (value, error) -> {
                            if (error == null) {
                                return value;
                            }
                            if (error instanceof CompletionException completion) {
                                throw completion;
                            }
                            throw new CompletionException("Hotfix async source failed", error);
                        })
                .join();
    }

    private List<HotfixCacheReader.Entry> entries(long tableHash) {
        return binding.index().entries().stream()
                .filter(e -> e.tableHash() == tableHash)
                .sorted(
                        Comparator.comparingInt(HotfixCacheReader.Entry::pushId)
                                .thenComparingInt(HotfixCacheReader.Entry::ordinal))
                .toList();
    }

    private View view(TableTypes.Ref ref, TableTypes.Priority priority, Cancellation token) {
        var metadata = await(base.hotfixMetadata(ref, priority, token, false));
        if (metadata == null) {
            return null;
        }
        long id = metadata.entry().fileDataId();
        observed.put(id, metadata.identity());
        if (operationSkipped.contains(id)) {
            // Refused earlier in this operation: stay on base rows even if the base or the
            // memory changed since, so one operation never mixes base and overlaid rows.
            return null;
        }
        var skipped = unavailable.get(id);
        if (skipped != null) {
            if (!skipped.identity().equals(metadata.identity())) {
                // The base changed; an earlier refusal no longer describes this table.
                unavailable.remove(id);
            } else if (skipped.reason().equals(HotfixViewInfo.TABLE_BUDGET)
                    || skipped.reason().equals(HotfixViewInfo.RETAINED_BUDGET)
                            && skipped.generation() == memory.generation()) {
                // An oversized table stays skipped. A full shared budget is retried only after
                // some handle released memory, never once per read.
                return null;
            }
        }
        try {
            var compiled = compile(metadata, priority, token);
            unavailable.remove(id);
            return compiled;
        } catch (OverBudget over) {
            // One table over its bounds must not fail the workspace. It reads base rows only, and
            // hotfixInfo names the reason so the missing overlay stays explainable.
            unavailable.put(
                    id,
                    new Unavailable(
                            metadata.identity(),
                            metadata.entry().name(),
                            metadata.rows(),
                            over.reason,
                            over.generation));
            operationSkipped.add(id);
            return null;
        }
    }

    private View compile(
            ClientBuildTables.HotfixMetadata metadata,
            TableTypes.Priority priority,
            Cancellation token) {
        long id = metadata.entry().fileDataId();
        var cached = views.get(id);
        if (cached != null
                && cached.metadata.identity().equals(metadata.identity())
                && Objects.equals(cached.metadata.schema(), metadata.schema())) {
            pinned.add(id);
            return cached;
        }
        if (cached != null) {
            if (pinned.contains(id)) {
                throw new IllegalStateException("Hotfix base changed during operation");
            }
            charge.release(views.remove(id).bytes);
        }
        long hash = Long.parseUnsignedLong(metadata.header().tableHash(), 16);
        var input = entries(hash);
        if (input.isEmpty()) {
            return null;
        }
        metadata = await(base.hotfixMetadata(TableTypes.Ref.id(id), priority, token));
        if (metadata == null) {
            throw new IllegalStateException("Hotfix metadata changed");
        }
        hash = Long.parseUnsignedLong(metadata.header().tableHash(), 16);
        input = entries(hash);
        if (input.isEmpty()) {
            return null;
        }
        if (pinned.size() >= CACHE_TABLES) {
            throw over(HotfixViewInfo.OPERATION_TABLES);
        }
        boolean special = hash == 0xDF2F53CFL || hash == 0x021826BBL;
        // Every non-ignored operation charges at least 384 bytes; check that floor before
        // decoding anything, so a full shared budget costs no decode work.
        requireRoom((256L << 10) + (special ? 0 : 384L * input.size()), memory.generation());
        compiles++;
        HotfixRowDecoder.Layout layout = null;
        try {
            layout =
                    new HotfixRowDecoder.Layout(
                            metadata.schema(),
                            metadata.header(),
                            metadata.raw().flags(),
                            metadata.raw().idFieldIndex(),
                            fittedRawColumns(metadata, priority, token));
        } catch (HotfixRowDecoder.Invalid unsupported) {
            // Selected IDs remain masked UNKNOWN; never fall back to stale base values.
        }
        var changes = new HashMap<Long, Change>();
        var regions = new HashMap<Long, Integer>();
        var regionConflicts = new HashSet<Long>();
        long bytes = 256 << 10;
        int ignored = 0;
        for (var entry : input) {
            token.check();
            Integer priorRegion = regions.putIfAbsent(entry.recordId(), entry.regionId());
            if (priorRegion != null && priorRegion != entry.regionId()) {
                regionConflicts.add(entry.recordId());
            }
            if (regionConflicts.contains(entry.recordId())) {
                bytes = Math.addExact(bytes, 384);
                if (bytes > memory.tableBytes()) {
                    throw over(HotfixViewInfo.TABLE_BUDGET);
                }
                changes.put(
                        entry.recordId(), new Change(entry, Kind.UNKNOWN, null, "REGION_CONFLICT"));
                continue;
            }
            var prior = changes.get(entry.recordId());
            boolean fallback =
                    entry.state() != HotfixCacheReader.State.NOT_PUBLIC
                            && !(entry.state() == HotfixCacheReader.State.VALID
                                    && entry.payloadBytes() > 0);
            if (special
                    && fallback
                    && entry.pushId() == -1
                    && prior != null
                    && prior.kind() == Kind.ADD
                    && prior.entry().pushId() == -1) {
                ignored++;
                continue;
            }
            Kind kind;
            Db2Types.Row row = null;
            if (entry.state() == HotfixCacheReader.State.VALID && entry.payloadBytes() > 0) {
                try {
                    if (layout == null || entry.payloadBytes() > 64 << 10) {
                        kind = Kind.UNKNOWN;
                    } else {
                        var payload =
                                HotfixCacheReader.readPayloadBlocking(
                                        binding.source(),
                                        binding.index(),
                                        entry.ordinal(),
                                        64 << 10,
                                        token);
                        row =
                                HotfixRowDecoder.decode(
                                                layout,
                                                hash,
                                                entry.recordId(),
                                                ByteBuffer.wrap(payload),
                                                new HotfixRowDecoder.Limits(),
                                                token)
                                        .row();
                        kind = Kind.ADD;
                    }
                } catch (com.vibeminer.formats.hotfix.HotfixRowDecoder.Invalid invalid) {
                    kind = Kind.UNKNOWN;
                }
            } else if (entry.state() == HotfixCacheReader.State.DELETE) {
                kind = Kind.DELETE;
            } else if (entry.state() == HotfixCacheReader.State.NOT_PUBLIC) {
                kind = Kind.PROTECTED;
            } else {
                kind = Kind.UNKNOWN;
            }
            long charge = 384;
            if (row != null) {
                charge = Math.addExact(charge, 128);
                for (String value : row.values()) {
                    charge = Math.addExact(charge, 64L + 4L * value.length());
                }
            }
            bytes = Math.addExact(bytes, charge);
            if (bytes > memory.tableBytes()) {
                throw over(HotfixViewInfo.TABLE_BUDGET);
            }
            changes.put(entry.recordId(), new Change(entry, kind, row));
        }
        var exclusions = new ArrayList<Long>();
        var additions = new ArrayList<Db2Types.Row>();
        var additionIndex = new HashMap<Long, Integer>();
        int added = 0, replaced = 0, deleted = 0, protectedRows = 0, unknown = 0;
        // One base preparation for every changed ID. When the base cannot be read, the table is
        // refused for this operation rather than preparing the base once per changed ID.
        var ordinals =
                ordinalsProbe.apply(
                        await(
                                base.foundOrdinals(
                                        TableTypes.Ref.id(id), changes.keySet(), priority, token)));
        if (ordinals == null) {
            throw over(HotfixViewInfo.BASE_UNAVAILABLE);
        }
        for (var changed : new TreeMap<>(changes).entrySet()) {
            token.check();
            Long ordinal = ordinals.get(changed.getKey());
            boolean found = ordinal != null;
            if (found) {
                if (ordinal < 0 || ordinal >= metadata.rows()) {
                    throw new IllegalStateException("Hotfix base ordinal differs");
                }
                exclusions.add(ordinal);
            }
            var change = changed.getValue();
            switch (change.kind()) {
                case ADD -> {
                    additionIndex.put(changed.getKey(), additions.size());
                    additions.add(change.row());
                    if (found) {
                        replaced++;
                    } else {
                        added++;
                    }
                }
                case DELETE -> deleted++;
                case PROTECTED -> protectedRows++;
                case UNKNOWN -> unknown++;
            }
        }
        long[] excluded = exclusions.stream().mapToLong(Long::longValue).sorted().toArray();
        for (int i = 1; i < excluded.length; i++) {
            if (excluded[i] == excluded[i - 1]) {
                throw new IllegalStateException("Hotfix base IDs share ordinal");
            }
        }
        var result =
                new View(
                        metadata,
                        changes,
                        excluded,
                        additions,
                        additionIndex,
                        bytes,
                        added,
                        replaced,
                        deleted,
                        protectedRows,
                        unknown,
                        ignored,
                        binding.inputFingerprint());
        // Check, evict and reserve under the accountant's lock: no other handle can take the room
        // between the check and the reservation, so a refusal never evicts this handle's views
        // and an accepted view never fails after evicting them.
        synchronized (memory) {
            requireRoom(bytes, memory.generation());
            while (views.size() >= CACHE_TABLES) {
                evictOldestUnpinned();
            }
            while (!charge.reserve(bytes)) {
                evictOldestUnpinned();
            }
        }
        views.put(id, result);
        pinned.add(id);
        return result;
    }

    private List<Db2Types.Row> read(
            View view, long first, int count, TableTypes.Priority priority, Cancellation token) {
        if (first < 0 || count < 0 || first > view.rows() || count > view.rows() - first) {
            throw new IllegalArgumentException("Hotfix ordinal range");
        }
        var result = new ArrayList<Db2Types.Row>(count);
        long next = first;
        while (result.size() < count) {
            token.check();
            if (next >= view.baseRows()) {
                result.add(view.additions.get((int) (next - view.baseRows())));
                next++;
                continue;
            }
            long raw = view.raw(next);
            int n = 1;
            while (n < count - result.size()
                    && next + n < view.baseRows()
                    && view.raw(next + n) == raw + n) {
                n++;
            }
            n = Math.min(n, 1000);
            var page =
                    await(
                            base.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(view.entry.fileDataId()),
                                            raw - 1,
                                            n,
                                            List.of(),
                                            view.metadata.identity()),
                                    priority,
                                    token));
            if (page.state() != TableTypes.PageState.OK || page.rows().size() != n) {
                throw new IllegalStateException("Hotfix base page changed");
            }
            for (int i = 0; i < n; i++) {
                var row = page.rows().get(i);
                if (row.ordinal() != raw + i) {
                    throw new IllegalStateException("Hotfix base page not contiguous");
                }
                result.add(row.row());
            }
            next += n;
        }
        return result;
    }

    /**
     * Raw-physical columns the hotfix body may decode at their declared width: unverified-name,
     * non-relation columns whose every base value already fits that width, so base and hotfix rows
     * print the same text for the same value. Only a READY base qualifies, scanned completely: a
     * PARTIAL table (encrypted or missing sections) hides rows that could not be checked. Any other
     * raw column, a table over the scan bound, a non-OK or failing page, a short scan or a base
     * that changed during the scan keeps the whole table refused.
     */
    private Set<Integer> fittedRawColumns(
            ClientBuildTables.HotfixMetadata metadata,
            TableTypes.Priority priority,
            Cancellation token) {
        var columns = metadata.header().columns();
        var candidates = new java.util.TreeSet<Integer>();
        int column = 0;
        for (var field : metadata.schema().columns()) {
            for (int element = 0; element < field.arrayLength(); element++, column++) {
                if (column < columns.size()
                        && HotfixRowDecoder.rawCandidate(columns.get(column), field)) {
                    candidates.add(column);
                }
            }
        }
        if (candidates.isEmpty()
                || metadata.entry().state() != TableTypes.State.READY
                || metadata.rows() > options.maxScanRows()) {
            return Set.of();
        }
        var cached = rawFits.get(metadata.identity());
        if (cached != null) {
            return cached;
        }
        Set<Integer> fitted;
        try {
            fitted = scanRawFits(metadata, candidates, priority, token);
        } catch (RuntimeException failure) {
            if (token.isCanceled()
                    || failure instanceof java.util.concurrent.CancellationException) {
                throw failure;
            }
            // A damaged or unreadable base proves nothing about its values: refuse the columns,
            // keep the view, and scan again next time instead of caching a failure.
            return Set.of();
        }
        if (fitted == null) {
            // Inconclusive (unreadable page, short scan, base changed): refuse, do not cache.
            return Set.of();
        }
        if (rawFits.size() >= CACHE_TABLES * 4) {
            rawFits.remove(rawFits.keySet().iterator().next());
        }
        // Keyed by the base identity, so an evicted view does not rescan an unchanged base.
        rawFits.put(metadata.identity(), fitted);
        return fitted;
    }

    private Set<Integer> scanRawFits(
            ClientBuildTables.HotfixMetadata metadata,
            java.util.SortedSet<Integer> candidates,
            TableTypes.Priority priority,
            Cancellation token) {
        var columns = metadata.header().columns();
        var ref = TableTypes.Ref.id(metadata.entry().fileDataId());
        long after = -1, scanned = 0;
        String identity = null;
        for (; ; ) {
            token.check();
            var page =
                    rawScanProbe.apply(
                            await(
                                    base.page(
                                            new TableTypes.PageRequest(
                                                    ref, after, 1000, List.of(), identity),
                                            priority,
                                            token)));
            if (page.state() != TableTypes.PageState.OK
                    || page.table().state() != TableTypes.State.READY) {
                return null;
            }
            identity = page.identity();
            for (var row : page.rows()) {
                scanned++;
                var values = row.row().values();
                candidates.removeIf(
                        c ->
                                c >= values.size()
                                        || !HotfixRowDecoder.fitsDeclared(
                                                values.get(c), columns.get(c).interpretation()));
            }
            if (candidates.isEmpty()) {
                return Set.of();
            }
            if (page.nextAfter() == null) {
                break;
            }
            after = page.nextAfter();
        }
        // Every row was checked, and the base is still the one the metadata describes.
        var again = await(base.hotfixMetadata(ref, priority, token));
        if (scanned != metadata.rows()
                || again == null
                || !again.identity().equals(metadata.identity())) {
            return null;
        }
        return Set.copyOf(candidates);
    }

    private List<Db2Types.Row> readBase(
            TableTypes.Ref ref,
            long first,
            int count,
            TableTypes.Priority priority,
            Cancellation token) {
        var result = new ArrayList<Db2Types.Row>(count);
        String identity = null;
        while (result.size() < count) {
            int n = Math.min(1000, count - result.size());
            long start = first + result.size();
            var page =
                    await(
                            base.page(
                                    new TableTypes.PageRequest(
                                            ref, start - 1, n, List.of(), identity),
                                    priority,
                                    token));
            if (page.state() != TableTypes.PageState.OK || page.rows().size() != n) {
                throw new IllegalStateException("Hotfix base relation page changed");
            }
            identity = page.identity();
            for (int i = 0; i < n; i++) {
                var row = page.rows().get(i);
                if (row.ordinal() != start + i) {
                    throw new IllegalStateException("Hotfix base relation ordinals differ");
                }
                result.add(row.row());
            }
        }
        return result;
    }

    private TableTypes.RowResult getActual(
            TableTypes.Ref table, long id, TableTypes.Priority priority, Cancellation token) {
        var view = view(table, priority, token);
        if (view == null) {
            return await(base.get(table, id, priority, token));
        }
        var change = view.changes.get(id);
        if (change != null) {
            return switch (change.kind()) {
                case ADD ->
                        new TableTypes.RowResult(
                                view.entry,
                                TableTypes.RowOutcome.FOUND,
                                new TableTypes.Row(
                                        view.baseRows() + view.additionIndex.get(id), change.row()),
                                null);
                case DELETE ->
                        new TableTypes.RowResult(
                                view.entry, TableTypes.RowOutcome.ABSENT, null, null);
                case PROTECTED ->
                        new TableTypes.RowResult(
                                view.entry, TableTypes.RowOutcome.PROTECTED, null, null);
                case UNKNOWN ->
                        new TableTypes.RowResult(
                                view.entry, TableTypes.RowOutcome.UNKNOWN, null, null);
            };
        }
        var original = await(base.get(table, id, priority, token));
        if (original.outcome() != TableTypes.RowOutcome.FOUND) {
            return new TableTypes.RowResult(
                    view.entry, original.outcome(), original.row(), original.protectedKey());
        }
        long raw = original.row().ordinal();
        return new TableTypes.RowResult(
                view.entry,
                original.outcome(),
                new TableTypes.Row(raw - view.excludedBefore(raw), original.row().row()),
                original.protectedKey());
    }

    @Override
    public CompletableFuture<TableTypes.SchemaResult> schema(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token) {
        return operation(
                token,
                t -> {
                    var v = view(table, priority, t);
                    return v == null
                            ? await(base.schema(table, priority, t))
                            : new TableTypes.SchemaResult(
                                    v.entry, v.metadata.header().columns(), v.metadata.schema());
                });
    }

    @Override
    public CompletableFuture<TableTypes.Entry> check(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token) {
        return schema(table, priority, token).thenApply(TableTypes.SchemaResult::table);
    }

    @Override
    public CompletableFuture<TableTypes.Page> page(
            TableTypes.PageRequest request, TableTypes.Priority priority, Cancellation token) {
        TableQueries.validate(request);
        return operation(
                token,
                t -> {
                    var v = view(request.table(), priority, t);
                    if (v == null) {
                        return await(base.page(request, priority, t));
                    }
                    return await(
                            TableQueries.page(
                                    v.entry,
                                    v.identity,
                                    request,
                                    v.rows(),
                                    (first, n, c) ->
                                            CompletableFuture.completedFuture(
                                                    read(v, first, n, priority, c)),
                                    v.metadata.header().columns(),
                                    v.metadata.schema(),
                                    options.maxScanRows(),
                                    t));
                });
    }

    @Override
    public CompletableFuture<TableTypes.RowResult> get(
            TableTypes.Ref table, long id, TableTypes.Priority priority, Cancellation token) {
        if (id < 0 || id > 0xffffffffL) {
            throw new IllegalArgumentException("Unsigned32 ID required");
        }
        return operation(token, t -> getActual(table, id, priority, t));
    }

    private TableRelations.Source relations(TableTypes.Priority priority) {
        return new TableRelations.Source() {
            public CompletableFuture<TableRelations.RelationTable> open(
                    TableTypes.Ref ref, Cancellation token) {
                var v = view(ref, priority, token);
                if (v != null) {
                    return CompletableFuture.completedFuture(
                            new TableRelations.RelationTable(
                                    v.entry,
                                    v.metadata.header().columns(),
                                    v.metadata.schema(),
                                    v.rows(),
                                    (first, n, c) ->
                                            CompletableFuture.completedFuture(
                                                    read(v, first, n, priority, c))));
                }
                var s = await(base.schema(ref, priority, token));
                long rows = s.table().counts() == null ? 0 : s.table().counts().readableRows();
                return CompletableFuture.completedFuture(
                        new TableRelations.RelationTable(
                                s.table(),
                                s.columns(),
                                s.schema(),
                                rows,
                                (first, n, c) ->
                                        CompletableFuture.completedFuture(
                                                readBase(ref, first, n, priority, c))));
            }

            public CompletableFuture<TableTypes.RowResult> get(
                    TableTypes.Ref ref, long id, Cancellation token) {
                return CompletableFuture.completedFuture(getActual(ref, id, priority, token));
            }

            public CompletableFuture<List<TableTypes.Entry>> list(Cancellation token) {
                var result = new ArrayList<TableTypes.Entry>();
                try (var cursor = base.list(TableTypes.ListFilter.ALL, priority, token)) {
                    for (; ; ) {
                        var page = await(cursor.next(256, token));
                        if (page.isEmpty()) {
                            break;
                        }
                        result.addAll(page);
                    }
                }
                return CompletableFuture.completedFuture(result);
            }

            public CompletableFuture<TableRelations.RelationSchema> schema(
                    TableTypes.Entry table, Cancellation token) {
                var s = await(base.schema(TableTypes.Ref.id(table.fileDataId()), priority, token));
                return CompletableFuture.completedFuture(
                        new TableRelations.RelationSchema(
                                s.schema() == null
                                        ? TableRelations.SchemaState.UNRESOLVED
                                        : TableRelations.SchemaState.RESOLVED,
                                s.schema()));
            }
        };
    }

    @Override
    public CompletableFuture<TableTypes.FollowResult> follow(
            TableTypes.FollowRequest request, TableTypes.Priority priority, Cancellation token) {
        return operation(
                token,
                t ->
                        await(
                                TableRelations.follow(
                                        relations(priority), request, options.maxScanRows(), t)));
    }

    @Override
    public CompletableFuture<TableTypes.ReferencesResult> referencedBy(
            TableTypes.ReferencesRequest request,
            TableTypes.Priority priority,
            Cancellation token) {
        return operation(
                token,
                t ->
                        await(
                                TableRelations.referencedBy(
                                        relations(priority),
                                        request,
                                        options.maxScanRows(),
                                        options.maxReferenceSources(),
                                        options.maxReferenceScanRows(),
                                        t)));
    }

    @Override
    public AsyncCursor<TableTypes.Entry> list(
            TableTypes.ListFilter filter, TableTypes.Priority priority, Cancellation token) {
        Objects.requireNonNull(filter);
        token.check();
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Hotfix view closed");
            }
        }
        return new AsyncCursor<>() {
            private AsyncCursor<TableTypes.Entry> cursor;
            private final java.util.ArrayDeque<TableTypes.Entry> pending =
                    new java.util.ArrayDeque<>();
            private boolean ended, busy, cursorClosed;
            private Cancellation active;

            @Override
            public CompletableFuture<List<TableTypes.Entry>> next(
                    int limit, Cancellation nextToken) {
                if (limit < 1 || limit > 1000) {
                    throw new IllegalArgumentException("Hotfix list limit");
                }
                var combined = new Cancellation();
                synchronized (this) {
                    if (busy) {
                        throw new IllegalStateException("Concurrent cursor read");
                    }
                    if (cursorClosed) {
                        return CompletableFuture.completedFuture(List.of());
                    }
                    busy = true;
                    active = combined;
                }
                var original = token.onCancel(combined::cancel);
                var current = nextToken.onCancel(combined::cancel);
                var result =
                        operation(
                                combined,
                                t -> {
                                    var output = new ArrayList<TableTypes.Entry>();
                                    if (cursor == null) {
                                        cursor = base.list(TableTypes.ListFilter.ALL, priority, t);
                                    }
                                    while (output.size() < limit) {
                                        t.check();
                                        if (pending.isEmpty()) {
                                            if (ended) {
                                                break;
                                            }
                                            var page = await(cursor.next(256, t));
                                            if (page.isEmpty()) {
                                                ended = true;
                                                break;
                                            }
                                            pending.addAll(page);
                                        }
                                        var entry = pending.removeFirst();
                                        var effective =
                                                view(
                                                        TableTypes.Ref.id(entry.fileDataId()),
                                                        priority,
                                                        t);
                                        var selected = effective == null ? entry : effective.entry;
                                        // Only detached metadata escapes; no row-reader closure
                                        // needs this pin.
                                        pinned.remove(entry.fileDataId());
                                        if (TableQueries.matches(filter, selected)) {
                                            output.add(selected);
                                        }
                                    }
                                    return List.copyOf(output);
                                });
                result.whenComplete(
                        (v, e) -> {
                            original.close();
                            current.close();
                            synchronized (this) {
                                busy = false;
                                active = null;
                            }
                        });
                return result;
            }

            @Override
            public void close() {
                Cancellation stopping;
                synchronized (this) {
                    cursorClosed = true;
                    stopping = active;
                }
                if (stopping != null) {
                    stopping.cancel();
                }
                if (cursor != null) {
                    cursor.close();
                } // Actual ClientBuildTables metadata only.
            }
        };
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Hotfix view closed");
        }
    }

    @Override
    public CompletableFuture<TableTypes.PreloadResult> preload(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        requireOpen();
        return base.preload(progress, token);
    }

    @Override
    public CompletableFuture<TableTypes.PreloadResult> prepareKnown(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        requireOpen();
        return base.prepareKnown(progress, token);
    }

    @Override
    public CompletableFuture<List<com.vibeminer.generated.GeneratedKey>> savedArtifacts(
            TableTypes.Ref table, Cancellation token) {
        requireOpen();
        return base.savedArtifacts(table, token);
    }

    @Override
    public CompletableFuture<CapturedRowsTypes.Capture> capturePreparedLocal(
            TableTypes.Ref table, Cancellation token) {
        requireOpen();
        return base.capturePreparedLocal(table, token);
    }

    @Override
    public CompletableFuture<NativePreparationTypes.Result> prepareNativeEvidence(
            TableTypes.Ref table,
            TableTypes.Priority priority,
            NativePreparationTypes.SourceAccess access,
            Consumer<NativePreparationTypes.Progress> progress,
            Cancellation token) {
        requireOpen();
        return base.prepareNativeEvidence(table, priority, access, progress, token);
    }

    @Override
    public CompletableFuture<Optional<HotfixViewInfo>> hotfixInfo(
            TableTypes.Ref table, Long rowId, TableTypes.Priority priority, Cancellation token) {
        if (rowId != null && (rowId < 0 || rowId > 0xffff_ffffL)) {
            throw new IllegalArgumentException("Unsigned32 row ID required");
        }
        return operation(
                token,
                t -> {
                    // Last compiled ordinary-view metadata only; no schema/source/payload IO.
                    var v =
                            table.fileDataId() != null
                                    ? views.get(table.fileDataId())
                                    : views.values().stream()
                                            .filter(
                                                    candidate ->
                                                            candidate.entry.name() != null
                                                                    && candidate
                                                                            .entry
                                                                            .name()
                                                                            .equalsIgnoreCase(
                                                                                    table.name()))
                                            .findFirst()
                                            .orElse(null);
                    if (v == null) {
                        return unavailableInfo(table, rowId);
                    }
                    HotfixViewInfo.RowSource origin = null;
                    if (rowId != null) {
                        var c = v.changes.get(rowId);
                        if (c == null) {
                            origin =
                                    new HotfixViewInfo.RowSource(
                                            HotfixViewInfo.Origin.BASE,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null);
                        } else {
                            var e = c.entry();
                            origin =
                                    new HotfixViewInfo.RowSource(
                                            switch (c.kind()) {
                                                case ADD -> HotfixViewInfo.Origin.HOTFIX;
                                                case DELETE -> HotfixViewInfo.Origin.DELETED;
                                                case PROTECTED -> HotfixViewInfo.Origin.NOT_PUBLIC;
                                                case UNKNOWN -> HotfixViewInfo.Origin.UNRESOLVED;
                                            },
                                            e.ordinal(),
                                            c.reason().equals("REGION_CONFLICT")
                                                    ? null
                                                    : e.regionId(),
                                            e.pushId(),
                                            e.uniqueId(),
                                            e.state().name(),
                                            c.reason());
                        }
                    }
                    return Optional.of(
                            new HotfixViewInfo(
                                    binding.inputFingerprint(),
                                    v.identity,
                                    HotfixBinding.POLICY,
                                    v.metadata.rows(),
                                    v.rows(),
                                    v.added,
                                    v.replaced,
                                    v.deleted,
                                    v.protectedRows,
                                    v.unknownRows,
                                    v.ignored,
                                    origin));
                });
    }

    private Optional<HotfixViewInfo> unavailableInfo(TableTypes.Ref table, Long rowId) {
        Unavailable skipped = null;
        for (var entry : unavailable.entrySet()) {
            var u = entry.getValue();
            boolean named =
                    table.fileDataId() != null
                            ? table.fileDataId() == entry.getKey()
                            : u.name() != null && u.name().equalsIgnoreCase(table.name());
            // Only while the refusal still describes the base the table was last read from.
            if (named && u.identity().equals(observed.get(entry.getKey()))) {
                skipped = u;
                break;
            }
        }
        if (skipped == null) {
            return Optional.empty();
        }
        var origin =
                rowId == null
                        ? null
                        : new HotfixViewInfo.RowSource(
                                HotfixViewInfo.Origin.BASE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                skipped.reason());
        return Optional.of(
                new HotfixViewInfo(
                        binding.inputFingerprint(),
                        null,
                        HotfixBinding.POLICY,
                        skipped.rows(),
                        skipped.rows(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        origin,
                        skipped.reason()));
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != null && first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        List<Job<?>> pending;
        synchronized (this) {
            if (closed) {
                return drained.copy();
            }
            closed = true;
            pending = List.copyOf(jobs);
        }
        for (var job : pending) {
            stop(job);
        }
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    Throwable failure = null;
                    CompletableFuture<Void> baseDrain;
                    try {
                        baseDrain = base.closeAsync();
                    } catch (RuntimeException | Error error) {
                        failure = error;
                        baseDrain = base.whenClosed();
                    }
                    boolean interrupted = false;
                    for (; ; ) {
                        try {
                            if (worker.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        } catch (InterruptedException error) {
                            interrupted = true;
                            failure = append(failure, error);
                        }
                    }
                    cancels.shutdown();
                    for (; ; ) {
                        try {
                            if (cancels.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        } catch (InterruptedException error) {
                            interrupted = true;
                            failure = append(failure, error);
                        }
                    }
                    views.clear();
                    charge.releaseAll();
                    try {
                        await(baseDrain);
                    } catch (RuntimeException | Error error) {
                        failure = append(failure, error);
                    }
                    synchronized (this) {
                        failure = append(failure, cancellationFailure);
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (failure == null) {
                        drained.complete(null);
                    } else {
                        drained.completeExceptionally(failure);
                    }
                });
        return drained.copy();
    }
}
