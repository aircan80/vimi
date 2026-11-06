package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded v2 command codec only. Does not open packet files or execute commands. */
final class WorldStreamControl {
    static final int MAX_LINE_BYTES = 256;

    sealed interface Command
            permits Camera, Offer, Commit, Cancel, CancelUnused, LateObjects, Close {
        WorldStreamState.Nonce nonce();
    }

    record Camera(
            WorldStreamState.Nonce nonce,
            long revision,
            long policy,
            int width,
            int height,
            TerrainCamera camera,
            WorldStreamTransport.Sky sky)
            implements Command {
        Camera {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(camera);
            positive(revision);
            positive(policy);
            if (width < 1 || width > 800 || height < 1 || height > 500) {
                throw new IllegalArgumentException("Invalid camera dimensions");
            }
        }

        Camera(
                WorldStreamState.Nonce nonce,
                long revision,
                long policy,
                int width,
                int height,
                TerrainCamera camera) {
            this(nonce, revision, policy, width, height, camera, null);
        }
    }

    record Offer(
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            int length,
            String sha256)
            implements Command {
        Offer {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            WorldStreamControl.transaction(transaction);
            digest(sha256);
            if (length < 4096 || length > WorldStreamEnvelope.MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Invalid packet length");
            }
        }
    }

    record Commit(WorldStreamState.Nonce nonce, long transaction, String sha256)
            implements Command {
        Commit {
            Objects.requireNonNull(nonce);
            WorldStreamControl.transaction(transaction);
            digest(sha256);
        }
    }

    record Cancel(WorldStreamState.Nonce nonce, long transaction, int slot, long generation)
            implements Command {
        Cancel {
            Objects.requireNonNull(nonce);
            WorldStreamControl.transaction(transaction);
            new WorldStreamState.Lease(slot, generation, 1);
        }
    }

    record CancelUnused(
            WorldStreamState.Nonce nonce, long transaction, WorldStreamState.Lease lease)
            implements Command {
        CancelUnused {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            WorldStreamControl.transaction(transaction);
        }
    }

    /**
     * Late BASIC_EXTERIOR objects file {@code tile-<txn>.wso} for the resident that tile
     * transaction staged under this exact lease. The child attaches it only while that slot
     * generation is still active, and answers with exactly one {@code OBJECTS_DONE}.
     */
    record LateObjects(
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            int length,
            String sha256)
            implements Command {
        LateObjects {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            WorldStreamControl.transaction(transaction);
            digest(sha256);
            if (length < WorldStreamObjectsFile.HEADER_BYTES
                    || length > WorldStreamObjectsFile.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid objects length");
            }
        }
    }

    record Close(WorldStreamState.Nonce nonce) implements Command {
        Close {
            Objects.requireNonNull(nonce);
        }
    }

    private WorldStreamControl() {}

    private static String sky(WorldStreamTransport.Sky sky) {
        if (sky == null) {
            return "";
        }
        float[] h = sky.horizon(), t = sky.top();
        return " SKY "
                + h[0]
                + " "
                + h[1]
                + " "
                + h[2]
                + " "
                + t[0]
                + " "
                + t[1]
                + " "
                + t[2]
                + " "
                + sky.fogWeight()
                + " "
                + sky.recordedStart()
                + " "
                + sky.recordedEnd();
    }

    private static WorldStreamTransport.Sky sky(String[] parts) {
        float[] v = new float[9];
        for (int i = 0; i < 9; i++) {
            v[i] = (float) decimal(parts[13 + i]);
        }
        return new WorldStreamTransport.Sky(
                new float[] {v[0], v[1], v[2]}, new float[] {v[3], v[4], v[5]}, v[6], v[7], v[8]);
    }

    static byte[] encode(Command command) {
        Objects.requireNonNull(command);
        var nonce = command.nonce();
        String prefix =
                "WS2 "
                        + HexFormat.of().toHexDigits(nonce.high())
                        + HexFormat.of().toHexDigits(nonce.low())
                        + " ";
        String tail =
                switch (command) {
                    case Camera c ->
                            "CAM "
                                    + c.revision()
                                    + " "
                                    + c.policy()
                                    + " "
                                    + c.width()
                                    + " "
                                    + c.height()
                                    + " "
                                    + c.camera().x()
                                    + " "
                                    + c.camera().y()
                                    + " "
                                    + c.camera().z()
                                    + " "
                                    + c.camera().yaw()
                                    + " "
                                    + c.camera().pitch()
                                    + sky(c.sky());
                    case Offer c ->
                            "OFFER "
                                    + c.transaction()
                                    + " "
                                    + c.lease().serial()
                                    + " "
                                    + c.lease().slot()
                                    + " "
                                    + c.lease().generation()
                                    + " tile-"
                                    + c.transaction()
                                    + ".wst "
                                    + c.length()
                                    + " "
                                    + c.sha256();
                    case Commit c ->
                            "COMMIT "
                                    + c.transaction()
                                    + " commit-"
                                    + c.transaction()
                                    + ".wsc "
                                    + c.sha256();
                    case Cancel c ->
                            "CANCEL " + c.transaction() + " " + c.slot() + " " + c.generation();
                    case CancelUnused c ->
                            "CANCEL_UNUSED "
                                    + c.transaction()
                                    + " "
                                    + c.lease().serial()
                                    + " "
                                    + c.lease().slot()
                                    + " "
                                    + c.lease().generation();
                    case LateObjects c ->
                            "OBJECTS "
                                    + c.transaction()
                                    + " "
                                    + c.lease().serial()
                                    + " "
                                    + c.lease().slot()
                                    + " "
                                    + c.lease().generation()
                                    + " "
                                    + WorldStreamObjectsFile.basename(c.transaction())
                                    + " "
                                    + c.length()
                                    + " "
                                    + c.sha256();
                    case Close ignored -> "CLOSE";
                };
        byte[] line = (prefix + tail + "\n").getBytes(StandardCharsets.US_ASCII);
        if (line.length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("Command exceeds line limit");
        }
        return line;
    }

    /** Reads at most256 bytes, never scans for LF after an invalid/oversized command. */
    static Command read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        byte[] line = new byte[MAX_LINE_BYTES];
        for (int i = 0; i < line.length; i++) {
            int value = input.read();
            if (value == -1) {
                if (i == 0) {
                    return null;
                }
                throw new EOFException("Truncated World command");
            }
            line[i] = (byte) value;
            if (value == '\n') {
                return decode(line, i + 1);
            }
            if (value < 32 || value > 126) {
                throw new IOException("Non-ASCII World command");
            }
        }
        throw new IOException("World command exceeds line limit");
    }

    static Command decode(byte[] line, int length) throws IOException {
        Objects.requireNonNull(line);
        if (length < 1
                || length > MAX_LINE_BYTES
                || length > line.length
                || line[length - 1] != '\n') {
            throw new IOException("Invalid World command length");
        }
        for (int i = 0; i < length - 1; i++) {
            if (line[i] < 32 || line[i] > 126) {
                throw new IOException("Non-ASCII World command");
            }
        }
        String text = new String(line, 0, length - 1, StandardCharsets.US_ASCII);
        String[] parts = text.split(" ", -1);
        try {
            if (parts.length < 3 || !parts[0].equals("WS2") || !parts[1].matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Invalid command prefix");
            }
            var nonce =
                    new WorldStreamState.Nonce(
                            Long.parseUnsignedLong(parts[1].substring(0, 16), 16),
                            Long.parseUnsignedLong(parts[1].substring(16), 16));
            Command result =
                    switch (parts[2]) {
                        case "CAM" -> {
                            // An optional sky tail: SKY h0 h1 h2 t0 t1 t2 weight start end.
                            if (parts.length != 12) {
                                arity(parts, 22);
                                if (!parts[12].equals("SKY")) {
                                    throw new IllegalArgumentException("Invalid camera sky");
                                }
                            }
                            yield new Camera(
                                    nonce,
                                    number(parts[3]),
                                    number(parts[4]),
                                    integer(parts[5]),
                                    integer(parts[6]),
                                    new TerrainCamera(
                                            decimal(parts[7]),
                                            decimal(parts[8]),
                                            decimal(parts[9]),
                                            decimal(parts[10]),
                                            decimal(parts[11])),
                                    parts.length == 12 ? null : sky(parts));
                        }
                        case "OFFER" -> {
                            arity(parts, 10);
                            long tx = number(parts[3]);
                            basename(parts[7], "tile-" + tx + ".wst");
                            yield new Offer(
                                    nonce,
                                    tx,
                                    new WorldStreamState.Lease(
                                            integer(parts[5]), number(parts[6]), number(parts[4])),
                                    integer(parts[8]),
                                    parts[9]);
                        }
                        case "COMMIT" -> {
                            arity(parts, 6);
                            long tx = number(parts[3]);
                            basename(parts[4], "commit-" + tx + ".wsc");
                            yield new Commit(nonce, tx, parts[5]);
                        }
                        case "CANCEL" -> {
                            arity(parts, 6);
                            yield new Cancel(
                                    nonce, number(parts[3]), integer(parts[4]), number(parts[5]));
                        }
                        case "CANCEL_UNUSED" -> {
                            arity(parts, 7);
                            yield new CancelUnused(
                                    nonce,
                                    number(parts[3]),
                                    new WorldStreamState.Lease(
                                            integer(parts[5]), number(parts[6]), number(parts[4])));
                        }
                        case "OBJECTS" -> {
                            arity(parts, 10);
                            long tx = number(parts[3]);
                            basename(parts[7], WorldStreamObjectsFile.basename(tx));
                            yield new LateObjects(
                                    nonce,
                                    tx,
                                    new WorldStreamState.Lease(
                                            integer(parts[5]), number(parts[6]), number(parts[4])),
                                    integer(parts[8]),
                                    parts[9]);
                        }
                        case "CLOSE" -> {
                            arity(parts, 3);
                            yield new Close(nonce);
                        }
                        default -> throw new IllegalArgumentException("Unknown World command");
                    };
            byte[] canonical = encode(result);
            if (canonical.length != length) {
                throw new IllegalArgumentException("Noncanonical World command");
            }
            for (int i = 0; i < length; i++) {
                if (canonical[i] != line[i]) {
                    throw new IllegalArgumentException("Noncanonical World command");
                }
            }
            return result;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid World command", failure);
        }
    }

    private static long number(String value) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Noncanonical counter");
        }
        return Long.parseLong(value);
    }

    private static int integer(String value) {
        return Math.toIntExact(number(value));
    }

    private static double decimal(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result) || !Double.toString(result).equals(value)) {
            throw new IllegalArgumentException("Noncanonical camera value");
        }
        return result;
    }

    private static void arity(String[] parts, int count) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Invalid command arity");
        }
    }

    private static void basename(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Invalid packet basename");
        }
    }

    private static void positive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid positive counter");
        }
    }

    private static void transaction(long value) {
        positive(value);
        if (value == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Exhausted transaction counter");
        }
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid packet digest");
        }
    }
}
