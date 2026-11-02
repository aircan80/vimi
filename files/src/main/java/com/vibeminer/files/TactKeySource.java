package com.vibeminer.files;

/** Finds an owned16-byte key by its unsigned little-endian64-bit name. */
@FunctionalInterface
public interface TactKeySource {
    byte[] find(long keyName);
}
