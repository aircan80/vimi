package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Conservative all-fan-plane bound on actual native float samples; no distance policy or IO. */
public final class TerrainLodError {
    private TerrainLodError() {}

    public enum Kind {
        EXACT_GEOMETRY,
        EMPTY_SOURCE,
        BOUNDED,
        UNRESOLVED
    }

    public enum Reason {
        NONE,
        SOURCE_HOLE,
        NATIVE_TOPOLOGY,
        WORK_BUDGET,
        DETERMINANT,
        NONFINITE
    }

    public static final class Result {
        private final Kind kind;
        private final Reason reason;
        private final double upperBound;

        private Result(Kind kind, Reason reason, double upperBound) {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(reason);
            if (!Double.isFinite(upperBound)
                    || upperBound < 0
                    || (kind == Kind.UNRESOLVED) != (reason != Reason.NONE)
                    || kind != Kind.BOUNDED && upperBound != 0) {
                throw new IllegalArgumentException("Invalid error certificate");
            }
            this.kind = kind;
            this.reason = reason;
            this.upperBound = upperBound;
        }

        public Kind kind() {
            return kind;
        }

        public Reason reason() {
            return reason;
        }

        public double upperBound() {
            return upperBound;
        }

        public boolean certifies(double tolerance) {
            if (!Double.isFinite(tolerance) || tolerance < 0) {
                throw new IllegalArgumentException("Invalid tolerance");
            }
            return kind == Kind.EXACT_GEOMETRY
                    || kind == Kind.BOUNDED && tolerance > 0 && upperBound <= tolerance;
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

    public static Result evaluate(CheckedLeafPlan plan, ErrorWork work, BooleanSupplier canceled) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(work);
        Objects.requireNonNull(canceled);
        try {
            var source = plan.source();
            int row = plan.row(), column = plan.column(), size = plan.size();
            boolean hole = false;
            for (int r = row; r < row + size; r++) {
                for (int c = column; c < column + size; c++) {
                    charge(work, canceled);
                    hole |= source.hole(r, c);
                }
            }
            if (hole) {
                return new Result(
                        size == 1 ? Kind.EMPTY_SOURCE : Kind.UNRESOLVED,
                        size == 1 ? Reason.NONE : Reason.SOURCE_HOLE,
                        0);
            }
            var heights = source.heights();
            int count = 0;
            for (int r = 2 * row; r <= 2 * (row + size); r++) {
                for (int c = column; c < column + size + (r % 2 == 0 ? 1 : 0); c++) {
                    charge(work, canceled);
                    int index = (r / 2) * 17 + (r % 2) * 9 + c;
                    work.samples[count++] = index;
                    work.coordinates[index * 3] =
                            (float) (source.originX() - r * (WorldFormats.TILE_SIZE / 256));
                    work.coordinates[index * 3 + 1] =
                            (float)
                                    (source.originY()
                                            - c * (WorldFormats.TILE_SIZE / 128)
                                            - (r % 2) * (WorldFormats.TILE_SIZE / 256));
                    work.coordinates[index * 3 + 2] = source.originZ() + heights.get(index);
                    for (int axis = 0; axis < 3; axis++) {
                        if (!Float.isFinite(work.coordinates[index * 3 + axis])) {
                            throw new Refusal(Reason.NONFINITE);
                        }
                    }
                }
            }
            for (int r = row; r < row + size; r++) {
                for (int c = column; c < column + size; c++) {
                    charge(work, canceled);
                    int a = r * 17 + c, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                    float[] p = work.coordinates;
                    if (!(p[a * 3] > p[m * 3]
                            && p[m * 3] > p[d * 3]
                            && p[a * 3 + 1] > p[m * 3 + 1]
                            && p[m * 3 + 1] > p[b * 3 + 1]
                            && p[a * 3] == p[b * 3]
                            && p[d * 3] == p[e * 3]
                            && p[a * 3 + 1] == p[d * 3 + 1]
                            && p[b * 3 + 1] == p[e * 3 + 1])) {
                        throw new Refusal(Reason.NATIVE_TOPOLOGY);
                    }
                }
            }
            if (size == 1) {
                return new Result(Kind.EXACT_GEOMETRY, Reason.NONE, 0);
            }
            int planes = plan.triangleCount();
            for (int i = 0; i < planes; i++) {
                charge(work, canceled);
                plane(work, canceled);
                coefficients(
                        work,
                        plan.perimeter(i),
                        plan.center(),
                        plan.perimeter((i + 1) % planes),
                        i * 7,
                        canceled);
            }
            double maximum = 0;
            for (int i = 0; i < planes; i++) {
                for (int v = 0; v < count; v++) {
                    plane(work, canceled);
                    int id = work.samples[v], offset = i * 7;
                    double[] s = work.intervals, p = work.coefficients;
                    pointSub(work, canceled, 0, work.coordinates[id * 3], p[offset]);
                    pointSub(work, canceled, 1, work.coordinates[id * 3 + 1], p[offset + 1]);
                    set(s, 2, p[offset + 3], p[offset + 4]);
                    mul(work, canceled, 3, 2, 0);
                    set(s, 2, p[offset + 5], p[offset + 6]);
                    mul(work, canceled, 4, 2, 1);
                    add(work, canceled, 5, 3, 4);
                    set(s, 2, p[offset + 2], p[offset + 2]);
                    add(work, canceled, 6, 2, 5);
                    set(s, 2, work.coordinates[id * 3 + 2], work.coordinates[id * 3 + 2]);
                    sub(work, canceled, 7, 2, 6);
                    maximum = Math.max(maximum, Math.max(Math.abs(s[14]), Math.abs(s[15])));
                }
            }
            return new Result(Kind.BOUNDED, Reason.NONE, maximum);
        } catch (Refusal refused) {
            return new Result(Kind.UNRESOLVED, refused.reason, 0);
        }
    }

    private static void coefficients(
            ErrorWork w, int a, int b, int c, int output, BooleanSupplier canceled) {
        float[] p = w.coordinates;
        double[] s = w.intervals;
        for (int axis = 0; axis < 3; axis++) {
            pointSub(w, canceled, axis, p[b * 3 + axis], p[a * 3 + axis]);
            pointSub(w, canceled, axis + 3, p[c * 3 + axis], p[a * 3 + axis]);
        }
        mul(w, canceled, 6, 0, 4);
        mul(w, canceled, 7, 1, 3);
        sub(w, canceled, 8, 6, 7);
        if (s[16] <= 0 && s[17] >= 0) {
            throw new Refusal(Reason.DETERMINANT);
        }
        mul(w, canceled, 9, 2, 4);
        mul(w, canceled, 10, 1, 5);
        sub(w, canceled, 11, 9, 10);
        div(w, canceled, 12, 11, 8);
        mul(w, canceled, 9, 0, 5);
        mul(w, canceled, 10, 2, 3);
        sub(w, canceled, 11, 9, 10);
        div(w, canceled, 13, 11, 8);
        w.coefficients[output] = p[a * 3];
        w.coefficients[output + 1] = p[a * 3 + 1];
        w.coefficients[output + 2] = p[a * 3 + 2];
        w.coefficients[output + 3] = s[24];
        w.coefficients[output + 4] = s[25];
        w.coefficients[output + 5] = s[26];
        w.coefficients[output + 6] = s[27];
    }

    private static void set(double[] s, int slot, double lo, double hi) {
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo > hi) {
            throw new Refusal(Reason.NONFINITE);
        }
        s[slot * 2] = lo;
        s[slot * 2 + 1] = hi;
    }

    private static void rounded(double[] s, int slot, double lo, double hi) {
        set(s, slot, Math.nextDown(lo), Math.nextUp(hi));
    }

    private static void pointSub(ErrorWork w, BooleanSupplier c, int out, double a, double b) {
        charge(w, c);
        if (a == b) {
            set(w.intervals, out, 0, 0);
        } else {
            rounded(w.intervals, out, a - b, a - b);
        }
    }

    private static void add(ErrorWork w, BooleanSupplier c, int out, int a, int b) {
        charge(w, c);
        double[] s = w.intervals;
        rounded(s, out, s[a * 2] + s[b * 2], s[a * 2 + 1] + s[b * 2 + 1]);
    }

    private static void sub(ErrorWork w, BooleanSupplier c, int out, int a, int b) {
        charge(w, c);
        double[] s = w.intervals;
        rounded(s, out, s[a * 2] - s[b * 2 + 1], s[a * 2 + 1] - s[b * 2]);
    }

    private static void mul(ErrorWork w, BooleanSupplier c, int out, int a, int b) {
        charge(w, c);
        double[] s = w.intervals;
        double x = s[a * 2] * s[b * 2],
                y = s[a * 2] * s[b * 2 + 1],
                z = s[a * 2 + 1] * s[b * 2],
                v = s[a * 2 + 1] * s[b * 2 + 1];
        rounded(
                s,
                out,
                Math.min(Math.min(x, y), Math.min(z, v)),
                Math.max(Math.max(x, y), Math.max(z, v)));
    }

    private static void div(ErrorWork w, BooleanSupplier c, int out, int a, int b) {
        charge(w, c);
        double[] s = w.intervals;
        if (s[b * 2] <= 0 && s[b * 2 + 1] >= 0) {
            throw new Refusal(Reason.DETERMINANT);
        }
        double x = s[a * 2] / s[b * 2],
                y = s[a * 2] / s[b * 2 + 1],
                z = s[a * 2 + 1] / s[b * 2],
                v = s[a * 2 + 1] / s[b * 2 + 1];
        rounded(
                s,
                out,
                Math.min(Math.min(x, y), Math.min(z, v)),
                Math.max(Math.max(x, y), Math.max(z, v)));
    }

    private static void charge(ErrorWork w, BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
        if (!w.arithmetic()) {
            throw new Refusal(Reason.WORK_BUDGET);
        }
    }

    private static void plane(ErrorWork w, BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
        if (!w.plane()) {
            throw new Refusal(Reason.WORK_BUDGET);
        }
    }
}
