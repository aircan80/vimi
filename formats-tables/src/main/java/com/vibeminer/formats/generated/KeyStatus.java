package com.vibeminer.formats.generated;

/** Snapshot of TACT key values: null is unknown, otherwise opaque printable fingerprint. */
@FunctionalInterface
public interface KeyStatus {
    String fingerprintOf(String keyName);
}
