package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.Refusal;

/** Owned proof/table metadata produced on CPU workers before the serial writer sees the payload. */
record PreparedPut(
        PutObject input, Refusal refusal, byte[] sha, byte[] table, History.Entry history) {
    static int parallelism(int processors) {
        return Math.max(1, processors / 2);
    }

    static PreparedPut prepare(Verification verifier, PutObject input, Cancellation cancellation) {
        Refusal refusal = verifier.check(input.key(), input.data(), input.declaredLength());
        if (refusal != null || cancellation.isCanceled()) {
            return new PreparedPut(input, refusal, null, null, null);
        }
        byte[] sha = Format.hash("SHA-256", input.data());
        byte[] table = new byte[Format.blocks(input.data().length) * 4];
        var output = Format.le(table);
        for (int block = 0; block < Format.blocks(input.data().length); block++) {
            if (cancellation.isCanceled()) {
                return new PreparedPut(input, null, null, null, null);
            }
            int start = block * Format.BLOCK;
            output.putInt(
                    (int)
                            Format.crc(
                                    input.data(),
                                    start,
                                    Math.min(Format.BLOCK, input.data().length - start)));
        }
        History.Entry history =
                input.key().type() == 6 ? History.parse(input.data(), input.key()) : null;
        return new PreparedPut(input, null, sha, table, history);
    }
}
