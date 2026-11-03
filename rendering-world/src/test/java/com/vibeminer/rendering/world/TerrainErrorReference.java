package com.vibeminer.rendering.world;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Test-only exact oracle in one normalized MCNK; intentionally not a production fast path. */
final class TerrainErrorReference {
    private static final int BITS = 1024;
    private static final int MAX_TRIANGLES = 512;

    record Vertex(int x2, int y2, float z) {
        Vertex {
            if (x2 < 0 || x2 > 16 || y2 < 0 || y2 > 16 || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Outside normalized finite MCNK");
            }
        }
    }

    record NativeVertex(float x, float y, float z) {
        NativeVertex {
            for (float value : new float[] {x, y, z}) {
                if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
                    throw new IllegalArgumentException("Invalid native sample");
                }
            }
        }
    }

    record NativeTriangle(NativeVertex a, NativeVertex b, NativeVertex c) {}

    record Triangle(Vertex a, Vertex b, Vertex c) {}

    record Result(double upperBound, long operations) {}

    private final long limit;
    private final BooleanSupplier canceled;
    private long operations;

    TerrainErrorReference(long limit, BooleanSupplier canceled) {
        if (limit < 1 || limit > 50_000_000) {
            throw new IllegalArgumentException("Missing work allowance");
        }
        this.limit = limit;
        this.canceled = java.util.Objects.requireNonNull(canceled);
    }

    Result compare(List<Triangle> fine, List<Triangle> coarse) {
        if (fine.isEmpty()
                || coarse.isEmpty()
                || fine.size() > MAX_TRIANGLES
                || coarse.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Triangle count outside bounds");
        }
        var left = prepare(fine);
        var right = prepare(coarse);
        return comparePrepared(left, right);
    }

    private Result comparePrepared(T[] left, T[] right) {
        nonoverlap(left);
        nonoverlap(right);
        R[] coveredLeft = new R[left.length], coveredRight = new R[right.length];
        java.util.Arrays.fill(coveredLeft, number(0));
        java.util.Arrays.fill(coveredRight, number(0));
        R maximum = number(0);
        P[] scratchA = new P[8], scratchB = new P[8];
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                tick();
                int count = intersection(left[i], right[j], scratchA, scratchB);
                if (count == 0) {
                    continue;
                }
                R area = area(scratchA, count);
                coveredLeft[i] = coveredLeft[i].add(area);
                coveredRight[j] = coveredRight[j].add(area);
                if (area.sign() == 0) {
                    continue;
                }
                for (int k = 0; k < count; k++) {
                    R delta = height(left[i], scratchA[k]).sub(height(right[j], scratchA[k])).abs();
                    if (delta.compare(maximum) > 0) {
                        maximum = delta;
                    }
                }
            }
        }
        coverage(left, coveredLeft);
        coverage(right, coveredRight);
        double rounded =
                new BigDecimal(maximum.n)
                        .divide(new BigDecimal(maximum.d), new MathContext(80))
                        .doubleValue();
        if (!Double.isFinite(rounded)) {
            throw new IllegalArgumentException("Unrepresentable error bound");
        }
        if (binary(rounded).compare(maximum) < 0) {
            rounded = Math.nextUp(rounded);
        }
        return new Result(rounded, operations);
    }

    Result compareNative(List<NativeTriangle> fine, List<NativeTriangle> coarse) {
        if (fine.isEmpty()
                || coarse.isEmpty()
                || fine.size() > MAX_TRIANGLES
                || coarse.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Native triangle count outside bounds");
        }
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (var partition : List.of(fine, coarse)) {
            for (var triangle : partition) {
                tick();
                for (var vertex : new NativeVertex[] {triangle.a(), triangle.b(), triangle.c()}) {
                    minX = Math.min(minX, vertex.x());
                    minY = Math.min(minY, vertex.y());
                    maxX = Math.max(maxX, vertex.x());
                    maxY = Math.max(maxY, vertex.y());
                }
            }
        }
        if ((double) maxX - minX > 64 || (double) maxY - minY > 64) {
            throw new IllegalArgumentException("Native MCNK span exceeds oracle profile");
        }
        R originX = binary(minX), originY = binary(minY);
        return comparePrepared(
                prepareNative(fine, originX, originY), prepareNative(coarse, originX, originY));
    }

    private T[] prepareNative(List<NativeTriangle> triangles, R originX, R originY) {
        T[] result = new T[triangles.size()];
        for (int i = 0; i < result.length; i++) {
            tick();
            var t = triangles.get(i);
            var a = nativePoint(t.a(), originX, originY);
            var b = nativePoint(t.b(), originX, originY);
            var c = nativePoint(t.c(), originX, originY);
            R signed = cross(a, b, c);
            if (signed.sign() == 0) {
                throw new IllegalArgumentException("Degenerate native triangle");
            }
            result[i] =
                    signed.sign() > 0
                            ? new T(
                                    a,
                                    b,
                                    c,
                                    binary(t.a().z()),
                                    binary(t.b().z()),
                                    binary(t.c().z()))
                            : new T(
                                    a,
                                    c,
                                    b,
                                    binary(t.a().z()),
                                    binary(t.c().z()),
                                    binary(t.b().z()));
        }
        return result;
    }

    private P nativePoint(NativeVertex v, R originX, R originY) {
        // Exact subtraction rebases arithmetic without changing any input float sample.
        return new P(binary(v.x()).sub(originX), binary(v.y()).sub(originY));
    }

    private T[] prepare(List<Triangle> triangles) {
        T[] result = new T[triangles.size()];
        for (int i = 0; i < result.length; i++) {
            tick();
            Triangle t = triangles.get(i);
            P a = point(t.a()), b = point(t.b()), c = point(t.c());
            R signed = cross(a, b, c);
            if (signed.sign() == 0) {
                throw new IllegalArgumentException("Degenerate triangle");
            }
            result[i] =
                    signed.sign() > 0
                            ? new T(
                                    a,
                                    b,
                                    c,
                                    binary(t.a().z()),
                                    binary(t.b().z()),
                                    binary(t.c().z()))
                            : new T(
                                    a,
                                    c,
                                    b,
                                    binary(t.a().z()),
                                    binary(t.c().z()),
                                    binary(t.b().z()));
        }
        return result;
    }

    private void coverage(T[] triangles, R[] covered) {
        for (int i = 0; i < triangles.length; i++) {
            T t = triangles[i];
            if (covered[i].compare(cross(t.a, t.b, t.c).div(number(2))) != 0) {
                throw new IllegalArgumentException("Uncovered or filled-hole domain");
            }
        }
    }

    private void nonoverlap(T[] triangles) {
        P[] a = new P[8], b = new P[8];
        for (int i = 0; i < triangles.length; i++) {
            for (int j = i + 1; j < triangles.length; j++) {
                tick();
                int count = intersection(triangles[i], triangles[j], a, b);
                if (count > 0 && area(a, count).sign() != 0) {
                    throw new IllegalArgumentException("Overlapping partition");
                }
            }
        }
    }

    private int intersection(T source, T clip, P[] result, P[] scratch) {
        P[] input = result, output = scratch;
        input[0] = source.a;
        input[1] = source.b;
        input[2] = source.c;
        int count = 3;
        for (int edge = 0; edge < 3 && count > 0; edge++) {
            tick();
            P start = clip.vertex(edge), end = clip.vertex((edge + 1) % 3);
            int used = 0;
            P previous = input[count - 1];
            R previousSide = cross(start, end, previous);
            for (int i = 0; i < count; i++) {
                tick();
                P current = input[i];
                R side = cross(start, end, current);
                if ((side.sign() >= 0) != (previousSide.sign() >= 0)) {
                    R t = previousSide.div(previousSide.sub(side));
                    used =
                            append(
                                    output,
                                    used,
                                    new P(
                                            previous.x.add(current.x.sub(previous.x).mul(t)),
                                            previous.y.add(current.y.sub(previous.y).mul(t))));
                }
                if (side.sign() >= 0) {
                    used = append(output, used, current);
                }
                previous = current;
                previousSide = side;
            }
            count = used;
            P[] swap = input;
            input = output;
            output = swap;
        }
        if (input != result) {
            System.arraycopy(input, 0, result, 0, count);
        }
        return count;
    }

    private int append(P[] output, int index, P point) {
        if (index == output.length) {
            throw new IllegalArgumentException("Clip scratch bound exceeded");
        }
        output[index] = point;
        return index + 1;
    }

    private R area(P[] polygon, int count) {
        R sum = number(0);
        for (int i = 0; i < count; i++) {
            P p = polygon[i], q = polygon[(i + 1) % count];
            sum = sum.add(p.x.mul(q.y).sub(q.x.mul(p.y)));
        }
        return sum.abs().div(number(2));
    }

    private R height(T t, P p) {
        R denominator = cross(t.a, t.b, t.c);
        return cross(t.b, t.c, p)
                .mul(t.za)
                .add(cross(t.c, t.a, p).mul(t.zb))
                .add(cross(t.a, t.b, p).mul(t.zc))
                .div(denominator);
    }

    private R cross(P a, P b, P c) {
        return b.x.sub(a.x).mul(c.y.sub(a.y)).sub(b.y.sub(a.y).mul(c.x.sub(a.x)));
    }

    private P point(Vertex v) {
        return new P(number(v.x2()), number(v.y2()));
    }

    private record P(R x, R y) {}

    private record T(P a, P b, P c, R za, R zb, R zc) {
        P vertex(int i) {
            return i == 0 ? a : i == 1 ? b : c;
        }
    }

    private R number(long value) {
        return rational(BigInteger.valueOf(value), BigInteger.ONE);
    }

    private R binary(double value) {
        long bits = Double.doubleToRawLongBits(value);
        long fraction = bits & ((1L << 52) - 1);
        int exponent = (int) (bits >>> 52 & 2047);
        long mantissa = exponent == 0 ? fraction : fraction | (1L << 52);
        int shift = exponent == 0 ? -1074 : exponent - 1023 - 52;
        if (mantissa == 0) {
            return number(0);
        }
        int zeros = Long.numberOfTrailingZeros(mantissa);
        mantissa >>>= zeros;
        shift += zeros;
        if (Math.abs(shift) + 53 > BITS) {
            throw new IllegalArgumentException("Binary bound");
        }
        BigInteger n = BigInteger.valueOf(bits < 0 ? -mantissa : mantissa);
        return shift >= 0
                ? rational(n.shiftLeft(shift), BigInteger.ONE)
                : rational(n, BigInteger.ONE.shiftLeft(-shift));
    }

    private R rational(BigInteger n, BigInteger d) {
        tick();
        if (n.abs().bitLength() > BITS || d.abs().bitLength() > BITS || d.signum() == 0) {
            throw new IllegalArgumentException("Rational bound");
        }
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        BigInteger gcd = n.gcd(d);
        return new R(n.divide(gcd), d.divide(gcd));
    }

    private BigInteger product(BigInteger a, BigInteger b) {
        tick();
        if (a.abs().bitLength() + b.abs().bitLength() > BITS) {
            throw new IllegalArgumentException("Cross-product bound");
        }
        return a.multiply(b);
    }

    private BigInteger sum(BigInteger a, BigInteger b) {
        tick();
        if (Math.max(a.abs().bitLength(), b.abs().bitLength()) + 1 > BITS) {
            throw new IllegalArgumentException("Sum bound");
        }
        return a.add(b);
    }

    private void tick() {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
        if (++operations > limit) {
            throw new IllegalArgumentException("Oracle work budget exhausted");
        }
    }

    private final class R {
        final BigInteger n, d;

        R(BigInteger n, BigInteger d) {
            this.n = n;
            this.d = d;
        }

        R add(R b) {
            return rational(sum(product(n, b.d), product(b.n, d)), product(d, b.d));
        }

        R sub(R b) {
            return add(new R(b.n.negate(), b.d));
        }

        R mul(R b) {
            return rational(product(n, b.n), product(d, b.d));
        }

        R div(R b) {
            return rational(product(n, b.d), product(d, b.n));
        }

        R abs() {
            return new R(n.abs(), d);
        }

        int sign() {
            return n.signum();
        }

        int compare(R b) {
            return product(n, b.d).compareTo(product(b.n, d));
        }
    }
}
