package com.vibeminer.questie.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CborTest {
    @Test
    void decodesBlizzardByteStringsAndSparseTables() {
        // Lua strings use CBOR byte strings, including the scalar-row presence key p.
        assertEquals(
                Map.of("1", "Hi", "p", 4L),
                Cbor.decode(new byte[] {(byte) 0xa2, 1, 0x42, 'H', 'i', 0x41, 'p', 4}));
        var list = (List<?>) Cbor.decode(new byte[] {(byte) 0x83, 1, (byte) 0xf6, 3});
        assertEquals(3, list.size());
        assertNull(list.get(1));
        assertEquals(
                Map.of("3", List.of(117L)),
                Cbor.decode(new byte[] {(byte) 0xa1, 3, (byte) 0x81, 0x18, 117}));
    }

    @Test
    void rejectsAmbiguousKeysUnsupportedTagsAndInvalidUtf8() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Cbor.decode(new byte[] {(byte) 0xa2, 1, 0, 0x41, '1', 0}));
        assertThrows(
                IllegalArgumentException.class, () -> Cbor.decode(new byte[] {(byte) 0xc0, 0}));
        assertThrows(
                IllegalArgumentException.class, () -> Cbor.decode(new byte[] {0x41, (byte) 0xff}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Cbor.decode(new byte[] {(byte) 0x9f, (byte) 0xff}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Cbor.decode(new byte[] {(byte) 0xfb, 0x7f, (byte) 0xf0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    void rejectsTruncationTrailingDataAndComplexityBombs() {
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(new byte[] {0x42, 1}));
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(new byte[] {1, 2}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Cbor.decode(new byte[] {(byte) 0x9a, 0, 0x20, 0, 1}));
        var nested = new byte[67];
        java.util.Arrays.fill(nested, (byte) 0x81);
        nested[66] = 0;
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(nested));
    }
}
