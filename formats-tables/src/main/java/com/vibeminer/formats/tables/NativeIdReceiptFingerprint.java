package com.vibeminer.formats.tables;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.generated.GeneratedTableSource;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded source-receipt fingerprint for selected-ID witnesses; no membership inference. */
public final class NativeIdReceiptFingerprint {
    private NativeIdReceiptFingerprint() {}

    private static void profileBuild(BuildRef build, String locale) {
        profileString(build.product(), 32, false);
        profileString(build.buildConfig(), 32, false);
        profileString(build.cdnConfig(), 32, false);
        profileString(build.region(), 32, true);
        profileString(locale, 16, false);
    }

    // Bound all variable inputs BEFORE allocating canonical identity encodings or tuple scratch.
    private static void profileMetadata(CapturedRowsTypes.Receipt r) {
        profileBuild(r.build(), r.locale());
        if (r.columns().size() > 128
                || r.schema().columns().size() > 128
                || r.schema().layoutHashes().size() > 32
                || r.header().columns().size() > 128
                || r.sections().size() > 128
                || r.holeKeys().size() > 128
                || r.effectiveKeys().size() > 128) {
            throw new UnsupportedOperationException("Map metadata shape exceeds hot profile");
        }
        long bytes =
                1024
                        + profileString(r.table().name(), 128, true)
                        + profileString(r.table().contentKey(), 32, false);
        bytes +=
                profileString(r.header().tableHash(), 16, false)
                        + profileString(r.header().layoutHash(), 16, false)
                        + profileString(r.header().format(), 16, false)
                        + profileString(r.completion().scope(), 64, false);
        if (r.table().counts() == null) {
            throw new UnsupportedOperationException("Missing Map counts");
        }
        bytes +=
                profileString(r.table().counts().format(), 16, false)
                        + profileString(r.table().counts().layoutHash(), 16, false);
        for (var section : r.sections()) {
            bytes += 64 + profileString(section.key(), 64, false);
        }
        for (var key : r.holeKeys()) {
            bytes += 32 + profileString(key, 64, false);
        }
        for (var key : r.effectiveKeys()) {
            bytes +=
                    64
                            + profileString(key.name(), 64, false)
                            + profileString(key.fingerprint(), 64, true);
        }

        bytes += profileString(r.sourceIdentity(), 8256, false);
        bytes += profileString(r.definitionSha256(), 64, false);
        bytes += profileString(r.graphIdentity(), 32, false);
        bytes += profileString(r.blockIdentity(), 64, false);
        bytes += profileString(r.readerOptions(), 64, false);
        bytes += profileString(r.readerRevision(), 128, false);
        bytes += profileString(r.rowEncoding(), 64, false);
        bytes += profileString(r.encodingScope(), 256, true);
        bytes += profileString(r.schema().comment(), 1024, true);
        for (var layout : r.schema().layoutHashes()) {
            bytes += profileString(layout, 8, false);
        }
        for (var column : r.columns()) {
            bytes +=
                    64
                            + profileString(column.name(), 128, false)
                            + profileString(column.kind(), 32, false);
            if (column.interpretation() != null) {
                bytes +=
                        64
                                + profileString(column.interpretation().policy(), 128, false)
                                + profileString(column.interpretation().warning(), 256, false);
            }
            if (bytes > 64 << 10) {
                throw new UnsupportedOperationException("Map metadata exceeds hot profile");
            }
        }
        for (var column : r.schema().columns()) {
            bytes +=
                    128
                            + profileString(column.name(), 128, false)
                            + profileString(column.columnComment(), 256, true)
                            + profileString(column.fieldComment(), 256, true);
            if (column.foreignKey() != null) {
                bytes +=
                        profileString(column.foreignKey().table(), 128, false)
                                + profileString(column.foreignKey().column(), 128, false);
            }
            if (bytes > 64 << 10) {
                throw new UnsupportedOperationException("Map metadata exceeds hot profile");
            }
        }
        if (bytes > 64 << 10) {
            throw new UnsupportedOperationException("Map metadata exceeds hot profile");
        }
        if (!r.header().columns().equals(r.columns())) {
            throw new IllegalStateException("Map header columns differ");
        }
        var e = r.issueChannelEvidence();
        bytes +=
                profileString(e.policyId(), 128, true)
                        + profileString(e.producerId(), 128, true)
                        + profileString(e.graphIdentity(), 32, true)
                        + profileString(e.sourceIdentitySha256(), 64, true)
                        + profileString(e.resolvedBlockSha256(), 64, true)
                        + profileString(e.readerOptionsSha256(), 64, true)
                        + profileString(e.actualColumnsSha256(), 64, true);
        if (bytes > 64 << 10) {
            throw new UnsupportedOperationException("Native ID receipt metadata exceeds profile");
        }
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
            tuple += 5 + profileString(value, 256, true);
        }
        if (tuple > 4096) {
            throw new UnsupportedOperationException("Map source tuple exceeds hot profile");
        }
    }

    private static long profileString(String value, int maximumChars, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return 0;
            }
            throw new UnsupportedOperationException("Missing hot source metadata");
        }
        if (value.length() > maximumChars) {
            throw new UnsupportedOperationException("Hot source metadata scalar exceeds profile");
        }
        return 16L + 4L * value.length();
    }

    // Canonical v1 stamp streams into SHA-256; no full-schema serialization buffer is retained.
    public static String sha256(CapturedRowsTypes.Receipt r) {
        validateStampInputs(r);
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var out =
                    new DataOutputStream(
                            new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                writeStamp(out, r);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Bounded descriptive identity only; not a native certificate or membership capability. */
    public static final class CanonicalStamp {
        private final byte[] bytes;

        private CanonicalStamp(byte[] bytes) {
            this.bytes = bytes;
        }

        public int encodedBytes() {
            return bytes.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CanonicalStamp stamp
                    && java.util.Arrays.equals(bytes, stamp.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }
    }

    /** Exact detached equality without retaining a receipt/schema graph or exposing its bytes. */
    public static CanonicalStamp canonicalStamp(CapturedRowsTypes.Receipt receipt) {
        validateStampInputs(receipt);
        var bounded = new StampOutput();
        try (var out = new DataOutputStream(bounded)) {
            writeStamp(out, receipt);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return new CanonicalStamp(bounded.take());
    }

    private static final class StampOutput extends OutputStream {
        private static final int MAX = 128 << 10;
        private byte[] bytes = new byte[512];
        private int size;

        private void reserve(int count) {
            if (count > MAX - size) {
                throw new UnsupportedOperationException("Native receipt stamp exceeds bound");
            }
            int needed = size + count;
            if (needed > bytes.length) {
                bytes =
                        java.util.Arrays.copyOf(
                                bytes, Math.min(MAX, Math.max(needed, bytes.length * 2)));
            }
        }

        @Override
        public void write(int value) {
            reserve(1);
            bytes[size++] = (byte) value;
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, value.length);
            reserve(length);
            System.arraycopy(value, offset, bytes, size, length);
            size += length;
        }

        byte[] take() {
            var result = java.util.Arrays.copyOf(bytes, size);
            bytes = new byte[0];
            size = 0;
            return result;
        }
    }

    private static void validateStampInputs(CapturedRowsTypes.Receipt receipt) {
        profileMetadata(receipt);
        if (!NativeIssueIdentity.columns(receipt.columns())
                .equals(receipt.issueChannelEvidence().actualColumnsSha256())) {
            throw new IllegalStateException("Captured native columns differ");
        }
    }

    private static void writeStamp(DataOutputStream out, CapturedRowsTypes.Receipt r)
            throws IOException {
        out.writeUTF("vimi-native-id-capture-receipt-v1");
        var build = r.build();
        for (var value :
                new String[] {
                    build.product(),
                    build.buildConfig(),
                    build.cdnConfig(),
                    build.region(),
                    r.locale()
                }) {
            text(out, value);
        }
        var table = r.table();
        out.writeLong(table.fileDataId());
        text(out, table.name());
        out.writeBoolean(table.checked());
        text(out, table.state().name());
        text(out, table.contentKey());
        var counts = table.counts();
        out.writeLong(counts.declaredRows());
        out.writeLong(counts.readableRows());
        out.writeInt(counts.excludedSections());
        out.writeLong(counts.excludedRecords());
        text(out, counts.format());
        text(out, counts.layoutHash());
        for (var value :
                new String[] {
                    r.definitionSha256(),
                    r.blockIdentity(),
                    r.graphIdentity(),
                    r.sourceIdentity(),
                    r.readerOptions(),
                    r.readerRevision(),
                    r.rowEncoding(),
                    r.encodingScope(),
                    r.retention().name(),
                    r.membership().name()
                }) {
            text(out, value);
        }
        var schema = r.schema();
        text(out, schema.match().name());
        text(out, schema.locale().name());
        text(out, schema.comment());
        out.writeInt(schema.layoutHashes().size());
        for (var value : schema.layoutHashes()) {
            text(out, value);
        }
        out.writeInt(schema.columns().size());
        for (var field : schema.columns()) {
            text(out, field.name());
            text(out, field.kind() == null ? null : field.kind().name());
            number(out, field.bits());
            bool(out, field.unsigned());
            out.writeInt(field.arrayLength());
            out.writeBoolean(field.foreignKey() != null);
            if (field.foreignKey() != null) {
                text(out, field.foreignKey().table());
                text(out, field.foreignKey().column());
            }
            text(out, field.id() == null ? null : field.id().name());
            text(out, field.relation() == null ? null : field.relation().name());
            out.writeBoolean(field.verifiedName());
            text(out, field.locale() == null ? null : field.locale().name());
            text(out, field.columnComment());
            text(out, field.fieldComment());
        }
        var e = r.issueChannelEvidence();
        for (var value :
                new String[] {
                    e.state().name(),
                    e.policyId(),
                    e.producerId(),
                    e.graphIdentity(),
                    e.sourceIdentitySha256(),
                    e.resolvedBlockSha256(),
                    e.readerOptionsSha256(),
                    e.actualColumnsSha256()
                }) {
            text(out, value);
        }
        var header = r.header();
        out.writeLong(header.schemaVersion());
        text(out, header.tableHash());
        text(out, header.layoutHash());
        out.writeLong(header.declaredRecords());
        out.writeLong(header.declaredCopyRecords());
        out.writeInt(header.recordSize());
        text(out, header.format());
        // Header/receipt columns equality and native actual-columns digest were verified
        // first.
        text(out, e.actualColumnsSha256());
        out.writeLong(r.rowCount());
        var completion = r.completion();
        out.writeLong(completion.declaredRecords());
        out.writeLong(completion.physicalRows());
        out.writeLong(completion.copyRows());
        out.writeInt(completion.excludedEncryptedSections());
        out.writeLong(completion.excludedPhysicalRecords());
        out.writeLong(completion.excludedCopyRecords());
        text(out, completion.scope());
        out.writeLong(completion.consumedBytes());
        out.writeLong(completion.textIssueCount());
        out.writeInt(r.sections().size());
        for (var section : r.sections()) {
            out.writeInt(section.index());
            out.writeInt(section.recordCount());
            out.writeInt(section.copyCount());
            text(out, section.key());
            out.writeBoolean(section.isEncrypted());
            out.writeInt(section.protectedCopyCount());
            out.writeInt(section.unresolvedCopyCount());
            out.writeBoolean(section.isDecrypted());
        }
        out.writeInt(r.holeKeys().size());
        for (var key : r.holeKeys()) {
            text(out, key);
        }
        out.writeInt(r.effectiveKeys().size());
        for (var key : r.effectiveKeys()) {
            text(out, key.name());
            text(out, key.fingerprint());
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUTF(value);
        }
    }

    private static void number(DataOutputStream out, Integer value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value);
        }
    }

    private static void bool(DataOutputStream out, Boolean value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeBoolean(value);
        }
    }
}
