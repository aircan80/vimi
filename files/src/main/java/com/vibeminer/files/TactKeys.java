package com.vibeminer.files;

import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe key collection; unknown keys leave named holes, never zero-filled bytes. */
public final class TactKeys implements TactKeySource {
    public static final TactKeySource NONE = key -> null;
    private final ConcurrentHashMap<Long, byte[]> keys = new ConcurrentHashMap<>();

    public TactKeys add(long name, byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("TACT key is16 bytes");
        }
        keys.put(name, key.clone());
        return this;
    }

    @Override
    public byte[] find(long name) {
        byte[] key = keys.get(name);
        return key == null ? null : key.clone();
    }
}
