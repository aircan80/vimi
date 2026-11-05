package com.vibeminer.formats.hotfix;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structural v9 hotfix payload decoder. Logical schema order, never native WDC bitpacking. Does not
 * select cache entries, authenticate sources, apply overlays or mint native capture evidence.
 */
public final class HotfixRowDecoder {
    public static final String REVISION = "hotfix-flat-dbd-v2";

    public enum Reason {
        LIMIT,
        TABLE_HASH,
        LAYOUT,
        COLUMNS,
        ID_STORAGE,
        SCHEMA,
        TRUNCATED,
        TEXT,
        INLINE_ID,
        TRAILING_BYTES,
        /** Non-finite single; base DB2 rows refuse these too. */
        FLOAT,
        /** Negative value in a column the base keeps as a raw physical integer. */
        RAW_INTEGER
    }

    private static final String RAW_PHYSICAL = "raw-physical-integer-v1";

    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        private Invalid(Reason reason) {
            super("Hotfix row " + reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Fixed upper ceilings cannot be widened by a caller. */
    public record Limits(
            int maxPayloadBytes, int maxColumns, int maxStringBytes, int maxDecodedChars) {
        public Limits() {
            this(65536, 128, 16384, 131072);
        }

        public Limits {
            if (maxPayloadBytes < 1
                    || maxPayloadBytes > 65536
                    || maxColumns < 1
                    || maxColumns > 128
                    || maxStringBytes < 1
                    || maxStringBytes > 16384
                    || maxDecodedChars < 1
                    || maxDecodedChars > 131072) {
                throw new IllegalArgumentException("Hotfix decoder limits");
            }
        }
    }

    /**
     * Structural inputs only. The caller must obtain header/flags/index from the SAME qualified
     * native layout; passing this record alone is not source authority. Header's public contract
     * does not retain raw flags/id index, so they are explicit, not guessed from names.
     */
    public record Layout(
            TableSchema schema, Db2Types.Header header, int nativeFlags, int nativeIdFieldIndex) {
        public Layout {
            Objects.requireNonNull(schema);
            Objects.requireNonNull(header);
            if (nativeFlags < 0
                    || nativeFlags > 65535
                    || nativeIdFieldIndex < 0
                    || nativeIdFieldIndex > 65535) {
                throw new IllegalArgumentException("Hotfix layout scalars");
            }
        }
    }

    /** Values may include key material; toString deliberately does not render any cells. */
    public record Decoded(Db2Types.Row row, int consumedBytes) {
        public Decoded {
            Objects.requireNonNull(row);
        }

        @Override
        public String toString() {
            return "DecodedHotfixRow[columns="
                    + row.values().size()
                    + ", bytes="
                    + consumedBytes
                    + "]";
        }
    }

    private HotfixRowDecoder() {}

    /**
     * Decodes a caller-owned immutable/pinned payload slice without mutating its position. Bounds
     * precede any body copy. Exact EOF is required; auxiliary tails need a separate reviewed
     * profile. This output is HOTFIX-derived, never VERIFIED_NATIVE or a physical capture row.
     */
    public static Decoded decode(
            Layout layout,
            long entryTableHash,
            long recordId,
            ByteBuffer payload,
            Limits limits,
            Cancellation token) {
        Objects.requireNonNull(layout);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(token);
        token.check();
        if (payload.remaining() > limits.maxPayloadBytes()) {
            throw invalid(Reason.LIMIT);
        }
        if (recordId < 0
                || recordId > 0xffff_ffffL
                || entryTableHash < 0
                || entryTableHash > 0xffff_ffffL) {
            throw invalid(Reason.TABLE_HASH);
        }
        validate(layout, entryTableHash, limits, token);
        var input = payload.asReadOnlyBuffer().slice().order(ByteOrder.LITTLE_ENDIAN);
        var values = new ArrayList<String>(layout.header().columns().size());
        int chars = 0;
        for (var field : layout.schema().columns()) {
            token.check();
            for (int element = 0; element < field.arrayLength(); element++) {
                token.check();
                String value;
                if (field.id() == TableSchema.KeyStorage.NONINLINE) {
                    value = Long.toString(recordId);
                } else if (field.kind() == TableSchema.ColumnKind.INT) {
                    int bits = field.bits();
                    need(input, bits / 8);
                    long raw = integer(input, bits);
                    if (field.id() == TableSchema.KeyStorage.INLINE) {
                        if (raw != recordId) {
                            throw invalid(Reason.INLINE_ID);
                        }
                        value = Long.toString(recordId);
                    } else if (Boolean.TRUE.equals(field.unsigned())) {
                        value = Long.toUnsignedString(raw);
                    } else {
                        long signed = bits == 64 ? raw : (raw << (64 - bits)) >> (64 - bits);
                        // The base row shows such a column's physical integer. A negative
                        // declared value has no single physical spelling, so it stays unknown.
                        if (signed < 0
                                && layout.header().columns().get(values.size()).interpretation()
                                        != null) {
                            throw invalid(Reason.RAW_INTEGER);
                        }
                        value = Long.toString(signed);
                    }
                } else if (field.kind() == TableSchema.ColumnKind.FLOAT) {
                    need(input, 4);
                    float single = input.getFloat();
                    if (!Float.isFinite(single)) {
                        throw invalid(Reason.FLOAT);
                    }
                    // Same text as base DB2 rows; the effective view mixes both in one table.
                    value = com.vibeminer.formats.db2.SingleText.format(single);
                } else {
                    value = text(input, limits, token);
                }
                if (value.length() > limits.maxDecodedChars() - chars) {
                    throw invalid(Reason.LIMIT);
                }
                chars += value.length();
                values.add(value);
            }
        }
        token.check();
        if (input.hasRemaining()) {
            throw invalid(Reason.TRAILING_BYTES);
        }
        return new Decoded(
                new Db2Types.Row(recordId, false, null, values, List.of()), input.position());
    }

    private static void validate(Layout layout, long tableHash, Limits limits, Cancellation token) {
        var schema = layout.schema();
        var header = layout.header();
        if (!hex(header.tableHash())
                || Long.parseUnsignedLong(header.tableHash(), 16) != tableHash) {
            throw invalid(Reason.TABLE_HASH);
        }
        if (!List.of("WDC3", "WDC4", "WDC5").contains(header.format())
                || !hex(header.layoutHash())
                || schema.layoutHashes().isEmpty()
                || schema.layoutHashes().size() > 64
                || schema.layoutHashes().stream()
                        .noneMatch(hash -> header.layoutHash().equalsIgnoreCase(hash))) {
            throw invalid(Reason.LAYOUT);
        }
        if (schema.columns().size() > limits.maxColumns()
                || header.columns().size() > limits.maxColumns()) {
            throw invalid(Reason.LIMIT);
        }
        int column = 0, id = -1, relations = 0;
        for (int index = 0; index < schema.columns().size(); index++) {
            token.check();
            var field = schema.columns().get(index);
            if (field.name() == null
                    || field.name().isEmpty()
                    || field.name().length() > 128
                    || field.kind() == null
                    || field.id() == null
                    || field.relation() == null
                    || field.arrayLength() < 1
                    || field.arrayLength() > limits.maxColumns() - column) {
                throw invalid(Reason.SCHEMA);
            }
            if (field.kind() == TableSchema.ColumnKind.INT) {
                if (field.bits() == null
                        || field.unsigned() == null
                        || !List.of(8, 16, 32, 64).contains(field.bits())) {
                    throw invalid(Reason.SCHEMA);
                }
            } else if (field.bits() != null || field.unsigned() != null) {
                throw invalid(Reason.SCHEMA);
            }
            if (field.id() != TableSchema.KeyStorage.NONE) {
                if (id != -1
                        || field.kind() != TableSchema.ColumnKind.INT
                        || field.arrayLength() != 1
                        || field.relation() != TableSchema.KeyStorage.NONE) {
                    throw invalid(Reason.ID_STORAGE);
                }
                id = index;
                boolean noninline = field.id() == TableSchema.KeyStorage.NONINLINE;
                if (noninline != ((layout.nativeFlags() & 4) != 0)
                        || layout.nativeIdFieldIndex() != index
                                && !(noninline && layout.nativeIdFieldIndex() == 65535)) {
                    throw invalid(Reason.ID_STORAGE);
                }
            }
            if (field.relation() != TableSchema.KeyStorage.NONE) {
                if (++relations > 1
                        || field.kind() != TableSchema.ColumnKind.INT
                        || field.arrayLength() != 1) {
                    throw invalid(Reason.SCHEMA);
                }
            }
            if (field.kind() == TableSchema.ColumnKind.LOCSTRING
                    && !schema.locale().equals(field.locale())) {
                throw invalid(Reason.SCHEMA);
            }
            String kind =
                    switch (field.kind()) {
                        case INT -> "integer";
                        case FLOAT -> "float";
                        case STRING, LOCSTRING -> "string";
                    };
            for (int element = 0; element < field.arrayLength(); element++) {
                if (column >= header.columns().size()) {
                    throw invalid(Reason.COLUMNS);
                }
                var actual = header.columns().get(column++);
                String name =
                        field.arrayLength() == 1
                                ? field.name()
                                : field.name() + "[" + element + "]";
                if (!name.equals(actual.name())
                        || !kind.equals(actual.kind())
                        || actual.isId() != (field.id() != TableSchema.KeyStorage.NONE)
                        || !declaredWidth(actual.interpretation(), field)) {
                    throw invalid(Reason.COLUMNS);
                }
            }
        }
        if (id == -1) {
            throw invalid(Reason.ID_STORAGE);
        }
        if (column != header.columns().size()) {
            throw invalid(Reason.COLUMNS);
        }
    }

    /**
     * A base column kept as its raw physical integer is still decoded from the hotfix body at the
     * declared width, provided the interpretation describes exactly that declared field.
     */
    private static boolean declaredWidth(
            Db2Types.Interpretation interpretation, TableSchema.SchemaColumn field) {
        return interpretation == null
                || RAW_PHYSICAL.equals(interpretation.policy())
                        && field.kind() == TableSchema.ColumnKind.INT
                        && field.id() == TableSchema.KeyStorage.NONE
                        && Integer.valueOf(interpretation.candidateBits()).equals(field.bits())
                        && interpretation.candidateUnsigned()
                                == Boolean.TRUE.equals(field.unsigned())
                        && interpretation.physicalBits() >= interpretation.candidateBits();
    }

    private static boolean hex(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}");
    }

    private static long integer(ByteBuffer input, int bits) {
        return switch (bits) {
            case 8 -> Byte.toUnsignedLong(input.get());
            case 16 -> Short.toUnsignedLong(input.getShort());
            case 32 -> Integer.toUnsignedLong(input.getInt());
            case 64 -> input.getLong();
            default -> throw invalid(Reason.SCHEMA);
        };
    }

    private static String text(ByteBuffer input, Limits limits, Cancellation token) {
        int first = input.position(), length = 0;
        for (; ; ) {
            if ((length & 255) == 0) {
                token.check();
            }
            need(input, 1);
            if (input.get() == 0) {
                break;
            }
            if (++length > limits.maxStringBytes()) {
                throw invalid(Reason.LIMIT);
            }
        }
        var bytes = input.asReadOnlyBuffer().position(first).limit(first + length);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw invalid(Reason.TEXT);
        }
    }

    private static void need(ByteBuffer input, int count) {
        if (input.remaining() < count) {
            throw invalid(Reason.TRUNCATED);
        }
    }

    private static Invalid invalid(Reason reason) {
        return new Invalid(reason);
    }
}
