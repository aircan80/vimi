package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.WorldFrameLimits;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Strict bounded child-status decoder. Parsing never implies accepted pixels or source authority.
 */
final class WorldStreamStatus {
    sealed interface Message {
        WorldStreamState.Nonce nonce();
    }

    /**
     * {@code exteriorObjects} is the child's BASIC_EXTERIOR_V1 session profile; {@code
     * objectFormats}: the compressed object texture formats the child samples as blocks (issue
     * #374), one bit per {@link com.vibeminer.rendering.world.ObjectTextureFormats} code (BC1 =
     * 0x2, BC2 = 0x4, BC3 = 0x8); 0 when every texture is decoded to RGBA8.
     */
    record Session(WorldStreamState.Nonce nonce, boolean exteriorObjects, int objectFormats)
            implements Message {
        Session {
            if ((objectFormats & ~0xE) != 0) {
                throw new IllegalArgumentException("Object format mask");
            }
        }

        Session(WorldStreamState.Nonce nonce, boolean exteriorObjects) {
            this(nonce, exteriorObjects, 0);
        }

        Session(WorldStreamState.Nonce nonce) {
            this(nonce, false);
        }

        /** Whether the child uploads {@code format} as blocks. */
        boolean samples(int format) {
            return (objectFormats & (1 << format)) != 0;
        }
    }

    record Credit(WorldStreamState.Nonce nonce, WorldStreamState.Lease lease) implements Message {}

    record CameraApplied(WorldStreamState.Nonce nonce, long policy, int width, int height)
            implements Message {}

    /** Non-reliable wake-up hint that the frame mapping published {@code sequence}. */
    record FramePublished(WorldStreamState.Nonce nonce, long sequence) implements Message {}

    /** The child's heartbeat: its render loop still runs. Carries no state. */
    record Alive(WorldStreamState.Nonce nonce, long beat) implements Message {}

    /**
     * #381, native presentation only: non-reliable latest value, the child window's swap chain
     * presented a frame drawn for {@code camera} (a camera revision) over committed content
     * revision {@code content}. No mapping is involved.
     */
    record Presented(WorldStreamState.Nonce nonce, long camera, long content) implements Message {}

    enum InputKind {
        TILE,
        COMMIT
    }

    record InputConsumed(WorldStreamState.Nonce nonce, long transaction, InputKind kind)
            implements Message {}

    record Staged(
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            int outcome,
            int coverage,
            // -1 outside the exterior profile; else 0 none, 1 drawn, 2 failed (terrain kept).
            int objects)
            implements Message {
        Staged(
                WorldStreamState.Nonce nonce,
                long transaction,
                WorldStreamState.Lease lease,
                int outcome,
                int coverage) {
            this(nonce, transaction, lease, outcome, coverage, -1);
        }
    }

    record Committed(WorldStreamState.Nonce nonce, long transaction, long expected, long next)
            implements Message {}

    /** The one terminal reply to a late {@code OBJECTS} command. */
    enum LateOutcome {
        /** Uploaded and attached to the still active slot generation; drawn with its terrain. */
        ATTACHED,
        /** Read, validation or upload failed; the tile's terrain is untouched. */
        FAILED,
        /** The slot generation was no longer active (stale or retired); nothing was attached. */
        DROPPED,
        /**
         * The session's shared objects GPU budget had no room; nothing was attached and the parent
         * may offer the same generation again later. Terrain is untouched.
         */
        BUDGET,
        /**
         * The file named an asset the child neither held nor was sent (#380 stage 4); nothing was
         * attached and the parent may send the same generation once more, with the assets.
         */
        MISSING
    }

    /**
     * The reply to a late objects command, with what the child's asset store holds after it ({@link
     * WorldStreamAssetStore.Report}): the parent's mirror follows it.
     */
    record ObjectsDone(
            WorldStreamState.Nonce nonce,
            long transaction,
            WorldStreamState.Lease lease,
            LateOutcome outcome,
            WorldStreamAssetStore.Report assets)
            implements Message {
        ObjectsDone(
                WorldStreamState.Nonce nonce,
                long transaction,
                WorldStreamState.Lease lease,
                LateOutcome outcome) {
            this(
                    nonce,
                    transaction,
                    lease,
                    outcome,
                    new WorldStreamAssetStore.Report(0, false, 0, 0, 0, 0));
        }

        ObjectsDone {
            java.util.Objects.requireNonNull(lease);
            java.util.Objects.requireNonNull(outcome);
            java.util.Objects.requireNonNull(assets);
            if (outcome == LateOutcome.MISSING && assets.applied()) {
                throw new IllegalArgumentException("A MISSING file was applied");
            }
        }
    }

    /** The one terminal reply to {@code HORIZON}; none of them is ever session-fatal. */
    enum HorizonOutcome {
        /** Read and attached; the horizon is drawn from the next frame on. */
        DRAWN,
        /** The WDL was valid but declares no readable tile. */
        NONE,
        /** Read, validation or allocation failed; the session continues without a horizon. */
        FAILED
    }

    record HorizonDone(WorldStreamState.Nonce nonce, HorizonOutcome outcome) implements Message {
        HorizonDone {
            java.util.Objects.requireNonNull(outcome);
        }
    }

    /** The one terminal reply to {@code BODY_MODEL}; none of them is ever session-fatal. */
    enum BodyOutcome {
        /** Read and attached; the body is drawn whenever a camera carries its pose. */
        DRAWN,
        /** Read, validation or allocation failed; the session continues without a body. */
        FAILED
    }

    record BodyDone(WorldStreamState.Nonce nonce, BodyOutcome outcome) implements Message {
        BodyDone {
            java.util.Objects.requireNonNull(outcome);
        }
    }

    enum Rejection {
        STALE_TRANSACTION,
        STALE_LEASE,
        BAD_LEASE,
        BUSY,
        INVALID_TABLE,
        REVISION_MISMATCH,
        INVALID_PACKET,
        INVALID_COMMIT,
        PROTOCOL_ERROR,
        GPU_UNAVAILABLE,
        SESSION_CLOSED,
        STATUS_OVERFLOW,
        COUNTER_EXHAUSTED
    }

    record Rejected(WorldStreamState.Nonce nonce, long transaction, Rejection reason)
            implements Message {}

    record Canceled(WorldStreamState.Nonce nonce, long transaction, WorldStreamState.Lease lease)
            implements Message {}

    record Closed(WorldStreamState.Nonce nonce) implements Message {}

    private WorldStreamStatus() {}

    /** EOF before a line is normal; invalid input stops after at most256bytes, without resync. */
    static Message read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        byte[] line = new byte[256];
        for (int i = 0; i < line.length; i++) {
            int value = input.read();
            if (value == -1) {
                if (i == 0) {
                    return null;
                }
                throw new EOFException("Truncated World status");
            }
            line[i] = (byte) value;
            if (value == '\n') {
                return decode(line, i + 1);
            }
            if (value < 32 || value > 126) {
                throw new IOException("Non-ASCII World status");
            }
        }
        throw new IOException("World status exceeds line limit");
    }

    static Message decode(byte[] bytes, int length) throws IOException {
        Objects.requireNonNull(bytes);
        if (length < 1 || length > 256 || length > bytes.length || bytes[length - 1] != '\n') {
            throw new IOException("Invalid World status length");
        }
        for (int i = 0; i < length - 1; i++) {
            if (bytes[i] < 32 || bytes[i] > 126) {
                throw new IOException("Non-ASCII World status");
            }
        }
        String[] p = new String(bytes, 0, length - 1, StandardCharsets.US_ASCII).split(" ", -1);
        try {
            require(p.length >= 3 && p[0].equals("WS2") && p[1].matches("[0-9a-f]{32}"));
            var nonce =
                    new WorldStreamState.Nonce(
                            Long.parseUnsignedLong(p[1].substring(0, 16), 16),
                            Long.parseUnsignedLong(p[1].substring(16), 16));
            return switch (p[2]) {
                case "SESSION" -> {
                    // SESSION <profile> [<object format mask>]; without a mask, RGBA8 only.
                    require(p.length == 4 || p.length == 5);
                    require(p[3].equals("TERRAIN_BASIC_V1") || p[3].equals("TERRAIN_EXTERIOR_V1"));
                    int formats = p.length == 5 ? integer(p[4]) : 0;
                    require((formats & ~0xE) == 0);
                    yield new Session(nonce, p[3].equals("TERRAIN_EXTERIOR_V1"), formats);
                }
                case "CREDIT" -> {
                    arity(p, 6);
                    yield new Credit(nonce, lease(p, 3));
                }
                case "CAMERA_APPLIED" -> {
                    arity(p, 6);
                    long policy = positive(p[3]);
                    int width = integer(p[4]), height = integer(p[5]);
                    require(WorldFrameLimits.fits(width, height));
                    yield new CameraApplied(nonce, policy, width, height);
                }
                case "PRESENTED" -> {
                    arity(p, 5);
                    yield new Presented(nonce, positive(p[3]), number(p[4]));
                }
                case "ALIVE" -> {
                    arity(p, 4);
                    yield new Alive(nonce, positive(p[3]));
                }
                case "FRAME" -> {
                    arity(p, 4);
                    long sequence = positive(p[3]);
                    require(sequence <= WorldFrameFileV2.MAX_SEQUENCE);
                    yield new FramePublished(nonce, sequence);
                }
                case "INPUT_CONSUMED" -> {
                    arity(p, 5);
                    yield new InputConsumed(nonce, transaction(p[3]), InputKind.valueOf(p[4]));
                }
                case "STAGED" -> {
                    require(p.length == 9 || p.length == 10);
                    long tx = transaction(p[3]);
                    var lease = lease(p, 4);
                    int outcome = integer(p[7]), coverage = integer(p[8]);
                    require(
                            outcome == 1 && coverage >= 1 && coverage <= 256
                                    || outcome == 2 && coverage == 0);
                    int objects = p.length == 9 ? -1 : integer(p[9]);
                    require(objects >= -1 && objects <= 2 && (p.length == 9) == (objects == -1));
                    yield new Staged(nonce, tx, lease, outcome, coverage, objects);
                }
                case "OBJECTS_DONE" -> {
                    // ... <outcome> <epoch> <applied> <evicted> <entries> <bytes> <hash>
                    arity(p, 14);
                    require(p[9].equals("0") || p[9].equals("1"));
                    require(p[13].matches("[0-9a-f]{16}"));
                    yield new ObjectsDone(
                            nonce,
                            transaction(p[3]),
                            lease(p, 4),
                            LateOutcome.valueOf(p[7]),
                            new WorldStreamAssetStore.Report(
                                    number(p[8]),
                                    p[9].equals("1"),
                                    integer(p[10]),
                                    integer(p[11]),
                                    number(p[12]),
                                    Long.parseUnsignedLong(p[13], 16)));
                }
                case "HORIZON_DONE" -> {
                    arity(p, 5);
                    require(p[3].equals("1"));
                    yield new HorizonDone(nonce, HorizonOutcome.valueOf(p[4]));
                }
                case "BODY_DONE" -> {
                    arity(p, 5);
                    require(p[3].equals("1"));
                    yield new BodyDone(nonce, BodyOutcome.valueOf(p[4]));
                }
                case "COMMITTED" -> {
                    arity(p, 6);
                    long tx = transaction(p[3]), expected = number(p[4]), next = positive(p[5]);
                    require(expected < Long.MAX_VALUE && next == expected + 1);
                    yield new Committed(nonce, tx, expected, next);
                }
                case "REJECTED" -> {
                    arity(p, 5);
                    long tx = number(p[3]);
                    require(tx < Long.MAX_VALUE);
                    var reason = Rejection.valueOf(p[4]);
                    require(
                            tx != 0
                                    || reason == Rejection.PROTOCOL_ERROR
                                    || reason == Rejection.GPU_UNAVAILABLE
                                    || reason == Rejection.SESSION_CLOSED
                                    || reason == Rejection.STATUS_OVERFLOW
                                    || reason == Rejection.COUNTER_EXHAUSTED);
                    yield new Rejected(nonce, tx, reason);
                }
                case "CANCELED" -> {
                    arity(p, 7);
                    yield new Canceled(nonce, transaction(p[3]), lease(p, 4));
                }
                case "CLOSED" -> {
                    arity(p, 3);
                    yield new Closed(nonce);
                }
                default -> throw new IllegalArgumentException("Unknown status");
            };
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Invalid World status", error);
        }
    }

    private static WorldStreamState.Lease lease(String[] p, int at) {
        long serial = positive(p[at]);
        int slot = integer(p[at + 1]);
        long generation = positive(p[at + 2]);
        return new WorldStreamState.Lease(slot, generation, serial);
    }

    private static long number(String value) {
        require(value.matches("0|[1-9][0-9]{0,18}"));
        return Long.parseLong(value);
    }

    private static long positive(String value) {
        long number = number(value);
        require(number > 0);
        return number;
    }

    private static long transaction(String value) {
        long number = positive(value);
        require(number < Long.MAX_VALUE);
        return number;
    }

    private static int integer(String value) {
        return Math.toIntExact(number(value));
    }

    private static void arity(String[] p, int expected) {
        require(p.length == expected);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid status field");
        }
    }
}
