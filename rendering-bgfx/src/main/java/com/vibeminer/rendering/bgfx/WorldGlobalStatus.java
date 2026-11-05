package com.vibeminer.rendering.bgfx;

import static com.vibeminer.rendering.bgfx.WorldGlobalLayout.require;

import com.vibeminer.rendering.world.GlobalInteriorLimits;

/** Bounded immutable-global status syntax, never source authority or a parent pixel receipt. */
public sealed interface WorldGlobalStatus {
    /**
     * Exclusive line bounds. READY is 187 fixed characters plus at most 28 count digits (six each
     * for vertices and both triangle counts, three for groups and images, four for batches).
     */
    int MAX_LINE_BYTES = 256, READY_BYTES = 216, CONSUMED_BYTES = 120, CLOSED_BYTES = 177;

    record Identity(String nonce, String scope, String fileSha256) {
        public Identity {
            WorldGlobalLayout.hex(nonce, 32);
            WorldGlobalLayout.hex(scope, 64);
            WorldGlobalLayout.hex(fileSha256, 64);
        }
    }

    /** Emitted only after the first actual draw/readback successfully writes the owned mapping. */
    record Ready(
            Identity identity,
            int vertices,
            int rawTriangles,
            int drawnTriangles,
            int groups,
            int batches,
            int images)
            implements WorldGlobalStatus {
        public Ready {
            require(
                    identity != null
                            && rawTriangles >= 1
                            && rawTriangles <= GlobalInteriorLimits.TRIANGLES);
            new WorldGlobalLayout.Counts(
                    images,
                    groups,
                    batches,
                    images,
                    vertices,
                    rawTriangles * 3,
                    drawnTriangles,
                    images);
        }

        public boolean matches(Identity expected, WorldGlobalLayout.Counts counts) {
            return identity.equals(expected)
                    && vertices == counts.vertices()
                    && rawTriangles == counts.rawTriangles()
                    && drawnTriangles == counts.drawnTriangles()
                    && groups == counts.groups()
                    && batches == counts.batches()
                    && images == counts.images();
        }

        @Override
        public String protocolLine() {
            return "GLOBAL_READY "
                    + identity.nonce()
                    + " "
                    + identity.scope()
                    + " "
                    + identity.fileSha256()
                    + " 1 "
                    + vertices
                    + " "
                    + rawTriangles
                    + " "
                    + drawnTriangles
                    + " "
                    + groups
                    + " "
                    + batches
                    + " "
                    + images
                    + " 511";
        }
    }

    /** File reads are closed; this is neither GPU initialization nor parent image acceptance. */
    record InputConsumed(String nonce, String fileSha256) implements WorldGlobalStatus {
        public InputConsumed {
            WorldGlobalLayout.hex(nonce, 32);
            WorldGlobalLayout.hex(fileSha256, 64);
        }

        public boolean matches(Identity expected) {
            return nonce.equals(expected.nonce()) && fileSha256.equals(expected.fileSha256());
        }

        @Override
        public String protocolLine() {
            return "GLOBAL_INPUT_CONSUMED " + nonce + " " + fileSha256;
        }
    }

    /** All child owners retired; parent still owes actual process/worker/mapping retirement. */
    record Closed(Identity identity) implements WorldGlobalStatus {
        public Closed {
            require(identity != null);
        }

        @Override
        public String protocolLine() {
            return "GLOBAL_CLOSED "
                    + identity.nonce()
                    + " "
                    + identity.scope()
                    + " "
                    + identity.fileSha256();
        }
    }

    enum Code {
        INPUT,
        CAPABILITY
    }

    /** Only explicitly classified input/capability negatives; arbitrary failures remain fatal. */
    record Unavailable(Identity identity, Code code) implements WorldGlobalStatus {
        public Unavailable {
            require(identity != null && code != null);
        }

        @Override
        public String protocolLine() {
            return "GLOBAL_UNAVAILABLE "
                    + identity.nonce()
                    + " "
                    + identity.scope()
                    + " "
                    + identity.fileSha256()
                    + " "
                    + code;
        }
    }

    String protocolLine();

    /**
     * The bounded line reader strips LF. No embedded LF/control/Unicode or unknown status allowed.
     */
    static WorldGlobalStatus parse(String line) {
        require(line != null && line.length() > 0 && line.length() < MAX_LINE_BYTES);
        for (int i = 0; i < line.length(); i++) {
            require(line.charAt(i) >= 0x20 && line.charAt(i) <= 0x7e);
        }
        String[] p = line.split(" ", -1); // At most255 bounded ASCII characters before allocation.
        return switch (p[0]) {
            case "GLOBAL_READY" -> {
                require(
                        line.length() < READY_BYTES
                                && p.length == 12
                                && p[4].equals("1")
                                && p[11].equals("511"));
                yield new Ready(
                        new Identity(p[1], p[2], p[3]),
                        number(p[5], GlobalInteriorLimits.VERTICES),
                        number(p[6], GlobalInteriorLimits.TRIANGLES),
                        number(p[7], GlobalInteriorLimits.TRIANGLES),
                        number(p[8], GlobalInteriorLimits.GROUPS),
                        number(p[9], GlobalInteriorLimits.BATCHES),
                        number(p[10], GlobalInteriorLimits.IMAGES));
            }
            case "GLOBAL_INPUT_CONSUMED" -> {
                require(line.length() < CONSUMED_BYTES && p.length == 3);
                yield new InputConsumed(p[1], p[2]);
            }
            case "GLOBAL_CLOSED" -> {
                require(line.length() < CLOSED_BYTES && p.length == 4);
                yield new Closed(new Identity(p[1], p[2], p[3]));
            }
            case "GLOBAL_UNAVAILABLE" -> {
                require(p.length == 5);
                Code code =
                        switch (p[4]) {
                            case "INPUT" -> Code.INPUT;
                            case "CAPABILITY" -> Code.CAPABILITY;
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown global status code");
                        };
                yield new Unavailable(new Identity(p[1], p[2], p[3]), code);
            }
            default -> throw new IllegalArgumentException("Unknown global status");
        };
    }

    private static int number(String value, int max) {
        require(
                !value.isEmpty()
                        && value.length() <= Integer.toString(max).length()
                        && (value.length() == 1 || value.charAt(0) != '0'));
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c >= '0' && c <= '9');
            result = result * 10 + c - '0';
        }
        require(result <= max);
        return result;
    }
}
