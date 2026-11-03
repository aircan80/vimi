package com.vibeminer.concepts.comparison.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.Side;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Cell;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.FieldAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectsStageCodecTest {
    @Test
    void bridgeSourceRoundtripFanoutAndFinalPairedMaskRetainSourceQualifiedInputs()
            throws Exception {
        var budget = new TempBudget();
        try (var snapshot =
                new EffectsSnapshot(
                        budget,
                        EffectsFixtures.endpoint("A"),
                        EffectsFixtures.endpoint("B"),
                        Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.BRIDGE);
            var b = EffectsFixtures.register(snapshot, Side.B, SourceKind.BRIDGE);
            var effectA = EffectsFixtures.register(snapshot, Side.A, SourceKind.RETAIL_EFFECT);
            EffectsFixtures.register(snapshot, Side.B, SourceKind.RETAIL_EFFECT);
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            snapshot.selectMode(Side.B, RelationMode.BRIDGE);
            assertEquals(IdentityAdmission.ADMITTED, snapshot.sealScope().identity());
            var codec = new EffectsStageCodec(snapshot);
            var facts = EffectsFixtures.facts(snapshot, a, 0xffffffffL, "77", "123");
            var stage = codec.encodeSource(a, facts);
            assertEquals(facts, codec.decodeSource(stage));
            assertEquals("00000000004294967295", stage.key());
            var target =
                    codec.encodeSource(
                            effectA,
                            EffectsFixtures.facts(
                                    snapshot, effectA, 77, "1", "2", "-1", "4", "5", "6", "333",
                                    "8", "9"));
            var route = codec.routeBridge(stage);
            assertTrue(route.key().startsWith("1" + EffectsStageCodec.ownKey(77)));
            var candidate = codec.candidate(Side.A, route, target);
            var projected = codec.project(Side.A, candidate);
            assertEquals(
                    "B" + EffectsStageCodec.ownKey(123) + EffectsStageCodec.ownKey(0xffffffffL),
                    projected.key());
            assertEquals("333", projected.fields().get("effect.RETAIL_EFFECT.SpellID").value());
            assertEquals("-1", projected.fields().get("effect.RETAIL_EFFECT.Charges").value());
            assertFalse(projected.fields().containsKey("effect.RETAIL_EFFECT.PlayerConditionID"));
            assertEquals(
                    ComparisonTypes.Aspect.AVAILABILITY,
                    projected.fields().get("effect.target.availability").aspect());
            assertThrows(
                    IllegalArgumentException.class, () -> codec.candidate(Side.B, route, target));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> codec.encodeSource(b, facts).fields().clear());
            assertEquals(1L << 20, budget.snapshot().memory());
        }
        assertEquals(0, budget.snapshot().memory());
        budget.close();
    }

    @Test
    void referenceOnlyBridgeSurvivesUnsupportedTargetsWithoutUnknownFillerFamilies()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.BRIDGE);
            EffectsFixtures.register(snapshot, Side.B, SourceKind.BRIDGE);
            var unsupported = EffectsFixtures.compilation(SourceKind.RETAIL_EFFECT);
            snapshot.register(
                    Side.A,
                    unsupported.profile().stamp(),
                    SourceState.UNSUPPORTED,
                    new Compilation(
                            IdentityAdmission.UNKNOWN, FieldAdmission.UNSUPPORTED, null, List.of()),
                    Map.of());
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            snapshot.selectMode(Side.B, RelationMode.BRIDGE);
            assertFalse(snapshot.sealScope().targetAssessed());
            var codec = new EffectsStageCodec(snapshot);
            var routed =
                    codec.routeBridge(
                            codec.encodeSource(
                                    a, EffectsFixtures.facts(snapshot, a, 1, "-1", "123")));
            var candidate = codec.candidate(Side.A, routed, null);
            var result = codec.project(Side.A, candidate);
            assertEquals(2, result.fields().size());
            assertEquals("-1", result.fields().get("effect.BRIDGE.ItemEffectID").value());
            assertFalse(result.fields().containsKey("effect.target.availability"));
        }
    }

    @Test
    void directOwnIdAndMissingPartialTargetRemainDifferentFromAbsent() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.DIRECT_EFFECT);
            EffectsFixtures.register(snapshot, Side.B, SourceKind.DIRECT_EFFECT);
            snapshot.selectMode(Side.A, RelationMode.DIRECT);
            snapshot.selectMode(Side.B, RelationMode.DIRECT);
            snapshot.sealScope();
            var codec = new EffectsStageCodec(snapshot);
            var stage =
                    codec.encodeSource(
                            a,
                            EffectsFixtures.facts(
                                    snapshot,
                                    a,
                                    0xffffffffL,
                                    "1",
                                    "2",
                                    "-1",
                                    "4",
                                    "5",
                                    "6",
                                    "333",
                                    "8",
                                    "9",
                                    "123"));
            var result = codec.project(Side.A, codec.candidate(Side.A, stage, null));
            assertEquals(
                    "D" + EffectsStageCodec.ownKey(123) + EffectsStageCodec.ownKey(0xffffffffL),
                    result.key());
            assertEquals("333", result.fields().get("effect.DIRECT_EFFECT.SpellID").value());
            assertFalse(result.fields().containsKey("effect.DIRECT_EFFECT.ParentItemID"));
        }
    }

    @Test
    void sourceAndCandidateStagesRejectCrossSessionMetadataEvenForIdenticalBuilds()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE);
                var other =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.BRIDGE);
            var otherA = EffectsFixtures.register(other, Side.A, SourceKind.BRIDGE);
            var codec = new EffectsStageCodec(snapshot);
            var encoded = codec.encodeSource(a, EffectsFixtures.facts(snapshot, a, 1, "77", "123"));
            assertThrows(IllegalArgumentException.class, () -> other.source(a));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EffectsStageCodec(other).decodeSource(encoded));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codec.encodeSource(
                                    otherA, EffectsFixtures.facts(snapshot, a, 1, "77", "123")));
        }
    }

    @Test
    void metadataLimitIsCheckedBeforeEncodingAndUnqualifiedLinkIsOnlyPrivatelySorted()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.DIRECT_EFFECT);
            var facts =
                    EffectsFixtures.facts(
                            snapshot, a, 1, "1", "2", "3", "4", "5", "6", "7", "8", "9", "123");
            var cells = new ArrayList<Cell>();
            for (var cell : facts.cells()) {
                cells.add(
                        new Cell(
                                cell.binding(),
                                cell.state(),
                                cell.value(),
                                "z".repeat(8192),
                                cell.issues()));
            }
            var huge =
                    new SourceFacts(
                            facts.stamp(),
                            facts.rowId(),
                            facts.state(),
                            facts.identity(),
                            facts.copy(),
                            cells,
                            facts.omissions());
            var codec = new EffectsStageCodec(snapshot);
            assertTrue(codec.estimateSource(huge) > EffectsStageCodec.MAX_SOURCE_META);
            assertThrows(EffectsSnapshot.Limit.class, () -> codec.encodeSource(a, huge));
            assertEquals(1L << 20, budget.snapshot().memory());
            assertNotNull(snapshot.profile(a));
            assertNull(snapshot.mode(Side.A));
        }
    }

    @Test
    void malformedPrivateFramesCannotChangeOwnIdentitySessionOrTargetAssociation()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.BRIDGE);
            EffectsFixtures.register(snapshot, Side.B, SourceKind.BRIDGE);
            var targetA = EffectsFixtures.register(snapshot, Side.A, SourceKind.RETAIL_EFFECT);
            EffectsFixtures.register(snapshot, Side.B, SourceKind.RETAIL_EFFECT);
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            snapshot.selectMode(Side.B, RelationMode.BRIDGE);
            snapshot.sealScope();
            var codec = new EffectsStageCodec(snapshot);
            var stage = codec.encodeSource(a, EffectsFixtures.facts(snapshot, a, 1, "77", "123"));
            var field = stage.fields().get("m0");
            byte[] bytes = java.util.Base64.getDecoder().decode(field.value());
            for (byte[] bad :
                    List.of(
                            java.util.Arrays.copyOf(bytes, bytes.length - 1),
                            java.util.Arrays.copyOf(bytes, bytes.length + 1))) {
                var forged =
                        new ComparisonTypes.Record(
                                stage.key(),
                                Map.of(
                                        "m0",
                                        new ComparisonTypes.Field(
                                                field.semantics(),
                                                field.kind(),
                                                field.state(),
                                                java.util.Base64.getEncoder().encodeToString(bad),
                                                field.aspect(),
                                                List.of())),
                                List.of());
                assertThrows(IllegalArgumentException.class, () -> codec.decodeSource(forged));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codec.decodeSource(
                                    new ComparisonTypes.Record(
                                            EffectsStageCodec.ownKey(2),
                                            stage.fields(),
                                            List.of())));
            var effect =
                    codec.encodeSource(
                            targetA,
                            EffectsFixtures.facts(
                                    snapshot, targetA, 78, "1", "2", "3", "4", "5", "6", "7", "8",
                                    "9"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> codec.candidate(Side.A, codec.routeBridge(stage), effect));
            var candidate = codec.candidate(Side.A, codec.routeBridge(stage), null);
            var fields = new java.util.LinkedHashMap<>(candidate.fields());
            var old = fields.get("t");
            fields.put(
                    "t",
                    new ComparisonTypes.Field(
                            old.semantics(),
                            old.kind(),
                            old.state(),
                            "UNKNOWN:78",
                            old.aspect(),
                            List.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            codec.project(
                                    Side.A,
                                    new ComparisonTypes.Record(
                                            candidate.key(), fields, List.of())));
        }
    }

    @Test
    void registrationQuotaFailureTransfersNothingAndEvidenceDoesNotBecomePerRowContent()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var compiled = EffectsFixtures.compilation(SourceKind.BRIDGE);
            long retained = snapshot.estimatedRetainedBytes();
            assertThrows(
                    EffectsSnapshot.Limit.class,
                    () ->
                            snapshot.register(
                                    Side.A,
                                    compiled.profile().stamp(),
                                    SourceState.READABLE,
                                    compiled,
                                    Map.of("too-large", "x".repeat(32768))));
            assertEquals(retained, snapshot.estimatedRetainedBytes());
            var a = EffectsFixtures.register(snapshot, Side.A, SourceKind.BRIDGE);
            var b = EffectsFixtures.register(snapshot, Side.B, SourceKind.BRIDGE);
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            snapshot.selectMode(Side.B, RelationMode.BRIDGE);
            snapshot.sealScope();
            var codec = new EffectsStageCodec(snapshot);
            var left =
                    codec.project(
                            Side.A,
                            codec.candidate(
                                    Side.A,
                                    codec.routeBridge(
                                            codec.encodeSource(
                                                    a,
                                                    EffectsFixtures.facts(
                                                            snapshot, a, 1, "77", "123"))),
                                    null));
            var right =
                    codec.project(
                            Side.B,
                            codec.candidate(
                                    Side.B,
                                    codec.routeBridge(
                                            codec.encodeSource(
                                                    b,
                                                    EffectsFixtures.facts(
                                                            snapshot, b, 1, "77", "123"))),
                                    null));
            assertEquals(left, right);
            assertFalse(
                    snapshot.descriptor(Side.A)
                            .identity()
                            .equals(snapshot.descriptor(Side.B).identity()));
            for (int i = 0; i < 100; i++) {
                snapshot.noteOmission("OMISSION_" + i);
            }
            assertTrue(snapshot.omissionCounts().size() <= 32);
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }
}
