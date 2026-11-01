package com.vibeminer.builds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Byte-identical C# build identity v2: product and every versions field except Region/CDNConfig.
 */
public record BuildIdentity(
        String id, String canonical, String productId, Map<String, String> fields) {
    public static final String REVISION = "vibeminer-build-identity-v2";

    public BuildIdentity {
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }

    public static boolean isValidRegion(String region) {
        return region != null && region.matches("[a-z]{2,8}");
    }

    public static BuildIdentity create(String product, Map<String, String> source) {
        product(product);
        Objects.requireNonNull(source);
        for (String name : new String[] {"Region", "BuildConfig", "CDNConfig", "VersionsName"}) {
            if (source.get(name) == null || source.get(name).isEmpty()) {
                throw invalid("A required identity field is missing");
            }
        }
        if (!isValidRegion(source.get("Region"))) {
            throw new BuildIdentityException("INVALID_REGION", "A region is invalid");
        }
        observedCdnConfig(source);
        Map<String, String> fields = new TreeMap<>(source);
        fields.remove("Region");
        fields.remove("CDNConfig");
        return fromFields(product, fields);
    }

    public static String observedCdnConfig(Map<String, String> source) {
        String value = source.get("CDNConfig");
        if (value == null || !value.matches("[a-fA-F0-9]{32}")) {
            throw invalid("A configuration identity is invalid");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public static BuildIdentity fromFields(String product, Map<String, String> source) {
        product(product);
        Objects.requireNonNull(source);
        for (String name : new String[] {"BuildConfig", "VersionsName"}) {
            if (source.get(name) == null || source.get(name).isEmpty()) {
                throw invalid("A required identity field is missing");
            }
        }
        if (source.containsKey("Region") || source.containsKey("CDNConfig")) {
            throw invalid("Region and CDNConfig are not identity fields");
        }
        Map<String, String> fields = new TreeMap<>();
        source.forEach(
                (name, value) -> {
                    Objects.requireNonNull(name);
                    Objects.requireNonNull(value);
                    if (name.equals("BuildConfig") || name.equals("ProductConfig")) {
                        if (!value.isEmpty() && !value.matches("[a-fA-F0-9]{32}")) {
                            throw invalid("A configuration identity is invalid");
                        }
                        value = value.toLowerCase(java.util.Locale.ROOT);
                    }
                    fields.put(name, value);
                });
        StringBuilder canonical = new StringBuilder();
        frame(canonical, REVISION);
        frame(canonical, product);
        frame(canonical, "Published");
        fields.forEach(
                (key, value) -> {
                    frame(canonical, key);
                    frame(canonical, value);
                });
        try {
            String text = canonical.toString();
            String id =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(text.getBytes(StandardCharsets.UTF_8)));
            return new BuildIdentity(id, text, product, fields);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void frame(StringBuilder text, String value) {
        text.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static void product(String product) {
        if (!Products.isWow(product)) {
            throw new BuildIdentityException(
                    "INVALID_PRODUCT", "A product is wow followed by up to 61 of a-z, 0-9, _ or -");
        }
    }

    private static BuildIdentityException invalid(String message) {
        return new BuildIdentityException("INVALID_IDENTITY", message);
    }
}
