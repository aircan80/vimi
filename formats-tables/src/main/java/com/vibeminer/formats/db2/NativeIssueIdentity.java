package com.vibeminer.formats.db2;

import com.vibeminer.formats.internal.Json;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/** Canonical fingerprints shared by the native producer and graph validator. */
public final class NativeIssueIdentity {
    private NativeIssueIdentity() {}

    /** Preserves the existing generated-table reader-options serialization exactly. */
    public static String readerOptions(Db2Types.Limits limits) {
        var map = new LinkedHashMap<>(Json.object(Json.parse(Json.bytes(limits), 1 << 20)));
        return Json.sha256(Json.bytes(map));
    }

    /** Null tag 0; nonnull tag 1 followed by unsigned32 UTF-8 byte length and bytes. */
    public static byte[] tuple(String... values) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (String value : values) {
                if (value == null) {
                    out.writeByte(0);
                } else {
                    ByteBuffer encoded =
                            StandardCharsets.UTF_8
                                    .newEncoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .encode(java.nio.CharBuffer.wrap(value));
                    out.writeByte(1);
                    out.writeInt(encoded.remaining());
                    out.write(encoded.array(), encoded.position(), encoded.remaining());
                }
            }
            return bytes.toByteArray();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 identity", e);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static String columns(List<Db2Types.Column> columns) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.write(tuple("native-row-columns-v1"));
            out.writeInt(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                var c = columns.get(i);
                out.writeInt(i);
                out.write(tuple(c.name(), c.kind()));
                out.writeBoolean(c.isId());
                var p = c.interpretation();
                out.writeBoolean(p != null);
                if (p != null) {
                    out.write(tuple(p.policy()));
                    out.writeInt(p.physicalBits());
                    out.writeBoolean(p.physicalSigned());
                    out.writeInt(p.candidateBits());
                    out.writeBoolean(p.candidateUnsigned());
                    out.write(tuple(p.warning()));
                }
                var n = c.narrowing();
                out.writeBoolean(n != null);
                if (n != null) {
                    out.writeInt(n.storedBits());
                    out.writeInt(n.usedBits());
                }
            }
            return Json.sha256(bytes.toByteArray());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
