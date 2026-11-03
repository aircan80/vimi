package com.vibeminer.concepts.comparison.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.items.PreparedItemProjection;
import com.vibeminer.concepts.items.PreparedItemTypes.Binding;
import com.vibeminer.concepts.items.PreparedItemTypes.CellState;
import com.vibeminer.concepts.items.PreparedItemTypes.Compilation;
import com.vibeminer.concepts.items.PreparedItemTypes.CopyMetadata;
import com.vibeminer.concepts.items.PreparedItemTypes.FactId;
import com.vibeminer.concepts.items.PreparedItemTypes.FieldAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueChannel;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceEvidence;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.RowState;
import com.vibeminer.concepts.items.PreparedItemTypes.ScalarKind;
import com.vibeminer.concepts.items.PreparedItemTypes.Selection;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceCell;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceFacts;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceQualifiedKey;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceRow;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceStamp;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ItemsSnapshotCodecTest {
    @Test
    void reservesMetadataUntilOwnerClosesAndBoundsEndpointCopies() throws Exception {
        try (var budget = new TempBudget()) {
            var snapshot = new ItemsSnapshot(budget, Selection.defaults(), Cancellation.NONE);
            assertEquals(ItemsSnapshot.METADATA_BYTES, budget.snapshot().memory());
            snapshot.registerEndpoint(ItemsSnapshot.Side.A, Map.of("root", "snapshot-a"));
            assertEquals("snapshot-a", snapshot.dependencies(ItemsSnapshot.Side.A).get("root"));
            assertThrows(
                    IllegalStateException.class,
                    () -> snapshot.registerEndpoint(ItemsSnapshot.Side.A, Map.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            snapshot.registerEndpoint(
                                    ItemsSnapshot.Side.B, Map.of("bad", "x".repeat(1025))));
            snapshot.close();
            snapshot.close();
            assertEquals(0, budget.snapshot().memory());
            assertThrows(IllegalStateException.class, snapshot::metadataReservation);
        }
    }

    @Test
    void compactRoundtripPreservesUnsignedIdSentinelUnknownCopyAndEvidence() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, false)) {
            var codec = new ItemsStageCodec(snapshot);
            var ref = snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM);
            var profile = snapshot.profile(ref);
            var binding = profile.bindings().getFirst();
            var cell =
                    new SourceCell(
                            binding.key(),
                            CellState.VALUE,
                            ScalarKind.INTEGER,
                            "-1",
                            "raw",
                            4294967295L,
                            0,
                            new CopyMetadata(null, null, CellState.UNKNOWN),
                            List.of("future-code"));
            var facts =
                    new SourceFacts(
                            4294967295L,
                            profile.stamp(),
                            SourceState.PARTIAL,
                            RowState.PRESENT,
                            IssueChannel.UNKNOWN,
                            List.of(cell),
                            List.of(),
                            List.of("unknown issue channel"),
                            PresenceEvidence.unknown());
            var encoded = codec.encode(ref, facts);
            assertEquals(facts, codec.decodeSource(encoded));
            assertTrue(ItemsStageCodec.retained(encoded) <= codec.maximumSourceRetainedBytes(ref));
            assertTrue(
                    encoded.fields().keySet().stream().allMatch(key -> key.matches("[bm][0-9]+")));
            assertFalse(encoded.toString().contains(profile.stamp().graphIdentity()));
        }
    }

    @Test
    void independentQualifiedFactsAndUnknownHalfSurviveFinalMask() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, false)) {
            var codec = new ItemsStageCodec(snapshot);
            var item = row(snapshot, SourceKind.ITEM, "1");
            var sparse = row(snapshot, SourceKind.ITEM_SPARSE, "2");
            var canonical =
                    codec.projectCandidate(
                            codec.candidate(
                                    ItemsSnapshot.Side.A,
                                    codec.encode(
                                            snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM),
                                            item),
                                    codec.encode(
                                            snapshot.ref(
                                                    ItemsSnapshot.Side.A, SourceKind.ITEM_SPARSE),
                                            sparse)));
            assertEquals(2, canonical.fields().size());
            assertEquals(
                    List.of("1", "2"),
                    canonical.fields().values().stream().map(v -> v.value()).sorted().toList());
            var missingHalf =
                    codec.projectCandidate(
                            codec.candidate(
                                    ItemsSnapshot.Side.A,
                                    codec.encode(
                                            snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM),
                                            item),
                                    null));
            assertEquals(
                    1,
                    missingHalf.fields().values().stream()
                            .filter(f -> f.state() == ValueState.UNKNOWN)
                            .count());
        }
    }

    @Test
    void identityIncompatibleSourceCannotPublishOnlyObservedId() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, true)) {
            var codec = new ItemsStageCodec(snapshot);
            var item = row(snapshot, SourceKind.ITEM, "1");
            assertNull(
                    codec.projectCandidate(
                            codec.candidate(
                                    ItemsSnapshot.Side.A,
                                    codec.encode(
                                            snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM),
                                            item),
                                    null)));
            assertEquals(1, snapshot.admittedFieldCount());
        }
    }

    @Test
    void protectedCertificateSurvivesLongNonAsciiIssuesAndNoOmissionsAreInvented()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, false)) {
            var codec = new ItemsStageCodec(snapshot);
            var ref = snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM);
            var p = snapshot.profile(ref);
            var proof =
                    new PresenceEvidence(
                            PresenceKind.PROTECTION_OBSERVED,
                            "certificate-" + "界".repeat(490),
                            "protected-code");
            var facts =
                    PreparedItemProjection.projectSource(
                            p,
                            new SourceRow(
                                    7,
                                    RowState.PROTECTED,
                                    null,
                                    IssueChannel.UNKNOWN,
                                    List.of(),
                                    proof));
            var encoded = codec.encode(ref, facts);
            assertEquals(facts, codec.decodeSource(encoded));
            var finalRow =
                    codec.projectCandidate(codec.candidate(ItemsSnapshot.Side.A, encoded, null));
            assertTrue(
                    finalRow.fields().values().stream()
                            .flatMap(f -> f.evidence().stream())
                            .anyMatch(
                                    e ->
                                            proof.identity().equals(e.identity())
                                                    && proof.code().equals(e.field())));
        }
    }

    @Test
    void malformedDuplicateRegistrationDoesNotPolluteDictionary() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = new ItemsSnapshot(budget, Selection.defaults(), Cancellation.NONE)) {
            var good = profile(ItemsSnapshot.Side.A, SourceKind.ITEM, "id");
            var bad =
                    new SourceProfile(
                            good.stamp(),
                            good.state(),
                            good.selection(),
                            good.identityFingerprint(),
                            good.fieldFingerprint(),
                            1,
                            List.of(),
                            List.of(good.bindings().getFirst(), good.bindings().getFirst()),
                            List.of());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            snapshot.register(
                                    ItemsSnapshot.Side.A,
                                    bad.stamp(),
                                    bad.state(),
                                    compilation(bad)));
            var ref =
                    snapshot.register(
                            ItemsSnapshot.Side.A, good.stamp(), good.state(), compilation(good));
            assertEquals(0, snapshot.bindingIndex(ref, good.bindings().getFirst().key()));
        }
    }

    @Test
    void largeFinalProjectionIsLimitedBeforeDecodingBeyondPlannedWorkspace() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = new ItemsSnapshot(budget, Selection.defaults(), Cancellation.NONE)) {
            for (var side : ItemsSnapshot.Side.values()) {
                for (var kind : SourceKind.values()) {
                    var base = profile(side, kind, "id");
                    var bindings = new java.util.ArrayList<Binding>();
                    for (int i = 0; i < 26; i++) {
                        bindings.add(
                                new Binding(
                                        new SourceQualifiedKey(
                                                FactId.RAW_DESCRIPTION,
                                                kind,
                                                kind.fileDataId(),
                                                "Description",
                                                true,
                                                i,
                                                "text"),
                                        i,
                                        ScalarKind.TEXT,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null));
                    }
                    var p =
                            new SourceProfile(
                                    base.stamp(),
                                    base.state(),
                                    base.selection(),
                                    "id",
                                    "wide-text",
                                    26,
                                    List.of(),
                                    bindings,
                                    List.of());
                    snapshot.register(side, p.stamp(), p.state(), compilation(p));
                }
            }
            snapshot.sealPairScope();
            var codec = new ItemsStageCodec(snapshot);
            var records =
                    new java.util.ArrayList<
                            com.vibeminer.concepts.comparison.ComparisonTypes.Record>();
            for (var kind : SourceKind.values()) {
                var ref = snapshot.ref(ItemsSnapshot.Side.A, kind);
                var p = snapshot.profile(ref);
                var cells =
                        p.bindings().stream()
                                .map(
                                        b ->
                                                new SourceCell(
                                                        b.key(),
                                                        CellState.VALUE,
                                                        ScalarKind.TEXT,
                                                        "x".repeat(8192),
                                                        "raw",
                                                        7L,
                                                        b.columnIndex(),
                                                        new CopyMetadata(
                                                                null, null, CellState.UNKNOWN),
                                                        List.of()))
                                .toList();
                records.add(
                        codec.encode(
                                ref,
                                new SourceFacts(
                                        7,
                                        p.stamp(),
                                        p.state(),
                                        RowState.PRESENT,
                                        IssueChannel.KNOWN,
                                        cells,
                                        List.of(),
                                        List.of())));
            }
            var joined = codec.candidate(ItemsSnapshot.Side.A, records.get(0), records.get(1));
            assertThrows(ItemsStageCodec.Limit.class, () -> codec.projectCandidate(joined));
        }
    }

    @Test
    void maximalIssueEvidenceKeepsValidSupplementaryUnicodeAndCertificate() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, false)) {
            var codec = new ItemsStageCodec(snapshot);
            var ref = snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM);
            var p = snapshot.profile(ref);
            var b = p.bindings().getFirst();
            var issues = java.util.Collections.nCopies(8, "😀".repeat(64));
            var cell =
                    new SourceCell(
                            b.key(),
                            CellState.VALUE,
                            ScalarKind.INTEGER,
                            "-1",
                            "界".repeat(128),
                            7L,
                            0,
                            new CopyMetadata(null, null, CellState.UNKNOWN),
                            issues);
            var facts =
                    new SourceFacts(
                            7,
                            p.stamp(),
                            p.state(),
                            RowState.PRESENT,
                            IssueChannel.KNOWN,
                            List.of(cell),
                            List.of(),
                            java.util.Collections.nCopies(16, "界".repeat(128)));
            var encoded = codec.encode(ref, facts);
            assertEquals(facts, codec.decodeSource(encoded));
            var output =
                    codec.projectCandidate(codec.candidate(ItemsSnapshot.Side.A, encoded, null));
            assertTrue(
                    output.fields().values().stream()
                            .flatMap(f -> f.evidence().stream())
                            .anyMatch(e -> e.raw().contains("[truncated]")));
        }
    }

    @Test
    void unavailableGlobalCounterpartOmitsFieldsButKeepsQualifiedObservedIdentity()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot = new ItemsSnapshot(budget, Selection.defaults(), Cancellation.NONE)) {
            for (var side : ItemsSnapshot.Side.values()) {
                for (var kind : SourceKind.values()) {
                    var p = profile(side, kind, "id");
                    if (side == ItemsSnapshot.Side.B && kind == SourceKind.ITEM) {
                        snapshot.registerUnavailable(
                                side, p.stamp(), SourceState.UNAVAILABLE, "NOT_PREPARED");
                    } else {
                        snapshot.register(side, p.stamp(), p.state(), compilation(p));
                    }
                }
            }
            snapshot.sealPairScope();
            var ref = snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM);
            assertTrue(snapshot.canPublishIds(ref));
            var codec = new ItemsStageCodec(snapshot);
            var result =
                    codec.projectCandidate(
                            codec.candidate(
                                    ItemsSnapshot.Side.A,
                                    codec.encode(ref, row(snapshot, SourceKind.ITEM, "1")),
                                    null));
            assertEquals(1, result.fields().size());
            assertEquals(ValueState.UNKNOWN, result.fields().values().iterator().next().state());
            assertTrue(
                    snapshot.dependencies(ItemsSnapshot.Side.B).values().stream()
                            .anyMatch(v -> v.contains("NOT_PREPARED")));
        }
    }

    @Test
    void malformedTemporaryFieldsAndForgedIntegerValuesAreRejected() throws Exception {
        try (var budget = new TempBudget();
                var snapshot = fixture(budget, false)) {
            var codec = new ItemsStageCodec(snapshot);
            var ref = snapshot.ref(ItemsSnapshot.Side.A, SourceKind.ITEM);
            var stage = codec.encode(ref, row(snapshot, SourceKind.ITEM, "1"));
            var fields = new java.util.LinkedHashMap<>(stage.fields());
            fields.put("b255", fields.get("b0"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codec.decodeSource(
                                    new com.vibeminer.concepts.comparison.ComparisonTypes.Record(
                                            stage.key(), fields, List.of())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.encode(ref, row(snapshot, SourceKind.ITEM, "x".repeat(8192))));
        }
    }

    private static ItemsSnapshot fixture(TempBudget budget, boolean incompatible) throws Exception {
        var snapshot = new ItemsSnapshot(budget, Selection.defaults(), Cancellation.NONE);
        for (var side : ItemsSnapshot.Side.values()) {
            for (var kind : SourceKind.values()) {
                var profile =
                        profile(
                                side,
                                kind,
                                incompatible
                                                && side == ItemsSnapshot.Side.B
                                                && kind == SourceKind.ITEM
                                        ? "other-id"
                                        : "id");
                snapshot.register(side, profile.stamp(), profile.state(), compilation(profile));
            }
        }
        snapshot.sealPairScope();
        return snapshot;
    }

    private static Compilation compilation(SourceProfile p) {
        return new Compilation(
                IdentityAdmission.ADMITTED, FieldAdmission.ADMITTED, p, p.omissions());
    }

    private static SourceProfile profile(
            ItemsSnapshot.Side side, SourceKind kind, String identity) {
        var stamp =
                new SourceStamp(
                        kind,
                        new BuildRef(
                                "wowf",
                                (side == ItemsSnapshot.Side.A ? "a" : "b").repeat(32),
                                "c".repeat(32)),
                        "enUS",
                        kind.fileDataId(),
                        kind.tableName(),
                        "d".repeat(32),
                        "01234567",
                        "e".repeat(64),
                        "block",
                        "reader",
                        "options",
                        "rows",
                        "graph-" + side + kind,
                        "keys");
        var key =
                new SourceQualifiedKey(
                        FactId.REQUIRED_LEVEL,
                        kind,
                        kind.fileDataId(),
                        "RequiredLevel",
                        false,
                        -1,
                        "signed32");
        var binding = new Binding(key, 0, ScalarKind.INTEGER, 32, false, false, null, null);
        return new SourceProfile(
                stamp,
                SourceState.PARTIAL,
                Selection.defaults(),
                identity,
                "fields",
                1,
                List.of(),
                List.of(binding),
                List.of());
    }

    private static SourceFacts row(ItemsSnapshot snapshot, SourceKind kind, String value) {
        var profile = snapshot.profile(snapshot.ref(ItemsSnapshot.Side.A, kind));
        var b = profile.bindings().getFirst();
        return new SourceFacts(
                7,
                profile.stamp(),
                SourceState.PARTIAL,
                RowState.PRESENT,
                IssueChannel.KNOWN,
                List.of(
                        new SourceCell(
                                b.key(),
                                CellState.VALUE,
                                ScalarKind.INTEGER,
                                value,
                                null,
                                7L,
                                0,
                                new CopyMetadata(false, null, CellState.VALUE),
                                List.of())),
                List.of(),
                List.of());
    }
}
