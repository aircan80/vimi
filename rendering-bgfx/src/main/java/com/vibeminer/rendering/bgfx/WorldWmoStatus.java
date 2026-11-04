package com.vibeminer.rendering.bgfx;

/**
 * Fixed cold-child presentation state, never source authentication or a GUI-frame acknowledgement.
 */
public record WorldWmoStatus(State state, Code code, int images, int batches) {
    private static final String PREFIX = "VIMI_GPU WORLD_WMO ";

    public enum State {
        LOADING,
        INITIALIZED,
        READY,
        UNAVAILABLE
    }

    public enum Code {
        NONE,
        LOAD
    }

    public WorldWmoStatus {
        if (state == null
                || code == null
                || images < 0
                || images > 6
                || batches < 0
                || batches > 6
                || state == State.UNAVAILABLE && (code != Code.LOAD || images != 0 || batches != 0)
                || state != State.UNAVAILABLE && code != Code.NONE
                || state == State.LOADING && batches != 0
                || (state == State.INITIALIZED || state == State.READY)
                        && (images == 0 || batches == 0)) {
            throw new IllegalArgumentException("Invalid cold WMO status");
        }
    }

    public String protocolLine() {
        return PREFIX + state + " " + code + " " + images + " " + batches;
    }

    public static WorldWmoStatus parse(String line) {
        if (!line.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = line.split(" ", -1);
        if (parts.length != 6 || !parts[4].matches("[0-6]") || !parts[5].matches("[0-6]")) {
            throw new IllegalArgumentException("Invalid cold WMO status line");
        }
        return new WorldWmoStatus(
                State.valueOf(parts[2]),
                Code.valueOf(parts[3]),
                Integer.parseInt(parts[4]),
                Integer.parseInt(parts[5]));
    }
}
