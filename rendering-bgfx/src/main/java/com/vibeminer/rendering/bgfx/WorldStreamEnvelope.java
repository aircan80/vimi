package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.WorldFrameLimits;
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
    /** Fixed envelope size; a commit of MAX_ACTIVE 16-byte members needs 72 + 400 bytes. */
    static final int BYTES = 512;

    /**
     * Layout version: 3 since issue #173 (512 bytes, session view distance and object texture
     * tier). A helper from another build fails as a version mismatch, not as invalid caps.
     */
    static final int VERSION = 3;

    // Terrain 2,581,504B (2,284,544B plus 296,960B of vertex normals and colours since packet
    // version 4, #373) plus the optional 753,792B static water trailer (622,720B, plus 98,304B of
    // per-vertex liquid colours since water trailer version 2 and 32,768B of liquid kinds since
    // version 3, #171).
    static final int MAX_PACKET_BYTES = 3_335_296 + WorldStreamTileFile.MAX_SPLAT_BYTES;
    private static final byte[] SESSION_MAGIC = "VIMIWSS2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMIT_MAGIC = "VIMIWSC2".getBytes(StandardCharsets.US_ASCII);

    /**
     * The fourth profile word is 1 for terrain only and 2 for BASIC_EXTERIOR_V1 sessions, whose
     * tile headers may name a per-tile objects file and whose STAGED status reports its outcome.
     */
    record Session(
            WorldStreamState.Nonce nonce,
            String scope,
            long policy,
            boolean exteriorObjects,
            int maxActive,
            int objectTextures) {
        /** Active-table bound of the near 3 x 3 view distance. */
        static final int NEAR_ACTIVE = 9;

        /** Object texture tiers: 1 STANDARD, 2 HIGH (more image pixels per object set). */
        static final int STANDARD_TEXTURES = 1, HIGH_TEXTURES = 2;

        /** GPU bytes all exterior object sets of a session may reserve together, per tier. */
        static final long STANDARD_OBJECT_BUDGET = 192L << 20, HIGH_OBJECT_BUDGET = 256L << 20;

        Session(WorldStreamState.Nonce nonce, String scope, long policy) {
            this(nonce, scope, policy, false);
        }

        Session(WorldStreamState.Nonce nonce, String scope, long policy, boolean exteriorObjects) {
            this(nonce, scope, policy, exteriorObjects, NEAR_ACTIVE);
        }

        Session(
                WorldStreamState.Nonce nonce,
                String scope,
                long policy,
                boolean exteriorObjects,
                int maxActive) {
            this(nonce, scope, policy, exteriorObjects, maxActive, STANDARD_TEXTURES);
        }

        Session {
            Objects.requireNonNull(nonce);
            if (scope == null
                    || !scope.matches("[0-9a-f]{64}")
                    || policy <= 0
                    || maxActive != NEAR_ACTIVE && maxActive != WorldStreamState.MAX_ACTIVE
                    || objectTextures != STANDARD_TEXTURES && objectTextures != HIGH_TEXTURES) {
                throw new IllegalArgumentException("Invalid session metadata");
            }
        }

        /** Renderer slots of this session: every active one plus one staged. */
        int slots() {
            return maxActive + 1;
        }

        /** Shared exterior objects GPU budget of this session's texture tier. */
        long objectBudgetBytes() {
            return objectTextures == HIGH_TEXTURES ? HIGH_OBJECT_BUDGET : STANDARD_OBJECT_BUDGET;
        }
    }

    record Resident(int slot, long generation) {
        Resident {
            if (slot < 0 || slot >= WorldStreamState.SLOTS || generation <= 0) {
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
                    || members.size() > WorldStreamState.MAX_ACTIVE) {
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
        out.put(SESSION_MAGIC).putInt(VERSION).putInt(BYTES);
        nonce(out, session.nonce());
        out.put(HexFormat.of().parseHex(session.scope()));
        out.putInt(1).putInt(1).putInt(1).putInt(session.exteriorObjects() ? 2 : 1);
        out.putLong(session.policy())
                .putInt(WorldFrameLimits.MAX_WIDTH)
                .putInt(WorldFrameLimits.MAX_HEIGHT);
        out.putInt(session.slots()).putInt(session.maxActive());
        out.putInt(MAX_PACKET_BYTES).putInt(session.objectTextures());
        return out.array();
    }

    static Session decodeSession(byte[] bytes, String expectedSha256) throws Invalid {
        var in = checked(bytes, expectedSha256, SESSION_MAGIC);
        try {
            var nonce = nonce(in);
            byte[] scope = new byte[32];
            in.get(scope);
            require(
                    in.getInt() == 1 && in.getInt() == 1 && in.getInt() == 1,
                    "Unsupported session profile");
            int objects = in.getInt();
            require(objects == 1 || objects == 2, "Unsupported session profile");
            long policy = in.getLong();
            require(
                    in.getInt() == WorldFrameLimits.MAX_WIDTH
                            && in.getInt() == WorldFrameLimits.MAX_HEIGHT,
                    "Invalid session caps");
            int slots = in.getInt(), active = in.getInt();
            require(
                    (active == Session.NEAR_ACTIVE || active == WorldStreamState.MAX_ACTIVE)
                            && slots == active + 1
                            && in.getInt() == MAX_PACKET_BYTES,
                    "Invalid session caps");
            int textures = in.getInt();
            require(
                    textures == Session.STANDARD_TEXTURES || textures == Session.HIGH_TEXTURES,
                    "Invalid object texture tier");
            zero(in, BYTES);
            return new Session(
                    nonce, HexFormat.of().formatHex(scope), policy, objects == 2, active, textures);
        } catch (IllegalArgumentException error) {
            throw new Invalid("Invalid session fields");
        }
    }

    static byte[] encode(Commit commit) {
        var out = buffer();
        out.put(COMMIT_MAGIC).putInt(VERSION).putInt(BYTES);
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
            require(
                    count >= 0 && count <= WorldStreamState.MAX_ACTIVE && in.getInt() == 0,
                    "Invalid commit count");
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
        require(
                in.getInt() == VERSION && in.getInt() == BYTES,
                "Wrong envelope version (parent and World helper differ)");
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
