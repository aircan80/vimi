package com.vibeminer.files.keys;

import com.vibeminer.files.TactKeySource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Immutable, bounded public key list; key values never escape without a defensive copy. */
public final class TactKeys implements TactKeySource {
    public static final TactKeys EMPTY = new TactKeys(Map.of());
    private final Map<Long, byte[]> keys;
    private final Map<String, String> fingerprints;
    private final String fingerprint;

    public TactKeys(Map<Long, byte[]> source) {
        if (source.size() > 65536) {
            throw new IllegalArgumentException("Too many TACT keys");
        }
        var owned = new HashMap<Long, byte[]>();
        var prints = new HashMap<String, String>();
        source.forEach(
                (name, key) -> {
                    if (key == null || key.length != 16) {
                        throw new IllegalArgumentException("Invalid TACT key width");
                    }
                    owned.put(name, key.clone());
                    prints.put(String.format(java.util.Locale.ROOT, "%016X", name), hash(key));
                });
        keys = Map.copyOf(owned);
        fingerprints = Map.copyOf(prints);
        fingerprint =
                hash(
                        prints.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> entry.getKey() + ":" + entry.getValue())
                                .collect(java.util.stream.Collectors.joining("\n"))
                                .getBytes(StandardCharsets.US_ASCII));
    }

    public static TactKeys parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > (4 << 20)) {
            throw new IllegalArgumentException("Invalid key list size");
        }
        for (byte value : bytes) {
            if (value < 0 || value == 0) {
                throw new IllegalArgumentException("Key list must be ASCII text");
            }
        }
        var values = new HashMap<Long, byte[]>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length < 2
                    || !parts[0].matches("[0-9A-Fa-f]{16}")
                    || !parts[1].matches("[0-9A-Fa-f]{32}")) {
                throw new IllegalArgumentException("Malformed TACT key list entry");
            }
            long name = Long.parseUnsignedLong(parts[0], 16);
            byte[] key = HexFormat.of().parseHex(parts[1]);
            byte[] previous = values.putIfAbsent(name, key);
            if (previous != null && !Arrays.equals(previous, key)) {
                throw new IllegalArgumentException("Conflicting TACT key entries");
            }
            if (values.size() > 65536) {
                throw new IllegalArgumentException("Too many TACT keys");
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Empty TACT key list");
        }
        return new TactKeys(values);
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override
    public byte[] find(long name) {
        var value = keys.get(name);
        return value == null ? null : value.clone();
    }

    public int count() {
        return keys.size();
    }

    public Map<String, String> fingerprints() {
        return fingerprints;
    }

    public String fingerprint() {
        return fingerprint;
    }
}
