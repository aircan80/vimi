package com.vibeminer.store;

import org.junit.jupiter.api.Test;
import com.vibeminer.store.StoreTypes.*;
import static com.vibeminer.store.StoreAssertions.*;

final class FormatTest {
    @Test void matchesPublishedFormatExample() {
        byte[] bytes = "build-name = example\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        check(key(bytes).hex().equals("0ed94175af8022f25c2104eca1211bc6"));
        var entry = new Format.Entry(key(bytes), bytes.length, Format.hash("SHA-256", bytes), 1793404800000L, Origin.CDN, 1, false);
        check(Integer.toUnsignedLong(Format.le(entry.header()).getInt(92)) == 0x6d1d7bcbL);
        check(Format.crc(bytes) == 0xa8d0a273L);
        check(Verification.CDN.check(key(bytes), bytes, bytes.length) == null);
        check(Verification.CDN.check(new ObjectKey(1, new byte[16]), bytes, bytes.length).code().equals("OBJECT_KEY_MISMATCH"));
    }
}
