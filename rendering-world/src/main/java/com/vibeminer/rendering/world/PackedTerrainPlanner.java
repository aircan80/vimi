package com.vibeminer.rendering.world;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Pure worker-owned packed topology/count planning. No source IO, mesh output, renderer or policy
 * defaults.
 */
public final class PackedTerrainPlanner {
    private PackedTerrainPlanner() {}

    public enum Status {
        READY,
        LIMITED,
        EMPTY,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        SOURCE_COVERAGE,
        WORK_BUDGET,
        PASS_LIMIT,
        GEOMETRY_CAP,
        NATIVE_TOPOLOGY
    }

    public record RingTolerances(double ring1, double ring2, double ring3) {
        public RingTolerances {
            if (!Double.isFinite(ring1)
                    || !Double.isFinite(ring2)
                    || !Double.isFinite(ring3)
                    || ring1 <= 0
                    || ring1 > ring2
                    || ring2 > ring3
                    || ring3 > 1_000_000) {
                throw new IllegalArgumentException("Invalid explicit ring tolerances");
            }
        }

        double at(int ring) {
            return ring == 0 ? 0 : ring == 1 ? ring1 : ring == 2 ? ring2 : ring3;
        }
    }

    public record Limits(int maxPasses, int maxBatches, int maxVertices, int maxIndices) {
        public Limits {
            if (maxPasses < 2
                    || maxPasses > 32
                    || maxBatches < 1
                    || maxBatches > 9
                    || maxVertices < 1
                    || maxVertices > 9 * TerrainScene.MAX_VERTICES_PER_TILE
                    || maxIndices < 3
                    || maxIndices > 9 * TerrainScene.MAX_INDICES_PER_TILE) {
                throw new IllegalArgumentException("Invalid planner limits");
            }
        }
    }

    public record Counts(
            int vertices, int indices, int batches, int retainedSources, int omittedSources) {
        public int triangles() {
            return indices / 3;
        }
    }

    public static final class Result {
        private final Status status;
        private final Reason reason;
        private final ScopedPlan plan;

        private Result(Status status, Reason reason, ScopedPlan plan) {
            this.status = status;
            this.reason = reason;
            this.plan = plan;
        }

        public Status status() {
            return status;
        }

        public Reason reason() {
            return reason;
        }

        public Optional<ScopedPlan> plan() {
            return Optional.ofNullable(plan);
        }
    }

    @FunctionalInterface
    public interface LeafConsumer {
        void accept(
                int sourceOrdinal, int chunkOrdinal, int row, int column, int size, int splitMask);
    }

    /**
     * Scoped packed topology. No raw source buffers escape; finished scenes must not retain this
     * object.
     */
    public static final class ScopedPlan implements AutoCloseable {
        private TerrainSourceWindow window;
        private byte[] levels, certificates;
        private final int width;
        private final long retained;
        private final Counts counts;

        private ScopedPlan(State state, Counts counts) {
            window = state.window;
            levels = state.levels;
            certificates = state.certificates;
            width = state.width;
            retained = state.retained;
            this.counts = counts;
        }

        public Counts counts() {
            requireOpen();
            return counts;
        }

        public CompactTerrainSource.Descriptor descriptor(int ordinal) {
            requireOpen();
            return window.source(ordinal).descriptor();
        }

        public void visit(LeafConsumer consumer, BooleanSupplier canceled) {
            requireOpen();
            Objects.requireNonNull(consumer);
            Objects.requireNonNull(canceled);
            for (int source = 0; source < window.count(); source++) {
                if ((retained & (1L << source)) != 0) {
                    for (int chunk = 0; chunk < 256; chunk++) {
                        for (int r = 0; r < 8; r++) {
                            for (int c = 0; c < 8; c++) {
                                check(canceled);
                                int index = index(window, width, source, chunk, r, c),
                                        size = levels[index] & 255;
                                if (size > 0 && r % size == 0 && c % size == 0) {
                                    consumer.accept(
                                            source,
                                            chunk,
                                            r,
                                            c,
                                            size,
                                            (certificates[index] & 255) - 1);
                                }
                            }
                        }
                    }
                }
            }
        }

        public void copyPosition(int source, int chunk, int sample, float[] target, int offset) {
            requireOpen();
            Objects.requireNonNull(target);
            if (source < 0
                    || source >= window.count()
                    || (retained & (1L << source)) == 0
                    || chunk < 0
                    || chunk >= 256
                    || sample < 0
                    || sample >= 145
                    || offset < 0
                    || offset > target.length - 3) {
                throw new IllegalArgumentException("Invalid source sample request");
            }
            var data = window.source(source);
            int cell = sample / 17,
                    within = sample % 17,
                    row = 2 * cell + (within >= 9 ? 1 : 0),
                    col = within >= 9 ? within - 9 : within;
            data.copyPosition(chunk, row, col, target, offset);
        }

        private void requireOpen() {
            if (window == null) {
                throw new IllegalStateException("Packed plan closed");
            }
        }

        @Override
        public void close() {
            window = null;
            levels = null;
            certificates = null;
        }
    }

    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Reason reason;

        Refusal(Reason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    private static final class State {
        final TerrainSourceWindow window;
        final RingTolerances tolerances;
        final Limits limits;
        final ErrorWork work;
        final BooleanSupplier canceled;
        final int width, height;
        final byte[] levels, certificates;
        final int[] chunkVertices, chunkIndices;
        final int[] sampleMarks = new int[145];
        final float[] heightScratch = new float[145];
        long retained;
        int passes;

        State(
                TerrainSourceWindow window,
                RingTolerances tolerances,
                Limits limits,
                ErrorWork work,
                BooleanSupplier canceled) {
            this.window = window;
            this.tolerances = tolerances;
            this.limits = limits;
            this.work = work;
            this.canceled = canceled;
            width = window.columns() * 128;
            height = window.rows() * 128;
            charge(this, 2L * width * height + 2L * window.count() * 256);
            levels = new byte[width * height];
            certificates = new byte[width * height];
            chunkVertices = new int[window.count() * 256];
            chunkIndices = new int[window.count() * 256];
        }
    }

    public static Result plan(
            TerrainSourceWindow window,
            RingTolerances tolerances,
            Limits limits,
            ErrorWork work,
            BooleanSupplier canceled) {
        Objects.requireNonNull(window);
        Objects.requireNonNull(tolerances);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(work);
        Objects.requireNonNull(canceled);
        check(canceled);
        try {
            var s = new State(window, tolerances, limits, work, canceled);
            boolean missing = false;
            for (int source = 0; source < window.count(); source++) {
                var data = window.source(source);
                if (!data.readable()) {
                    missing |= data.descriptor().outcome() != CompactTerrainSource.Outcome.ABSENT;
                    continue;
                }
                s.retained |= 1L << source;
                int desired = 1 << ring(window, data);
                for (int chunk = 0; chunk < 256; chunk++) {
                    for (int r = 0; r < 8; r += desired) {
                        for (int c = 0; c < 8; c += desired) {
                            fill(s, source, chunk, r, c, desired, data.holes(chunk));
                        }
                    }
                }
            }
            boolean changed;
            do {
                balance(s);
                pass(s);
                changed = false;
                for (int source = 0; source < window.count(); source++) {
                    if ((s.retained & (1L << source)) != 0) {
                        var data = window.source(source);
                        double tolerance = tolerances.at(ring(window, data));
                        for (int chunk = 0; chunk < 256; chunk++) {
                            com.vibeminer.formats.world.TerrainGrid.Chunk grid = null;
                            for (int r = 0; r < 8; r++) {
                                for (int c = 0; c < 8; c++) {
                                    charge(s, 1);
                                    int at = index(window, s.width, source, chunk, r, c),
                                            size = s.levels[at] & 255;
                                    if (size == 0 || r % size != 0 || c % size != 0) {
                                        continue;
                                    }
                                    int mask = splitMask(s, at, size);
                                    if (mask < 0) {
                                        changed = true;
                                        continue;
                                    }
                                    if ((s.certificates[at] & 255) == mask + 1) {
                                        continue;
                                    }
                                    if (grid == null) {
                                        if (!work.reserveArithmetic(145 + 145 + 64)) {
                                            throw new Refusal(Reason.WORK_BUDGET);
                                        }
                                        grid =
                                                data.chunkUncharged(
                                                        chunk, s.heightScratch, canceled);
                                    }
                                    var result =
                                            TerrainLodError.evaluate(
                                                    CheckedLeafPlan.create(grid, r, c, size, mask),
                                                    work,
                                                    canceled);
                                    if (result.reason() == TerrainLodError.Reason.WORK_BUDGET) {
                                        throw new Refusal(Reason.WORK_BUDGET);
                                    }
                                    if (result.certifies(tolerance)) {
                                        s.certificates[at] = (byte) (mask + 1);
                                    } else {
                                        if (size > 1) {
                                            split(s, at, size);
                                            changed = true;
                                        } else {
                                            throw new Refusal(Reason.NATIVE_TOPOLOGY);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } while (changed);
            prepareCounts(s);
            Counts counts;
            int omitted = 0;
            while (true) {
                counts = count(s, omitted);
                if (counts.vertices() <= limits.maxVertices()
                        && counts.indices() <= limits.maxIndices()
                        && counts.batches() <= limits.maxBatches()) {
                    break;
                }
                int far = farthest(s);
                if (far < 0) {
                    throw new Refusal(Reason.GEOMETRY_CAP);
                }
                s.retained &= ~(1L << far);
                omitted++;
                // Keep every already-certified midpoint: source omission must not mutate a fan.
            }
            if (counts.indices() == 0 && missing) {
                throw new Refusal(Reason.SOURCE_COVERAGE);
            }
            Status status =
                    counts.indices() == 0
                            ? Status.EMPTY
                            : missing || omitted > 0 ? Status.LIMITED : Status.READY;
            return new Result(
                    status,
                    omitted > 0
                            ? Reason.GEOMETRY_CAP
                            : missing ? Reason.SOURCE_COVERAGE : Reason.NONE,
                    new ScopedPlan(s, counts));
        } catch (Refusal refusal) {
            return new Result(Status.UNAVAILABLE, refusal.reason, null);
        }
    }

    private static void fill(
            State s, int source, int chunk, int row, int col, int size, long holes) {
        charge(s, 1L + size * size);
        boolean hole = false;
        for (int r = row; r < row + size; r++) {
            for (int c = col; c < col + size; c++) {
                hole |= (holes & (1L << (r * 8 + c))) != 0;
            }
        }
        if (hole && size > 1) {
            int half = size / 2;
            for (int r = row; r < row + size; r += half) {
                for (int c = col; c < col + size; c += half) {
                    fill(s, source, chunk, r, c, half, holes);
                }
            }
            return;
        }
        charge(s, (long) size * size);
        for (int r = row; r < row + size; r++) {
            for (int c = col; c < col + size; c++) {
                s.levels[index(s.window, s.width, source, chunk, r, c)] = (byte) (hole ? 0 : size);
            }
        }
    }

    private static void balance(State s) {
        boolean changed;
        do {
            pass(s);
            changed = false;
            for (int r = 0; r < s.height; r++) {
                for (int c = 0; c < s.width; c++) {
                    charge(s, 1);
                    int at = r * s.width + c, size = s.levels[at] & 255;
                    if (size < 2) {
                        continue;
                    }
                    if (finer(s, r - 1, c, size)
                            || finer(s, r + 1, c, size)
                            || finer(s, r, c - 1, size)
                            || finer(s, r, c + 1, size)) {
                        split(s, at, size);
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    private static boolean finer(State s, int r, int c, int size) {
        charge(s, 1);
        if (r < 0 || c < 0 || r >= s.height || c >= s.width) {
            return false;
        }
        int neighbor = s.levels[r * s.width + c] & 255;
        return neighbor > 0 && size > 2 * neighbor;
    }

    private static void split(State s, int at, int size) {
        int r = at / s.width, c = at % s.width;
        r -= r % size;
        c -= c % size;
        charge(s, (long) size * size);
        for (int dr = 0; dr < size; dr++) {
            for (int dc = 0; dc < size; dc++) {
                int cell = (r + dr) * s.width + c + dc;
                s.levels[cell] = (byte) (size / 2);
                s.certificates[cell] = 0;
            }
        }
    }

    private static int splitMask(State s, int at, int size) {
        int r = at / s.width, c = at % s.width, mask = 0;
        for (int edge = 0; edge < 4; edge++) {
            for (int d = 0; d < size; d++) {
                charge(s, 1);
                int nr = edge == 0 ? r - 1 : edge == 2 ? r + size : r + d,
                        nc = edge == 1 ? c + size : edge == 3 ? c - 1 : c + d;
                if (nr < 0 || nc < 0 || nr >= s.height || nc >= s.width) {
                    continue;
                }
                int other = s.levels[nr * s.width + nc] & 255;
                if (other > 0 && other < size) {
                    if (other < size / 2) {
                        return -1;
                    }
                    mask |= 1 << edge;
                }
            }
        }
        return mask;
    }

    private static void prepareCounts(State s) {
        for (int source = 0; source < s.window.count(); source++) {
            if ((s.retained & (1L << source)) != 0) {
                for (int chunk = 0; chunk < 256; chunk++) {
                    charge(s, 145);
                    Arrays.fill(s.sampleMarks, 0);
                    int ci = 0;
                    for (int r = 0; r < 8; r++) {
                        for (int c = 0; c < 8; c++) {
                            charge(s, 1);
                            int at = index(s.window, s.width, source, chunk, r, c),
                                    size = s.levels[at] & 255;
                            if (size == 0 || r % size != 0 || c % size != 0) {
                                continue;
                            }
                            int code = s.certificates[at] & 255;
                            if (code == 0) {
                                throw new Refusal(Reason.NATIVE_TOPOLOGY);
                            }
                            int mask = code - 1, half = size / 2, n = 4 + Integer.bitCount(mask);
                            charge(s, n + 1);
                            s.sampleMarks[size == 1 ? r * 17 + c + 9 : (r + half) * 17 + c + half] =
                                    1;
                            s.sampleMarks[r * 17 + c] = 1;
                            s.sampleMarks[r * 17 + c + size] = 1;
                            s.sampleMarks[(r + size) * 17 + c + size] = 1;
                            s.sampleMarks[(r + size) * 17 + c] = 1;
                            if ((mask & 1) != 0) {
                                s.sampleMarks[r * 17 + c + half] = 1;
                            }
                            if ((mask & 2) != 0) {
                                s.sampleMarks[(r + half) * 17 + c + size] = 1;
                            }
                            if ((mask & 4) != 0) {
                                s.sampleMarks[(r + size) * 17 + c + half] = 1;
                            }
                            if ((mask & 8) != 0) {
                                s.sampleMarks[(r + half) * 17 + c] = 1;
                            }
                            ci += n * 3;
                        }
                    }
                    int cv = 0;
                    for (int mark : s.sampleMarks) {
                        cv += mark;
                    }
                    s.chunkVertices[source * 256 + chunk] = cv;
                    s.chunkIndices[source * 256 + chunk] = ci;
                }
            }
        }
    }

    private static Counts count(State s, int omitted) {
        int vertices = 0, indices = 0, batches = 0, bv = 0, bi = 0, sources = 0;
        for (int source = 0; source < s.window.count(); source++) {
            if ((s.retained & (1L << source)) != 0) {
                boolean usedSource = false;
                for (int chunk = 0; chunk < 256; chunk++) {
                    charge(s, 1);
                    int cv = s.chunkVertices[source * 256 + chunk],
                            ci = s.chunkIndices[source * 256 + chunk];
                    if (ci == 0) {
                        continue;
                    }
                    usedSource = true;
                    if (batches == 0
                            || bv + cv > TerrainScene.MAX_VERTICES_PER_TILE
                            || bi + ci > TerrainScene.MAX_INDICES_PER_TILE) {
                        batches++;
                        bv = 0;
                        bi = 0;
                    }
                    bv += cv;
                    bi += ci;
                    vertices += cv;
                    indices += ci;
                }
                if (usedSource) {
                    sources++;
                }
            }
        }
        return new Counts(vertices, indices, batches, sources, omitted);
    }

    private static int farthest(State s) {
        int best = -1, score = -1;
        for (int i = 0; i < s.window.count(); i++) {
            if ((s.retained & (1L << i)) != 0) {
                var source = s.window.source(i);
                int ring = ring(s.window, source);
                if (ring == 0) {
                    continue;
                }
                int distance =
                        Math.abs(source.descriptor().x() - s.window.anchorX())
                                + Math.abs(source.descriptor().y() - s.window.anchorY());
                int next = ring * 1000 + distance * 50 + i;
                if (next > score) {
                    score = next;
                    best = i;
                }
            }
        }
        return best;
    }

    private static int ring(TerrainSourceWindow window, CompactTerrainSource source) {
        return Math.max(
                Math.abs(source.descriptor().x() - window.anchorX()),
                Math.abs(source.descriptor().y() - window.anchorY()));
    }

    private static int index(
            TerrainSourceWindow window, int width, int source, int chunk, int row, int col) {
        int tileRow = source / window.columns(), tileCol = source % window.columns();
        return (tileRow * 128 + (chunk / 16) * 8 + row) * width
                + tileCol * 128
                + (chunk % 16) * 8
                + col;
    }

    private static void pass(State s) {
        charge(s, 1);
        if (++s.passes > s.limits.maxPasses()) {
            throw new Refusal(Reason.PASS_LIMIT);
        }
    }

    private static void charge(State s, long groups) {
        check(s.canceled);
        if (!s.work.reserveArithmetic(groups)) {
            throw new Refusal(Reason.WORK_BUDGET);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
