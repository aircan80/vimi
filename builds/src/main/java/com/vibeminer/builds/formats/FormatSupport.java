package com.vibeminer.builds.formats;

import com.vibeminer.builds.BuildFailure;
import com.vibeminer.builds.BuildFormatException;
import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class FormatSupport {
    private FormatSupport() {}

    static BuildFormatException corrupt(String code, String message) {
        return new BuildFormatException(BuildFailure.CORRUPT, code, message);
    }

    static BuildFormatException unsupported(String code, String message) {
        return new BuildFormatException(BuildFailure.UNSUPPORTED, code, message);
    }

    static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static long u32(byte[] bytes, int at) {
        return Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    static long le32(byte[] bytes, int at) {
        return Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    static byte[] slice(byte[] bytes, int at, int length) {
        return Arrays.copyOfRange(bytes, at, at + length);
    }

    static CdnKey key(byte[] bytes, int at) {
        return new CdnKey(slice(bytes, at, 16));
    }

    static boolean zero(byte[] bytes, int at, int length) {
        for (int i = at; i < at + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    static byte[] md5(byte[] bytes) {
        return md5(bytes, 0, bytes.length);
    }

    static byte[] md5(byte[] bytes, int at, int length) {
        try {
            var md5 = MessageDigest.getInstance("MD5");
            md5.update(bytes, at, length);
            return md5.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void hash(byte[] actual, byte[] expected, String code, String message) {
        if (!MessageDigest.isEqual(actual, expected)) {
            throw corrupt(code, message);
        }
    }
}
