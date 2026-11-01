package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.access.SourceTypes.Options;
import com.vibeminer.access.SourceTypes.ServedFrom;
import com.vibeminer.network.CdnClient;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.network.RangePlanner;
import com.vibeminer.store.History;
import com.vibeminer.store.HistoryLog;
import com.vibeminer.store.HistoryReader;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectStaging;
import com.vibeminer.store.ObjectVerifier;
import com.vibeminer.store.ObjectWriter;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import com.vibeminer.store.Verification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Local then shared then CDN access, verified persistence and in-process commit notifications. */
public final class CachingObjectSource implements ObjectSource, StoreCommits {
    private final ObjectReader reader, shared;
    private final ObjectWriter writer;
    private final HistoryReader historyReader;
    private final HistoryLog historyLog;
    private final CdnClient cdn, interactiveCdn;
    private final ObjectVerifier verifier;
    private final Options options;
    private final boolean localOnly;
    private final Map<ObservationKind, ObservationSource> observationSources = new HashMap<>();
    private final StoreCommitFeed commits = new StoreCommitFeed();
    private final StreamScheduler streams;
    private final Map<ObjectKey, Claim> claims = new HashMap<>();
    private final HostRanking hosts;
    private final AccessLifetime lifetime = new AccessLifetime();
    private final AtomicInteger pendingGroups = new AtomicInteger();
    private BlteRangeReader ranged;

    public CachingObjectSource(
            ObjectReader reader,
            ObjectWriter writer,
            HistoryLog history,
            ObjectReader shared,
            CdnClient cdn,
            List<ObservationSource> observations,
            ObjectVerifier verifier,
            Options options) {
        this(
                reader,
                writer,
                history,
                history,
                shared,
                cdn,
                observations,
                verifier,
                options,
                false,
                cdn);
    }

    public CachingObjectSource(
            ObjectReader reader,
            ObjectWriter writer,
            HistoryLog history,
            ObjectReader shared,
            CdnClient cdn,
            List<ObservationSource> observations) {
        this(reader, writer, history, shared, cdn, observations, Verification.CDN, new Options());
    }

    public CachingObjectSource(
            ObjectReader reader,
            ObjectWriter writer,
            HistoryLog history,
            ObjectReader shared,
            CdnClient cdn,
            List<ObservationSource> observations,
            ObjectVerifier verifier,
            Options options,
            CdnClient interactiveCdn) {
        this(
                Objects.requireNonNull(reader),
                Objects.requireNonNull(writer),
                Objects.requireNonNull(history),
                history,
                shared,
                Objects.requireNonNull(cdn),
                observations,
                verifier,
                options,
                false,
                Objects.requireNonNull(interactiveCdn));
    }

    private CachingObjectSource(
            ObjectReader reader,
            ObjectWriter writer,
            HistoryReader historyReader,
            HistoryLog historyLog,
            ObjectReader shared,
            CdnClient cdn,
            List<ObservationSource> observations,
            ObjectVerifier verifier,
            Options options,
            boolean localOnly,
            CdnClient interactiveCdn) {
        this.reader = reader;
        this.writer = writer;
        this.historyReader = historyReader;
        this.historyLog = historyLog;
        this.shared = shared;
        this.cdn = cdn;
        this.interactiveCdn = interactiveCdn;
        this.verifier = Objects.requireNonNull(verifier);
        this.options = Objects.requireNonNull(options);
        this.localOnly = localOnly;
        streams = new StreamScheduler(options);
        hosts = new HostRanking(options.clock(), options.hostCooldown());
        for (ObservationSource source : List.copyOf(observations)) {
            for (ObservationKind kind : source.kinds()) {
                if (observationSources.putIfAbsent(kind, source) != null) {
                    throw new IllegalArgumentException("Two observation sources serve " + kind);
                }
            }
        }
    }

    public static CachingObjectSource localOnly(ObjectReader reader, HistoryReader history) {
        return new CachingObjectSource(
                Objects.requireNonNull(reader),
                null,
                history,
                null,
                null,
                null,
                List.of(),
                Verification.CDN,
                new Options(),
                true,
                null);
    }

    public static CachingObjectSource cdnOnly(CdnClient cdn, List<ObservationSource> observations) {
        return new CachingObjectSource(
                null,
                null,
                null,
                null,
                null,
                Objects.requireNonNull(cdn),
                observations,
                Verification.CDN,
                new Options(),
                false,
                cdn);
    }

    public StreamScheduler streams() {
        return streams;
    }

    @Override
    public StoreCommitSubscription subscribe(int capacity) {
        return commits.subscribe(capacity);
    }

    private final class Group {
        final Cancellation token = new Cancellation();
        final List<Claim> owned = new ArrayList<>();
        final CdnContext context;
        final boolean withBytes;
        final Progress progress;
        final FetchPriority initialPriority;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile BatchPump pump;
        StreamScheduler.Ticket<Void> ticket;

        Group(
                CdnContext context,
                boolean withBytes,
                Consumer<ObjectProgress> progress,
                long total,
                FetchPriority priority) {
            this.context = context;
            this.withBytes = withBytes;
            this.progress = new Progress(progress, total);
            this.initialPriority = priority;
        }

        void abandoned() {
            synchronized (claims) {
                if (owned.stream().allMatch(c -> c.waiters.get() == 0)) {
                    token.cancel();
                }
            }
        }

        void finish(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            for (Claim claim : owned) {
                if (!claim.answer.isDone()) {
                    claim.fail(
                            error != null
                                    ? error
                                    : claim.waiters.get() == 0
                                            ? new java.util.concurrent.CancellationException()
                                            : new IllegalStateException(
                                                    "Transfer ended without settling key"));
                }
            }
            pendingGroups.decrementAndGet();
            lifetime.release();
        }
    }

    /** One logical network transfer: one loose object or one merged archive range. */
    private final class Transfer {
        final Group parent;
        final List<Claim> members;
        final CdnContext context;
        final Progress progress;
        final long reservation;
        final int order;
        final Cancellation token = new Cancellation();
        final Consumer<Transfer> action;
        final AtomicBoolean finished = new AtomicBoolean();
        final Cancellation.Registration parentCancellation;
        volatile StreamScheduler.Ticket<Void> ticket;

        Transfer(Group parent, List<Claim> members, long reservation, Consumer<Transfer> action) {
            this.parent = parent;
            this.members = List.copyOf(members);
            this.context = parent.context;
            this.progress = parent.progress;
            this.reservation = Math.max(1, reservation);
            this.order = members.getFirst().request.order();
            this.action = action;
            parentCancellation = parent.token.onCancel(token::cancel);
            for (Claim claim : members) {
                claim.transfer = this;
            }
        }

        FetchPriority priority() {
            return parent.initialPriority == FetchPriority.INTERACTIVE
                            || members.stream().anyMatch(c -> c.urgent)
                    ? FetchPriority.INTERACTIVE
                    : FetchPriority.BACKGROUND;
        }

        void promote() {
            var current = ticket;
            if (current != null) {
                current.promote();
            }
        }

        void abandoned() {
            if (members.stream().allMatch(c -> c.waiters.get() == 0)) {
                token.cancel();
            }
        }

        boolean start() {
            boolean establishedBlock = false;
            if (finished.get()) {
                return true;
            }
            try {
                if (members.stream().allMatch(c -> c.waiters.get() == 0)) {
                    token.cancel();
                }
                StreamScheduler.Ticket<Void> submitted =
                        streams.submit(
                                priority(),
                                reservation,
                                token,
                                () -> {
                                    action.accept(this);
                                    return null;
                                });
                ticket = submitted;
                if (submitted.rejected()) {
                    Throwable error =
                            submitted.future().handle((value, failure) -> unwrap(failure)).join();
                    if (error instanceof IllegalStateException
                            && "OBJECT_ACCESS_LIMIT".equals(error.getMessage())) {
                        parent.pump.block();
                        establishedBlock = true;
                        streams.whenAvailable(token)
                                .whenComplete(
                                        (ignored, failure) ->
                                                Thread.startVirtualThread(
                                                        () -> {
                                                            if (failure != null) {
                                                                finish(failure);
                                                                parent.pump.unblock();
                                                            } else if (start()) {
                                                                parent.pump.unblock();
                                                            }
                                                        }));
                        return false;
                    }
                    finish(error);
                    return true;
                }
                if (priority() == FetchPriority.INTERACTIVE) {
                    submitted.promote();
                }
                submitted.future().whenComplete((ignored, error) -> finish(error));
                return true;
            } catch (Throwable error) {
                finish(error);
                if (establishedBlock) {
                    parent.pump.unblock();
                }
                return true;
            }
        }

        void finish(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            parentCancellation.close();
            for (Claim claim : members) {
                if (!claim.answer.isDone()) {
                    claim.fail(
                            error != null
                                    ? error
                                    : claim.waiters.get() == 0
                                            ? new java.util.concurrent.CancellationException()
                                            : new IllegalStateException(
                                                    "Transfer ended without settling key"));
                }
            }
            parent.pump.done();
        }
    }

    /** Nonblocking bounded fan-out: coordinator never occupies a worker while children need it. */
    private final class BatchPump {
        final Group parent;
        final List<Transfer> work;
        final int window;
        final Object gate = new Object();
        final AtomicBoolean draining = new AtomicBoolean();
        int next, active, done, activeOrder;
        boolean blocked;

        BatchPump(Group parent, List<Transfer> work) {
            this.parent = parent;
            this.work = List.copyOf(work);
            this.window = Math.max(1, options.maxBackground() + options.interactive());
        }

        void block() {
            synchronized (gate) {
                blocked = true;
            }
        }

        void unblock() {
            synchronized (gate) {
                blocked = false;
            }
            drain();
        }

        void done() {
            boolean all;
            synchronized (gate) {
                active--;
                done++;
                all = done == work.size();
            }
            if (all) {
                parent.finish(null);
            } else {
                drain();
            }
        }

        boolean ready() {
            synchronized (gate) {
                return !blocked
                        && active < window
                        && next < work.size()
                        && (active == 0 || work.get(next).order == activeOrder);
            }
        }

        void drain() {
            if (work.isEmpty()) {
                parent.finish(null);
                return;
            }
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            do {
                try {
                    while (true) {
                        Transfer transfer;
                        synchronized (gate) {
                            if (blocked
                                    || active >= window
                                    || next == work.size()
                                    || active > 0 && work.get(next).order != activeOrder) {
                                break;
                            }
                            transfer = work.get(next++);
                            if (active == 0) {
                                activeOrder = transfer.order;
                            }
                            active++;
                        }
                        if (!transfer.start()) {
                            break;
                        }
                    }
                } finally {
                    draining.set(false);
                }
            } while (ready() && draining.compareAndSet(false, true));
        }
    }

    private final class Claim {
        final ObjectRequest request;
        final Group group;
        final CompletableFuture<ObjectResult> answer = new CompletableFuture<>();
        final AtomicInteger waiters = new AtomicInteger();
        volatile boolean wantBytes, urgent;
        volatile Transfer transfer;

        Claim(ObjectRequest request, Group group) {
            this.request = request;
            this.group = group;
            this.wantBytes = group.withBytes;
        }

        CompletableFuture<ObjectResult> join(
                Cancellation token, FetchPriority priority, boolean withBytes) {
            if (withBytes) {
                wantBytes = true;
            }
            waiters.incrementAndGet();
            if (priority == FetchPriority.INTERACTIVE) {
                urgent = true;
                Transfer operation = transfer;
                if (operation != null) {
                    operation.promote();
                } else if (group.ticket != null) {
                    group.ticket.promote();
                }
            }
            CompletableFuture<ObjectResult> result = new CompletableFuture<>();
            AtomicBoolean left = new AtomicBoolean();
            AtomicReference<Cancellation.Registration> registration = new AtomicReference<>();
            Runnable leave =
                    () -> {
                        if (left.compareAndSet(false, true)
                                && waiters.decrementAndGet() == 0
                                && !answer.isDone()) {
                            Transfer operation = transfer;
                            if (operation != null) {
                                operation.abandoned();
                            }
                            group.abandoned();
                        }
                    };
            Cancellation.Registration registered =
                    token.onCancel(
                            () -> {
                                leave.run();
                                Thread.startVirtualThread(
                                        () ->
                                                result.completeExceptionally(
                                                        new java.util.concurrent
                                                                .CancellationException()));
                            });
            registration.set(registered);
            if (left.get()) {
                registered.close();
            }
            answer.whenComplete(
                    (value, error) -> {
                        registered.close();
                        leave.run();
                        Thread.startVirtualThread(
                                () -> {
                                    if (error == null) {
                                        result.complete(value);
                                    } else {
                                        result.completeExceptionally(unwrap(error));
                                    }
                                });
                    });
            return result;
        }

        void complete(ObjectResult result) {
            synchronized (claims) {
                answer.complete(wantBytes ? result : result.withoutBytes());
                claims.remove(request.key(), this);
            }
        }

        void fail(Throwable error) {
            answer.completeExceptionally(unwrap(error));
            synchronized (claims) {
                claims.remove(request.key(), this);
            }
        }
    }

    private static final class Progress {
        final Consumer<ObjectProgress> consumer;
        final long total;
        final AtomicInteger done = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong bytes =
                new java.util.concurrent.atomic.AtomicLong();
        final AtomicReference<ObjectProgress> latest = new AtomicReference<>();
        final AtomicBoolean publishing = new AtomicBoolean();

        Progress(Consumer<ObjectProgress> consumer, long total) {
            this.consumer = consumer;
            this.total = total;
        }

        void transferred(long count) {
            bytes.addAndGet(count);
        }

        void done() {
            int count = done.incrementAndGet();
            if (consumer == null) {
                return;
            }
            latest.set(new ObjectProgress(count, total, bytes.get()));
            if (publishing.compareAndSet(false, true)) {
                Thread.startVirtualThread(this::drain);
            }
        }

        void drain() {
            while (true) {
                ObjectProgress value = latest.getAndSet(null);
                if (value != null) {
                    try {
                        consumer.accept(value);
                    } catch (Throwable ignored) {
                        /* Progress cannot fail durable work. */
                    }
                }
                publishing.set(false);
                if (latest.get() == null || !publishing.compareAndSet(false, true)) {
                    return;
                }
            }
        }
    }

    private CompletableFuture<List<ObjectResult>> request(
            List<ObjectRequest> requests,
            CdnContext context,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token,
            boolean withBytes) {
        Objects.requireNonNull(priority);
        Objects.requireNonNull(token);
        List<ObjectRequest> copy = List.copyOf(requests);
        if (token.isCanceled()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        }
        Group group =
                new Group(
                        context,
                        withBytes,
                        progress,
                        copy.stream().map(ObjectRequest::key).distinct().count(),
                        priority);
        List<CompletableFuture<ObjectResult>> answers = new ArrayList<>();
        java.util.Set<ObjectKey> reported = new java.util.HashSet<>();
        synchronized (claims) {
            List<CompletableFuture<?>> abandoned = new ArrayList<>();
            for (ObjectRequest wanted : copy) {
                Claim old = claims.get(wanted.key());
                if (old != null && old.answer.isDone()) {
                    claims.remove(wanted.key(), old);
                } else if (old != null
                        && (old.group.token.isCanceled()
                                || old.transfer != null && old.transfer.token.isCanceled())) {
                    abandoned.add(old.answer.handle((value, error) -> null));
                }
            }
            if (!abandoned.isEmpty()) {
                return waitFor(
                                CompletableFuture.allOf(
                                        abandoned.toArray(CompletableFuture[]::new)),
                                token)
                        .thenCompose(
                                ignored ->
                                        request(
                                                copy, context, priority, progress, token,
                                                withBytes));
            }
            for (ObjectRequest wanted : copy) {
                Claim claim = claims.get(wanted.key());
                if (claim == null) {
                    claim = new Claim(wanted, group);
                    group.owned.add(claim);
                    claims.put(wanted.key(), claim);
                }
                CompletableFuture<ObjectResult> answer = claim.join(token, priority, withBytes);
                answers.add(answer);
                if (reported.add(wanted.key())) {
                    answer.whenComplete((value, error) -> group.progress.done());
                }
            }
            if (!group.owned.isEmpty()) {
                int groups = pendingGroups.incrementAndGet();
                if (groups > options.queued() + options.maxBackground() + options.interactive()) {
                    pendingGroups.decrementAndGet();
                    for (Claim claim : group.owned) {
                        claim.fail(new IllegalStateException("OBJECT_ACCESS_LIMIT"));
                    }
                    return CompletableFuture.allOf(answers.toArray(CompletableFuture[]::new))
                            .thenApply(
                                    ignored ->
                                            answers.stream().map(CompletableFuture::join).toList());
                }
                lifetime.retain();
                long reservation =
                        group.owned.stream()
                                .mapToLong(
                                        c ->
                                                c.request.declaredLength() == null
                                                        ? 4L << 20
                                                        : c.request.declaredLength()
                                                                        > options
                                                                                .inMemoryObjectBytes()
                                                                ? 1L << 20
                                                                : Math.max(
                                                                        1,
                                                                        c.request.declaredLength()))
                                .max()
                                .orElse(1);
                if (group.owned.stream().anyMatch(c -> !c.request.locations().isEmpty())) {
                    reservation = Math.max(reservation, 16L << 20);
                }
                group.ticket =
                        streams.submit(
                                priority,
                                reservation,
                                group.token,
                                () -> {
                                    load(group);
                                    return null;
                                });
                group.ticket
                        .future()
                        .whenComplete(
                                (ignored, error) -> {
                                    if (error != null) {
                                        group.finish(error);
                                    } else if (group.pump == null) {
                                        group.finish(null);
                                    }
                                });
            }
        }
        return CompletableFuture.allOf(answers.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> answers.stream().map(CompletableFuture::join).toList());
    }

    private static <T> CompletableFuture<T> waitFor(
            CompletableFuture<T> shared, Cancellation token) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Cancellation.Registration registration =
                token.onCancel(
                        () ->
                                Thread.startVirtualThread(
                                        () ->
                                                result.completeExceptionally(
                                                        new java.util.concurrent
                                                                .CancellationException())));
        shared.whenComplete(
                (value, error) -> {
                    registration.close();
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(unwrap(error));
                    }
                });
        return result;
    }

    @Override
    public CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext context, FetchPriority priority, Cancellation token) {
        return lifetime.run(() -> getKeyCore(request, context, priority, token));
    }

    private CompletableFuture<ObjectResult> getKeyCore(
            ObjectRequest request, CdnContext context, FetchPriority priority, Cancellation token) {
        return request(List.of(request), context, priority, null, token, true)
                .thenCompose(
                        results -> {
                            ObjectResult result = results.getFirst();
                            if (!result.isPresent()
                                    || result.bytes() != null
                                    || reader == null
                                    || result.length() != null
                                            && result.length() > options.inMemoryObjectBytes()) {
                                return CompletableFuture.completedFuture(result);
                            }
                            return reader.read(request.key(), token.storeToken())
                                    .thenApply(
                                            read ->
                                                    read.state() == StoreTypes.ReadState.PRESENT
                                                            ? present(
                                                                    request.key(),
                                                                    result.servedFrom(),
                                                                    read.info().origin(),
                                                                    read.info().length(),
                                                                    read.bytes())
                                                            : result);
                        });
    }

    @Override
    public CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext context,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return lifetime.run(() -> getKeysCore(requests, context, priority, progress, token));
    }

    private CompletableFuture<List<ObjectResult>> getKeysCore(
            List<ObjectRequest> requests,
            CdnContext context,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return request(requests, context, priority, progress, token, false)
                .thenApply(results -> results.stream().map(ObjectResult::withoutBytes).toList());
    }

    @Override
    public CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        return lifetime.run(() -> getRangeCore(request, offset, length, context, priority, token));
    }

    private CompletableFuture<ObjectResult> getRangeCore(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        if (offset < 0
                || length < 0
                || offset > Long.MAX_VALUE - length
                || request.declaredLength() != null && offset > request.declaredLength() - length) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Range past the object's end"));
        }
        return streams.submit(
                        priority,
                        1,
                        token,
                        () -> {
                            StoreTypes.ObjectRead read =
                                    reader == null
                                            ? null
                                            : reader.readRange(
                                                            request.key(),
                                                            offset,
                                                            length,
                                                            token.storeToken())
                                                    .join();
                            if (read != null && read.state() == StoreTypes.ReadState.PRESENT) {
                                return CompletableFuture.completedFuture(
                                        present(
                                                request.key(),
                                                ServedFrom.STORE,
                                                read.info().origin(),
                                                read.info().length(),
                                                read.bytes()));
                            }
                            if (read != null && read.state() != StoreTypes.ReadState.ABSENT) {
                                if (writer == null) {
                                    return CompletableFuture.completedFuture(
                                            failure(
                                                    request.key(),
                                                    ObjectState.REFUSED,
                                                    "OBJECT_ACCESS_" + read.state(),
                                                    read.problem()));
                                }
                                drop(request.key(), token);
                            }
                            if (context != null
                                    && canRange(request)
                                    && (shared == null
                                            || !shared.has(request.key(), token.storeToken())
                                                    .join())) {
                                BlteRangeReader helper;
                                synchronized (this) {
                                    if (ranged == null) {
                                        ranged =
                                                new BlteRangeReader(
                                                        (key,
                                                                start,
                                                                count,
                                                                total,
                                                                check,
                                                                route,
                                                                rank,
                                                                cancel) ->
                                                                streams.submit(
                                                                                rank,
                                                                                count,
                                                                                cancel,
                                                                                () ->
                                                                                        fetchRange(
                                                                                                route,
                                                                                                key,
                                                                                                start,
                                                                                                count,
                                                                                                total,
                                                                                                check,
                                                                                                rank,
                                                                                                cancel))
                                                                        .future(),
                                                        Math.min(
                                                                options.interactiveInFlightBytes(),
                                                                options.inMemoryObjectBytes()));
                                    }
                                    helper = ranged;
                                }
                                return helper.read(
                                                request, offset, length, context, priority, token)
                                        .thenCompose(
                                                bytes ->
                                                        bytes == null
                                                                ? wholeRange(
                                                                        request, offset, length,
                                                                        context, priority, token)
                                                                : CompletableFuture.completedFuture(
                                                                        present(
                                                                                request.key(),
                                                                                ServedFrom.CDN,
                                                                                StoreTypes.Origin
                                                                                        .CDN,
                                                                                request
                                                                                        .declaredLength(),
                                                                                bytes)));
                            }
                            return wholeRange(request, offset, length, context, priority, token);
                        })
                .future()
                .thenCompose(value -> value)
                .exceptionallyCompose(
                        error -> {
                            Throwable cause = unwrap(error);
                            if (cause instanceof CdnException failure) {
                                return CompletableFuture.completedFuture(
                                        fromCdn(request.key(), failure));
                            }
                            if (cause instanceof com.vibeminer.network.NetworkException network) {
                                return CompletableFuture.completedFuture(
                                        failure(
                                                request.key(),
                                                ObjectState.UNAVAILABLE,
                                                network.code(),
                                                network.getMessage()));
                            }
                            return CompletableFuture.failedFuture(cause);
                        });
    }

    private boolean canRange(ObjectRequest request) {
        return request.key().type() == ObjectKey.ENCODED
                && request.key().namespace() == 0
                && request.locations().isEmpty()
                && request.declaredLength() != null
                && request.declaredLength() >= options.rangeFetchMinBytes();
    }

    private byte[] fetchRange(
            CdnContext context,
            ObjectKey key,
            long offset,
            int length,
            long total,
            NetworkTypes.Check check,
            FetchPriority priority,
            Cancellation token) {
        try {
            var reply =
                    client(priority)
                            .fetch(
                                    order(context.route(), priority),
                                    NetworkTypes.Request.range(
                                            NetworkTypes.ObjectKind.DATA,
                                            new CdnKey(key.bytes()),
                                            offset,
                                            length,
                                            total,
                                            check),
                                    token.networkToken())
                            .join();
            observe(reply);
            return reply.bytes();
        } catch (RuntimeException e) {
            Throwable failure = unwrap(e);
            if (failure instanceof CdnException cdnFailure) {
                observe(cdnFailure);
            }
            throw e;
        }
    }

    private CompletableFuture<ObjectResult> wholeRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        return getKeyCore(request, context, priority, token)
                .thenCompose(
                        result -> {
                            if (!result.isPresent()) {
                                return CompletableFuture.completedFuture(result);
                            }
                            if (reader != null) {
                                return reader.readRange(
                                                request.key(), offset, length, token.storeToken())
                                        .thenApply(
                                                read ->
                                                        read.state() == StoreTypes.ReadState.PRESENT
                                                                ? present(
                                                                        request.key(),
                                                                        result.servedFrom(),
                                                                        read.info().origin(),
                                                                        read.info().length(),
                                                                        read.bytes())
                                                                : failure(
                                                                        request.key(),
                                                                        ObjectState.REFUSED,
                                                                        "OBJECT_ACCESS_"
                                                                                + read.state(),
                                                                        read.problem()));
                            }
                            byte[] bytes = result.bytes();
                            if (bytes == null) {
                                return CompletableFuture.completedFuture(result);
                            }
                            if (offset > bytes.length - length) {
                                return CompletableFuture.failedFuture(
                                        new IllegalArgumentException(
                                                "Range past the object's end"));
                            }
                            return CompletableFuture.completedFuture(
                                    present(
                                            request.key(),
                                            result.servedFrom(),
                                            result.origin(),
                                            (long) bytes.length,
                                            Arrays.copyOfRange(
                                                    bytes, (int) offset, (int) offset + length)));
                        });
    }

    private void load(Group group) {
        List<Claim> missing = new ArrayList<>();
        for (Claim claim : group.owned) {
            group.token.check();
            if (claim.waiters.get() == 0) {
                claim.fail(new java.util.concurrent.CancellationException());
                continue;
            }
            ObjectResult local = local(claim.request, group.withBytes, group.token);
            if (local != null) {
                claim.complete(local);
                continue;
            }
            ObjectResult copied = copyShared(claim.request, group.token);
            if (copied != null) {
                claim.complete(copied);
                continue;
            }
            if (localOnly) {
                claim.complete(
                        failure(
                                claim.request.key(),
                                ObjectState.NEEDS_NETWORK,
                                "OBJECT_ACCESS_NEEDS_NETWORK",
                                "Object is not stored"));
                continue;
            }
            if (group.context == null || cdn == null) {
                claim.complete(
                        failure(
                                claim.request.key(),
                                ObjectState.UNAVAILABLE,
                                "OBJECT_ACCESS_NO_CDN_CONTEXT",
                                "No CDN context was supplied"));
                continue;
            }
            ObjectResult bad = preflight(claim.request);
            if (bad != null) {
                claim.complete(bad);
            } else {
                missing.add(claim);
            }
        }
        missing.sort(Comparator.comparingInt(c -> c.request.order()));
        List<Transfer> work = new ArrayList<>();
        Map<Integer, List<Claim>> groups = new LinkedHashMap<>();
        for (Claim claim : missing) {
            groups.computeIfAbsent(claim.request.order(), ignored -> new ArrayList<>()).add(claim);
        }
        for (List<Claim> ordered : groups.values()) {
            Map<CdnKey, Claim> indexed = new LinkedHashMap<>();
            List<RangePlanner.ObjectToPlan> planned = new ArrayList<>();
            for (Claim claim : ordered) {
                if (claim.request.locations().isEmpty()) {
                    long bytes =
                            claim.request.declaredLength() == null
                                    ? 4L << 20
                                    : claim.request.declaredLength() > options.inMemoryObjectBytes()
                                            ? 1L << 20
                                            : claim.request.declaredLength();
                    work.add(
                            new Transfer(
                                    group,
                                    List.of(claim),
                                    bytes,
                                    transfer -> {
                                        if (claim.waiters.get() > 0) {
                                            claim.complete(loose(claim.request, transfer));
                                        }
                                    }));
                } else {
                    CdnKey key = new CdnKey(claim.request.key().bytes());
                    indexed.put(key, claim);
                    planned.add(
                            new RangePlanner.ObjectToPlan(
                                    key,
                                    claim.request.declaredLength(),
                                    claim.request.locations()));
                }
            }
            RangePlanner.Plan plan =
                    RangePlanner.plan(
                            planned, group.context.archives(), new RangePlanner.Options());
            for (var rejected : plan.rejected()) {
                Claim claim = indexed.get(rejected.key());
                claim.complete(
                        failure(
                                claim.request.key(),
                                ObjectState.REFUSED,
                                rejected.code(),
                                rejected.message()));
            }
            for (var range : plan.ranges()) {
                List<Claim> members =
                        range.members().stream().map(member -> indexed.get(member.key())).toList();
                work.add(
                        new Transfer(
                                group,
                                members,
                                range.length(),
                                transfer -> range(range, indexed, transfer, null, null)));
            }
        }
        group.pump = new BatchPump(group, work);
        group.pump.drain();
    }

    private ObjectResult local(ObjectRequest request, boolean withBytes, Cancellation token) {
        if (reader == null) {
            return null;
        }
        if (!withBytes
                || request.declaredLength() != null
                        && request.declaredLength() > options.inMemoryObjectBytes()) {
            return reader.has(request.key(), token.storeToken()).join()
                    ? present(request.key(), ServedFrom.STORE, null, request.declaredLength(), null)
                    : null;
        }
        try {
            var read = reader.read(request.key(), token.storeToken()).join();
            if (read.state() == StoreTypes.ReadState.PRESENT) {
                return present(
                        request.key(),
                        ServedFrom.STORE,
                        read.info().origin(),
                        read.info().length(),
                        read.bytes());
            }
            if (read.state() != StoreTypes.ReadState.ABSENT) {
                if (writer == null) {
                    return failure(
                            request.key(),
                            ObjectState.REFUSED,
                            "OBJECT_ACCESS_" + read.state(),
                            read.problem());
                }
                drop(request.key(), token);
            }
            return null;
        } catch (RuntimeException e) {
            Throwable failure = unwrap(e);
            if (failure instanceof StoreException store
                    && store.code().equals("OBJECT_STORE_LIMIT")) {
                try {
                    var probe = reader.readRange(request.key(), 0, 0, token.storeToken()).join();
                    if (probe.state() == StoreTypes.ReadState.ABSENT) {
                        return null;
                    }
                    if (probe.state() == StoreTypes.ReadState.PRESENT) {
                        if (probe.info() == null
                                || probe.info().length() <= options.inMemoryObjectBytes()) {
                            return failure(
                                    request.key(),
                                    ObjectState.UNAVAILABLE,
                                    store.code(),
                                    store.getMessage());
                        }
                        return present(
                                request.key(),
                                ServedFrom.STORE,
                                probe.info().origin(),
                                probe.info().length(),
                                null);
                    }
                    drop(request.key(), token);
                    return null;
                } catch (RuntimeException refused) {
                    Throwable actual = unwrap(refused);
                    if (actual instanceof StoreException limit
                            && limit.code().equals("OBJECT_STORE_LIMIT")) {
                        return failure(
                                request.key(),
                                ObjectState.UNAVAILABLE,
                                limit.code(),
                                limit.getMessage());
                    }
                    throw refused;
                }
            }
            throw e;
        }
    }

    private void drop(ObjectKey key, Cancellation token) {
        if (writer == null) {
            return;
        }
        try {
            var results = writer.delete(List.of(key), token.storeToken()).join();
            commits.publish(
                    List.of(),
                    results.stream()
                            .filter(r -> r.outcome() == StoreTypes.DeleteOutcome.DELETED)
                            .map(StoreTypes.DeleteResult::key)
                            .toList());
        } catch (RuntimeException e) {
            if (!(unwrap(e) instanceof StoreException)) {
                throw e;
            }
        }
    }

    private ObjectResult copyShared(ObjectRequest request, Cancellation token) {
        if (shared == null
                || writer == null
                || !shared.has(request.key(), token.storeToken()).join()) {
            return null;
        }
        if (request.declaredLength() != null
                && request.declaredLength() > options.inMemoryObjectBytes()) {
            ObjectStaging stage = null;
            try {
                stage =
                        writer.stage(
                                        request.key(),
                                        request.declaredLength(),
                                        StoreTypes.Origin.SHARED_STORE,
                                        token.storeToken())
                                .join();
                while (stage.length() < stage.declaredLength()) {
                    int count = (int) Math.min(4L << 20, stage.declaredLength() - stage.length());
                    StoreTypes.ObjectRead read;
                    try {
                        read =
                                shared.readRange(
                                                request.key(),
                                                stage.length(),
                                                count,
                                                token.storeToken())
                                        .join();
                    } catch (RuntimeException failed) {
                        if (unwrap(failed) instanceof StoreException) {
                            stage.abandon().join();
                            return null;
                        }
                        throw failed;
                    }
                    if (read.state() != StoreTypes.ReadState.PRESENT) {
                        stage.abandon().join();
                        return null;
                    }
                    stage.append(read.bytes(), token.storeToken()).join();
                }
                var result = stage.commit(token.storeToken()).join();
                publish(List.of(result));
                if (result.outcome() == StoreTypes.PutOutcome.REFUSED) {
                    return null;
                }
                return putResult(result, ServedFrom.SHARED_STORE, request.declaredLength(), null);
            } catch (RuntimeException e) {
                Throwable error = unwrap(e);
                if (error instanceof StoreException failure) {
                    if (stage != null && failure.code().equals("OBJECT_STORE_STAGING_REJECTED")) {
                        stage.abandon().join();
                        return null;
                    }
                    return failure(
                            request.key(),
                            ObjectState.STORE_FAILED,
                            failure.code(),
                            failure.getMessage());
                }
                throw e;
            } finally {
                if (stage != null) {
                    stage.closeAsync().join();
                }
            }
        }
        StoreTypes.ObjectRead read;
        try {
            read = shared.read(request.key(), token.storeToken()).join();
        } catch (RuntimeException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof StoreException limit
                    && limit.code().equals("OBJECT_STORE_LIMIT")) {
                return null;
            }
            throw error;
        }
        if (read.state() != StoreTypes.ReadState.PRESENT) {
            return null;
        }
        ObjectResult result =
                deliver(
                        request,
                        read.bytes(),
                        StoreTypes.Origin.SHARED_STORE,
                        ServedFrom.SHARED_STORE,
                        token);
        return result.state() == ObjectState.REFUSED ? null : result;
    }

    private ObjectResult preflight(ObjectRequest request) {
        if (request.key().namespace() != 0) {
            return failure(
                    request.key(),
                    ObjectState.REFUSED,
                    "OBJECT_NAMESPACE_UNSUPPORTED",
                    "Unsupported namespace");
        }
        if (kind(request.key()) == null) {
            return failure(
                    request.key(),
                    ObjectState.REFUSED,
                    "OBJECT_KEY_TYPE_UNSUPPORTED",
                    "Object has no CDN location");
        }
        if (request.declaredLength() != null
                && (request.declaredLength() < 1 || request.declaredLength() > (512L << 20))) {
            return failure(
                    request.key(),
                    ObjectState.REFUSED,
                    "OBJECT_TOO_LARGE",
                    "Invalid declared size");
        }
        if (!request.locations().isEmpty() && request.key().type() != ObjectKey.ENCODED) {
            return failure(
                    request.key(),
                    ObjectState.REFUSED,
                    "OBJECT_LOCATION_INVALID",
                    "Only encoded objects can be archived");
        }
        if (!request.locations().isEmpty()
                && request.locations().stream()
                        .anyMatch(l -> l.size() > options.inMemoryObjectBytes())) {
            return failure(
                    request.key(),
                    ObjectState.UNSUPPORTED,
                    "OBJECT_LOCATION_TOO_LARGE",
                    "Archived entry is larger than an in-memory fetch");
        }
        return null;
    }

    private ObjectResult loose(ObjectRequest request, Transfer group) {
        if (request.declaredLength() != null
                && request.declaredLength() > options.inMemoryObjectBytes()) {
            return large(request, group);
        }
        FetchPriority priority = group.priority();
        try {
            var reply =
                    client(priority)
                            .fetch(
                                    order(group.context.route(), group.priority()),
                                    NetworkTypes.Request.whole(
                                            kind(request.key()),
                                            new CdnKey(request.key().bytes()),
                                            request.declaredLength(),
                                            options.inMemoryObjectBytes(),
                                            bytes -> {
                                                var refusal =
                                                        verifier.check(
                                                                request.key(),
                                                                bytes,
                                                                request.declaredLength() == null
                                                                        ? bytes.length
                                                                        : request.declaredLength());
                                                return refusal == null ? null : refusal.code();
                                            }),
                                    group.token.networkToken())
                            .join();
            observe(reply);
            group.progress.transferred(reply.length());
            return deliver(
                    request, reply.bytes(), StoreTypes.Origin.CDN, ServedFrom.CDN, group.token);
        } catch (RuntimeException e) {
            Throwable error = unwrap(e);
            if (error instanceof CdnException cdnFailure) {
                observe(cdnFailure);
                return fromCdn(request.key(), cdnFailure);
            }
            if (error instanceof com.vibeminer.network.NetworkException network) {
                return failure(
                        request.key(),
                        ObjectState.UNAVAILABLE,
                        network.code(),
                        network.getMessage());
            }
            throw e;
        }
    }

    private ObjectResult large(ObjectRequest request, Transfer group) {
        if (writer == null) {
            return failure(
                    request.key(),
                    ObjectState.UNSUPPORTED,
                    "OBJECT_ACCESS_TOO_LARGE",
                    "CDN-only source cannot stage large objects");
        }
        List<NetworkTypes.Attempt> attempts = new ArrayList<>();
        String refused = null;
        for (String host : order(group.context.route(), group.priority()).hosts()) {
            group.token.check();
            ObjectStaging stage = null;
            try {
                stage =
                        writer.stage(
                                        request.key(),
                                        request.declaredLength(),
                                        StoreTypes.Origin.CDN,
                                        group.token.storeToken())
                                .join();
                ObjectStaging sink = stage;
                long offset = stage.length();
                if (offset < stage.declaredLength()) {
                    NetworkTypes.Request wanted =
                            offset == 0
                                    ? NetworkTypes.Request.whole(
                                            kind(request.key()),
                                            new CdnKey(request.key().bytes()),
                                            request.declaredLength(),
                                            null,
                                            null)
                                    : NetworkTypes.Request.range(
                                            kind(request.key()),
                                            new CdnKey(request.key().bytes()),
                                            offset,
                                            request.declaredLength() - offset,
                                            request.declaredLength(),
                                            null);
                    var reply =
                            client(group.priority())
                                    .fetchTo(
                                            group.context.route().withHosts(List.of(host)),
                                            wanted,
                                            (bytes, ignored) ->
                                                    sink.append(bytes, group.token.storeToken()),
                                            group.token.networkToken())
                                    .join();
                    observe(reply);
                    group.progress.transferred(reply.length());
                }
                var result = stage.commit(group.token.storeToken()).join();
                publish(List.of(result));
                if (result.outcome() == StoreTypes.PutOutcome.REFUSED) {
                    refused = result.code();
                    continue;
                }
                return putResult(result, ServedFrom.CDN, request.declaredLength(), null);
            } catch (RuntimeException e) {
                Throwable error = unwrap(e);
                if (error instanceof CdnException cdnFailure) {
                    observe(cdnFailure);
                    attempts.addAll(cdnFailure.attempts());
                } else if (error instanceof com.vibeminer.network.NetworkException network) {
                    return failure(
                            request.key(),
                            ObjectState.UNAVAILABLE,
                            network.code(),
                            network.getMessage());
                } else if (error instanceof StoreException store) {
                    if (store.code().equals("OBJECT_STORE_STAGING_REJECTED")) {
                        refused = store.getMessage();
                        hosts.sentBadBytes(host);
                    } else {
                        return failure(
                                request.key(),
                                ObjectState.STORE_FAILED,
                                store.code(),
                                store.getMessage());
                    }
                } else {
                    throw e;
                }
            } finally {
                if (stage != null) {
                    stage.closeAsync().join();
                }
            }
        }
        if (!attempts.isEmpty()) {
            return fromCdn(request.key(), new CdnException(attempts));
        }
        return failure(
                request.key(),
                ObjectState.REFUSED,
                refused == null ? "OBJECT_KEY_MISMATCH" : refused,
                "No host supplied verified object bytes");
    }

    private void range(
            RangePlanner.Range range,
            Map<CdnKey, Claim> members,
            Transfer group,
            String exclude,
            String previousCode) {
        List<RangePlanner.Member> alive =
                range.members().stream()
                        .filter(
                                m ->
                                        members.get(m.key()).waiters.get() > 0
                                                && !members.get(m.key()).answer.isDone())
                        .toList();
        if (alive.isEmpty()) {
            return;
        }
        long first = alive.getFirst().offsetInRange(),
                end = alive.getLast().offsetInRange() + alive.getLast().size();
        RangePlanner.Range actual =
                new RangePlanner.Range(
                        range.archive(),
                        range.offset() + first,
                        end - first,
                        alive.stream()
                                .map(
                                        m ->
                                                new RangePlanner.Member(
                                                        m.key(),
                                                        m.offsetInRange() - first,
                                                        m.size()))
                                .toList());
        CdnRoute route = order(group.context.route(), group.priority());
        if (exclude != null) {
            List<String> other = route.hosts().stream().filter(h -> !h.equals(exclude)).toList();
            if (other.isEmpty()) {
                for (var member : alive) {
                    Claim c = members.get(member.key());
                    c.complete(
                            failure(
                                    c.request.key(),
                                    ObjectState.REFUSED,
                                    previousCode,
                                    "No other host can verify this archive entry"));
                }
                return;
            }
            route = route.withHosts(other);
        }
        try {
            NetworkTypes.Check check =
                    alive.size() == 1
                            ? bytes -> {
                                Claim c = members.get(alive.getFirst().key());
                                var refused =
                                        verifier.check(
                                                c.request.key(), bytes, alive.getFirst().size());
                                return refused == null ? null : refused.code();
                            }
                            : null;
            var reply =
                    client(group.priority())
                            .fetch(
                                    route,
                                    NetworkTypes.Request.range(
                                            NetworkTypes.ObjectKind.DATA,
                                            actual.archive(),
                                            actual.offset(),
                                            actual.length(),
                                            null,
                                            check),
                                    group.token.networkToken())
                            .join();
            observe(reply);
            group.progress.transferred(reply.length());
            for (var part : RangePlanner.split(actual, reply.bytes())) {
                Claim c = members.get(part.key());
                if (c.answer.isDone() || c.waiters.get() == 0) {
                    continue;
                }
                byte[] bytes = part.bytes();
                var refused = verifier.check(c.request.key(), bytes, bytes.length);
                if (refused == null) {
                    c.complete(
                            deliver(
                                    c.request,
                                    bytes,
                                    StoreTypes.Origin.CDN,
                                    ServedFrom.CDN,
                                    group.token));
                } else {
                    hosts.sentBadBytes(reply.host());
                    var member =
                            actual.members().stream()
                                    .filter(m -> m.key().equals(part.key()))
                                    .findFirst()
                                    .orElseThrow();
                    range(
                            new RangePlanner.Range(
                                    actual.archive(),
                                    actual.offset() + member.offsetInRange(),
                                    member.size(),
                                    List.of(
                                            new RangePlanner.Member(
                                                    member.key(), 0, member.size()))),
                            members,
                            group,
                            reply.host(),
                            refused.code());
                }
            }
        } catch (RuntimeException e) {
            Throwable error = unwrap(e);
            if (error instanceof com.vibeminer.network.NetworkException network) {
                for (var member : alive) {
                    Claim c = members.get(member.key());
                    c.complete(
                            failure(
                                    c.request.key(),
                                    ObjectState.UNAVAILABLE,
                                    network.code(),
                                    network.getMessage()));
                }
                return;
            }
            if (!(error instanceof CdnException failure)) {
                throw e;
            }
            observe(failure);
            boolean retry =
                    alive.size() > 1
                            && failure.retryable()
                            && failure.overall() != NetworkTypes.Failure.NOT_FOUND
                            && failure.overall() != NetworkTypes.Failure.OFFLINE;
            for (var member : alive) {
                Claim c = members.get(member.key());
                if (retry) {
                    range(
                            new RangePlanner.Range(
                                    actual.archive(),
                                    actual.offset() + member.offsetInRange(),
                                    member.size(),
                                    List.of(
                                            new RangePlanner.Member(
                                                    member.key(), 0, member.size()))),
                            members,
                            group,
                            null,
                            null);
                } else {
                    c.complete(fromCdn(c.request.key(), failure));
                }
            }
        }
    }

    private ObjectResult deliver(
            ObjectRequest request,
            byte[] bytes,
            StoreTypes.Origin origin,
            ServedFrom served,
            Cancellation token) {
        long declared = request.declaredLength() == null ? bytes.length : request.declaredLength();
        var refusal = verifier.check(request.key(), bytes, declared);
        if (refusal != null) {
            return failure(request.key(), ObjectState.REFUSED, refusal.code(), refusal.message());
        }
        if (writer == null) {
            return present(request.key(), served, origin, declared, bytes);
        }
        try {
            var puts =
                    writer.put(
                                    List.of(
                                            new StoreTypes.PutObject(
                                                    request.key(), declared, bytes, origin)),
                                    true,
                                    token.storeToken())
                            .join();
            publish(puts);
            return putResult(puts.getFirst(), served, declared, bytes);
        } catch (RuntimeException e) {
            Throwable error = unwrap(e);
            if (error instanceof StoreException failed) {
                return failure(
                        request.key(),
                        ObjectState.STORE_FAILED,
                        failed.code(),
                        failed.getMessage());
            }
            throw e;
        }
    }

    private void publish(List<StoreTypes.PutResult> results) {
        commits.publish(
                results.stream()
                        .filter(r -> r.outcome() == StoreTypes.PutOutcome.STORED)
                        .map(StoreTypes.PutResult::key)
                        .toList(),
                List.of());
    }

    private ObjectResult putResult(
            StoreTypes.PutResult result, ServedFrom served, long length, byte[] bytes) {
        return switch (result.outcome()) {
            case STORED ->
                    present(
                            result.key(),
                            served,
                            served == ServedFrom.SHARED_STORE
                                    ? StoreTypes.Origin.SHARED_STORE
                                    : StoreTypes.Origin.CDN,
                            length,
                            bytes);
            case ALREADY_PRESENT -> present(result.key(), ServedFrom.STORE, null, length, bytes);
            case REFUSED ->
                    failure(
                            result.key(),
                            ObjectState.REFUSED,
                            result.code(),
                            "Store refused the object");
            case CANCELED ->
                    failure(
                            result.key(),
                            ObjectState.STORE_FAILED,
                            "OBJECT_ACCESS_PUT_CANCELED",
                            "Put was canceled before append");
        };
    }

    private NetworkTypes.ObjectKind kind(ObjectKey key) {
        return switch (key.type()) {
            case ObjectKey.CONFIG -> NetworkTypes.ObjectKind.CONFIG;
            case ObjectKey.ARCHIVE_INDEX, ObjectKey.FILE_INDEX -> NetworkTypes.ObjectKind.INDEX;
            case ObjectKey.ENCODED -> NetworkTypes.ObjectKind.DATA;
            default -> null;
        };
    }

    private CdnClient client(FetchPriority priority) {
        return priority == FetchPriority.INTERACTIVE ? interactiveCdn : cdn;
    }

    private CdnRoute order(CdnRoute route, FetchPriority priority) {
        return hosts.order(route, priority);
    }

    private void observe(NetworkTypes.Reply reply) {
        hosts.observe(reply);
        streams.observe(null);
    }

    private void observe(CdnException failure) {
        hosts.observe(failure);
        streams.observe(failure.failure());
    }

    static ObjectResult fromCdn(ObjectKey key, CdnException failure) {
        ObjectState state =
                switch (failure.overall()) {
                    case NOT_FOUND -> ObjectState.NOT_FOUND;
                    case VERIFICATION_FAILED, SIZE_CONFLICT, PROTOCOL, TOO_LARGE ->
                            ObjectState.REFUSED;
                    default -> ObjectState.UNAVAILABLE;
                };
        String code = "CDN_" + failure.overall();
        if (failure.overall() == NetworkTypes.Failure.VERIFICATION_FAILED) {
            for (int i = failure.attempts().size() - 1; i >= 0; i--) {
                String refusal = failure.attempts().get(i).code();
                if (refusal != null) {
                    code = refusal;
                    break;
                }
            }
        }
        return new ObjectResult(
                key,
                state,
                null,
                null,
                null,
                null,
                code,
                failure.getMessage(),
                failure.attempts().getLast().observedAt());
    }

    private static ObjectResult present(
            ObjectKey key, ServedFrom served, StoreTypes.Origin origin, Long length, byte[] bytes) {
        return new ObjectResult(
                key, ObjectState.PRESENT, served, origin, length, bytes, null, null, null);
    }

    private static ObjectResult failure(
            ObjectKey key, ObjectState state, String code, String message) {
        return new ObjectResult(key, state, null, null, null, null, code, message, null);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    @Override
    public CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        return lifetime.run(() -> observeCore(request, token));
    }

    private CompletableFuture<ObservationResult> observeCore(
            ObservationRequest request, Cancellation token) {
        Objects.requireNonNull(request);
        String problem = request.problem();
        if (problem != null) {
            return CompletableFuture.completedFuture(invalidObservation(problem));
        }
        ObservationSource source = observationSources.get(request.kind());
        if (source == null) {
            return CompletableFuture.completedFuture(
                    new ObservationResult(
                            ObjectState.REFUSED,
                            null,
                            false,
                            "OBSERVATION_SOURCE_MISSING",
                            "No source serves this observation",
                            null));
        }
        return streams.submit(
                        FetchPriority.INTERACTIVE,
                        1L << 20,
                        token,
                        () -> {
                            try {
                                Observation observation = source.fetch(request, token).join();
                                return request.record()
                                        ? append(observation, token)
                                        : new ObservationResult(
                                                ObjectState.PRESENT,
                                                observation,
                                                false,
                                                null,
                                                null,
                                                null);
                            } catch (RuntimeException e) {
                                Throwable error = unwrap(e);
                                if (error instanceof CdnException failure) {
                                    ObjectResult mapped = fromCdn(null, failure);
                                    return new ObservationResult(
                                            mapped.state(),
                                            null,
                                            false,
                                            mapped.code(),
                                            mapped.message(),
                                            mapped.observedAt());
                                }
                                if (error
                                        instanceof com.vibeminer.network.NetworkException network) {
                                    return new ObservationResult(
                                            ObjectState.UNAVAILABLE,
                                            null,
                                            false,
                                            network.code(),
                                            network.getMessage(),
                                            null);
                                }
                                throw e;
                            }
                        })
                .future();
    }

    @Override
    public CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        return lifetime.run(() -> recordCore(observation, token));
    }

    private CompletableFuture<ObservationResult> recordCore(
            Observation observation, Cancellation token) {
        String problem = ObservationRequest.recordProblem(observation);
        if (problem != null) {
            return CompletableFuture.completedFuture(invalidObservation(problem));
        }
        return streams.submit(
                        FetchPriority.INTERACTIVE,
                        Math.max(1, observation.bytes().length),
                        token,
                        () -> append(observation, token))
                .future();
    }

    private static ObservationResult invalidObservation(String problem) {
        return new ObservationResult(
                ObjectState.REFUSED, null, false, "OBSERVATION_REQUEST_INVALID", problem, null);
    }

    private ObservationResult append(Observation observation, Cancellation token) {
        if (historyLog == null) {
            return new ObservationResult(ObjectState.PRESENT, observation, false, null, null, null);
        }
        try {
            var results =
                    historyLog
                            .appendHistory(
                                    new History.Observation(
                                            observation.kind().historyKind(),
                                            observation.product(),
                                            observation.region(),
                                            observation.observedAt(),
                                            observation.url().toString(),
                                            observation.bytes()),
                                    token.storeToken())
                            .join();
            publish(results);
            for (var result : results) {
                if (result.outcome() == StoreTypes.PutOutcome.REFUSED
                        || result.outcome() == StoreTypes.PutOutcome.CANCELED) {
                    return new ObservationResult(
                            ObjectState.STORE_FAILED,
                            observation,
                            false,
                            result.code(),
                            "History put refused",
                            null);
                }
            }
            return new ObservationResult(ObjectState.PRESENT, observation, true, null, null, null);
        } catch (RuntimeException e) {
            Throwable error = unwrap(e);
            if (error instanceof StoreException failure) {
                return new ObservationResult(
                        ObjectState.STORE_FAILED,
                        observation,
                        false,
                        failure.code(),
                        failure.getMessage(),
                        null);
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
        return lifetime.run(() -> hasKeysCore(keys, token));
    }

    private CompletableFuture<List<Boolean>> hasKeysCore(List<ObjectKey> keys, Cancellation token) {
        List<ObjectKey> owned = List.copyOf(keys);
        return streams.submit(
                        FetchPriority.INTERACTIVE,
                        1,
                        token,
                        () -> {
                            List<Boolean> values = new ArrayList<>(owned.size());
                            for (ObjectKey key : owned) {
                                token.check();
                                values.add(
                                        reader != null
                                                && reader.has(key, token.storeToken()).join());
                            }
                            return List.copyOf(values);
                        })
                .future();
    }

    @Override
    public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
        return lifetime.run(() -> countPresentCore(keys, token));
    }

    private CompletableFuture<Long> countPresentCore(List<ObjectKey> keys, Cancellation token) {
        return hasKeysCore(keys, token)
                .thenApply(values -> values.stream().filter(Boolean::booleanValue).count());
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return lifetime.run(() -> readHistoryCore(query, token));
    }

    private CompletableFuture<List<History.Entry>> readHistoryCore(
            History.Query query, Cancellation token) {
        return streams.submit(
                        FetchPriority.INTERACTIVE,
                        1,
                        token,
                        () ->
                                historyReader == null
                                        ? List.<History.Entry>of()
                                        : historyReader
                                                .readHistory(query, token.storeToken())
                                                .join())
                .future();
    }

    /** Drains access work only. Composition owns lower stores and network clients. */
    public CompletableFuture<Void> closeAsync() {
        return lifetime.close(streams::closeAsync);
    }
}
