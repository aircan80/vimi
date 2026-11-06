package com.vimi.gui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javafx.scene.input.KeyCode;

/**
 * World explorer control preferences: mouse-look sensitivity and invert-Y, the flight speed
 * preset, and the key bindings. They persist with the other World settings in the library
 * database ({@link WorldBookmarkStore}) as one versioned text value. Decoding is lenient: an
 * unknown key, action or key code is ignored, and a missing or malformed value falls back to its
 * default, so a damaged entry never blocks the explorer.
 *
 * <p>Bindings map {@link KeyCode}s to actions. JavaFX key codes for letter keys follow the
 * active keyboard layout (they name the key's character, not its physical position), so the
 * QWERTY W/A/S/D positions report other codes on AZERTY; the layout presets and per-action
 * rebinding cover that.
 */
record WorldControls(double sensitivity, boolean invertY, Speed speed, Map<KeyCode, Action> keys) {
    /** Radians of look per pixel of mouse movement. */
    static final double MIN_SENSITIVITY = .001, MAX_SENSITIVITY = .02, DEFAULT_SENSITIVITY = .006;

    /** Explorer actions a key can drive. */
    enum Action {
        FORWARD("Forward"),
        BACK("Back"),
        LEFT("Strafe left"),
        RIGHT("Strafe right"),
        UP("Up"),
        DOWN("Down"),
        TURN_LEFT("Turn left"),
        TURN_RIGHT("Turn right"),
        LOOK_UP("Look up"),
        LOOK_DOWN("Look down"),
        SPRINT("Faster (hold; with the wheel: speed)"),
        WALK("Walk on terrain (toggle)"),
        DEV_OVERLAY("Developer overlay (next page)", true),
        SCREENSHOT("Save a viewport screenshot", true);

        final String label;
        /**
         * A HUD command: it fires once per physical press through {@link
         * WorldExplorerInput.Host#command} and never drives motion.
         */
        final boolean command;

        Action(String label) {
            this(label, false);
        }

        Action(String label, boolean command) {
            this.label = label;
            this.command = command;
        }
    }

    /** Flight speed presets in yards per second; walking on terrain keeps its own speed. */
    enum Speed {
        STROLL("Stroll", 7),
        MOUNT("Mount", 14),
        FLY("Fly", 20),
        FAST("Fast", 60),
        TURBO("Turbo", 200);

        final String label;
        final double yardsPerSecond;

        Speed(String label, double yardsPerSecond) {
            this.label = label;
            this.yardsPerSecond = yardsPerSecond;
        }

        /** The next preset in {@code direction} (+1 faster, -1 slower), stopping at the ends. */
        Speed step(int direction) {
            var all = values();
            return all[Math.clamp(ordinal() + Integer.signum(direction), 0, all.length - 1)];
        }

        @Override
        public String toString() {
            return label + String.format(Locale.ROOT, " · %.0f yd/s", yardsPerSecond);
        }
    }

    /** Key layout presets; QWERTZ keeps the QWERTY positions for every bound key. */
    enum Layout {
        QWERTY("QWERTY / QWERTZ"),
        AZERTY("AZERTY");

        final String label;

        Layout(String label) {
            this.label = label;
        }

        Map<KeyCode, Action> keys() {
            var keys = new LinkedHashMap<KeyCode, Action>();
            if (this == AZERTY) {
                keys.put(KeyCode.Z, Action.FORWARD);
                keys.put(KeyCode.S, Action.BACK);
                keys.put(KeyCode.Q, Action.LEFT);
                keys.put(KeyCode.D, Action.RIGHT);
                keys.put(KeyCode.E, Action.UP);
                keys.put(KeyCode.SPACE, Action.UP);
                keys.put(KeyCode.A, Action.DOWN);
                keys.put(KeyCode.X, Action.DOWN);
            } else {
                keys.put(KeyCode.W, Action.FORWARD);
                keys.put(KeyCode.S, Action.BACK);
                keys.put(KeyCode.A, Action.LEFT);
                keys.put(KeyCode.D, Action.RIGHT);
                keys.put(KeyCode.E, Action.UP);
                keys.put(KeyCode.SPACE, Action.UP);
                keys.put(KeyCode.Q, Action.DOWN);
                keys.put(KeyCode.X, Action.DOWN);
            }
            keys.put(KeyCode.LEFT, Action.TURN_LEFT);
            keys.put(KeyCode.RIGHT, Action.TURN_RIGHT);
            keys.put(KeyCode.UP, Action.LOOK_UP);
            keys.put(KeyCode.DOWN, Action.LOOK_DOWN);
            keys.put(KeyCode.SHIFT, Action.SPRINT);
            keys.put(KeyCode.F, Action.WALK);
            // Function keys sit in the same place on every layout.
            keys.put(KeyCode.F3, Action.DEV_OVERLAY);
            keys.put(KeyCode.F12, Action.SCREENSHOT);
            return keys;
        }
    }

    /**
     * Keys that never bind: Escape cancels a rebind, F11 and Alt(+Enter) are fullscreen, and the
     * system keys belong to the desktop. Shift and Control can bind.
     */
    static boolean bindable(KeyCode code) {
        return code != null
                && switch (code) {
                    case UNDEFINED, ESCAPE, F11, ALT, ALT_GRAPH, META, WINDOWS, COMMAND -> false;
                    default -> true;
                };
    }

    static final WorldControls DEFAULTS =
            new WorldControls(DEFAULT_SENSITIVITY, false, Speed.FLY, Layout.QWERTY.keys());

    private static final String VERSION = "v1";

    WorldControls {
        sensitivity =
                Double.isFinite(sensitivity)
                        ? Math.clamp(sensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY)
                        : DEFAULT_SENSITIVITY;
        speed = speed == null ? Speed.FLY : speed;
        var copy = new LinkedHashMap<KeyCode, Action>();
        Objects.requireNonNull(keys)
                .forEach(
                        (code, action) -> {
                            if (bindable(code) && action != null) copy.put(code, action);
                        });
        keys = Collections.unmodifiableMap(copy);
    }

    WorldControls withSensitivity(double next) {
        return new WorldControls(next, invertY, speed, keys);
    }

    WorldControls withInvertY(boolean next) {
        return new WorldControls(sensitivity, next, speed, keys);
    }

    WorldControls withSpeed(Speed next) {
        return new WorldControls(sensitivity, invertY, next, keys);
    }

    WorldControls withKeys(Map<KeyCode, Action> next) {
        return new WorldControls(sensitivity, invertY, speed, next);
    }

    /** Adds {@code code} to {@code action}, taking it from whatever it drove before. */
    WorldControls bind(KeyCode code, Action action) {
        if (!bindable(code)) return this;
        var next = new LinkedHashMap<>(keys);
        next.remove(code);
        next.put(code, Objects.requireNonNull(action));
        return withKeys(next);
    }

    /** Removes every key bound to {@code action}. */
    WorldControls unbind(Action action) {
        var next = new LinkedHashMap<>(keys);
        next.values().removeIf(action::equals);
        return withKeys(next);
    }

    /** Keys per action, in binding order, for display. */
    Map<Action, java.util.List<KeyCode>> byAction() {
        var result = new EnumMap<Action, java.util.List<KeyCode>>(Action.class);
        for (var action : Action.values()) result.put(action, new java.util.ArrayList<>());
        keys.forEach((code, action) -> result.get(action).add(code));
        return result;
    }

    /**
     * The modifier the SPRINT action is bound to (Shift or Control), so the wheel can change speed
     * with it; null when sprint is bound to no modifier.
     */
    KeyCode sprintModifier() {
        for (var e : keys.entrySet()) {
            if (e.getValue() == Action.SPRINT
                    && (e.getKey() == KeyCode.SHIFT || e.getKey() == KeyCode.CONTROL))
                return e.getKey();
        }
        return null;
    }

    /** The explorer's one-line key help, from the current bindings. */
    String help() {
        var bound = byAction();
        java.util.function.Function<Action, String> keysOf = action -> bound.get(action).isEmpty()
                ? "unbound" : bound.get(action).stream().map(KeyCode::getName).collect(Collectors.joining("/"));
        var modifier = sprintModifier();
        return keysOf.apply(Action.FORWARD) + " " + keysOf.apply(Action.LEFT) + " "
                + keysOf.apply(Action.BACK) + " " + keysOf.apply(Action.RIGHT) + " move · "
                + keysOf.apply(Action.UP) + " up · " + keysOf.apply(Action.DOWN) + " down · hold a mouse"
                + " button or " + keysOf.apply(Action.TURN_LEFT) + "/" + keysOf.apply(Action.TURN_RIGHT)
                + "/" + keysOf.apply(Action.LOOK_UP) + "/" + keysOf.apply(Action.LOOK_DOWN) + " to look · "
                + keysOf.apply(Action.SPRINT) + " faster · wheel zooms"
                + (modifier == null ? "" : " · " + modifier.getName() + "+wheel changes speed")
                + " · " + keysOf.apply(Action.WALK) + " walks · click inspects · "
                + keysOf.apply(Action.DEV_OVERLAY) + " developer overlay · Keys… rebinds";
    }

    String encode() {
        return String.format(
                Locale.ROOT,
                "%s;sensitivity=%s;invertY=%b;speed=%s;keys=%s;actions=%s",
                VERSION,
                sensitivity,
                invertY,
                speed.name(),
                keys.entrySet().stream()
                        .map(e -> e.getKey().name() + ":" + e.getValue().name())
                        .collect(Collectors.joining(",")),
                java.util.Arrays.stream(Action.values()).map(Enum::name).collect(Collectors.joining(",")));
    }

    /**
     * Lenient decode: anything unreadable keeps its default. Null or another version: defaults.
     * Bindings: a non-empty stored list that yields no usable binding falls back to the defaults.
     * Actions the stored value did not know about (listed in {@code actions=}; for older values,
     * those with no stored key) get their default keys, unless a stored binding uses the key.
     */
    static WorldControls decode(String text) {
        if (text == null) return DEFAULTS;
        var parts = text.split(";", -1);
        if (parts.length == 0 || !parts[0].equals(VERSION)) return DEFAULTS;
        double sensitivity = DEFAULT_SENSITIVITY;
        boolean invertY = false;
        Speed speed = Speed.FLY;
        Map<KeyCode, Action> keys = DEFAULTS.keys;
        String storedKeys = null, storedActions = null;
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
                case "speed" -> speed = named(Speed.class, value, Speed.FLY);
                case "keys" -> storedKeys = value;
                case "actions" -> storedActions = value;
                default -> {}
            }
        }
        if (storedKeys != null) keys = merged(storedKeys, storedActions);
        return new WorldControls(sensitivity, invertY, speed, keys);
    }

    private static Map<KeyCode, Action> merged(String storedKeys, String storedActions) {
        var stored = keys(storedKeys);
        if (stored.isEmpty() && !storedKeys.isEmpty()) return DEFAULTS.keys;
        var known = java.util.EnumSet.noneOf(Action.class);
        if (storedActions != null) {
            for (var name : storedActions.split(",", -1)) {
                var action = named(Action.class, name, null);
                if (action != null) known.add(action);
            }
        } else {
            known.addAll(stored.values());
        }
        var result = new LinkedHashMap<>(stored);
        DEFAULTS.keys.forEach((code, action) -> {
            if (!known.contains(action) && !result.containsKey(code)) result.put(code, action);
        });
        return result;
    }

    private static Map<KeyCode, Action> keys(String value) {
        var keys = new LinkedHashMap<KeyCode, Action>();
        for (var entry : value.split(",", -1)) {
            int at = entry.indexOf(':');
            if (at < 0) continue;
            var code = named(KeyCode.class, entry.substring(0, at), null);
            var action = named(Action.class, entry.substring(at + 1), null);
            if (code != null && action != null) keys.put(code, action);
        }
        return keys;
    }

    private static <E extends Enum<E>> E named(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
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
