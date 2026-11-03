package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiFunction;

/** Session-only ordered union of two staged dependencies, retaining at most two heads. */
public final class StreamingItemJoin {
    private static final long HEAD_BYTES = 4L << 20;
    private static final long CODEC_BYTES = 8L << 20;

    private StreamingItemJoin() {}

    /**
     * Takes ownership of both sorted inputs, including on failure. The merger must preserve the
     * selected key and bound its transient/output allocations to 3 MiB before allocation. The
     * returned sealed file remains charged to the shared budget until its owner deletes it. Runs on
     * an opening worker, never an input worker or UI thread.
     */
    @SuppressWarnings("try") // Codec allowance covers framing and bounded merger temporaries.
    public static BudgetedTempFile join(
            SortedComparisonInput item,
            SortedComparisonInput sparse,
            Path directory,
            TempBudget budget,
            BiFunction<Record, Record, Record> merger,
            Cancellation token)
            throws IOException {
        Objects.requireNonNull(item);
        Objects.requireNonNull(sparse);
        Objects.requireNonNull(merger);
        BudgetedTempFile output = null;
        Throwable primary = null;
        try (var codec = budget.reserveMemory("items-join-codec", CODEC_BYTES, token);
                var a = new Head(item, budget, token);
                var b = new Head(sparse, budget, token)) {
            output =
                    BudgetedTempFile.create(
                            directory, "candidates-", budget, "items-candidates", token);
            a.advance(token);
            b.advance(token);
            while (a.row != null || b.row != null) {
                token.check();
                int order =
                        a.row == null ? 1 : b.row == null ? -1 : a.row.key().compareTo(b.row.key());
                var left = order <= 0 ? a.row : null;
                var right = order >= 0 ? b.row : null;
                String key = left != null ? left.key() : right.key();
                var joined = Objects.requireNonNull(merger.apply(left, right));
                if (!key.equals(joined.key())) {
                    throw new IOException("Items union changed the admitted key");
                }
                if (SortedComparisonInput.retainedBytes(joined) > 2L << 20) {
                    throw new ComparisonLimit("Joined Items candidate exceeds retained bound");
                }
                byte[] frame = ComparisonCodec.pair(joined, null);
                output.appendFrame(frame, token);
                // Drop transient output before either input expands its reader allowance.
                frame = null;
                joined = null;
                left = null;
                right = null;
                if (order <= 0) {
                    a.advance(token);
                }
                if (order >= 0) {
                    b.advance(token);
                }
            }
            output.seal();
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            // Input files are deleted only after the successor was sealed (or failure cleanup).
            Throwable cleanup = null;
            try {
                item.closeAsync().join();
            } catch (RuntimeException | Error failure) {
                cleanup = failure;
            }
            try {
                sparse.closeAsync().join();
            } catch (RuntimeException | Error failure) {
                if (cleanup == null) {
                    cleanup = failure;
                } else {
                    cleanup.addSuppressed(failure);
                }
            }
            if (output != null && (primary != null || cleanup != null)) {
                try {
                    output.close();
                } catch (IOException failure) {
                    if (cleanup == null) {
                        cleanup = failure;
                    } else {
                        cleanup.addSuppressed(failure);
                    }
                }
            }
            if (cleanup != null) {
                if (primary != null) {
                    primary.addSuppressed(cleanup);
                } else {
                    throw new IOException("Items dependency cleanup failed", cleanup);
                }
            }
        }
        return output;
    }

    private static final class Head implements AutoCloseable {
        private final ComparisonInput input;
        private final TempBudget.Reservation memory;
        private String cursor, previous;
        private boolean exhausted;
        private Record row;

        Head(ComparisonInput input, TempBudget budget, Cancellation token) throws IOException {
            this.input = input;
            memory = budget.reserveMemory("items-join-head", 0, token);
        }

        void advance(Cancellation token) throws IOException {
            row = null;
            memory.shrink(memory.size());
            if (exhausted) {
                return;
            }
            memory.grow(SortedComparisonInput.READ_ALLOWANCE_BYTES, token);
            var page = input.read(cursor, 1, token, memory).join();
            if (!input.descriptor().identity().equals(page.identity()) || page.rows().size() > 1) {
                throw new IOException(
                        "Items dependency changed its captured identity or page bound");
            }
            if (page.nextCursor() != null && page.nextCursor().equals(cursor)) {
                throw new IOException("Items dependency cursor did not advance");
            }
            cursor = page.nextCursor();
            exhausted = cursor == null;
            if (page.rows().isEmpty()) {
                if (!exhausted) {
                    throw new IOException("Items dependency returned an empty intermediate page");
                }
                memory.shrink(memory.size());
                return;
            }
            row = page.rows().getFirst();
            if (previous != null && previous.compareTo(row.key()) >= 0) {
                throw new IOException("Items dependency keys are not strictly increasing");
            }
            if (!row.key().matches("[0-9]{20}")) {
                throw new IOException("Items dependency uses a noncanonical unsigned key");
            }
            previous = row.key();
            long retained = SortedComparisonInput.retainedBytes(row) + 1024;
            if (retained > HEAD_BYTES) {
                throw new ComparisonLimit("Items dependency head exceeds bound");
            }
            memory.shrink(memory.size() - retained);
        }

        @Override
        public void close() {
            row = null;
            memory.close();
        }
    }
}
