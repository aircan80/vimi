package com.vibeminer.formats.generated;

import java.util.Map;

/** Immutable key fingerprints; changing a key value changes the table's generated identity. */
public final class TactKeyStatus implements KeyStatus {
    public static final TactKeyStatus NONE = new TactKeyStatus(Map.of());
    private final Map<String, String> fingerprints;

    public TactKeyStatus(Map<String, String> fingerprints) {
        fingerprints.forEach(
                (name, fingerprint) -> {
                    checkName(name);
                    if (fingerprint == null
                            || !fingerprint.matches("[!-~]{1,128}")
                            || fingerprint.equals("?")) {
                        throw new IllegalArgumentException("Invalid key fingerprint");
                    }
                });
        this.fingerprints = Map.copyOf(fingerprints);
    }

    public String fingerprintOf(String name) {
        return fingerprints.get(name);
    }

    public static void checkName(String name) {
        if (name == null || !name.matches("[0-9A-F]{16}")) {
            throw new IllegalArgumentException("Canonical TACT name required");
        }
    }
}
