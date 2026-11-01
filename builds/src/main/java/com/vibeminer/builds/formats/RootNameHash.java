package com.vibeminer.builds.formats;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Bob Jenkins lookup3 hashlittle2, public domain; both seeds zero, pc in the high half. */
public final class RootNameHash {
    private RootNameHash() {}

    public static long of(String path) {
        byte[] key =
                Objects.requireNonNull(path)
                        .toUpperCase(Locale.ROOT)
                        .replace('/', '\\')
                        .getBytes(StandardCharsets.UTF_8);
        int a = 0xdeadbeef + key.length, b = a, c = a, at = 0;
        if (key.length == 0) {
            return (Integer.toUnsignedLong(c) << 32) | Integer.toUnsignedLong(b);
        }
        while (key.length - at > 12) {
            a += le(key, at);
            b += le(key, at + 4);
            c += le(key, at + 8);
            a -= c;
            a ^= Integer.rotateLeft(c, 4);
            c += b;
            b -= a;
            b ^= Integer.rotateLeft(a, 6);
            a += c;
            c -= b;
            c ^= Integer.rotateLeft(b, 8);
            b += a;
            a -= c;
            a ^= Integer.rotateLeft(c, 16);
            c += b;
            b -= a;
            b ^= Integer.rotateLeft(a, 19);
            a += c;
            c -= b;
            c ^= Integer.rotateLeft(b, 4);
            b += a;
            at += 12;
        }
        byte[] tail = new byte[12];
        System.arraycopy(key, at, tail, 0, key.length - at);
        a += le(tail, 0);
        b += le(tail, 4);
        c += le(tail, 8);
        c ^= b;
        c -= Integer.rotateLeft(b, 14);
        a ^= c;
        a -= Integer.rotateLeft(c, 11);
        b ^= a;
        b -= Integer.rotateLeft(a, 25);
        c ^= b;
        c -= Integer.rotateLeft(b, 16);
        a ^= c;
        a -= Integer.rotateLeft(c, 4);
        b ^= a;
        b -= Integer.rotateLeft(a, 14);
        c ^= b;
        c -= Integer.rotateLeft(b, 24);
        return (Integer.toUnsignedLong(c) << 32) | Integer.toUnsignedLong(b);
    }

    private static int le(byte[] bytes, int at) {
        return (bytes[at] & 255)
                | ((bytes[at + 1] & 255) << 8)
                | ((bytes[at + 2] & 255) << 16)
                | ((bytes[at + 3] & 255) << 24);
    }
}
