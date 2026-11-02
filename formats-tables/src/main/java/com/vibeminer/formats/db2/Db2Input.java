package com.vibeminer.formats.db2;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class Db2Input {
    final Db2ByteSource source;
    final long length;
    final List<Db2Types.Hole> holes;

    Db2Input(Db2ByteSource source, Db2Types.Limits limits) {
        this.source = source;
        length = source.length();
        var declared = source.holes();
        if (declared == null || declared.size() > 128) {
            throw fail("PROJECTION_INVALID", "Hole metadata exceeds chunk bound");
        }
        var snapshot = new java.util.ArrayList<>(declared);
        long after = 0;
        for (var hole : snapshot) {
            if (hole == null
                    || hole.offset() < after
                    || hole.length() <= 0
                    || hole.offset() > length
                    || hole.length() > length - hole.offset()
                    || hole.name() == null
                    || hole.name().isEmpty()) {
                throw fail("PROJECTION_INVALID", "Holes must be bounded ordered disjoint ranges");
            }
            after = hole.offset() + hole.length();
        }
        holes = List.copyOf(snapshot);
        if (!holes.isEmpty() && !limits.allowPartialEncryptedSections()) {
            throw fail("ENCRYPTED", "Partial sections require explicit admission");
        }
    }

    byte[] read(long offset, int count, Cancellation token) {
        token.check();
        if (offset < 0 || count < 0 || offset > length || count > length - offset) {
            throw fail("INVALID", "Read outside DB2 source");
        }
        for (var hole : holes) {
            if (hole.offset() >= offset + count) {
                break;
            }
            if (hole.offset() + hole.length() > offset && count > 0) {
                throw new Db2FormatException(
                        "BLTE_RANGE_UNAVAILABLE",
                        "Plaintext range intersects unavailable encrypted chunk");
            }
        }
        var buffer = ByteBuffer.allocate(count);
        source.readRange(offset, buffer, token).join();
        token.check();
        if (buffer.hasRemaining()) {
            throw fail("INVALID", "Short DB2 source read");
        }
        return buffer.array();
    }

    void padding(long first, long last, Cancellation token) {
        while (first < last) {
            var b = read(first, (int) Math.min(65536, last - first), token);
            for (byte v : b) {
                if (v != 0) {
                    throw fail("UNSUPPORTED", "Nonzero undeclared gap");
                }
            }
            first += b.length;
        }
    }

    static long u(byte[] bytes, int at) {
        return Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    static int h(byte[] bytes, int at) {
        return Short.toUnsignedInt(
                ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    static long q(byte[] bytes, int at) {
        return ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw fail("INVALID", "Table text is not strict UTF-8");
        }
    }

    static Db2FormatException fail(String code, String message) {
        return new Db2FormatException("DB2_" + code, message);
    }
}
