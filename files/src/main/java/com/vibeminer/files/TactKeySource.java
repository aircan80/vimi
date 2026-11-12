package com.vibeminer.files;

/** Finds an owned16-byte key by its unsigned little-endian64-bit name. */
@FunctionalInterface
public interface TactKeySource {
    byte[] find(long keyName);

    /**
     * Changes whenever the set of keys this source finds changes, so a reader can retry what an
     * earlier key set could not decrypt; a fixed source answers 0.
     */
    default long revision() {
        return 0;
    }
}
