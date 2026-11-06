package com.vimi.gui;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * World explorer control preferences: mouse-look sensitivity and invert-Y. They persist with the
 * other World settings in the library database ({@link WorldBookmarkStore}) as one versioned
 * text value. Decoding is lenient: an unknown key is ignored, and a missing or malformed
 * value falls back to its default, so a damaged entry never blocks the explorer.
 */
record WorldControls(double sensitivity, boolean invertY) {
    /** Radians of look per pixel of mouse movement. */
    static final double MIN_SENSITIVITY = .001, MAX_SENSITIVITY = .02, DEFAULT_SENSITIVITY = .006;

    static final WorldControls DEFAULTS = new WorldControls(DEFAULT_SENSITIVITY, false);

    private static final String VERSION = "v1";

    WorldControls {
        sensitivity =
                Double.isFinite(sensitivity)
                        ? Math.clamp(sensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY)
                        : DEFAULT_SENSITIVITY;
    }

    WorldControls withSensitivity(double next) {
        return new WorldControls(next, invertY);
    }

    WorldControls withInvertY(boolean next) {
        return new WorldControls(sensitivity, next);
    }

    String encode() {
        return String.format(
                Locale.ROOT, "%s;sensitivity=%s;invertY=%b", VERSION, sensitivity, invertY);
    }

    /** Lenient decode: anything unreadable keeps its default. Null or another version: defaults. */
    static WorldControls decode(String text) {
        if (text == null) return DEFAULTS;
        var parts = text.split(";", -1);
        if (parts.length == 0 || !parts[0].equals(VERSION)) return DEFAULTS;
        double sensitivity = DEFAULT_SENSITIVITY;
        boolean invertY = false;
        for (int i = 1; i < parts.length; i++) {
            int at = parts[i].indexOf('=');
            if (at < 0) continue;
            String key = parts[i].substring(0, at), value = parts[i].substring(at + 1);
            switch (key) {
                case "sensitivity" -> {
                    try {
                        sensitivity = Double.parseDouble(value);
                    } catch (NumberFormatException ignored) {
                        sensitivity = DEFAULT_SENSITIVITY;
                    }
                }
                case "invertY" -> invertY = value.equals("true");
                default -> {}
            }
        }
        return new WorldControls(sensitivity, invertY);
    }

    /** Where the encoded value lives. Failures are logged, never thrown: controls are a convenience. */
    interface Store {
        /** The stored value, or null when none was saved; completes on any thread. */
        CompletableFuture<String> load();

        void save(String value);

        /** The World store of the library (or its session-only store). */
        static Store of(WorldBookmarkStore store) {
            Objects.requireNonNull(store);
            return new Store() {
                @Override
                public CompletableFuture<String> load() {
                    return store.loadControls();
                }

                @Override
                public void save(String value) {
                    store.saveControls(value).exceptionally(failure -> {
                        WorldDiagnostics.warn("World controls could not be saved", failure);
                        return null;
                    });
                }
            };
        }
    }

    /** Session-only storage, used by views without a library and by tests. */
    static Store memory() {
        return new Store() {
            private String value;

            @Override
            public CompletableFuture<String> load() {
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public void save(String next) {
                value = Objects.requireNonNull(next);
            }
        };
    }
}
