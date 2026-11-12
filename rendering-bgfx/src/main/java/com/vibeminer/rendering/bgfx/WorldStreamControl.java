package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.WorldFrameLimits;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded v2 command codec only. Does not open packet files or execute commands. */
final class WorldStreamControl {
    /**
     * The longest camera line with its sky, light and water tails is 333 bytes with its newline
     * (#171, #177); a body tail ({@link #body}) adds at most 59, so 392.
     */
    static final int MAX_LINE_BYTES = 400;

    sealed interface Command
            permits Camera,
                    Offer,
                    Commit,
                    Cancel,
                    CancelUnused,
                    LateObjects,
                    Horizon,
                    BodyModel,
                    Close {
        WorldStreamState.Nonce nonce();
    }

    record Camera(
            WorldStreamState.Nonce nonce,
            long revision,
            long policy,
            int width,
            int height,
            TerrainCamera camera,
            WorldStreamTransport.Sky sky,
            WorldStreamTransport.Body body)
            implements Command {
        Camera {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(camera);
            positive(revision);
            positive(policy);
            if (!WorldFrameLimits.fits(width, height)) {
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
            this(nonce, revision, policy, width, height, camera, null, null);
        }

        Camera(
                WorldStreamState.Nonce nonce,
                long revision,
                long policy,
                int width,
                int height,
                TerrainCamera camera,
                WorldStreamTransport.Sky sky) {
            this(nonce, revision, policy, width, height, camera, sky, null);
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
     * generation is still active, and answers with exactly one {@code OBJECTS_DONE}. With {@code
     * replace} ({@code OBJECTS_REPLACE}) the generation already holds late objects and the new set
     * takes their place only once it is fully built; until then, and whenever it does not attach,
     * the held set stays drawn.
     */
    record LateObjects(
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            int length,
            String sha256,
            boolean replace)
            implements Command {
        LateObjects(
                WorldStreamState.Nonce nonce,
                long transaction,
                WorldStreamState.Lease lease,
                int length,
                String sha256) {
            this(nonce, transaction, lease, length, sha256, false);
        }

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

    /**
     * The map's WDL bytes for the horizon (#372), file {@code horizon-1.wsh}. At most one per
     * session, outside the tile transaction sequence; the child answers with exactly one {@code
     * HORIZON_DONE}, and any failure only means no horizon.
     */
    record Horizon(WorldStreamState.Nonce nonce, int length, String sha256) implements Command {
        static final String BASENAME = "horizon-1.wsh";

        Horizon {
            Objects.requireNonNull(nonce);
            digest(sha256);
            if (length < 12 || length > com.vibeminer.formats.world.WorldLowRes.MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("Invalid horizon length");
            }
        }
    }

    /**
     * The walking body's model and animations, file {@code body-1.wsb} ({@link
     * WorldStreamBodyFile}). One at a time, outside the tile transaction sequence: the child
     * answers each with exactly one {@code BODY_DONE}, a later one replaces the drawn body (walking
     * and flying bodies), and any failure only means no body.
     */
    record BodyModel(WorldStreamState.Nonce nonce, int length, String sha256) implements Command {
        static final String BASENAME = "body-1.wsb";

        BodyModel {
            Objects.requireNonNull(nonce);
            digest(sha256);
            if (length < WorldStreamBodyFile.MIN_BYTES || length > WorldStreamBodyFile.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid body length");
            }
        }
    }

    record Close(WorldStreamState.Nonce nonce) implements Command {
        Close {
            Objects.requireNonNull(nonce);
        }
    }

    private WorldStreamControl() {}

    /**
     * The optional sky tail, bounded so a camera line always fits: {@code SKY} then the six colours
     * as one 12-digit hex token (horizon then top, red-green-blue), the fog weight in thousandths
     * (0..1000) and the recorded start and end in whole yards. Sky values are already quantized to
     * exactly these steps. A recorded light (#177 step 2c) adds {@code LIGHT}, the direct and
     * ambient colours as one 12-digit hex token, and the light weight in thousandths. A recorded
     * water light (#171) then adds {@code WATER}, the four water colours as one 24-digit hex token,
     * the four opacities in thousandths and the water weight in thousandths.
     */
    private static String sky(WorldStreamTransport.Sky sky) {
        if (sky == null) {
            return "";
        }
        String light = "";
        if (sky.lightWeight() > 0) {
            var lights = new StringBuilder(12);
            for (float[] rgb : new float[][] {sky.direct(), sky.ambient()}) {
                for (float channel : rgb) {
                    lights.append(HexFormat.of().toHexDigits((byte) (int) channel));
                }
            }
            light = " LIGHT " + lights + " " + Math.round(sky.lightWeight() * 1000);
        }
        String water = "";
        if (sky.waterWeight() > 0) {
            var hex = new StringBuilder(24);
            for (float channel : sky.water()) {
                hex.append(HexFormat.of().toHexDigits((byte) (int) channel));
            }
            var tail = new StringBuilder(" WATER ").append(hex);
            for (float alpha : sky.waterAlphas()) {
                tail.append(' ').append(Math.round(alpha * 1000));
            }
            water = tail.append(' ').append(Math.round(sky.waterWeight() * 1000)).toString();
        }
        var colours = new StringBuilder(12);
        for (float[] rgb : new float[][] {sky.horizon(), sky.top()}) {
            for (float channel : rgb) {
                colours.append(HexFormat.of().toHexDigits((byte) (int) channel));
            }
        }
        return " SKY "
                + colours
                + " "
                + Math.round(sky.fogWeight() * 1000)
                + " "
                + (long) sky.recordedStart()
                + " "
                + (long) sky.recordedEnd()
                + light
                + water;
    }

    /**
     * The optional body tail, after any sky: {@code BODY}, the position in thousandths of a yard,
     * the yaw in ten-thousandths of a radian (0 up to 2 pi), the sequence, its clock in
     * milliseconds and 1 when hidden (first person), else 0. Body values are already quantized to
     * exactly these steps.
     */
    private static String body(WorldStreamTransport.Body body) {
        if (body == null) {
            return "";
        }
        return " BODY "
                + Math.round(body.x() * 1000)
                + " "
                + Math.round(body.y() * 1000)
                + " "
                + Math.round(body.z() * 1000)
                + " "
                + Math.round(body.yaw() * 10_000)
                + " "
                + body.sequence()
                + " "
                + body.millis()
                + " "
                + (body.hidden() ? 1 : 0);
    }

    /** Fields of the body tail, {@code BODY} included. */
    static final int BODY_FIELDS = 8;

    private static WorldStreamTransport.Body body(String[] parts, int at) {
        if (!parts[at].equals("BODY") || !parts[at + 7].matches("[01]")) {
            throw new IllegalArgumentException("Invalid camera body");
        }
        return new WorldStreamTransport.Body(
                signed(parts[at + 1]) / 1000.0,
                signed(parts[at + 2]) / 1000.0,
                signed(parts[at + 3]) / 1000.0,
                number(parts[at + 4]) / 10_000.0,
                integer(parts[at + 5]),
                number(parts[at + 6]),
                parts[at + 7].equals("1"));
    }

    /** A canonical signed decimal integer. */
    private static long signed(String value) {
        if (!value.matches("0|-?[1-9][0-9]{0,11}")) {
            throw new IllegalArgumentException("Noncanonical body value");
        }
        return Long.parseLong(value);
    }

    private static WorldStreamTransport.Sky sky(String[] parts) {
        if (!parts[13].matches("[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid sky colours");
        }
        float[] channels = new float[6];
        for (int i = 0; i < 6; i++) {
            channels[i] = Integer.parseInt(parts[13].substring(2 * i, 2 * i + 2), 16);
        }
        long weight = bounded(parts[14], 1000);
        long start = bounded(parts[15], WorldStreamTransport.Sky.MAX_YARDS);
        long end = bounded(parts[16], WorldStreamTransport.Sky.MAX_YARDS);
        float[] light = new float[6];
        long lightWeight = 0;
        int at = 17;
        if (parts.length > at && parts[at].equals("LIGHT")) {
            if (parts.length < at + 3) {
                throw new IllegalArgumentException("Invalid camera light");
            }
            if (!parts[18].matches("[0-9a-f]{12}")) {
                throw new IllegalArgumentException("Invalid camera light");
            }
            for (int i = 0; i < 6; i++) {
                light[i] = Integer.parseInt(parts[18].substring(2 * i, 2 * i + 2), 16);
            }
            lightWeight = bounded(parts[19], 1000);
            if (lightWeight == 0) {
                throw new IllegalArgumentException("Invalid camera light");
            }
            at += 3;
        }
        float[] water = new float[12], alphas = new float[4];
        long waterWeight = 0;
        if (parts.length > at) {
            if (parts.length != at + 7
                    || !parts[at].equals("WATER")
                    || !parts[at + 1].matches("[0-9a-f]{24}")) {
                throw new IllegalArgumentException("Invalid camera water light");
            }
            for (int i = 0; i < 12; i++) {
                water[i] = Integer.parseInt(parts[at + 1].substring(2 * i, 2 * i + 2), 16);
            }
            for (int i = 0; i < 4; i++) {
                alphas[i] = bounded(parts[at + 2 + i], 1000) / 1000f;
            }
            waterWeight = bounded(parts[at + 6], 1000);
            if (waterWeight == 0) {
                throw new IllegalArgumentException("Invalid camera water light");
            }
        }
        return new WorldStreamTransport.Sky(
                new float[] {channels[0], channels[1], channels[2]},
                new float[] {channels[3], channels[4], channels[5]},
                weight / 1000.0,
                start,
                end,
                new float[] {light[0], light[1], light[2]},
                new float[] {light[3], light[4], light[5]},
                lightWeight / 1000.0,
                water,
                alphas,
                waterWeight / 1000.0);
    }

    /** A canonical decimal integer in 0..max. */
    private static long bounded(String text, long max) {
        if (!text.matches("0|[1-9][0-9]{0,6}")) {
            throw new IllegalArgumentException("Invalid sky number");
        }
        long value = Long.parseLong(text);
        if (value > max) {
            throw new IllegalArgumentException("Sky number out of range");
        }
        return value;
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
                                    + sky(c.sky())
                                    + body(c.body());
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
                            (c.replace() ? "OBJECTS_REPLACE " : "OBJECTS ")
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
                    case Horizon c ->
                            "HORIZON 1 " + Horizon.BASENAME + " " + c.length() + " " + c.sha256();
                    case BodyModel c ->
                            "BODY_MODEL 1 "
                                    + BodyModel.BASENAME
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

    /**
     * Reads at most {@link #MAX_LINE_BYTES} bytes, never scans for LF after an invalid/oversized
     * command.
     */
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
                            // An optional body tail last: BODY and its seven values.
                            WorldStreamTransport.Body body = null;
                            if (parts.length >= 12 + BODY_FIELDS
                                    && parts[parts.length - BODY_FIELDS].equals("BODY")) {
                                body = body(parts, parts.length - BODY_FIELDS);
                                parts = java.util.Arrays.copyOf(parts, parts.length - BODY_FIELDS);
                            }
                            // An optional sky tail: SKY colours weight start end.
                            if (parts.length != 12) {
                                // SKY, then an optional LIGHT (3 more) and WATER (7 more).
                                if (parts.length != 20
                                        && parts.length != 24
                                        && parts.length != 27) {
                                    arity(parts, 17);
                                }
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
                                    parts.length == 12 ? null : sky(parts),
                                    body);
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
                        case "OBJECTS", "OBJECTS_REPLACE" -> {
                            arity(parts, 10);
                            long tx = number(parts[3]);
                            basename(parts[7], WorldStreamObjectsFile.basename(tx));
                            yield new LateObjects(
                                    nonce,
                                    tx,
                                    new WorldStreamState.Lease(
                                            integer(parts[5]), number(parts[6]), number(parts[4])),
                                    integer(parts[8]),
                                    parts[9],
                                    parts[2].equals("OBJECTS_REPLACE"));
                        }
                        case "HORIZON" -> {
                            arity(parts, 7);
                            basename(parts[3], "1");
                            basename(parts[4], Horizon.BASENAME);
                            yield new Horizon(nonce, integer(parts[5]), parts[6]);
                        }
                        case "BODY_MODEL" -> {
                            arity(parts, 7);
                            basename(parts[3], "1");
                            basename(parts[4], BodyModel.BASENAME);
                            yield new BodyModel(nonce, integer(parts[5]), parts[6]);
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
