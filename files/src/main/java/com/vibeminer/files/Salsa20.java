package com.vibeminer.files;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Salsa20/20, Bernstein's specified20-round core. */
final class Salsa20 {
    private Salsa20() {}

    static void transform(byte[] key, byte[] nonce, byte[] data) {
        if ((key.length != 16 && key.length != 32) || nonce.length != 8) {
            throw new IllegalArgumentException("Invalid Salsa20 key/nonce length");
        }
        int[] input = new int[16];
        boolean wide = key.length == 32;
        input[0] = 0x61707865;
        input[5] = wide ? 0x3320646e : 0x3120646e;
        input[10] = wide ? 0x79622d32 : 0x79622d36;
        input[15] = 0x6b206574;
        ByteBuffer kb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN),
                nb = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            input[1 + i] = kb.getInt(i * 4);
            input[11 + i] = kb.getInt((wide ? 16 : 0) + i * 4);
        }
        input[6] = nb.getInt();
        input[7] = nb.getInt();
        int[] x = new int[16];
        byte[] stream = new byte[64];
        try {
            for (int at = 0; at < data.length; ) {
                System.arraycopy(input, 0, x, 0, 16);
                for (int r = 0; r < 10; r++) {
                    quarter(x, 0, 4, 8, 12);
                    quarter(x, 5, 9, 13, 1);
                    quarter(x, 10, 14, 2, 6);
                    quarter(x, 15, 3, 7, 11);
                    quarter(x, 0, 1, 2, 3);
                    quarter(x, 5, 6, 7, 4);
                    quarter(x, 10, 11, 8, 9);
                    quarter(x, 15, 12, 13, 14);
                }
                ByteBuffer out = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 16; i++) {
                    out.putInt(x[i] + input[i]);
                }
                int n = Math.min(64, data.length - at);
                for (int i = 0; i < n; i++) {
                    data[at + i] ^= stream[i];
                }
                at += n;
                if (++input[8] == 0) {
                    input[9]++;
                }
            }
        } finally {
            Arrays.fill(input, 0);
            if (x != null) {
                Arrays.fill(x, 0);
            }
            Arrays.fill(stream, (byte) 0);
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[b] ^= Integer.rotateLeft(x[a] + x[d], 7);
        x[c] ^= Integer.rotateLeft(x[b] + x[a], 9);
        x[d] ^= Integer.rotateLeft(x[c] + x[b], 13);
        x[a] ^= Integer.rotateLeft(x[d] + x[c], 18);
    }
}
