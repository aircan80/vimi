package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.Side;
import com.vibeminer.concepts.comparison.effects.EffectsStageCodec;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Charged sequential effects passes. Invoke on an opening worker, never FX or an input worker. */
public final class EffectsStaging {
    private static final long CODEC = 8L << 20, PROJECTOR = 3L << 20, HEAD = 4L << 20;

    private EffectsStaging() {}

    public enum StageOrder {
        ROUTED,
        LINK
    }

    /**
     * After same-budget admission consumes the sorter on every path; returns a charged route spool.
     */
    public static BudgetedTempFile routeBridge(
            SortedComparisonInput ownIds,
            Side side,
            EffectsSnapshot snapshot,
            Path directory,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        require(snapshot, budget);
        var codec = new EffectsStageCodec(snapshot);
        return transform(
                ownIds,
                directory,
                budget,
                token,
                record -> {
                    if (!codec.sourceRef(record).equals(snapshot.relationRef(side))) {
                        throw new IllegalArgumentException(
                                "Effects route source differs from endpoint");
                    }
                    return codec.routeBridge(record);
                },
                false);
    }

    /** Direct mode also sorts by own ID first, so duplicate own IDs cannot hide across parents. */
    public static BudgetedTempFile directCandidates(
            SortedComparisonInput ownIds,
            Side side,
            EffectsSnapshot snapshot,
            Path directory,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        require(snapshot, budget);
        if (snapshot.mode(side) != RelationMode.DIRECT) {
            throw new IllegalArgumentException("Expected direct effects mode");
        }
        var codec = new EffectsStageCodec(snapshot);
        return transform(
                ownIds,
                directory,
                budget,
                token,
                record -> codec.candidate(side, record, null),
                false);
    }

    /**
     * After same-budget admission consumes a charged spool, including capture failure; never
     * reserves a nested standalone reader. The sorter supplies the source's4MiB combined
     * frame/decode/page allowance.
     */
    public static SortedComparisonInput sortSpool(
            BudgetedTempFile spool,
            Side side,
            EffectsSnapshot snapshot,
            Path directory,
            TempBudget budget,
            Cancellation token,
            StageOrder order)
            throws IOException {
        require(snapshot, budget);
        Objects.requireNonNull(order);
        SpoolSource source = null;
        try {
            source = new SpoolSource(spool, descriptor(snapshot, side, order), budget);
            return SortedComparisonInput.capture(
                            source,
                            directory,
                            budget,
                            token,
                            order == StageOrder.ROUTED
                                    ? SortedComparisonInput.KeyPolicy.EFFECT_ROUTE
                                    : SortedComparisonInput.KeyPolicy.EFFECT_LINK)
                    .join();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (source != null) {
                    source.closeAsync().join();
                } else {
                    spool.close();
                }
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Seals sorted candidates; consumes the sorter on every path after same-budget admission. */
    public static BudgetedTempFile sealCandidates(
            SortedComparisonInput sorted,
            EffectsSnapshot snapshot,
            Path directory,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        require(snapshot, budget);
        return transform(sorted, directory, budget, token, record -> record, true);
    }

    /**
     * After same-budget/mode admission consumes both inputs on every path. Routed links join
     * own-ID-sorted effects. A null effect input means explicit unknown target scope, never
     * certified absence.
     */
    @SuppressWarnings("try")
    public static BudgetedTempFile joinBridge(
            SortedComparisonInput routed,
            SortedComparisonInput effects,
            Side side,
            EffectsSnapshot snapshot,
            Path directory,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        require(snapshot, budget);
        if (snapshot.mode(side) != RelationMode.BRIDGE) {
            throw new IllegalArgumentException("Expected bridge effects mode");
        }
        var codec = new EffectsStageCodec(snapshot);
        BudgetedTempFile output = null;
        Throwable problem = null;
        try (var codecMemory = budget.reserveMemory("effects-join-codec", CODEC, token);
                var projectMemory =
                        budget.reserveMemory("effects-join-projector", PROJECTOR, token);
                var links = new Head(routed, budget, token, KeyKind.ROUTED);
                var targets =
                        effects == null ? null : new Head(effects, budget, token, KeyKind.OWN)) {
            output =
                    BudgetedTempFile.create(
                            directory, "effects-candidates-", budget, "effects-candidates", token);
            links.advance(token);
            if (targets != null) {
                targets.advance(token);
            }
            while (links.row != null) {
                token.check();
                if (!codec.sourceRef(links.row).equals(snapshot.relationRef(side))) {
                    throw new IOException("Routed effect link differs from endpoint");
                }
                Long reference = EffectsStageCodec.reference(codec.decodeSource(links.row));
                Record target = null;
                if (reference != null && reference > 0 && targets != null) {
                    String key = EffectsStageCodec.ownKey(reference);
                    while (targets.row != null && targets.row.key().compareTo(key) < 0) {
                        targets.advance(token);
                    }
                    if (targets.row != null && targets.row.key().equals(key)) {
                        target = targets.row;
                    }
                }
                var candidate = codec.candidate(side, links.row, target);
                if (candidate != null) {
                    byte[] frame = ComparisonCodec.pair(candidate, null);
                    output.appendFrame(frame, token);
                    frame = null;
                }
                candidate = null;
                target = null;
                links.advance(token);
            }
            output.seal();
        } catch (IOException | RuntimeException | Error failure) {
            problem = failure;
            throw failure;
        } finally {
            Throwable cleanup = closeInputs(routed, effects);
            if (problem != null || cleanup != null) {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Throwable deleting) {
                        if (cleanup == null) {
                            cleanup = deleting;
                        } else {
                            cleanup.addSuppressed(deleting);
                        }
                    }
                }
                if (problem != null && cleanup != null) {
                    problem.addSuppressed(cleanup);
                } else if (cleanup != null) {
                    throw new IOException("Effects join cleanup failed", cleanup);
                }
            }
        }
        return output;
    }

    @SuppressWarnings("try")
    private static BudgetedTempFile transform(
            SortedComparisonInput input,
            Path directory,
            TempBudget budget,
            Cancellation token,
            java.util.function.Function<Record, Record> projector,
            boolean finalKeys)
            throws IOException {
        BudgetedTempFile output = null;
        Throwable problem = null;
        try (var codec = budget.reserveMemory("effects-pass-codec", CODEC, token);
                var project = budget.reserveMemory("effects-pass-projector", PROJECTOR, token);
                var head =
                        new Head(input, budget, token, finalKeys ? KeyKind.FINAL : KeyKind.OWN)) {
            output =
                    BudgetedTempFile.create(
                            directory, "effects-pass-", budget, "effects-pass", token);
            head.advance(token);
            while (head.row != null) {
                token.check();
                var transformed = projector.apply(head.row);
                if (transformed != null) {
                    if (SortedComparisonInput.retainedBytes(transformed)
                            > EffectsStageCodec.MAX_STAGE_RETAINED) {
                        throw new ComparisonLimit("Effects stage retained output limit");
                    }
                    byte[] frame = ComparisonCodec.pair(transformed, null);
                    output.appendFrame(frame, token);
                    frame = null;
                }
                transformed = null;
                head.advance(token);
            }
            output.seal();
        } catch (IOException | RuntimeException | Error failure) {
            problem = failure;
            throw failure;
        } finally {
            Throwable cleanup = closeInputs(input, null);
            if (problem != null || cleanup != null) {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Throwable deleting) {
                        if (cleanup == null) {
                            cleanup = deleting;
                        } else {
                            cleanup.addSuppressed(deleting);
                        }
                    }
                }
                if (problem != null && cleanup != null) {
                    problem.addSuppressed(cleanup);
                } else if (cleanup != null) {
                    throw new IOException("Effects pass cleanup failed", cleanup);
                }
            }
        }
        return output;
    }

    private static Throwable closeInputs(SortedComparisonInput a, SortedComparisonInput b) {
        Throwable result = null;
        for (var input : new SortedComparisonInput[] {a, b}) {
            if (input == null) {
                continue;
            }
            try {
                input.closeAsync().join();
            } catch (Throwable failure) {
                if (result == null) {
                    result = failure;
                } else {
                    result.addSuppressed(failure);
                }
            }
        }
        return result;
    }

    private static void require(EffectsSnapshot snapshot, TempBudget budget) {
        if (snapshot.budget() != budget) {
            throw new IllegalArgumentException("Effects staging requires shared budget");
        }
        snapshot.metadataReservation().requireMemory(budget, EffectsSnapshot.METADATA_BYTES);
    }

    private static ComparisonTypes.Descriptor descriptor(
            EffectsSnapshot snapshot, Side side, StageOrder order) {
        var endpoint = snapshot.endpoint(side);
        return new ComparisonTypes.Descriptor(
                "effects-spool-" + UUID.randomUUID(),
                endpoint.build().product(),
                endpoint.locale(),
                order == StageOrder.ROUTED ? "effects-private-route-v1" : "effects-private-link-v1",
                "effects-private-stage-v1",
                Map.of(
                        "effects.endpointSession",
                        endpoint.sessionId(),
                        "effects.side",
                        side.name(),
                        "comparison.keyPolicy",
                        order == StageOrder.ROUTED ? "EFFECT_ROUTE" : "EFFECT_LINK"));
    }

    private enum KeyKind {
        OWN,
        ROUTED,
        FINAL
    }

    private static void key(String value, KeyKind kind) throws IOException {
        boolean valid =
                switch (kind) {
                    case OWN ->
                            value.matches("[0-9]{20}")
                                    && value.compareTo(EffectsStageCodec.ownKey(0xffff_ffffL)) <= 0;
                    case ROUTED -> value.matches("[01][0-9]{20}B[0-9]{40}");
                    case FINAL -> value.matches("[BD][0-9]{40}");
                };
        if (!valid) {
            throw new IOException("Invalid effects stage key");
        }
    }

    private static final class Head implements AutoCloseable {
        private final SortedComparisonInput input;
        private final TempBudget.Reservation memory;
        private final KeyKind kind;
        private String cursor, previous;
        private boolean exhausted;
        private Record row;

        Head(SortedComparisonInput input, TempBudget budget, Cancellation token, KeyKind kind)
                throws IOException {
            this.input = Objects.requireNonNull(input);
            this.kind = kind;
            memory = budget.reserveMemory("effects-join-head", 0, token);
        }

        void advance(Cancellation token) throws IOException {
            row = null;
            memory.shrink(memory.size());
            if (exhausted) {
                return;
            }
            memory.grow(SortedComparisonInput.READ_ALLOWANCE_BYTES, token);
            var page = input.read(cursor, 1, token, memory).join();
            if (!input.descriptor().identity().equals(page.identity())
                    || page.rows().size() > 1
                    || !input.descriptor().scope().equals(page.coverage().scope())) {
                throw new IOException("Effects input identity/coverage/page bound changed");
            }
            if (page.nextCursor() != null && page.nextCursor().equals(cursor)) {
                throw new IOException("Effects cursor did not advance");
            }
            cursor = page.nextCursor();
            exhausted = cursor == null;
            if (page.rows().isEmpty()) {
                if (!exhausted) {
                    throw new IOException("Empty intermediate effects page");
                }
                memory.shrink(memory.size());
                return;
            }
            row = page.rows().getFirst();
            key(row.key(), kind);
            if (previous != null && previous.compareTo(row.key()) >= 0) {
                throw new IOException("Effects input keys not strictly ordered");
            }
            previous = row.key();
            long retained = SortedComparisonInput.retainedBytes(row) + 1024;
            if (retained > HEAD) {
                throw new ComparisonLimit("Effects head retained bound");
            }
            memory.shrink(memory.size() - retained);
        }

        @Override
        public void close() {
            row = null;
            memory.close();
        }
    }

    /** Private synchronous source; only the external sort worker invokes these local reads. */
    private static final class SpoolSource implements SortedComparisonInput.Source {
        private final BudgetedTempFile spool;
        private final ComparisonTypes.Descriptor descriptor;
        private final RandomAccessFile reader;
        private final long length;
        private long position, count;
        private boolean closed, failed;

        SpoolSource(
                BudgetedTempFile spool, ComparisonTypes.Descriptor descriptor, TempBudget budget)
                throws IOException {
            spool.requireBudget(budget);
            spool.seal();
            length = Files.size(spool.path());
            if (length != spool.size()) {
                throw new IOException("Effects spool charged size differs");
            }
            reader = new RandomAccessFile(spool.path().toFile(), "r");
            this.spool = spool;
            this.descriptor = descriptor;
        }

        @Override
        public ComparisonTypes.Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public synchronized CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                String cursor, int maxRows, long maxRetainedBytes, Cancellation token) {
            try {
                token.check();
                if (closed
                        || failed
                        || maxRows < 1
                        || maxRows > 256
                        || maxRetainedBytes < 4096
                        || maxRetainedBytes > SortedComparisonInput.SOURCE_PAGE_BYTES
                        || (cursor == null ? 0 : Long.parseLong(cursor)) != position) {
                    throw new IllegalArgumentException(
                            "Invalid effects spool continuation/allowance");
                }
                var coverage =
                        new ComparisonTypes.Coverage(
                                ComparisonTypes.Completeness.UNKNOWN,
                                descriptor.scope(),
                                List.of("Sealed session stages; membership not certified."));
                if (position == length) {
                    return CompletableFuture.completedFuture(
                            new SortedComparisonInput.UnsortedPage(
                                    descriptor.identity(), List.of(), null, coverage));
                }
                if (length - position < 4 || ++count > 5_000_000) {
                    throw new ComparisonLimit("Effects spool count/framing limit");
                }
                int size = reader.readInt();
                if (size < 1
                        || size > ComparisonService.RECORD_BYTES
                        || size > length - position - 4
                        || size + 4096L > maxRetainedBytes) {
                    throw new IOException("Invalid bounded effects spool frame");
                }
                byte[] frame = new byte[size];
                reader.readFully(frame);
                // No decoded map/string graph until frame+classified allocations fit4MiB total.
                ComparisonCodec.classifiedBytes(frame, maxRetainedBytes - size - 4096);
                var pair = ComparisonCodec.pair(frame);
                if (pair[0] == null
                        || pair[1] != null
                        || SortedComparisonInput.retainedBytes(pair[0])
                                > EffectsStageCodec.MAX_STAGE_RETAINED) {
                    throw new IOException("Invalid effects spool stage shape");
                }
                var row = pair[0];
                position += 4L + size;
                token.check();
                return CompletableFuture.completedFuture(
                        new SortedComparisonInput.UnsortedPage(
                                descriptor.identity(),
                                List.of(row),
                                position == length ? null : Long.toString(position),
                                coverage));
            } catch (Throwable failure) {
                failed = true;
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public synchronized CompletableFuture<Void> closeAsync() {
            closed = true;
            Throwable problem = null;
            try {
                reader.close();
            } catch (IOException failure) {
                problem = failure;
            }
            try {
                spool.close();
            } catch (Throwable failure) {
                if (problem == null) {
                    problem = failure;
                } else {
                    problem.addSuppressed(failure);
                }
            }
            return problem == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(problem);
        }
    }
}
