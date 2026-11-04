package com.vibeminer.rendering.bgfx;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded dedicated controls only; no stream worker, native operation or request history. */
final class GlobalWmoControl {
    static final int MAX_LINE_BYTES = 256;

    sealed interface Command permits Camera, Close {
        String nonce();
    }

    record Camera(String nonce, GlobalWmoFrameRequest request) implements Command {
        Camera {
            WorldGlobalLayout.hex(nonce, 32);
            Objects.requireNonNull(request);
        }
    }

    record Close(String nonce) implements Command {
        Close {
            WorldGlobalLayout.hex(nonce, 32);
        }
    }

    private GlobalWmoControl() {}

    static Command parse(String line, String expectedNonce, long previousRevision) {
        WorldGlobalLayout.hex(expectedNonce, 32);
        if (previousRevision < 0
                || line == null
                || line.isEmpty()
                || line.length() >= MAX_LINE_BYTES) {
            throw new IllegalArgumentException("Invalid global command bounds");
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) < 0x20 || line.charAt(i) > 0x7e) {
                throw new IllegalArgumentException("Invalid global command character");
            }
        }
        var fields = line.split(" ", -1);
        if (fields.length < 2 || !fields[1].equals(expectedNonce)) {
            throw new IllegalArgumentException("Global command identity differs");
        }
        if (fields[0].equals("GLOBAL_CLOSE") && fields.length == 2) {
            return new Close(fields[1]);
        }
        if (!fields[0].equals("GLOBAL_CAM") || fields.length != 10) {
            throw new IllegalArgumentException("Invalid global command");
        }
        long revision = GlobalWmoLaunch.positive(fields[2], Long.MAX_VALUE);
        if (revision <= previousRevision) {
            throw new IllegalArgumentException("Stale global camera");
        }
        return new Camera(
                fields[1],
                new GlobalWmoFrameRequest(
                        revision,
                        new GlobalWmoCamera(
                                number(fields[5]),
                                number(fields[6]),
                                number(fields[7]),
                                number(fields[8]),
                                number(fields[9])),
                        (int) GlobalWmoLaunch.positive(fields[3], 800),
                        (int) GlobalWmoLaunch.positive(fields[4], 500)));
    }

    static byte[] encode(Command command) {
        Objects.requireNonNull(command);
        String line;
        if (command instanceof Camera c) {
            var request = c.request();
            var camera = request.camera();
            line =
                    "GLOBAL_CAM "
                            + c.nonce()
                            + " "
                            + request.revision()
                            + " "
                            + request.width()
                            + " "
                            + request.height()
                            + " "
                            + camera.x()
                            + " "
                            + camera.y()
                            + " "
                            + camera.z()
                            + " "
                            + camera.yaw()
                            + " "
                            + camera.pitch();
        } else {
            line = "GLOBAL_CLOSE " + command.nonce();
        }
        var bytes = (line + "\n").getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("Global command too long");
        }
        return bytes;
    }

    private static double number(String text) {
        if (text.isEmpty() || text.length() > 24) {
            throw new IllegalArgumentException("Invalid global camera number");
        }
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || !Double.toString(value).equals(text)) {
            throw new IllegalArgumentException("Noncanonical global camera number");
        }
        return value;
    }

    /** Returns null only at a clean line boundary; exact256B including LF is the upper bound. */
    static String readLine(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        byte[] bytes = new byte[MAX_LINE_BYTES - 1];
        int count = 0;
        for (; ; ) {
            int value = input.read();
            if (value == -1) {
                if (count == 0) {
                    return null;
                }
                throw new EOFException("Partial global command");
            }
            if (value == '\n') {
                return new String(bytes, 0, count, StandardCharsets.US_ASCII);
            }
            if (value < 0x20 || value > 0x7e || count == bytes.length) {
                throw new IOException("Global command line bounds");
            }
            bytes[count++] = (byte) value;
        }
    }
}
