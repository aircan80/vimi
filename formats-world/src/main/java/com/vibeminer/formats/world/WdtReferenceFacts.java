package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Raw WDT18 reference counts, without terrain arrays, file resolution or source authority. */
public final class WdtReferenceFacts {
    public static final int MAX_BYTES = 1 << 20, MAX_CHUNKS = 256;
    public static final int MAX_METADATA_BYTES = 32 << 10, MAX_GLOBAL_RECORDS = 16;
    private static final int MVER = 0x4d564552, MPHD = 0x4d504844;
    private static final int MAIN = 0x4d41494e, MAID = 0x4d414944, MODF = 0x4d4f4446;

    private WdtReferenceFacts() {}

    /** Presence of an unparsed body is not understanding of its render-affecting operands. */
    public record Unparsed(int tag, int bytes) {
        public Unparsed {
            if (bytes < 0 || bytes > MAX_BYTES) {
                throw invalid("Unparsed WDT span");
            }
        }
    }

    /** Detached raw facts; absent MAIN/MAID cannot establish a zero-reference domain. */
    public static final class Facts {
        private final boolean mainPresent, maidPresent, modfPresent;
        private final int activeMainRecords, nonzeroMainWords;
        private final int positiveMaidCells, positiveMaidReferences, modfRecords;
        private final long mphdFlags;
        private final List<Unparsed> unparsed;
        private final int metadataBytes;

        private Facts(Scan s) {
            mainPresent = s.main;
            maidPresent = s.maid;
            modfPresent = s.modf;
            activeMainRecords = s.activeMain;
            nonzeroMainWords = s.nonzeroMain;
            positiveMaidCells = s.positiveCells;
            positiveMaidReferences = s.positiveRefs;
            modfRecords = s.globals;
            mphdFlags = s.flags;
            unparsed = List.copyOf(s.unparsed);
            metadataBytes = s.budget;
        }

        public boolean mainPresent() {
            return mainPresent;
        }

        public boolean maidPresent() {
            return maidPresent;
        }

        public boolean modfPresent() {
            return modfPresent;
        }

        public int activeMainRecords() {
            return activeMainRecords;
        }

        public int nonzeroMainWords() {
            return nonzeroMainWords;
        }

        public int positiveMaidCells() {
            return positiveMaidCells;
        }

        public int positiveMaidReferences() {
            return positiveMaidReferences;
        }

        public int modfRecords() {
            return modfRecords;
        }

        public long mphdFlags() {
            return mphdFlags;
        }

        public List<Unparsed> unparsed() {
            return unparsed;
        }

        // Conservative logical metadata allowance, not a JVM/RSS measurement.
        int metadataBytes() {
            return metadataBytes;
        }
    }

    /** Caller exclusively freezes its input until this synchronous scan returns. */
    public static Facts scan(byte[] raw, BooleanSupplier canceled) {
        Objects.requireNonNull(raw);
        Objects.requireNonNull(canceled);
        check(canceled);
        require(raw.length >= 12 && raw.length <= MAX_BYTES, "WDT source limit");
        var state = new Scan(canceled);
        var input = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int chunks = 0;
        while (input.hasRemaining()) {
            check(canceled);
            require(++chunks <= MAX_CHUNKS && input.remaining() >= 8, "WDT chunk count/header");
            int tag = input.getInt(), bytes = input.getInt();
            require(bytes >= 0 && bytes <= input.remaining(), "WDT chunk span");
            int end = input.position() + bytes;
            switch (tag) {
                case MVER -> {
                    require(!state.version && bytes == 4 && input.getInt() == 18, "WDT version18");
                    state.version = true;
                }
                case MPHD -> {
                    require(
                            !state.mphd && bytes >= 4 && bytes <= 128 && bytes % 4 == 0,
                            "WDT MPHD");
                    state.mphd = true;
                    state.flags = Integer.toUnsignedLong(input.getInt());
                    if (bytes > 4) {
                        state.unparsed(MPHD, bytes - 4);
                    }
                }
                case MAIN -> {
                    require(!state.main && bytes == 4096 * 8, "WDT MAIN");
                    state.main = true;
                    for (int record = 0; record < 4096; record++) {
                        check(canceled);
                        int flags = input.getInt(), reserved = input.getInt();
                        if ((flags & 1) != 0) {
                            state.activeMain++;
                        }
                        if (flags != 0) {
                            state.nonzeroMain++;
                        }
                        if (reserved != 0) {
                            state.nonzeroMain++;
                        }
                    }
                }
                case MAID -> {
                    require(!state.maid && bytes == 4096 * 32, "WDT MAID");
                    state.maid = true;
                    for (int cell = 0; cell < 4096; cell++) {
                        check(canceled);
                        boolean positive = false;
                        for (int column = 0; column < 8; column++) {
                            if (input.getInt() != 0) {
                                positive = true;
                                state.positiveRefs++;
                            }
                        }
                        if (positive) {
                            state.positiveCells++;
                        }
                    }
                }
                case MODF -> {
                    require(
                            !state.modf && bytes % 64 == 0 && bytes / 64 <= MAX_GLOBAL_RECORDS,
                            "WDT MODF");
                    state.modf = true;
                    state.globals = bytes / 64;
                    for (int record = 0; record < state.globals; record++) {
                        check(canceled);
                    }
                    // Counts only. Do not reinterpret a global placement/reference here.
                    state.unparsed(MODF, bytes);
                }
                default -> state.unparsed(tag, bytes);
            }
            input.position(end);
        }
        require(state.version && state.mphd, "WDT missing required declarations");
        check(canceled);
        return new Facts(state);
    }

    private static final class Scan {
        private final ArrayList<Unparsed> unparsed = new ArrayList<>();
        private int budget = 4096;
        private boolean version, mphd, main, maid, modf;
        private int activeMain, nonzeroMain, positiveCells, positiveRefs, globals;
        private long flags;

        private Scan(BooleanSupplier canceled) {
            check(canceled);
        }

        private void unparsed(int tag, int bytes) {
            require(budget <= MAX_METADATA_BYTES - 96, "WDT metadata limit");
            budget += 96;
            unparsed.add(new Unparsed(tag, bytes));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
