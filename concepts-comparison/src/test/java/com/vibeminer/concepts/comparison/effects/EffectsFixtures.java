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
}
