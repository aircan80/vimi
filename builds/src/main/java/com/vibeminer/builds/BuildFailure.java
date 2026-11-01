package com.vibeminer.builds;

/** Stable metadata failure category, kept separate from network/store outcomes. */
public enum BuildFailure {
    CORRUPT,
    ENCRYPTED,
    UNSUPPORTED
}
