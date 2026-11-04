package com.vibeminer.formats.hotfix;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded XFTH v9 structural inspection. It applies no row operations, decodes no table values,
 * activates no keys and supplies no native DB2 source authority. The source remains caller owned.
 */
public final class HotfixCacheReader {
    public static final String REVISION = "xfth-v9-index-v1";
    public static final int HEADER_BYTES = 44, ENTRY_BYTES = 32, READ_BYTES = 65536;
    private static final int MAGIC = 0x48544658;

    private HotfixCacheReader() {}

    public record Limits(long maxSourceBytes, int maxRecords, int maxRecordBytes) {
        public Limits() {
            this(64L << 20, 65536, 1 << 20);
        }

        public Limits {
            if (maxSourceBytes < HEADER_BYTES
                    || maxRecords < 0
                    || maxRecords > 1_000_000
                    || maxRecordBytes < 0
                    || maxRecordBytes > maxSourceBytes) {
                throw new IllegalArgumentException("Invalid hotfix inspection bounds");
            }
        }
    }

    public enum State {
        NOT_SET,
        VALID,
        DELETE,
        INVALID,
        NOT_PUBLIC
    }

    private static final State[] STATES = State.values();

    /** Raw physical entry; duplicate IDs and input ordering are intentionally preserved. */
    public record Entry(
            int ordinal,
            int regionId,
            int pushId,
            int uniqueId,
            long tableHash,
            long recordId,
            State state,
            long payloadOffset,
            int payloadBytes,
            String payloadSha256) {}

    /**
     * Retains only a borrowed immutable source identity and metadata, never payload copies. Caller
     * keeps that source alive through all outstanding reads. This is not native authority.
     */
    public static final class Index {
        private final Db2ByteSource source;
        private final int build;
        private final long length;
        private final String opaqueHeader, sha256;
        private final List<Entry> entries;

        private Index(
                Db2ByteSource source,
                int build,
                long length,
                String opaqueHeader,
                String sha256,
                ArrayList<Entry> entries) {
            this.source = source;
            this.build = build;
            this.length = length;
            this.opaqueHeader = opaqueHeader;
            this.sha256 = sha256;
            // This private builder has no other surviving mutable alias; avoid an index clone.
            this.entries = Collections.unmodifiableList(entries);
        }

        public int version() {
            return 9;
        }

        public int build() {
            return build;
        }

        public long length() {
            return length;
        }

        public String opaqueHeader() {
            return opaqueHeader;
        }

        public String sha256() {
            return sha256;
        }

        public List<Entry> entries() {
            return entries;
        }
    }

    /** Uses one virtual worker; source reads may block and never run on the JavaFX thread. */
    public static CompletableFuture<Index> inspect(
            Db2ByteSource source, int expectedBuild, Limits limits, Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(token);
        if (expectedBuild < 1) {
            throw new IllegalArgumentException("Positive expected build required");
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return inspectActual(source, expectedBuild, limits, token);
                    } catch (RuntimeException | Error failure) {
                        throw new java.util.concurrent.CompletionException(
                                "Hotfix inspection failed", failure);
                    }
                },
                command -> Thread.ofVirtual().name("hotfix-inspection").start(command));
    }

    /** Copies only the selected physical body; it does not choose or decode an effective row. */
    public static CompletableFuture<byte[]> readPayload(
            Db2ByteSource source, Index index, int ordinal, int maxBytes, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return readPayloadBlocking(source, index, ordinal, maxBytes, token);
                    } catch (RuntimeException | Error failure) {
                        throw new java.util.concurrent.CompletionException(
                                "Hotfix payload read failed", failure);
                    }
                },
                command -> Thread.ofVirtual().name("hotfix-payload").start(command));
    }

    /** Worker-only variant for an existing table worker; same bounded checks and owned result. */
    public static byte[] readPayloadBlocking(
            Db2ByteSource source, Index index, int ordinal, int maxBytes, Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(index);
        Objects.requireNonNull(token);
        token.check();
        if (source != index.source) {
            throw new IllegalArgumentException("Hotfix index belongs to another source");
        }
        var entry = index.entries.get(Objects.checkIndex(ordinal, index.entries.size()));
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Negative hotfix payload bound");
        }
        if (entry.payloadBytes() > maxBytes) {
            throw fail("LIMIT", "Selected hotfix payload exceeds its byte bound");
        }
        unchanged(source, index.length);
        var bytes = new byte[entry.payloadBytes()];
        for (int at = 0; at < bytes.length; ) {
            token.check();
            int count = Math.min(READ_BYTES, bytes.length - at);
            var out = ByteBuffer.wrap(bytes, at, count).slice();
            await(source.readRange(entry.payloadOffset() + at, out, token));
            token.check();
            if (out.position() != count || out.limit() != count) {
                throw fail("INVALID", "Short hotfix payload read");
            }
            at += count;
        }
        token.check();
        unchanged(source, index.length);
        if (!entry.payloadSha256().equals(HexFormat.of().formatHex(digest().digest(bytes)))) {
            throw fail("SOURCE_CHANGED", "Selected hotfix payload differs from inspected bytes");
        }
        return bytes;
    }

    private static void unchanged(Db2ByteSource source, long length) {
        var holes = source.holes();
        if (source.length() != length || holes == null || !holes.isEmpty()) {
            throw fail("SOURCE_CHANGED", "Hotfix source metadata changed");
        }
    }

    private static void await(CompletableFuture<Void> read) {
        Throwable failure = read.handle((ignored, error) -> error).join();
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new java.util.concurrent.CompletionException(
                    "Hotfix source read failed", failure);
        }
    }

    private static Index inspectActual(
            Db2ByteSource source, int expectedBuild, Limits limits, Cancellation token) {
        token.check();
        long length = source.length();
        if (length < HEADER_BYTES) {
            throw fail("INVALID", "Hotfix header is truncated");
        }
        if (length > limits.maxSourceBytes()) {
            throw fail("LIMIT", "Hotfix source exceeds its byte bound");
        }
        var holes = source.holes();
        if (holes == null || !holes.isEmpty()) {
            throw fail("UNREADABLE", "Hotfix inspection requires a complete readable source");
        }
        var input = new Input(source, length, token);
        // Read exactly the header before matching the build; no mismatched payload is fetched.
        input.fill(HEADER_BYTES);
        if (input.integer() != MAGIC) {
            throw fail("UNSUPPORTED", "Only XFTH hotfix containers are supported");
        }
        if (input.integer() != 9) {
            throw fail("UNSUPPORTED", "Only the reviewed XFTH version 9 is supported");
        }
        int build = input.integer();
        if (build != expectedBuild) {
            throw fail("BUILD_MISMATCH", "Hotfix cache build differs from the selected build");
        }
        var header = new byte[32];
        for (int i = 0; i < header.length; i++) {
            header[i] = (byte) input.unsignedByte();
        }
        var entries = new ArrayList<Entry>();
        while (input.position() < length) {
            token.check();
            if (entries.size() == limits.maxRecords()) {
                throw fail("LIMIT", "Hotfix record count exceeds its bound");
            }
            if (length - input.position() < ENTRY_BYTES) {
                throw fail("INVALID", "Hotfix entry header is truncated");
            }
            if (input.integer() != MAGIC) {
                throw fail("INVALID", "Hotfix entry signature differs");
            }
            int region = input.integer(), push = input.integer(), unique = input.integer();
            long table = Integer.toUnsignedLong(input.integer());
            long id = Integer.toUnsignedLong(input.integer());
            int bytes = input.integer(), state = input.unsignedByte();
            if (state >= STATES.length) {
                throw fail("UNSUPPORTED", "Hotfix entry state is not supported");
            }
            if ((input.unsignedByte() | input.unsignedByte() | input.unsignedByte()) != 0) {
                throw fail("UNSUPPORTED", "Hotfix entry reserved bytes are nonzero");
            }
            if (bytes < 0 || bytes > length - input.position()) {
                throw fail("INVALID", "Hotfix entry payload lies outside the source");
            }
            if (bytes > limits.maxRecordBytes()) {
                throw fail("LIMIT", "Hotfix record payload exceeds its byte bound");
            }
            long payloadOffset = input.position();
            String payloadSha256 = input.payloadDigest(bytes);
            entries.add(
                    new Entry(
                            entries.size(),
                            region,
                            push,
                            unique,
                            table,
                            id,
                            STATES[state],
                            payloadOffset,
                            bytes,
                            payloadSha256));
        }
        token.check();
        var finalHoles = source.holes();
        if (source.length() != length || finalHoles == null || !finalHoles.isEmpty()) {
            throw fail("SOURCE_CHANGED", "Hotfix source metadata changed during inspection");
        }
        return new Index(
                source,
                build,
                length,
                HexFormat.of().formatHex(header),
                HexFormat.of().formatHex(input.digest.digest()),
                entries);
    }

    private static HotfixFormatException fail(String code, String message) {
        return new HotfixFormatException("HOTFIX_" + code, message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class Input {
        private final Db2ByteSource source;
        private final long length;
        private final Cancellation token;
        private final ByteBuffer buffer = ByteBuffer.allocate(READ_BYTES);
        private final MessageDigest digest;
        private long fetched;

        private Input(Db2ByteSource source, long length, Cancellation token) {
            this.source = source;
            this.length = length;
            this.token = token;
            buffer.limit(0);
            digest = digest();
        }

        private void fill(int maximum) {
            token.check();
            if (buffer.hasRemaining()) {
                throw new IllegalStateException("Unread hotfix input");
            }
            int size = (int) Math.min(maximum, length - fetched);
            if (size == 0) {
                throw fail("INVALID", "Unexpected hotfix EOF");
            }
            buffer.clear().limit(size);
            var out = buffer.slice();
            await(source.readRange(fetched, out, token));
            token.check();
            if (out.position() != size || out.limit() != size) {
                throw fail("INVALID", "Short hotfix source read");
            }
            digest.update(buffer.duplicate());
            fetched += size;
        }

        private long position() {
            return fetched - buffer.remaining();
        }

        private int unsignedByte() {
            if (!buffer.hasRemaining()) {
                fill(READ_BYTES);
            }
            return buffer.get() & 255;
        }

        private int integer() {
            return unsignedByte()
                    | unsignedByte() << 8
                    | unsignedByte() << 16
                    | unsignedByte() << 24;
        }

        private String payloadDigest(int bytes) {
            var body = digest();
            while (bytes > 0) {
                token.check();
                if (!buffer.hasRemaining()) {
                    fill(READ_BYTES);
                }
                int count = Math.min(bytes, buffer.remaining());
                body.update(buffer.slice(buffer.position(), count));
                buffer.position(buffer.position() + count);
                bytes -= count;
            }
            return HexFormat.of().formatHex(body.digest());
        }
    }
}
