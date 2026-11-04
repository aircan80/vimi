package com.vibeminer.formats.generated;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Portable rows-v1 unsigned LEB128 and strict UTF-8 row codec. */
public final class RowCodec {
    public record DecodeLimits(
            int maxRecordBytes, int maxValues, int maxIssues, int maxStringBytes) {
        public DecodeLimits {
            if (maxRecordBytes < 1
                    || maxRecordBytes > GeneratedTypes.MAX_OBJECT_BYTES
                    || maxValues < 1
                    || maxIssues < 1
                    || maxStringBytes < 1
                    || maxStringBytes > GeneratedTypes.MAX_OBJECT_BYTES) {
                throw new IllegalArgumentException("Invalid row decode limits");
            }
        }
    }

    private RowCodec() {}

    public static byte[] encode(Db2Types.Row row) {
        var out = new ByteArrayOutputStream();
        out.write(
                (row.isCopy() ? 1 : 0)
                        | (row.copySourceId() != null ? 2 : 0)
                        | (row.textIssues() != null ? 4 : 0));
        if (row.copySourceId() != null) {
            var(out, row.copySourceId());
        }
        var(out, row.values().size());
        for (var value : row.values()) {
            string(out, value);
        }
        if (row.textIssues() != null) {
            var(out, row.textIssues().size());
            for (var issue : row.textIssues()) {
                if (issue.columnIndex() < 0) {
                    throw new IllegalArgumentException("Negative issue column");
                }
                var(out, issue.columnIndex());
                string(out, issue.code());
                string(out, issue.rawBytesHex());
            }
        }
        return out.toByteArray();
    }

    private static void var(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Unsigned varint required");
        }
        while (value >= 128) {
            out.write((int) value | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void string(ByteArrayOutputStream out, String value) {
        try {
            var b =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            var(out, b.remaining());
            while (b.hasRemaining()) {
                out.write(b.get());
            }
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF16 row text", e);
        }
    }

    public static Db2Types.Row decode(long id, byte[] bytes) {
        return decodeInternal(id, bytes, null);
    }

    public static Db2Types.Row decode(long id, byte[] bytes, DecodeLimits limits) {
        return decodeInternal(id, bytes, java.util.Objects.requireNonNull(limits));
    }

    private static Db2Types.Row decodeInternal(long id, byte[] bytes, DecodeLimits limits) {
        if (limits != null && bytes.length > limits.maxRecordBytes()) {
            throw limit();
        }
        try {
            var input = new Decoder(bytes, limits);
            int flags = input.byteValue();
            if ((flags & ~7) != 0) {
                throw corrupt();
            }
            Long source = (flags & 2) != 0 ? input.number(0xffffffffL) : null;
            int count = input.count();
            if (limits != null && count > limits.maxValues()) {
                throw limit();
            }
            var values = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                values.add(input.string());
            }
            ArrayList<Db2Types.TextIssue> issues = null;
            if ((flags & 4) != 0) {
                int n = input.count();
                if (limits != null && n > limits.maxIssues()) {
                    throw limit();
                }
                issues = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    issues.add(
                            new Db2Types.TextIssue(
                                    (int) input.number(Integer.MAX_VALUE),
                                    input.string(),
                                    input.string()));
                }
            }
            if (input.at != bytes.length) {
                throw corrupt();
            }
            return new Db2Types.Row(id, (flags & 1) != 0, source, values, issues);
        } catch (IndexOutOfBoundsException | CharacterCodingException e) {
            throw corrupt();
        }
    }

    private static final class Decoder {
        final byte[] bytes;
        final DecodeLimits limits;
        int at;

        Decoder(byte[] bytes, DecodeLimits limits) {
            this.bytes = bytes;
            this.limits = limits;
        }

        int byteValue() {
            if (at == bytes.length) {
                throw corrupt();
            }
            return bytes[at++] & 255;
        }

        long number(long max) {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = byteValue();
                if (shift == 63 && b > 1) {
                    throw corrupt();
                }
                v |= (long) (b & 127) << shift;
                if ((b & 128) == 0) {
                    if (v < 0 || v > max) {
                        throw corrupt();
                    }
                    return v;
                }
            }
            throw corrupt();
        }

        int count() {
            int n = (int) number(Integer.MAX_VALUE);
            if (n > bytes.length - at) {
                throw corrupt();
            }
            return n;
        }

        String string() throws CharacterCodingException {
            int n = (int) number(Integer.MAX_VALUE);
            if (n > bytes.length - at) {
                throw corrupt();
            }
            if (limits != null && n > limits.maxStringBytes()) {
                throw limit();
            }
            var value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, at, n))
                            .toString();
            at += n;
            return value;
        }
    }

    private static GeneratedStoreException corrupt() {
        return new GeneratedStoreException(GeneratedTypes.CORRUPT, "Malformed rows-v1 record");
    }

    private static GeneratedStoreException limit() {
        return new GeneratedStoreException(GeneratedTypes.LIMIT, "Row decode limit");
    }
}
