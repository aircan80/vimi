package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ObjectKeyTest {
    @Test
    void orderingAndHashAgreeWithUnsignedBinaryIdentity() {
        var keys = new ArrayList<ObjectKey>();
        for (int type : new int[] {0, 127, 128, 255}) {
            for (int namespace : new int[] {0, 127, 128, 255}) {
                for (int value : new int[] {0, 127, 128, 255}) {
                    byte[] bytes = new byte[16];
                    bytes[0] = (byte) value;
                    keys.add(new ObjectKey(type, namespace, bytes));
                }
            }
        }
        for (ObjectKey left : keys) {
            assertEquals(Arrays.hashCode(left.indexBytes()), left.hashCode());
            assertEquals(left, new ObjectKey(left.type(), left.namespace(), left.bytes()));
            for (ObjectKey right : keys) {
                int expected = Arrays.compareUnsigned(left.indexBytes(), right.indexBytes());
                assertEquals(Integer.signum(expected), Integer.signum(left.compareTo(right)));
                assertEquals(expected == 0, left.equals(right));
            }
        }
    }

    @Test
    void publicByteArraysCannotMutateKeyIdentity() {
        byte[] bytes = new byte[16];
        ObjectKey key = new ObjectKey(1, bytes);
        int hash = key.hashCode();
        bytes[0] = 1;
        key.bytes()[0] = 2;
        key.indexBytes()[2] = 3;
        assertArrayEquals(new byte[16], key.bytes());
        assertEquals(hash, key.hashCode());
        assertNotEquals(key, new ObjectKey(1, bytes));
    }
}
