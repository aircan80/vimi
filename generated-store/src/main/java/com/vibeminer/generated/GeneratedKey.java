package com.vibeminer.generated;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Caller-chosen 16-byte identity, typically the first 16 bytes of SHA-256. */
public final class GeneratedKey implements Comparable<GeneratedKey> {
    private final byte[] bytes;

    public GeneratedKey(byte[] bytes) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("A generated key is 16 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static GeneratedKey hash(byte[] identity) {
        try {
            return new GeneratedKey(
                    Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(identity), 16));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static GeneratedKey parse(String hex) {
        Objects.requireNonNull(hex);
        if (hex.length() != 32) {
            throw new IllegalArgumentException("A generated key is 32 hex digits");
        }
        return new GeneratedKey(HexFormat.of().parseHex(hex));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public int compareTo(GeneratedKey other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeneratedKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}
