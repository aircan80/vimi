package com.vibeminer.concepts.comparison;

import com.vibeminer.concepts.comparison.ComparisonTypes.Aspect;
import com.vibeminer.concepts.comparison.ComparisonTypes.Evidence;
import com.vibeminer.concepts.comparison.ComparisonTypes.Field;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Private temporary framing; not a durable format or public cache identity. */
final class ComparisonCodec {
    private ComparisonCodec() {}

    static byte[] pair(Record a, Record b) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            write(out, a);
            write(out, b);
        }
        return bytes.toByteArray();
    }

    static Record[] pair(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var pair = new Record[] {read(in), read(in)};
            if (in.available() != 0) {
                throw new IOException("Invalid staging frame");
            }
            return pair;
        }
    }

    /** Parses frame structure only: no decoded strings, records, maps or FieldDelta graph. */
    static long classifiedBytes(byte[] frame, long limit) throws IOException {
        if (frame.length > ComparisonService.RECORD_BYTES) {
            throw new ComparisonLimit("Result frame exceeds policy");
        }
        var scan = new FrameScan(frame);
        long records = scan.record() + scan.record();
        if (scan.position != frame.length || scan.records == 0) {
            throw new IOException("Invalid pair frame structure");
        }
        // Includes union TreeSet nodes, immutable result/list copies and one FieldDelta per
        // possible union key. Counting both records' keys overestimates shared-key unions.
        long estimate = Math.addExact(records, Math.addExact(1024L, 256L * scan.fields));
        estimate = Math.max(estimate, 8L * frame.length);
        if (estimate > limit) {
            throw new ComparisonLimit("Decoded/classified candidate exceeds memory plan");
        }
        return estimate;
    }

    private static final class FrameScan {
        private static final int KINDS = ValueKind.values().length,
                STATES = ValueState.values().length,
                ASPECTS = Aspect.values().length;
        private final byte[] bytes;
        private int position, records, fields;
        private boolean nullString;

        FrameScan(byte[] bytes) {
            this.bytes = bytes;
        }

        long record() throws IOException {
            int present = octet();
            if (present == 0) {
                return 0;
            }
            if (present != 1) {
                throw new IOException("Invalid record presence flag");
            }
            records++;
            long size = 768L + string(false);
            int count = count(4096);
            fields = Math.addExact(fields, count);
            size = Math.addExact(size, 128L + 64L * count);
            for (int i = 0; i < count; i++) {
                long field = 320L + string(false) + string(false);
                ordinal(KINDS);
                int state = ordinal(STATES);
                field = Math.addExact(field, string(true));
                if ((state == ValueState.VALUE.ordinal()) == nullString) {
                    throw new IOException("Invalid field value state");
                }
                ordinal(ASPECTS);
                size = Math.addExact(size, Math.addExact(field, evidence()));
            }
            return Math.addExact(size, evidence());
        }

        long evidence() throws IOException {
            int count = count(65536);
            long size = 64L + 16L * count;
            for (int i = 0; i < count; i++) {
                size =
                        Math.addExact(
                                size,
                                128L + string(false) + string(false) + string(true) + string(true));
            }
            return size;
        }

        int count(int maximum) throws IOException {
            int value = integer();
            if (value < 0 || value > maximum) {
                throw new IOException("Invalid frame count");
            }
            return value;
        }

        int ordinal(int maximum) throws IOException {
            int value = integer();
            if (value < 0 || value >= maximum) {
                throw new IOException("Invalid frame enum");
            }
            return value;
        }

        long string(boolean nullable) throws IOException {
            int length = integer();
            nullString = length == -1;
            if (nullString) {
                if (!nullable) {
                    throw new IOException("Missing required frame text");
                }
                return 0;
            }
            if (length < 0
                    || length > ComparisonService.RECORD_BYTES
                    || length > bytes.length - position) {
                throw new IOException("Invalid frame text length");
            }
            int end = position + length;
            // Validate UTF-8 without allocating a replacement-decoded String.
            while (position < end) {
                int a = octet();
                if (a < 128) {
                    continue;
                }
                int extra, lower = 128, upper = 191;
                if (a >= 194 && a <= 223) {
                    extra = 1;
                } else {
                    if (a >= 224 && a <= 239) {
                        extra = 2;
                        if (a == 224) {
                            lower = 160;
                        }
                        if (a == 237) {
                            upper = 159;
                        }
                    } else {
                        if (a >= 240 && a <= 244) {
                            extra = 3;
                            if (a == 240) {
                                lower = 144;
                            }
                            if (a == 244) {
                                upper = 143;
                            }
                        } else {
                            throw new IOException("Invalid frame UTF-8");
                        }
                    }
                }
                if (extra > end - position) {
                    throw new IOException("Truncated frame UTF-8");
                }
                int b = octet();
                if (b < lower || b > upper) {
                    throw new IOException("Invalid frame UTF-8 continuation");
                }
                for (int i = 1; i < extra; i++) {
                    b = octet();
                    if (b < 128 || b > 191) {
                        throw new IOException("Invalid frame UTF-8 continuation");
                    }
                }
            }
            return 64L + 4L * length;
        }

        int integer() throws IOException {
            return (octet() << 24) | (octet() << 16) | (octet() << 8) | octet();
        }

        int octet() throws IOException {
            if (position >= bytes.length) {
                throw new IOException("Truncated result frame");
            }
            return bytes[position++] & 255;
        }
    }

    static Record bounded(Record record, int maxBytes) throws IOException {
        if (record == null) {
            return null;
        }
        if (record.key().length() > 4096) {
            throw new ComparisonLimit("Record identity exceeds limit");
        }
        if (record.fields().size() > 4096) {
            throw new ComparisonLimit("Field inventory exceeds limit");
        }
        var fields = new LinkedHashMap<String, Field>();
        int retained = record.key().getBytes(StandardCharsets.UTF_8).length + 64;
        for (var entry : new TreeMap<>(record.fields()).entrySet()) {
            if (fields.size() >= 4096 || entry.getKey().length() > 4096) {
                throw new ComparisonLimit("Field inventory exceeds limit");
            }
            var field = entry.getValue();
            long estimate = 64L + 4L * (entry.getKey().length() + field.semantics().length());
            if (field.value() != null) {
                estimate += 4L * field.value().length();
            }
            for (var e : field.evidence()) {
                estimate += estimate(e);
            }
            if (estimate > maxBytes / 2 || retained + estimate > maxBytes - 32768) {
                field =
                        new Field(
                                field.semantics().length() <= 4096
                                        ? field.semantics()
                                        : "comparison:limited",
                                field.kind(),
                                ValueState.LIMITED,
                                null,
                                field.aspect(),
                                List.of(
                                        new Evidence(
                                                "comparison",
                                                "resource-policy-v1",
                                                null,
                                                "Field exceeds canonical record budget")));
                estimate = 128L + 4L * entry.getKey().length();
            }
            if (retained + estimate > maxBytes - 16384) {
                throw new ComparisonLimit("Field identities exceed record limit");
            }
            retained += (int) estimate;
            fields.put(entry.getKey(), field);
        }
        var evidence = new ArrayList<Evidence>();
        for (var e : record.evidence()) {
            long size = estimate(e);
            if (retained + size > maxBytes - 1024) {
                evidence.add(
                        new Evidence(
                                "comparison",
                                "resource-policy-v1",
                                null,
                                "Additional record evidence exceeds budget"));
                break;
            }
            retained += (int) size;
            evidence.add(e);
        }
        return new Record(record.key(), fields, evidence);
    }

    static long retainedBytes(Record record) {
        if (record == null) {
            return 0;
        }
        long size = 256L + 4L * record.key().length();
        for (var entry : record.fields().entrySet()) {
            var field = entry.getValue();
            size += 256L + 4L * entry.getKey().length() + 4L * field.semantics().length();
            if (field.value() != null) {
                size += 4L * field.value().length();
            }
            for (var evidence : field.evidence()) {
                size += estimate(evidence);
            }
        }
        for (var evidence : record.evidence()) {
            size += estimate(evidence);
        }
        return size;
    }

    private static long estimate(Evidence e) {
        return 32L
                + 4L
                        * (e.source().length()
                                + e.identity().length()
                                + (e.field() == null ? 0 : e.field().length())
                                + (e.raw() == null ? 0 : e.raw().length()));
    }

    private static void write(DataOutputStream out, Record r) throws IOException {
        out.writeBoolean(r != null);
        if (r == null) {
            return;
        }
        string(out, r.key());
        out.writeInt(r.fields().size());
        for (var e : new TreeMap<>(r.fields()).entrySet()) {
            string(out, e.getKey());
            var f = e.getValue();
            string(out, f.semantics());
            out.writeInt(f.kind().ordinal());
            out.writeInt(f.state().ordinal());
            string(out, f.value());
            out.writeInt(f.aspect().ordinal());
            evidence(out, f.evidence());
        }
        evidence(out, r.evidence());
    }

    private static Record read(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        String key = string(in);
        int n = count(in);
        Map<String, Field> fields = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String k = string(in), semantics = string(in);
            var kind = ValueKind.values()[in.readInt()];
            var state = ValueState.values()[in.readInt()];
            String value = string(in);
            var aspect = Aspect.values()[in.readInt()];
            fields.put(k, new Field(semantics, kind, state, value, aspect, evidence(in)));
        }
        return new Record(key, fields, evidence(in));
    }

    private static void evidence(DataOutputStream out, List<Evidence> evidence) throws IOException {
        out.writeInt(evidence.size());
        for (var e : evidence) {
            string(out, e.source());
            string(out, e.identity());
            string(out, e.field());
            string(out, e.raw());
        }
    }

    private static List<Evidence> evidence(DataInputStream in) throws IOException {
        var values = new ArrayList<Evidence>();
        int n = count(in);
        for (int i = 0; i < n; i++) {
            values.add(new Evidence(string(in), string(in), string(in), string(in)));
        }
        return values;
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 65536) {
            throw new IOException("Invalid staging count");
        }
        return n;
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String string(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n == -1) {
            return null;
        }
        if (n < 0 || n > 1048576) {
            throw new IOException("Invalid staging string");
        }
        byte[] b = in.readNBytes(n);
        if (b.length != n) {
            throw new IOException("Truncated staging string");
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
