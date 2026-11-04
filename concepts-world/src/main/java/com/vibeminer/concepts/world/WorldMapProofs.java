package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.NativeIdDomainTypes;
import com.vibeminer.formats.tables.NativeIdReceiptFingerprint;
import com.vibeminer.formats.tables.TableTypes;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Shared actual-native Map qualification only; no terrain/WDT proof, cache or worker. The owning
 * source engine supplies its existing one-active guard and per-operation cancellation lifetime.
 */
final class WorldMapProofs {
    static final CapturedRowsTypes.ReadLimits READ_LIMITS =
            new CapturedRowsTypes.ReadLimits(256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10);

    enum Authority {
        COMPLETE_READY,
        OBSERVED_SELECTED_ROW
    }

    /** Detached privately minted binding; no receipt/schema/rows/source/MAID owner retained. */
    static final class Binding {
        private final long mapId, wdtId, ordinal;
        private final String identity, sourceStamp;
        private final NativeIdDomainTypes.Witness witness;

        private Binding(
                long mapId,
                long wdtId,
                String identity,
                String sourceStamp,
                long ordinal,
                NativeIdDomainTypes.Witness witness) {
            this.mapId = mapId;
            this.wdtId = wdtId;
            this.identity = identity;
            this.sourceStamp = sourceStamp;
            this.ordinal = ordinal;
            this.witness = witness;
        }

        long mapId() {
            return mapId;
        }

        long wdtId() {
            return wdtId;
        }

        String identity() {
            return identity;
        }

        String sourceStamp() {
            return sourceStamp;
        }

        long ordinal() {
            return ordinal;
        }

        NativeIdDomainTypes.Witness witness() {
            return witness;
        }

        Authority authority() {
            return witness == null ? Authority.COMPLETE_READY : Authority.OBSERVED_SELECTED_ROW;
        }
    }

    private record FileBinding(long id, String key, long length) {}

    private final BuildTables tables;
    private final BuildFiles files;
    private final BuildRef build;
    private final String locale;

    WorldMapProofs(BuildTables tables, BuildFiles files) {
        this.tables = Objects.requireNonNull(tables);
        this.files = Objects.requireNonNull(files);
        build = Objects.requireNonNull(tables.build());
        locale = tables.locale();
        profileBuild(build, locale);
        if (!Objects.equals(build, files.build()) || !Objects.equals(locale, files.locale())) {
            throw new IllegalArgumentException("Source build/locale mismatch");
        }
        WowLocale.parse(locale);
    }

    BuildRef build() {
        return build;
    }

    String locale() {
        return locale;
    }

    private record MapContext(
            CapturedRowsTypes.Receipt receipt,
            TableTypes.SchemaResult schema,
            int column,
            String layout,
            String stamp) {}

    private CapturedRows openMap(Cancellation token) {
        token.check();
        var capture = tables.capturePreparedLocal(TableTypes.Ref.forName("Map"), token).join();
        if (capture.state() == CapturedRowsTypes.CaptureState.CANCELED) {
            throw new java.util.concurrent.CancellationException();
        }
        if (capture.rows() == null) {
            if (capture.state() == CapturedRowsTypes.CaptureState.UNSUPPORTED
                    || capture.state() == CapturedRowsTypes.CaptureState.NOT_PREPARED
                    || capture.state() == CapturedRowsTypes.CaptureState.SCHEMA_UNSUPPORTED) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NATIVE_CAPTURE);
            }
            throw new IllegalStateException("Prepared native Map capture failed");
        }
        return capture.rows();
    }

    private MapContext qualifyMap(CapturedRows rows, Cancellation token) {
        token.check();
        var receipt = rows.receipt();
        requireEntry(receipt.table());
        if (!receipt.build().equals(build) || !receipt.locale().equals(locale)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
        }
        if (receipt.rowCount() > 10_000) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        if (receipt.table().state() == TableTypes.State.PARTIAL) {
            requirePartialMapProfile(receipt);
        } else if (receipt.completion().excludedEncryptedSections() != 0
                || receipt.completion().excludedPhysicalRecords() != 0
                || receipt.completion().excludedCopyRecords() != 0) {
            throw new IllegalStateException("Unqualified complete Map capture scope");
        }
        validateNative(receipt);
        var schema =
                new TableTypes.SchemaResult(receipt.table(), receipt.columns(), receipt.schema());
        int column = WorldService.column(schema, "WdtFileDataID", true);
        if (column < 0) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NUMERIC_WDT);
        }
        String layout = mapLayout(schema);
        return new MapContext(
                receipt, schema, column, layout, sourceStamp(receipt, column, layout));
    }

    boolean revalidate(Binding binding, Cancellation token) {
        try (var rows = openMap(token)) {
            var context = qualifyMap(rows, token);
            probeEof(rows, context.receipt(), token);
            if (!binding.sourceStamp().equals(context.stamp())) {
                return false;
            }
            if (context.receipt().table().state() == TableTypes.State.PARTIAL) {
                var witness = binding.witness();
                if (witness == null
                        || !witness.receiptFingerprint()
                                .equals(NativeIdReceiptFingerprint.sha256(context.receipt()))) {
                    return false;
                }
                validateBinding(
                        new FileBinding(
                                witness.sourceFileDataId(),
                                witness.sourceContentKey(),
                                witness.consumedLength()),
                        token);
            }
            return true;
        }
    }

    private static CapturedRowsTypes.Page strictPage(
            CapturedRows rows, long first, Cancellation token) {
        CapturedRowsTypes.Page page;
        try {
            page = rows.readOrdinalPage(first, 1, READ_LIMITS, token).join();
        } catch (RuntimeException failure) {
            if (WorldSourceRefusals.defaultStrictUnsupported(failure)) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.STRICT_ROWS);
            }
            throw failure;
        }
        token.check();
        switch (page.state()) {
            case CANCELED -> throw new java.util.concurrent.CancellationException();
            case LIMIT_EXCEEDED ->
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
            case CLOSED, INVALIDATED ->
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
            case CORRUPT -> throw new IllegalStateException("Corrupt native Map page");
            case READ -> {
                return page;
            }
            default -> throw new IllegalStateException("Unknown native Map read state");
        }
    }

    private static void probeEof(
            CapturedRows rows, CapturedRowsTypes.Receipt receipt, Cancellation token) {
        token.check();
        var page = strictPage(rows, receipt.rowCount(), token);
        token.check();
        if (page.state() != CapturedRowsTypes.ReadState.READ
                || !page.identity().equals(receipt.graphIdentity())
                || page.firstOrdinal() != receipt.rowCount()
                || !page.rows().isEmpty()
                || !page.cellIssues().isEmpty()
                || page.nextOrdinal() != null) {
            throw new IllegalStateException("Contradictory native Map EOF page");
        }
    }

    Binding bind(long requestedId, Cancellation token) {
        try (var rows = openMap(token)) {
            var context = qualifyMap(rows, token);
            var receipt = context.receipt();
            var schema = context.schema();
            int column = context.column();
            String layout = context.layout();
            long ordinal = 0;
            Binding found = null;
            do {
                token.check();
                var page = strictPage(rows, ordinal, token);
                if (page.state() == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
                }
                token.check();
                int expectedRows = (int) Math.min(1, receipt.rowCount() - ordinal);
                long expectedNext = ordinal + expectedRows;
                Long next = expectedNext < receipt.rowCount() ? expectedNext : null;
                if (page.state() != CapturedRowsTypes.ReadState.READ
                        || !page.identity().equals(receipt.graphIdentity())
                        || page.firstOrdinal() != ordinal
                        || page.rows().size() != expectedRows
                        || !Objects.equals(page.nextOrdinal(), next)) {
                    throw new IllegalStateException("Noncontiguous Map capture page");
                }
                for (var row : page.rows()) {
                    if (row.ordinal() != ordinal || row.ordinal() >= receipt.rowCount()) {
                        throw new IllegalStateException("Invalid Map row ordinal");
                    }
                    if (row.row().id() != requestedId) {
                        continue;
                    }
                    if (found != null) {
                        throw new IllegalStateException("Duplicate Map ID");
                    }
                    if (row.row().textIssues() == null
                            || row.row().textIssues().stream()
                                    .anyMatch(issue -> issue.columnIndex() == column)
                            || page.cellIssues().stream()
                                    .anyMatch(
                                            issue ->
                                                    issue.columnIndex() < 0
                                                            || issue.columnIndex() == column)) {
                        throw new IllegalStateException("Unqualified Map numeric issue channel");
                    }
                    Long wdt = WorldService.number(schema, row.row(), "WdtFileDataID");
                    if (wdt == null || wdt <= 0 || wdt > 0xffff_ffffL) {
                        throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NUMERIC_WDT);
                    }
                    String binding =
                            receipt.table().fileDataId()
                                    + ":"
                                    + receipt.table().contentKey()
                                    + ":"
                                    + layout
                                    + ":"
                                    + receipt.graphIdentity()
                                    + ":"
                                    + receipt.definitionSha256()
                                    + ":"
                                    + receipt.blockIdentity()
                                    + ":"
                                    + receipt.issueChannelEvidence().sourceIdentitySha256()
                                    + ":"
                                    + row.ordinal()
                                    + ":"
                                    + requestedId
                                    + ":"
                                    + column
                                    + ":"
                                    + wdt
                                    + ":"
                                    + context.stamp();
                    found =
                            new Binding(
                                    requestedId,
                                    wdt,
                                    sha(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                    context.stamp(),
                                    row.ordinal(),
                                    null);
                }
                if (page.nextOrdinal() == null) {
                    if (ordinal + page.rows().size() != receipt.rowCount()) {
                        throw new IllegalStateException("Truncated Map capture");
                    }
                    break;
                }
                ordinal = page.nextOrdinal();
            } while (true);
            if (found == null) {
                throw new IllegalStateException("Unknown Map ID");
            }
            if (receipt.table().state() == TableTypes.State.PARTIAL) {
                var inspected =
                        rows.inspectNativeIdDomain(
                                        requestedId,
                                        NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE,
                                        token)
                                .join();
                token.check();
                if (inspected.state() == NativeIdDomainTypes.State.BUSY) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.ID_INSPECTION);
                }
                if (inspected.state() == NativeIdDomainTypes.State.CANCELED) {
                    throw new java.util.concurrent.CancellationException();
                }
                if (inspected.state() == NativeIdDomainTypes.State.SOURCE_CHANGED) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
                }
                if (inspected.state() == NativeIdDomainTypes.State.UNSUPPORTED_DOMAIN
                        || inspected.state() == NativeIdDomainTypes.State.UNAVAILABLE
                        || inspected.state() == NativeIdDomainTypes.State.LIMIT_EXCEEDED) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
                }
                var witness = inspected.witness();
                if (inspected.state() != NativeIdDomainTypes.State.READABLE_UNIQUE
                        || witness == null
                        || witness.selectedId() != requestedId
                        || witness.readableOrdinal() != found.ordinal()
                        || witness.sourceFileDataId() != receipt.table().fileDataId()
                        || !witness.sourceContentKey().equals(receipt.table().contentKey())
                        || witness.consumedLength() != receipt.completion().consumedBytes()
                        || !witness.layoutHash().equals(receipt.header().layoutHash())
                        || witness.excludedCount() != receipt.completion().excludedPhysicalRecords()
                        || !NativeIdDomainTypes.REVISION.equals(witness.profileRevision())
                        || !witness.receiptFingerprint()
                                .equals(NativeIdReceiptFingerprint.sha256(receipt))) {
                    throw new IllegalStateException("Contradictory selected-ID witness");
                }
                validateBinding(
                        new FileBinding(
                                witness.sourceFileDataId(),
                                witness.sourceContentKey(),
                                witness.consumedLength()),
                        token);
                String identity =
                        sha(
                                (found.identity()
                                                + ":OBSERVED_SELECTED_ROW:"
                                                + witness.evidenceDigest())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                found =
                        new Binding(
                                found.mapId(),
                                found.wdtId(),
                                identity,
                                found.sourceStamp(),
                                found.ordinal(),
                                witness);
            }
            probeEof(rows, receipt, token);
            return found;
        }
    }

    private static void requirePartialMapProfile(CapturedRowsTypes.Receipt r) {
        var c = r.completion();
        var counts = r.table().counts();
        if (c.excludedEncryptedSections() < 1
                || c.excludedPhysicalRecords() < 1
                || !"unencryptedSections".equals(c.scope())) {
            throw new IllegalStateException("PARTIAL Map state lacks an encrypted-section domain");
        }
        if (!List.of("WDC4", "WDC5").contains(r.header().format())
                || r.rowCount() < 1
                || r.rowCount() > 256
                || r.header().declaredRecords() > 256
                || r.header().declaredCopyRecords() != 0
                || c.copyRows() != 0
                || c.excludedCopyRecords() != 0
                || c.excludedEncryptedSections() < 1
                || c.excludedEncryptedSections() > 16
                || c.excludedPhysicalRecords() < 1
                || !"unencryptedSections".equals(c.scope())
                || c.physicalRows() != r.rowCount()
                || c.declaredRecords() != c.physicalRows() + c.excludedPhysicalRecords()
                || c.declaredRecords() != r.header().declaredRecords()
                || r.sections().size() > 16
                || counts == null
                || counts.declaredRows() != c.declaredRecords()
                || counts.readableRows() != r.rowCount()
                || counts.excludedSections() != c.excludedEncryptedSections()
                || counts.excludedRecords() != c.excludedPhysicalRecords()) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        long declared = 0, excluded = 0;
        int hidden = 0;
        for (var section : r.sections()) {
            if (section.copyCount() != 0
                    || section.protectedCopyCount() != 0
                    || section.unresolvedCopyCount() != 0) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
            }
            declared += section.recordCount();
            if (section.isEncrypted() && !section.isDecrypted()) {
                hidden++;
                excluded += section.recordCount();
            }
        }
        if (declared != c.declaredRecords()
                || hidden != c.excludedEncryptedSections()
                || excluded != c.excludedPhysicalRecords()) {
            throw new IllegalStateException("Partial Map section domain differs");
        }
    }

    private static void validateNative(CapturedRowsTypes.Receipt r) {
        profileMetadata(r);
        var e = r.issueChannelEvidence();
        if (e.state() != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NATIVE_ISSUES);
        }
        if (!e.graphIdentity().equals(r.graphIdentity())
                || !e.resolvedBlockSha256().equals(r.blockIdentity())
                || !e.readerOptionsSha256().equals(r.readerOptions())
                || !e.actualColumnsSha256().equals(NativeIssueIdentity.columns(r.columns()))) {
            throw new IllegalStateException("Map capture lacks matching native issue proof");
        }
        for (var locale : new String[] {null, r.locale()}) {
            for (var encoding : new String[] {null, r.encodingScope()}) {
                byte[] tuple =
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
                if (tuple.length > 4096) {
                    throw new IllegalStateException("Map capture identity exceeds bound");
                }
                if (r.sourceIdentity().equals("native-tuple-hex=" + HexFormat.of().formatHex(tuple))
                        && e.sourceIdentitySha256().equals(sha(tuple))) {
                    return;
                }
            }
        }
        throw new IllegalStateException("Map native issue identity differs");
    }

    static void profileBuild(BuildRef build, String locale) {
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
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        long bytes = 1024;
        bytes +=
                profileString(r.header().tableHash(), 16, false)
                        + profileString(r.header().layoutHash(), 16, false)
                        + profileString(r.header().format(), 16, false)
                        + profileString(r.completion().scope(), 64, false);
        if (r.table().counts() == null) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
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
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
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
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
            }
        }
        if (bytes > 64 << 10) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        if (!r.header().columns().equals(r.columns())) {
            throw new IllegalStateException("Map header columns differ");
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
            tuple += 5 + profileString(value, 256, true);
        }
        if (tuple > 4096) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
    }

    private static long profileString(String value, int maximumChars, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return 0;
            }
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        if (value.length() > maximumChars) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_PROFILE);
        }
        return 16L + 4L * value.length();
    }

    // Canonical v1 stamp streams into SHA-256; no full-schema serialization buffer is retained.
    private static String sourceStamp(CapturedRowsTypes.Receipt r, int column, String layout) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var out =
                    new DataOutputStream(
                            new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                out.writeUTF("vimi-world-map-source-stamp-v1");
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
                out.writeInt(column);
                text(out, layout);
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
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
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

    private static String mapLayout(TableTypes.SchemaResult schema) {
        if (schema.table().counts() == null
                || schema.table().counts().layoutHash() == null
                || !schema.table().counts().layoutHash().matches("[0-9a-fA-F]{8}")
                || !schema.schema().layoutHashes().contains(schema.table().counts().layoutHash())) {
            throw new IllegalStateException("Unbound Map layout");
        }
        var field =
                schema.schema().columns().stream()
                        .filter(c -> c.name().equals("WdtFileDataID"))
                        .toList();
        if (field.isEmpty()) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NUMERIC_WDT);
        }
        if (field.size() != 1) {
            throw new IllegalStateException("Ambiguous Map WDT field");
        }
        var column = field.getFirst();
        return schema.table().counts().layoutHash()
                + ":"
                + column.kind()
                + ":"
                + column.bits()
                + ":"
                + column.unsigned()
                + ":"
                + column.verifiedName()
                + ":"
                + column.arrayLength();
    }

    private static void requireEntry(TableTypes.Entry entry) {
        if (entry == null
                || !entry.checked()
                || entry.state() != TableTypes.State.READY
                        && entry.state() != TableTypes.State.PARTIAL
                || !"Map".equals(entry.name())
                || entry.contentKey() == null
                || !entry.contentKey().matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unqualified Map source");
        }
    }

    private void validateBinding(FileBinding binding, Cancellation token) {
        token.check();
        var lookup = files.stat(FileTypes.FileRef.id(binding.id()), token).join();
        token.check();
        var fresh = lookup.file();
        if (lookup.state() != FileTypes.FileLookupState.FOUND
                || fresh == null
                || fresh.fileDataId() != binding.id()
                || !binding.key().equals(fresh.contentKey())
                || fresh.size() != null && fresh.size() != binding.length()) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
