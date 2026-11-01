package com.vibeminer.network;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Opaque 16-byte CDN key; its interpretation belongs to the caller. */
public final class CdnKey implements Comparable<CdnKey> {
    private final byte[] bytes;

    public CdnKey(byte[] bytes) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("A generated key is 16 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static CdnKey parse(String hex) {
        Objects.requireNonNull(hex);
        if (hex.length() != 32) {
            throw new IllegalArgumentException("A generated key is 32 hex digits");
        }
        return new CdnKey(HexFormat.of().parseHex(hex));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public int compareTo(CdnKey other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CdnKey key && Arrays.equals(bytes, key.bytes);
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
