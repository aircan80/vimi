package com.vibeminer.formats.db2;

import static com.vibeminer.formats.db2.Db2Input.fail;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Reader.Binding;
import com.vibeminer.formats.db2.Db2Reader.Storage;
import com.vibeminer.formats.definitions.DbdTypes.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

final class Db2Numbers {
    private Db2Numbers() {}

    static long bits(byte[] record, long offset, int width) {
        if (width < 1
                || width > 64
                || offset < 0
                || offset > record.length * 8L
                || width > record.length * 8L - offset) {
            throw fail("INVALID", "Field outside exact physical record");
        }
        long value = 0;
        int written = 0;
        while (written < width) {
            long pos = offset + written;
            int within = (int) (pos & 7), take = Math.min(8 - within, width - written);
            int part = ((record[(int) (pos / 8)] & 255) >>> within) & ((1 << take) - 1);
            value |= (long) part << written;
            written += take;
        }
        return value;
    }

    static long raw(byte[] record, Storage s, Field field, int element, long id) {
        return switch (s.kind) {
            case 0 ->
                    bits(
                            record,
                            s.fieldOffset * 8L + element * (long) s.noneWidth(),
                            s.noneWidth());
            case 1, 5 -> bits(record, s.offset, s.width);
            case 2 -> s.common.getOrDefault(id, Integer.toUnsignedLong(s.p1));
            case 3, 4 -> {
                long index = bits(record, s.offset, s.width);
                long slot = index * (s.kind == 4 ? field.arrayLength() : 1) + element;
                if (slot < 0 || slot >= s.palette.length) {
                    throw fail("INVALID", "Palette index outside slice");
                }
                yield s.palette[(int) slot];
            }
            default -> throw fail("UNSUPPORTED", "Unknown storage kind");
        };
    }

    static long signed(long raw, int width) {
        return width == 64 ? raw : raw << (64 - width) >> (64 - width);
    }

    static boolean usesRaw(Storage s, Field f) {
        return f.column().family().equals("int")
                && (!f.column().verifiedName() || s.raw || s.collides)
                && !f.isId()
                && s.physicalBits() > (f.integerBits() == null ? 32 : f.integerBits());
    }

    static String number(long raw, Storage s, Field f) {
        if (usesRaw(s, f)) {
            return s.kind == 5 ? Long.toString(signed(raw, s.width)) : Long.toUnsignedString(raw);
        }
        if (f.column().family().equals("float")) {
            if (s.kind == 0 && s.noneWidth() != 32
                    || (s.kind == 1 || s.kind == 5) && s.width != 32) {
                throw fail("UNSUPPORTED", "Float needs32 physical bits");
            }
            float value = Float.intBitsToFloat((int) raw);
            if (!Float.isFinite(value)) {
                throw fail("UNSUPPORTED", "Nonfinite float");
            }
            return SingleText.format(value);
        }
        int width = f.integerBits() == null ? 32 : f.integerBits();
        if (s.kind == 5) {
            raw = signed(raw, s.width);
        }
        if (width < 64) {
            long mask = (1L << width) - 1;
            if (Long.compareUnsigned(raw, mask) > 0) {
                int sourceWidth =
                        s.kind == 0 ? s.noneWidth() : s.kind == 1 ? s.width : s.kind == 5 ? 64 : 32;
                long canonical = signed(raw & mask, width);
                if (sourceWidth < 64) {
                    canonical &= (1L << sourceWidth) - 1;
                }
                if (!s.narrow
                        && (Boolean.TRUE.equals(f.unsigned()) && s.kind != 5 || raw != canonical)) {
                    throw fail("SCHEMA_MISMATCH", "Integer does not fit selected definition width");
                }
            }
            raw &= mask;
        }
        return Boolean.TRUE.equals(f.unsigned())
                ? Long.toUnsignedString(raw)
                : Long.toString(signed(raw, width));
    }

    static String relationship(long raw, Field field) {
        var s = new Storage();
        s.kind = 0;
        s.fieldBits = 0;
        return number(raw, s, field);
    }

    private static List<Long> values(Storage s) {
        var values = new ArrayList<Long>();
        if (s.kind == 2) {
            values.add(Integer.toUnsignedLong(s.p1));
            values.addAll(s.common.values());
        } else {
            for (long value : s.palette) {
                values.add(value);
            }
        }
        return values;
    }

    static void select(
            List<Binding> fields,
            Storage[] storage,
            Db2Types.Limits limits,
            String table,
            String layout,
            Cancellation token) {
        if (limits.definitionSha256() != null
                && !limits.definitionSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Canonical definition SHA256 required");
        }
        if (limits.rules().size() > 8) {
            throw new IllegalArgumentException("At most8 raw integer rules");
        }
        var targets = new HashSet<String>();
        for (var rule : limits.rules()) {
            token.check();
            if (rule.expectedDefinitionSha256() == null
                    || !rule.expectedDefinitionSha256().matches("[0-9a-f]{64}")
                    || !rule.tableHash().matches("[0-9A-F]{8}")
                    || !rule.layoutHash().matches("[0-9A-F]{8}")
                    || rule.fieldOrdinal() < 0
                    || rule.fieldOrdinal() >= limits.maxFields()
                    || !rule.fieldName().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || rule.storageKind() < 0
                    || rule.storageKind() > 5
                    || rule.physicalBits() < 1
                    || rule.physicalBits() > 64
                    || rule.storageKind() >= 2
                            && rule.storageKind() <= 4
                            && rule.physicalBits() != 32
                    || !List.of(8, 16, 32, 64).contains(rule.candidateBits())
                    || rule.physicalBits() <= rule.candidateBits()
                    || !targets.add(
                            rule.expectedDefinitionSha256()
                                    + rule.tableHash()
                                    + rule.layoutHash()
                                    + rule.fieldOrdinal())) {
                throw new IllegalArgumentException("Invalid or duplicate exact integer rule");
            }
        }
        for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
            token.check();
            var b = fields.get(ordinal);
            var f = b.field();
            if (b.storage() < 0) {
                continue;
            }
            var s = storage[b.storage()];
            int width = f.integerBits() == null ? 32 : f.integerBits();
            if (!f.isId()
                    && !f.relationStorage()
                    && f.column().verifiedName()
                    && f.column().family().equals("int")) {
                for (var rule : limits.rules()) {
                    if (rule.expectedDefinitionSha256().equals(limits.definitionSha256())
                            && rule.tableHash().equals(table)
                            && rule.layoutHash().equals(layout)
                            && rule.fieldOrdinal() == ordinal
                            && rule.fieldName().equals(f.column().name())
                            && rule.storageKind() == s.kind
                            && rule.physicalBits() == s.physicalBits()
                            && rule.candidateBits() == width
                            && rule.candidateUnsigned() == Boolean.TRUE.equals(f.unsigned())) {
                        s.raw = true;
                        s.yieldRaw = rule.narrowsWhenConstant();
                    }
                }
            }
            boolean yielding = s.raw && s.yieldRaw && s.kind >= 2 && s.kind <= 4;
            if (!yielding
                    && !(s.kind >= 2
                            && s.kind <= 4
                            && f.column().family().equals("int")
                            && !f.isId()
                            && !f.relationStorage()
                            && width < 32
                            && !usesRaw(s, f))) {
                continue;
            }
            var patterns = new HashSet<Long>();
            var lowToUpper = new HashMap<Long, Long>();
            boolean collision = false;
            long mask = (1L << width) - 1;
            int observed = 0;
            for (long value : values(s)) {
                if ((++observed & 4095) == 0) {
                    token.check();
                }
                boolean fits =
                        value <= mask
                                || !Boolean.TRUE.equals(f.unsigned())
                                        && value == (signed(value & mask, width) & 0xffffffffL);
                if (!fits) {
                    patterns.add(value >>> width);
                }
                long low = value & mask, upper = value >>> width;
                Long old = lowToUpper.putIfAbsent(low, upper);
                if (old != null && old != upper) {
                    collision = true;
                }
            }
            if (yielding) {
                if (patterns.size() > 1 || collision) {
                    continue;
                }
                s.raw = false;
                s.narrow = patterns.size() == 1;
                continue;
            }
            if (patterns.size() > 1) {
                throw fail(
                        "SCHEMA_MISMATCH", "Varying upper-bit patterns forbid integer narrowing");
            }
            if (collision) {
                s.collides = true;
                continue;
            }
            s.narrow = patterns.size() == 1;
        }
    }

    static List<Db2Types.Column> columns(
            List<Binding> fields,
            Storage[] storage,
            Db2Reader.Relations[] relations,
            Db2Types.Limits limits) {
        var columns = new ArrayList<Db2Types.Column>();
        for (var b : fields) {
            var f = b.field();
            String kind =
                    switch (f.column().family()) {
                        case "int" -> "integer";
                        case "float" -> "float";
                        case "string", "locstring" -> "string";
                        default -> throw fail("UNSUPPORTED", "Unknown scalar family");
                    };
            Db2Types.Interpretation interpretation = null;
            Db2Types.Narrowing narrowing = null;
            if (b.storage() >= 0) {
                var s = storage[b.storage()];
                if (usesRaw(s, f)) {
                    interpretation =
                            new Db2Types.Interpretation(
                                    "raw-physical-integer-v1",
                                    s.physicalBits(),
                                    s.kind == 5,
                                    f.integerBits() == null ? 32 : f.integerBits(),
                                    Boolean.TRUE.equals(f.unsigned()),
                                    s.raw
                                            ? "Candidate integer width is narrower than the physical value width. Explicit raw evidence preserves this entire column without narrowing; semantic references and calculations are not admitted."
                                            : s.collides
                                                    ? "Candidate integer width is narrower than the physical value width. Keeping only the low bits would give different stored values the same number, so this entire column keeps its physical integers; semantic references and calculations are not admitted."
                                                    : "Unverified provider field: values preserve the physical integer without narrowing to the candidate schema. Names, relationships and semantic meaning are unverified.");
                }
                if (s.narrow) {
                    narrowing =
                            new Db2Types.Narrowing(
                                    32, f.integerBits() == null ? 32 : f.integerBits());
                }
            } else if (f.relationStorage() && !f.column().verifiedName()) {
                int width = f.integerBits() == null ? 32 : f.integerBits();
                long max =
                        Boolean.TRUE.equals(f.unsigned())
                                ? (width == 32 ? 0xffffffffL : (1L << width) - 1)
                                : (1L << (width - 1)) - 1;
                boolean raw = false;
                for (var map : relations) {
                    if (map != null) {
                        for (int i = 0; i < map.values.length; i++) {
                            if (map.present[i] && map.values[i] > max) {
                                raw = true;
                            }
                        }
                    }
                }
                if (raw) {
                    interpretation =
                            new Db2Types.Interpretation(
                                    "raw-physical-integer-v1",
                                    32,
                                    false,
                                    width,
                                    Boolean.TRUE.equals(f.unsigned()),
                                    "Unverified provider field: values preserve the physical integer without narrowing to the candidate schema. Names, relationships and semantic meaning are unverified.");
                }
            }
            for (int i = 0; i < f.arrayLength(); i++) {
                if (columns.size() >= limits.maxColumns()) {
                    throw fail("LIMIT", "Flattened columns exceed bound");
                }
                columns.add(
                        new Db2Types.Column(
                                f.arrayLength() == 1
                                        ? f.column().name()
                                        : f.column().name() + "[" + i + "]",
                                kind,
                                f.isId(),
                                interpretation,
                                narrowing));
            }
        }
        return List.copyOf(columns);
    }
}
