package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class Salsa20Test {
    @ParameterizedTest
    @CsvSource({
        "16,4DFA5E481DA23EA09A31022050859936DA52FCEE218005164F267CB65F5CFD7F2B4F97E0FF16924A52DF269515110A07F9E460BC65EF95DA58F740B7D1DBB0AA,DA9C1581F429E0A00F7D67E23B730676783B262E8EB43A25F55FB90B3E753AEF8C6713EC66C51881111593CCB3E8CB8F8DE124080501EEEB389C4BCB6977CF95",
        "32,E3BE8FDD8BECA2E3EA8EF9475B29A6E7003951E1097A5C38D23B7A5FAD9F6844B22C97559E2723C7CBBD3FE4FC8D9A0744652A83E72A9C461876AF4D7EF1A117,57BE81F47B17D9AE7C4FF15429A73E10ACF250ED3A90A93C711308A74C6216A9ED84CD126DA7F28E8ABF8BB63517E1CA98E712F4FB2E1A6AED9FDC73291FAA17"
    })
    void estreamSetOne(int keyBytes, String first, String fourth) {
        byte[] key = new byte[keyBytes];
        key[0] = (byte) 128;
        byte[] data = new byte[256];
        Salsa20.transform(key, new byte[8], data);
        assertArrayEquals(HexFormat.of().parseHex(first), Arrays.copyOf(data, 64));
        assertArrayEquals(HexFormat.of().parseHex(fourth), Arrays.copyOfRange(data, 192, 256));
        Salsa20.transform(key, new byte[8], data);
        assertArrayEquals(new byte[256], data);
    }

    @ParameterizedTest
    @CsvSource({
        "0053A6F94C9FF24598EB3E91E4378ADD3083D6297CCF2275C81B6EC11467BA0D,F5FAD53F79F9DF58C4AEA0D0ED9A9601F278112CA7180D565B420A48019670EAF24CE493A86263F677B46ACE1924773D2BB25571E1AA8593758FC382B1280B71",
        "0053A6F94C9FF24598EB3E91E4378ADD,05E1E7BEB697D999656BF37C1B978806735D0B903A6007BD329927EFBE1B0E2A8137C1AE291493AA83A821755BEE0B06CD14855A67E46703EBF8F3114B584CBA"
    })
    void nonzeroNonceVector(String key, String expected) {
        byte[] data = new byte[64];
        Salsa20.transform(
                HexFormat.of().parseHex(key), HexFormat.of().parseHex("0D74DB42A91077DE"), data);
        assertArrayEquals(HexFormat.of().parseHex(expected), data);
    }

    @Test
    void rejectsInvalidInputs() {
        for (int n : new int[] {0, 15, 24, 33}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Salsa20.transform(new byte[n], new byte[8], new byte[0]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> Salsa20.transform(new byte[16], new byte[7], new byte[0]));
    }
}
