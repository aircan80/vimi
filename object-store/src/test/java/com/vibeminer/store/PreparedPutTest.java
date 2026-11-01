package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.store.StoreTypes.PutObject;
import org.junit.jupiter.api.Test;

final class PreparedPutTest {
    @Test
    void parallelismIsHalfTheProcessorsWithMinimumOne() {
        assertEquals(1, PreparedPut.parallelism(1));
        assertEquals(1, PreparedPut.parallelism(2));
        assertEquals(1, PreparedPut.parallelism(3));
        assertEquals(2, PreparedPut.parallelism(4));
        assertEquals(8, PreparedPut.parallelism(16));
    }

    @Test
    void preparesTheSameShaAndBlockTableFromOwnedInput() {
        byte[] bytes = new byte[2 * Format.BLOCK + 13];
        bytes[0] = 7;
        PutObject input = new PutObject(StoreAssertions.key(bytes), bytes);
        byte[] expected = bytes.clone();
        bytes[0] = 99;
        PreparedPut prepared = PreparedPut.prepare(Verification.CDN, input, Cancellation.NONE);
        assertNull(prepared.refusal());
        assertArrayEquals(expected, prepared.input().bytes());
        assertArrayEquals(Format.hash("SHA-256", expected), prepared.sha());
        for (int block = 0; block < 3; block++) {
            int start = block * Format.BLOCK;
            assertEquals(
                    (int)
                            Format.crc(
                                    expected,
                                    start,
                                    Math.min(Format.BLOCK, expected.length - start)),
                    Format.le(prepared.table()).getInt(4 * block));
        }
    }

    @Test
    void refusalPrecedesCancellationAndCanceledValidInputNeedsNoPreparedDigest() {
        Cancellation token = new Cancellation();
        token.cancel();
        byte[] bytes = {1, 2, 3};
        var valid =
                PreparedPut.prepare(
                        Verification.CDN, new PutObject(StoreAssertions.key(bytes), bytes), token);
        assertNull(valid.refusal());
        assertNull(valid.sha());
        var invalid =
                PreparedPut.prepare(
                        Verification.CDN,
                        new PutObject(StoreAssertions.key(new byte[] {9}), bytes),
                        token);
        assertEquals("OBJECT_KEY_MISMATCH", invalid.refusal().code());
        assertNull(invalid.table());
    }
}
