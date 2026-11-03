package com.vibeminer.rendering.world;

/** One caller-owned shared work allowance; not thread safe and not a shipping distance policy. */
public final class ErrorWork {
    private final long arithmeticLimit, planeLimit;
    private long arithmetic, planes;
    final float[] coordinates;
    final int[] samples;
    final double[] intervals, coefficients;

    public ErrorWork(long arithmeticLimit, long planeLimit) {
        if (arithmeticLimit < 1
                || arithmeticLimit > 100_000_000
                || planeLimit < 1
                || planeLimit > 1_000_000) {
            throw new IllegalArgumentException("Invalid error-oracle work limits");
        }
        this.arithmeticLimit = arithmeticLimit;
        this.planeLimit = planeLimit;
        coordinates = new float[145 * 3];
        samples = new int[145];
        intervals = new double[32];
        coefficients = new double[8 * 7];
    }

    public long arithmeticUsed() {
        return arithmetic;
    }

    public long planesUsed() {
        return planes;
    }

    boolean arithmetic() {
        if (arithmetic == arithmeticLimit) {
            return false;
        }
        arithmetic++;
        return true;
    }

    boolean plane() {
        if (planes == planeLimit) {
            return false;
        }
        planes++;
        return true;
    }
}
