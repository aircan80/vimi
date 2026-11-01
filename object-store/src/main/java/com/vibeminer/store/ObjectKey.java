package com.vibeminer.store;

import java.util.Arrays;
import java.util.HexFormat;

/** Full identity: type, namespace, and 16 key bytes. Unknown byte types can be represented for refusals. */
public final class ObjectKey implements Comparable<ObjectKey> {
    public static final int CONFIG = 1, ARCHIVE_INDEX = 2, FILE_INDEX = 3, ENCODED = 4,
        HISTORY_DOCUMENT = 5, HISTORY_RECORD = 6, GENERATED = 7;
    private final int type, namespace;
    private final byte[] key;
    public ObjectKey(int type, int namespace, byte[] key) {
        if (type < 0 || type > 255 || namespace < 0 || namespace > 255 || key.length != 16)
            throw new IllegalArgumentException("Key requires byte type/namespace and 16 bytes");
        this.type = type; this.namespace = namespace; this.key = key.clone();
    }
    public ObjectKey(int type, byte[] key) { this(type, 0, key); }
    public static ObjectKey parse(int type, String hex) { return new ObjectKey(type, HexFormat.of().parseHex(hex)); }
    public int type() { return type; }
    public int namespace() { return namespace; }
    public byte[] bytes() { return key.clone(); }
    public String hex() { return HexFormat.of().formatHex(key); }
    public byte[] indexBytes() {
        byte[] b = new byte[18]; b[0] = (byte) type; b[1] = (byte) namespace;
        System.arraycopy(key, 0, b, 2, 16); return b;
    }
    static ObjectKey fromIndex(byte[] b) {
        if (b.length != 18) throw new IllegalArgumentException("Index key requires 18 bytes");
        return new ObjectKey(Byte.toUnsignedInt(b[0]), Byte.toUnsignedInt(b[1]), Arrays.copyOfRange(b, 2, 18));
    }
    @Override public int compareTo(ObjectKey other) { return Arrays.compareUnsigned(indexBytes(), other.indexBytes()); }
    @Override public boolean equals(Object o) { return o instanceof ObjectKey k && compareTo(k) == 0; }
    @Override public int hashCode() { return Arrays.hashCode(indexBytes()); }
    @Override public String toString() { return type + "/" + namespace + "/" + hex(); }
}
