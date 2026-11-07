package com.vibeminer.rendering.bgfx;

/**
 * Fixed, bounded acknowledgement of optional native terrain atlas upload, before first READY.
 * {@code totalRects} counts drawable rectangles: every scene rectangle except those an uploaded
 * atlas marks as having nothing to draw (no texture layers).
 */
public record TerrainTextureStatus(
        State state,
        int activeAtlases,
        int sceneTiles,
        int coveredRects,
        int totalRects,
        Code code) {
    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public enum Code {
        NONE,
        INPUT_UNAVAILABLE,
        CHECKSUM,
        BINDING,
        MAPPING,
        UPLOAD,
        CAPABILITY
    }

    public TerrainTextureStatus {
        java.util.Objects.requireNonNull(state);
        java.util.Objects.requireNonNull(code);
        if (sceneTiles < 1
                || sceneTiles > 9
                || activeAtlases < 0
                || activeAtlases > sceneTiles
                || totalRects > sceneTiles * 256
                || totalRects < sceneTiles * 256 - activeAtlases * 255
                || coveredRects < activeAtlases
                || coveredRects > activeAtlases * 256
                || coveredRects > totalRects
                || state != state(activeAtlases, sceneTiles, coveredRects, totalRects)
                || state == State.READY && code != Code.NONE) {
            throw new IllegalArgumentException("Invalid terrain texture acknowledgement");
        }
    }

    public static TerrainTextureStatus of(int active, int tiles, int covered, Code code) {
        return of(active, tiles, covered, 0, code);
    }

    /** {@code empty}: rectangles of uploaded atlases that have nothing to draw. */
    public static TerrainTextureStatus of(
            int active, int tiles, int covered, int empty, Code code) {
        int total = tiles * 256 - empty;
        return new TerrainTextureStatus(
                state(active, tiles, covered, total), active, tiles, covered, total, code);
    }

    private static State state(int active, int tiles, int covered, int total) {
        return active == 0
                ? State.UNAVAILABLE
                : active == tiles && covered == total ? State.READY : State.PARTIAL;
    }

    public String protocolLine() {
        return "VIMI_GPU TEXTURES 1 "
                + state
                + " "
                + activeAtlases
                + " "
                + sceneTiles
                + " "
                + coveredRects
                + " "
                + totalRects
                + " "
                + code;
    }

    public static TerrainTextureStatus parse(String line) {
        if (line == null || line.length() > 160) {
            throw new IllegalArgumentException("Invalid texture status length");
        }
        var parts = line.split(" ", -1);
        if (parts.length != 9
                || !parts[0].equals("VIMI_GPU")
                || !parts[1].equals("TEXTURES")
                || !parts[2].equals("1")) {
            throw new IllegalArgumentException("Invalid texture status framing");
        }
        return new TerrainTextureStatus(
                State.valueOf(parts[3]),
                number(parts[4]),
                number(parts[5]),
                number(parts[6]),
                number(parts[7]),
                Code.valueOf(parts[8]));
    }

    private static int number(String text) {
        if (!text.matches("0|[1-9][0-9]{0,3}")) {
            throw new IllegalArgumentException("Invalid texture status count");
        }
        return Integer.parseInt(text);
    }
}
