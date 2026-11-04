package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Fixed-size v2 metadata codecs only. No paths, source authority, mappings or native resources. */
final class WorldStreamEnvelope {
    static final int BYTES = 256;
    static final int MAX_PACKET_BYTES = 2_284_544;
    private static final byte[] SESSION_MAGIC = "VIMIWSS2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMIT_MAGIC = "VIMIWSC2".getBytes(StandardCharsets.US_ASCII);

    record Session(WorldStreamState.Nonce nonce, String scope, long policy) {
        Session {
            Objects.requireNonNull(nonce);
            if (scope == null || !scope.matches("[0-9a-f]{64}") || policy <= 0) {
                throw new IllegalArgumentException("Invalid session metadata");
            }
        }
    }

    record Resident(int slot, long generation) {
        Resident {
            if (slot < 0 || slot >= 10 || generation <= 0) {
                throw new IllegalArgumentException("Invalid resident identity");
            }
        }
    }

    record Commit(
            WorldStreamState.Nonce nonce,
            long transaction,
            long expected,
            long next,
            long policy,
            List<Resident> members) {
        Commit {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(members);
            if (transaction <= 0
                    || transaction == Long.MAX_VALUE
                    || expected < 0
                    || expected == Long.MAX_VALUE
                    || next != expected + 1
                    || policy <= 0
                    || members.size() > 9) {
                throw new IllegalArgumentException("Invalid commit metadata");
            }
            members = List.copyOf(members);
            var slots = new HashSet<Integer>();
            for (var member : members) {
                if (!slots.add(member.slot())) {
                    throw new IllegalArgumentException("Duplicate resident slot");
                }
            }
        }
    }

    static final class Invalid extends IOException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    private WorldStreamEnvelope() {}

    static byte[] encode(Session session) {
        var out = buffer();
        out.put(SESSION_MAGIC).putInt(2).putInt(BYTES);
        nonce(out, session.nonce());
        out.put(HexFormat.of().parseHex(session.scope()));
        out.putInt(1).putInt(1).putInt(1).putInt(1);
        out.putLong(session.policy()).putInt(800).putInt(500).putInt(10).putInt(9);
        out.putInt(MAX_PACKET_BYTES);
        return out.array();
    }

    static Session decodeSession(byte[] bytes, String expectedSha256) throws Invalid {
        var in = checked(bytes, expectedSha256, SESSION_MAGIC);
        try {
            var nonce = nonce(in);
            byte[] scope = new byte[32];
            in.get(scope);
            require(
                    in.getInt() == 1 && in.getInt() == 1 && in.getInt() == 1 && in.getInt() == 1,
                    "Unsupported session profile");
            long policy = in.getLong();
            require(
                    in.getInt() == 800
                            && in.getInt() == 500
                            && in.getInt() == 10
                            && in.getInt() == 9
                            && in.getInt() == MAX_PACKET_BYTES,
                    "Invalid session caps");
            zero(in, BYTES);
            return new Session(nonce, HexFormat.of().formatHex(scope), policy);
        } catch (IllegalArgumentException error) {
            throw new Invalid("Invalid session fields");
        }
    }

    static byte[] encode(Commit commit) {
        var out = buffer();
        out.put(COMMIT_MAGIC).putInt(2).putInt(BYTES);
        nonce(out, commit.nonce());
        out.putLong(commit.transaction()).putLong(commit.expected()).putLong(commit.next());
        out.putLong(commit.policy()).putInt(commit.members().size()).putInt(0);
        for (var resident : commit.members()) {
            out.put((byte) resident.slot());
            out.position(out.position() + 7);
            out.putLong(resident.generation());
        }
        return out.array();
    }

    static Commit decodeCommit(byte[] bytes, String expectedSha256, WorldStreamState.Nonce session)
            throws Invalid {
        var in = checked(bytes, expectedSha256, COMMIT_MAGIC);
        try {
            var nonce = nonce(in);
            require(nonce.equals(session), "Wrong commit session");
            long transaction = in.getLong(),
                    expected = in.getLong(),
                    next = in.getLong(),
                    policy = in.getLong();
            int count = in.getInt();
            require(count >= 0 && count <= 9 && in.getInt() == 0, "Invalid commit count");
            var members = new ArrayList<Resident>(count);
            for (int i = 0; i < count; i++) {
                int slot = Byte.toUnsignedInt(in.get());
                zero(in, in.position() + 7);
                members.add(new Resident(slot, in.getLong()));
            }
            zero(in, BYTES);
            return new Commit(nonce, transaction, expected, next, policy, members);
        } catch (IllegalArgumentException error) {
            throw new Invalid("Invalid commit fields");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static ByteBuffer checked(byte[] bytes, String sha, byte[] magic) throws Invalid {
        require(bytes != null && bytes.length == BYTES, "Invalid envelope length");
        require(
                sha != null && sha.matches("[0-9a-f]{64}") && sha256(bytes).equals(sha),
                "Envelope checksum mismatch");
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : magic) {
            require(in.get() == expected, "Wrong envelope mode");
        }
        require(in.getInt() == 2 && in.getInt() == BYTES, "Wrong envelope version");
        return in;
    }

    private static ByteBuffer buffer() {
        return ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void nonce(ByteBuffer out, WorldStreamState.Nonce nonce) {
        out.putLong(nonce.high()).putLong(nonce.low());
    }

    private static WorldStreamState.Nonce nonce(ByteBuffer in) {
        return new WorldStreamState.Nonce(in.getLong(), in.getLong());
    }

    private static void zero(ByteBuffer in, int end) throws Invalid {
        while (in.position() < end) {
            require(in.get() == 0, "Nonzero reserved bytes");
        }
    }

    private static void require(boolean valid, String message) throws Invalid {
        if (!valid) {
            throw new Invalid(message);
        }
    }
}
