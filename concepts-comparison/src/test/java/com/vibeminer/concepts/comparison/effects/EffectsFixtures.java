package com.vibeminer.concepts.comparison.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.effects.PreparedItemEffectProjection;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueChannel;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PresenceEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RowState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceRow;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceSchema;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceStamp;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class EffectsFixtures {
    static final BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");

    private EffectsFixtures() {}

    static EffectsSnapshot.Endpoint endpoint(String id) {
        return new EffectsSnapshot.Endpoint(BUILD, "enUS", id, Map.of());
    }

    static SourceSchema schema(SourceKind kind) {
        var p = kind.profile();
        var selected =
                BundledDefinitions.open(Cancellation.NONE)
                        .join()
                        .require(kind.tableHash().toLowerCase(java.util.Locale.ROOT))
                        .document()
                        .selectFile(
                                kind == SourceKind.DIRECT_EFFECT ? "1.15.9.70003" : "12.1.5.70448",
                                p.layout(),
                                Cancellation.NONE);
        var schema =
                TableSchema.fromBlock(
                        selected.blocks().getFirst(),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US);
        var columns = new ArrayList<Db2Types.Column>();
        for (int i = 0; i < p.columns().size(); i++) {
            columns.add(new Db2Types.Column(p.columns().get(i), "integer", i == 0, null, null));
        }
        var stamp =
                new SourceStamp(
                        kind,
                        BUILD,
                        "enUS",
                        p.fileDataId(),
                        p.table(),
                        "c".repeat(32),
                        p.layout(),
                        "d".repeat(64),
                        "b".repeat(64),
                        "e".repeat(32),
                        GeneratedTableSource.READER_REVISION,
                        "a".repeat(64),
                        GeneratedTableSource.ROW_ENCODING,
                        "f".repeat(64),
                        new IssueEvidence(
                                "VERIFIED_NATIVE",
                                "native-row-issues-v1",
                                "java-db2-native-producer-v1",
                                "a".repeat(64)));
        return new SourceSchema(
                stamp,
                new Db2Types.Header(1, kind.tableHash(), p.layout(), 3, 0, 40, columns, "WDC5"),
                schema,
                columns,
                SourceState.READABLE);
    }

    static Compilation compilation(SourceKind kind) {
        return PreparedItemEffectProjection.compile(schema(kind));
    }

    static EffectsSnapshot.SourceRef register(
            EffectsSnapshot snapshot, EffectsSnapshot.Side side, SourceKind kind) {
        var compiled = compilation(kind);
        return snapshot.register(
                side, compiled.profile().stamp(), SourceState.READABLE, compiled, Map.of());
    }

    static SourceFacts facts(
            EffectsSnapshot snapshot, EffectsSnapshot.SourceRef ref, long id, String... values) {
        var cells = new ArrayList<String>();
        cells.add(Long.toString(id));
        cells.addAll(List.of(values));
        return PreparedItemEffectProjection.projectSource(
                snapshot.profile(ref),
                new SourceRow(
                        id,
                        RowState.PRESENT,
                        new Db2Types.Row(id, false, null, cells, List.of()),
                        IssueChannel.KNOWN,
                        List.of(),
                        PresenceEvidence.unknown()));
    }

    static com.vibeminer.formats.tables.CapturedRows capture(
            SourceKind kind, List<Db2Types.Row> rows) throws Exception {
        var input = schema(kind);
        var stamp = input.stamp();
        String content =
                digest(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring(0, 32);
        String graph =
                digest(("graph:" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring(0, 32);
        byte[] tuple =
                com.vibeminer.formats.db2.NativeIssueIdentity.tuple(
                        GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                        "native-row-issues-v1",
                        "java-db2-native-producer-v1",
                        null,
                        content,
                        stamp.blockIdentity(),
                        stamp.readerOptions(),
                        stamp.readerRevision(),
                        stamp.rowEncoding(),
                        null);
        var evidence =
                new com.vibeminer.formats.tables.CapturedRowsTypes.IssueChannelEvidence(
                        com.vibeminer.formats.tables.CapturedRowsTypes.IssueChannelState
                                .VERIFIED_NATIVE,
                        "native-row-issues-v1",
                        "java-db2-native-producer-v1",
                        graph,
                        digest(tuple),
                        stamp.blockIdentity(),
                        stamp.readerOptions(),
                        com.vibeminer.formats.db2.NativeIssueIdentity.columns(input.columns()));
        var receipt =
                new com.vibeminer.formats.tables.CapturedRowsTypes.Receipt(
                        stamp.build(),
                        stamp.locale(),
                        new com.vibeminer.formats.tables.TableTypes.Entry(
                                stamp.fileDataId(),
                                stamp.tableName(),
                                com.vibeminer.formats.tables.TableTypes.State.PARTIAL,
                                true,
                                null,
                                new com.vibeminer.formats.tables.TableTypes.Counts(
                                        rows.size() + 1,
                                        rows.size(),
                                        1,
                                        1,
                                        "WDC5",
                                        stamp.layoutHash()),
                                content),
                        input.schema(),
                        input.columns(),
                        stamp.definitionSha256(),
                        stamp.blockIdentity(),
                        graph,
                        "native-tuple-hex=" + java.util.HexFormat.of().formatHex(tuple),
                        stamp.readerOptions(),
                        stamp.readerRevision(),
                        stamp.rowEncoding(),
                        null,
                        rows.size(),
                        input.header(),
                        new Db2Types.Completion(
                                rows.size() + 1, rows.size(), 0, 1, 1, 0, "partial", 100, 0),
                        List.of(),
                        List.of(),
                        List.of(),
                        com.vibeminer.formats.tables.CapturedRowsTypes.Retention
                                .BEST_EFFORT_INVALIDATING,
                        com.vibeminer.formats.tables.CapturedRowsTypes.Membership.NOT_CERTIFIED,
                        evidence);
        return new com.vibeminer.formats.tables.CapturedRows() {
            private boolean closed;

            @Override
            public com.vibeminer.formats.tables.CapturedRowsTypes.Receipt receipt() {
                return receipt;
            }

            @Override
            public java.util.concurrent.CompletableFuture<
                            com.vibeminer.formats.tables.CapturedRowsTypes.Page>
                    readOrdinalPage(long at, int max, Cancellation token) {
                token.check();
                if (closed) {
                    throw new IllegalStateException("Authored capture already closed");
                }
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new com.vibeminer.formats.tables.CapturedRowsTypes.Page(
                                graph,
                                at,
                                com.vibeminer.formats.tables.CapturedRowsTypes.ReadState.READ,
                                List.of(
                                        new com.vibeminer.formats.tables.TableTypes.Row(
                                                at, rows.get((int) at))),
                                at + 1 == rows.size() ? null : at + 1,
                                null,
                                null));
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                closed = true;
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
