package com.vibeminer.concepts.locations;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.util.HexFormat;
import java.util.List;

/** Prehash admission of detached captured metadata; no claim of source cryptographic authority. */
final class MapCaptureIdentity {
    static final int MAX_CANONICAL_BYTES = 64 << 10;
    private long bytes = 2048;

    private MapCaptureIdentity() {}

    static void validate(CapturedRowsTypes.Receipt r) {
        var e = r.issueChannelEvidence();
        if (e.state() != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
            fail("Native evidence unavailable");
        }
        var profile = new MapCaptureIdentity();
        profile.metadata(r);
        if (!e.graphIdentity().equals(r.graphIdentity())
                || !e.resolvedBlockSha256().equals(r.blockIdentity())
                || !e.readerOptionsSha256().equals(r.readerOptions())
                || !e.actualColumnsSha256().equals(NativeIssueIdentity.columns(r.columns()))) {
            fail("Native certificate does not match captured stream");
        }
        // Every tuple scalar and its aggregate bound have been checked BEFORE helper allocation.
        for (var locale : new String[] {null, r.locale()}) {
            for (var encoding : new String[] {null, r.encodingScope()}) {
                var tuple =
                        NativeIssueIdentity.tuple(
                                GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                                e.policyId(),
                                e.producerId(),
                                locale,
                                r.table().contentKey(),
                                r.blockIdentity(),
                                r.readerOptions(),
                                r.readerRevision(),
                                r.rowEncoding(),
                                encoding);
                var hex = HexFormat.of().formatHex(tuple);
                if (r.sourceIdentity().equals("native-tuple-hex=" + hex)
                        && e.sourceIdentitySha256().equals(sha(tuple))) {
                    return;
                }
            }
        }
        fail("Native source tuple differs");
    }

    private void metadata(CapturedRowsTypes.Receipt r) {
        shape(r.columns().size(), 128);
        shape(r.header().columns().size(), 128);
        shape(r.schema().columns().size(), 128);
        shape(r.schema().layoutHashes().size(), 32);
        shape(r.sections().size(), 128);
        shape(r.holeKeys().size(), 128);
        shape(r.effectiveKeys().size(), 128);
        text(r.build().product(), 32, false);
        text(r.build().buildConfig(), 32, false);
        text(r.build().cdnConfig(), 32, false);
        text(r.build().region(), 32, true);
        text(r.locale(), 16, false);
        text(r.table().name(), 64, false);
        hex(r.table().contentKey(), 32);
        if (r.table().counts() == null) {
            fail("Missing counts");
        }
        text(r.table().counts().format(), 16, false);
        hex(r.table().counts().layoutHash(), 8);
        var reason = r.table().reason();
        if (reason != null) {
            text(reason.code(), 128, true);
            text(reason.message(), 1024, true);
            shape(reason.keyNames().size(), 128);
            for (var key : reason.keyNames()) {
                text(key, 64, false);
            }
        }
        hex(r.definitionSha256(), 64);
        hex(r.blockIdentity(), 64);
        hex(r.graphIdentity(), 32);
        hex(r.readerOptions(), 64);
        text(r.sourceIdentity(), 8256, false);
        text(r.readerRevision(), 128, false);
        text(r.rowEncoding(), 64, false);
        text(r.encodingScope(), 256, true);
        text(r.header().tableHash(), 16, false);
        text(r.header().layoutHash(), 16, false);
        text(r.header().format(), 16, false);
        text(r.completion().scope(), 64, false);
        text(r.schema().comment(), 1024, true);
        for (var layout : r.schema().layoutHashes()) {
            hex(layout, 8);
        }
        columns(r.columns());
        columns(r.header().columns());
        for (var c : r.schema().columns()) {
            add(128);
            text(c.name(), 128, false);
            text(c.columnComment(), 256, true);
            text(c.fieldComment(), 256, true);
            if (c.foreignKey() != null) {
                text(c.foreignKey().table(), 128, false);
                text(c.foreignKey().column(), 128, false);
            }
        }
        for (var section : r.sections()) {
            add(128);
            text(section.key(), 64, false);
        }
        for (var key : r.holeKeys()) {
            add(32);
            text(key, 64, false);
        }
        for (var key : r.effectiveKeys()) {
            add(64);
            text(key.name(), 64, false);
            text(key.fingerprint(), 64, true);
        }
        if (!r.header().columns().equals(r.columns())) {
            fail("Header columns differ");
        }
        var e = r.issueChannelEvidence();
        long tuple = 64;
        for (var value :
                new String[] {
                    GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                    e.policyId(),
                    e.producerId(),
                    r.locale(),
                    r.table().contentKey(),
                    r.blockIdentity(),
                    r.readerOptions(),
                    r.readerRevision(),
                    r.rowEncoding(),
                    r.encodingScope()
                }) {
            // Policy/producer IDs are constructor-qualified literals; nevertheless bound them
            // first.
            scalar(value, 256, true);
            tuple += value == null ? 1 : 5L + 4L * value.length();
        }
        if (tuple > 4096) {
            fail("Native tuple exceeds bound");
        }
    }

    private void columns(List<Db2Types.Column> columns) {
        for (var c : columns) {
            add(128);
            text(c.name(), 128, false);
            text(c.kind(), 32, false);
            if (c.interpretation() != null) {
                add(64);
                text(c.interpretation().policy(), 128, false);
                text(c.interpretation().warning(), 256, false);
            }
        }
    }

    private void text(String value, int maximum, boolean nullable) {
        scalar(value, maximum, nullable);
        add(value == null ? 1 : 16L + 4L * value.length());
    }

    private void hex(String value, int length) {
        scalar(value, length, false);
        if (value.length() != length) {
            fail("Identity length");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                fail("Identity encoding");
            }
        }
        add(16L + value.length());
    }

    private static void scalar(String value, int maximum, boolean nullable) {
        if (value == null) {
            if (!nullable) {
                fail("Missing scalar");
            }
            return;
        }
        if (value.length() > maximum) {
            fail("Oversized scalar");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    fail("Invalid UTF16 scalar");
                }
            } else if (Character.isLowSurrogate(c)) {
                fail("Invalid UTF16 scalar");
            }
        }
    }

    private void add(long amount) {
        if (bytes > MAX_CANONICAL_BYTES - amount) {
            fail("Metadata aggregate bound");
        }
        bytes += amount;
    }

    private static void shape(int size, int maximum) {
        if (size < 0 || size > maximum) {
            fail("Metadata shape bound");
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void fail(String message) {
        throw new UnsupportedOperationException(message);
    }
}
