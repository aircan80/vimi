package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Origin;
import org.junit.jupiter.api.Test;

final class PendingWritesTest {
    private Format.Entry entry(long length, boolean tombstone) {
        return new Format.Entry(
                StoreAssertions.key(new byte[] {1}),
                length,
                new byte[32],
                0,
                Origin.CDN,
                1,
                tombstone);
    }

    @Test
    void countClosesAt128Entries() {
        PendingWrites pending = new PendingWrites(1, 64, 0);
        for (int i = 0; i < 127; i++) {
            pending.add(entry(1, false), null);
        }
        assertFalse(pending.full());
        pending.add(entry(1, false), null);
        assertTrue(pending.full());
    }

    @Test
    void byteThresholdIncludesHeadersAndBlockTables() {
        PendingWrites pending = new PendingWrites(1, 64, 0);
        pending.add(entry((8L << 20) - 1, false), null);
        assertFalse(pending.full());
        pending.add(entry((8L << 20) - 1, false), null);
        assertTrue(pending.full());
    }

    @Test
    void monotonicDeadlineIncludesExactBoundaryAndClockWrap() {
        long start = Long.MAX_VALUE - 100;
        PendingWrites pending = new PendingWrites(1, 64, start);
        assertFalse(pending.expired(start + PendingWrites.MAX_NANOS - 1));
        assertTrue(pending.expired(start + PendingWrites.MAX_NANOS));
    }
}
