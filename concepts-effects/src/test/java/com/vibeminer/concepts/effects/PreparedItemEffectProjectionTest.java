package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.FieldAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueChannel;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueOverride;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PresenceEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RowState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceFacts;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceProfile;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceRow;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceSchema;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceStamp;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetObservation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.TargetState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.generated.GeneratedTableSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedItemEffectProjectionTest {
    static SourceSchema schema(SourceKind kind, List<Integer> raw) {
        var p = kind.profile();
        var columns = EffectsWorld.columns(p, raw);
        var schema =
                EffectsWorld.schema(
                        kind.tableHash().toLowerCase(java.util.Locale.ROOT),
                        kind == SourceKind.DIRECT_EFFECT
                                ? EffectsWorld.CLASSIC_VERSION
                                : EffectsWorld.RETAIL_VERSION,
                        p.layout());
        var stamp =
                new SourceStamp(
                        kind,
                        EffectsWorld.BUILD,
                        EffectsWorld.LOCALE,
                        p.fileDataId(),
                        p.table(),
                        "c".repeat(32),
                        p.layout(),
                        "d".repeat(64),
                        "selected-block",
                        "e".repeat(32),
                        GeneratedTableSource.READER_REVISION,
                        "bounded-options",
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

    static SourceProfile profile(SourceKind kind, List<Integer> raw) {
        var compiled = PreparedItemEffectProjection.compile(schema(kind, raw));
        assertEquals(
                IdentityAdmission.ADMITTED, compiled.identity(), compiled.omissions().toString());
        return compiled.profile();
    }

    static Db2Types.Row row(long id, String... data) {
        var values = new ArrayList<String>();
        values.add(Long.toString(id));
        values.addAll(List.of(data));
        return new Db2Types.Row(id, false, null, values, List.of());
    }

    static SourceFacts facts(SourceProfile profile, Db2Types.Row row) {
        return PreparedItemEffectProjection.projectSource(
                profile,
                new SourceRow(
                        row.id(),
                        RowState.PRESENT,
                        row,
                        IssueChannel.KNOWN,
                        List.of(),
                        PresenceEvidence.unknown()));
    }

    static Db2Types.Row effect(long id) {
        return row(id, "0", "255", "-1", "-1", "0", "65535", "-42", "8", "9");
    }

    @Test
    void exactNativeProfilesAndStrictPerCellSignedDomainsAreReused() {
        for (var kind : SourceKind.values()) {
            var compiled = PreparedItemEffectProjection.compile(schema(kind, List.of()));
            assertEquals(IdentityAdmission.ADMITTED, compiled.identity());
        }
        var p = profile(SourceKind.RETAIL_EFFECT, List.of());
        var f = facts(p, effect(77));
        assertEquals(IdentityAdmission.ADMITTED, f.identity());
        assertEquals("-1", f.cells().get(3).value());
        assertEquals("-1", f.cells().get(4).value());
        assertEquals("0", f.cells().get(5).value());
        assertEquals("65535", f.cells().get(6).value());
        assertEquals("-42", f.cells().get(7).value());
        assertEquals(CellState.UNSUPPORTED, f.cells().get(9).state());
        assertNull(f.cells().get(9).value());
        assertEquals(
                FieldAdmission.PARTIAL,
                PreparedItemEffectProjection.compile(schema(SourceKind.RETAIL_EFFECT, List.of()))
                        .fields());
        var invalid = new ArrayList<>(effect(77).values());
        invalid.set(3, "32768");
        invalid.set(7, "042");
        var bad = facts(p, new Db2Types.Row(77, false, null, invalid, List.of()));
        assertEquals(CellState.INVALID, bad.cells().get(3).state());
        assertEquals(CellState.INVALID, bad.cells().get(7).state());
        assertEquals("0", bad.cells().get(5).value());
    }

    @Test
    void rawPhysicalAndUnverifiedInputsRemainEvidenceAndDoNotExcludeOtherStrictFields() {
        var strict = profile(SourceKind.RETAIL_EFFECT, List.of());
        var raw = profile(SourceKind.RETAIL_EFFECT, List.of(3, 6, 8));
        var rawFacts = facts(raw, effect(77));
        for (int index : List.of(3, 6, 8, 9)) {
            assertEquals(CellState.UNSUPPORTED, rawFacts.cells().get(index).state());
            assertNull(rawFacts.cells().get(index).value());
            assertNotNull(rawFacts.cells().get(index).rawEvidence());
        }
        var plan = PreparedItemEffectProjection.pair(strict, raw);
        assertEquals(IdentityAdmission.ADMITTED, plan.identity());
        assertEquals(
                List.of(
                        "LegacySlotIndex",
                        "TriggerType",
                        "CoolDownMSec",
                        "CategoryCoolDownMSec",
                        "SpellID"),
                plan.assessed().stream().map(b -> b.left().field()).toList());
        assertEquals(4, plan.omissions().size());
        var malformed = schema(SourceKind.RETAIL_EFFECT, List.of(4));
        assertNull(PreparedItemEffectProjection.compile(malformed).profile());
    }

    @Test
    void rowIssuesAndLimitedOverridesPreserveIndependentValidCells() {
        var p = profile(SourceKind.RETAIL_EFFECT, List.of());
        var broken =
                new Db2Types.Row(
                        77,
                        false,
                        null,
                        effect(77).values(),
                        List.of(new Db2Types.TextIssue(3, "DB2_TEXT_ENCODING_INVALID", "ff")));
        var f =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                77,
                                RowState.PRESENT,
                                broken,
                                IssueChannel.KNOWN,
                                List.of(
                                        new IssueOverride(
                                                3, CellState.LIMITED, "CAPTURE_CELL_LIMIT")),
                                PresenceEvidence.unknown()));
        assertEquals(CellState.LIMITED, f.cells().get(3).state());
        assertEquals("raw-bytes:ff", f.cells().get(3).rawEvidence());
        assertEquals("-42", f.cells().get(7).value());
        var legacy = new Db2Types.Row(77, false, null, effect(77).values(), null);
        var unknown = facts(p, legacy);
        assertEquals(IdentityAdmission.UNKNOWN, unknown.identity());
        assertEquals(CellState.UNKNOWN, unknown.cells().getFirst().state());
        assertEquals("-42", unknown.cells().get(7).value());
        var rowLimited =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                77,
                                RowState.PRESENT,
                                effect(77),
                                IssueChannel.KNOWN,
                                List.of(
                                        new IssueOverride(
                                                -1, CellState.LIMITED, "CAPTURE_ROW_LIMIT")),
                                PresenceEvidence.unknown()));
        assertEquals(IdentityAdmission.LIMITED, rowLimited.identity());
        assertEquals("-1", rowLimited.cells().get(3).value());
    }

    @Test
    void unsignedOwnCopyIdsNeverCoerceSignedParentOrReferenceCells() {
        var p = profile(SourceKind.BRIDGE, List.of());
        var copy =
                new Db2Types.Row(
                        0xffffffffL, true, 17L, List.of("4294967295", "-1", "123"), List.of());
        var link = facts(p, copy);
        assertEquals(IdentityAdmission.ADMITTED, link.identity());
        assertEquals(17L, link.copy().sourceId());
        var relation =
                PreparedItemEffectProjection.relate(
                        link,
                        new TargetObservation(
                                -1,
                                TargetState.UNRESOLVABLE_REFERENCE,
                                null,
                                PresenceEvidence.unknown()),
                        RelationMode.BRIDGE);
        assertEquals(0xffffffffL, relation.identity().ownLinkRowId());
        assertEquals(123, relation.identity().parentItemId());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PreparedItemEffectProjection.relate(
                                link,
                                new TargetObservation(
                                        0xffffffffL,
                                        TargetState.UNKNOWN,
                                        null,
                                        PresenceEvidence.unknown()),
                                RelationMode.BRIDGE));
        var badParent = facts(p, row(1, "12", "4294967295"));
        assertEquals(CellState.INVALID, badParent.cells().get(2).state());
        assertNull(
                PreparedItemEffectProjection.relate(
                                badParent,
                                new TargetObservation(
                                        12, TargetState.UNKNOWN, null, PresenceEvidence.unknown()),
                                RelationMode.BRIDGE)
                        .identity());
        var mismatch = new Db2Types.Row(55, false, null, List.of("56", "12", "123"), List.of());
        assertEquals(IdentityAdmission.INVALID, facts(p, mismatch).identity());
    }

    @Test
    void bridgeTargetsAreExplicitAndSameEndpointWhileDuplicateLinksSurvive() {
        var bridge = profile(SourceKind.BRIDGE, List.of());
        var effectProfile = profile(SourceKind.RETAIL_EFFECT, List.of());
        var observed = facts(effectProfile, effect(77));
        var target =
                new TargetObservation(
                        77, TargetState.OBSERVED, observed, PresenceEvidence.unknown());
        var a =
                PreparedItemEffectProjection.relate(
                        facts(bridge, row(2, "77", "123")), target, RelationMode.BRIDGE);
        var b =
                PreparedItemEffectProjection.relate(
                        facts(bridge, row(3, "77", "123")), target, RelationMode.BRIDGE);
        assertFalse(a.identity().equals(b.identity()));
        assertEquals(a.target(), b.target());
        var wrong =
                new TargetObservation(
                        78, TargetState.OBSERVED, observed, PresenceEvidence.unknown());
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedItemEffectProjection.relate(a.link(), wrong, RelationMode.BRIDGE));
        var old = observed.stamp();
        var otherStamp =
                new SourceStamp(
                        old.kind(),
                        new BuildRef("wow", "1".repeat(32), "2".repeat(32), "us"),
                        old.locale(),
                        old.fileDataId(),
                        old.tableName(),
                        old.contentKey(),
                        old.layoutHash(),
                        old.definitionSha256(),
                        old.blockIdentity(),
                        old.graphIdentity(),
                        old.readerRevision(),
                        old.readerOptions(),
                        old.rowEncoding(),
                        old.effectiveKeysFingerprint(),
                        old.issueEvidence());
        var other =
                new SourceFacts(
                        otherStamp,
                        77,
                        observed.state(),
                        observed.identity(),
                        observed.copy(),
                        observed.cells(),
                        observed.omissions());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PreparedItemEffectProjection.relate(
                                a.link(),
                                new TargetObservation(
                                        77,
                                        TargetState.OBSERVED,
                                        other,
                                        PresenceEvidence.unknown()),
                                RelationMode.BRIDGE));
    }

    @Test
    void directRowsSupplyOwnEffectAndCannotCrossRelationProfiles() {
        var p = profile(SourceKind.DIRECT_EFFECT, List.of());
        var raw = new ArrayList<>(effect(0xffffffffL).values());
        raw.add("123");
        var direct = facts(p, new Db2Types.Row(0xffffffffL, false, null, raw, List.of()));
        var relation =
                PreparedItemEffectProjection.relate(
                        direct,
                        new TargetObservation(
                                0xffffffffL,
                                TargetState.OBSERVED,
                                direct,
                                PresenceEvidence.unknown()),
                        RelationMode.DIRECT);
        assertEquals(0xffffffffL, relation.identity().ownLinkRowId());
        assertEquals(123, relation.identity().parentItemId());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PreparedItemEffectProjection.relate(
                                direct, relation.target(), RelationMode.BRIDGE));
        assertEquals(
                IdentityAdmission.INVALID,
                PreparedItemEffectProjection.pair(p, profile(SourceKind.RETAIL_EFFECT, List.of()))
                        .identity());
    }

    @Test
    void missingLimitedAndProtectedRowsAreNotAbsentOrInventedObservedTargets() {
        var p = profile(SourceKind.BRIDGE, List.of());
        var unknown =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                1,
                                RowState.UNKNOWN,
                                null,
                                IssueChannel.UNKNOWN,
                                List.of(),
                                PresenceEvidence.unknown()));
        assertEquals(IdentityAdmission.UNKNOWN, unknown.identity());
        var proof = new PresenceEvidence("exact-row-1", "CAPTURE_PROTECTED");
        var protectedRow =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                1,
                                RowState.PROTECTED,
                                null,
                                IssueChannel.UNKNOWN,
                                List.of(),
                                proof));
        assertEquals(RowState.PROTECTED, protectedRow.state());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SourceRow(
                                1,
                                RowState.PROTECTED,
                                null,
                                IssueChannel.UNKNOWN,
                                List.of(),
                                PresenceEvidence.unknown()));
        var current = facts(p, row(1, "77", "123"));
        assertNotNull(
                PreparedItemEffectProjection.relate(
                                current,
                                new TargetObservation(
                                        77, TargetState.UNKNOWN, null, PresenceEvidence.unknown()),
                                RelationMode.BRIDGE)
                        .identity());
    }

    @Test
    void rowShapeAndCollectionsAreBoundedBeforeProjectionAndDefensiveCopy() {
        var p = profile(SourceKind.BRIDGE, List.of());
        var shortRow = new Db2Types.Row(1, false, null, List.of("1", "77"), List.of());
        assertEquals(IdentityAdmission.INVALID, facts(p, shortRow).identity());
        var limited =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                1,
                                RowState.PRESENT,
                                shortRow,
                                IssueChannel.KNOWN,
                                List.of(new IssueOverride(2, CellState.LIMITED, "SHORT_CELL")),
                                PresenceEvidence.unknown()));
        assertEquals(CellState.LIMITED, limited.cells().get(2).state());
        assertEquals("77", limited.cells().get(1).value());
        var overrides = new ArrayList<IssueOverride>();
        var input =
                new SourceRow(
                        1,
                        RowState.PRESENT,
                        row(1, "77", "123"),
                        IssueChannel.KNOWN,
                        overrides,
                        PresenceEvidence.unknown());
        overrides.add(new IssueOverride(1, CellState.INVALID, "late-mutation"));
        assertTrue(input.overrides().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> p.bindings().clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SourceRow(
                                1,
                                RowState.PRESENT,
                                row(1, "77", "123"),
                                IssueChannel.KNOWN,
                                java.util.Collections.nCopies(
                                        1025, new IssueOverride(1, CellState.LIMITED, "LIMIT")),
                                PresenceEvidence.unknown()));
    }

    private static SourceStamp evidenceStamp(SourceStamp s, IssueEvidence evidence) {
        return new SourceStamp(
                s.kind(),
                s.build(),
                s.locale(),
                s.fileDataId(),
                s.tableName(),
                s.contentKey(),
                s.layoutHash(),
                s.definitionSha256(),
                s.blockIdentity(),
                s.graphIdentity(),
                s.readerRevision(),
                s.readerOptions(),
                s.rowEncoding(),
                s.effectiveKeysFingerprint(),
                evidence);
    }

    @Test
    void unknownReceiptCannotCertifyExplicitEmptyIssueArraysAndNativeEvidenceIsNotAValueChange() {
        var s = schema(SourceKind.BRIDGE, List.of());
        var legacyStamp = evidenceStamp(s.stamp(), new IssueEvidence("UNKNOWN", null, null, null));
        var legacyProfile =
                PreparedItemEffectProjection.compile(
                                new SourceSchema(
                                        legacyStamp,
                                        s.header(),
                                        s.schema(),
                                        s.columns(),
                                        s.state()))
                        .profile();
        var legacy = facts(legacyProfile, row(1, "77", "123"));
        assertEquals(IdentityAdmission.UNKNOWN, legacy.identity());
        assertEquals(CellState.UNKNOWN, legacy.cells().get(2).state());
        var nativeProfile = PreparedItemEffectProjection.compile(s).profile();
        assertEquals(
                IdentityAdmission.ADMITTED, facts(nativeProfile, row(1, "77", "123")).identity());
        assertEquals(
                2,
                PreparedItemEffectProjection.pair(legacyProfile, nativeProfile).assessed().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new IssueEvidence("UNKNOWN", "fake", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new IssueEvidence(
                                "VERIFIED_NATIVE",
                                "native-row-issues-v1",
                                "fake-producer",
                                "a".repeat(64)));
    }

    @Test
    void structuralProfileFailuresAreTypedAndEqualFingerprintCannotBypassBindingCompatibility() {
        var s = schema(SourceKind.BRIDGE, List.of());
        var reordered = List.of(s.columns().get(0), s.columns().get(2), s.columns().get(1));
        var header =
                new Db2Types.Header(
                        1,
                        s.header().tableHash(),
                        s.header().layoutHash(),
                        3,
                        0,
                        12,
                        reordered,
                        "WDC5");
        assertNull(
                PreparedItemEffectProjection.compile(
                                new SourceSchema(
                                        s.stamp(), header, s.schema(), reordered, s.state()))
                        .profile());
        var wrongFormat =
                new Db2Types.Header(
                        1,
                        s.header().tableHash(),
                        s.header().layoutHash(),
                        3,
                        0,
                        12,
                        s.columns(),
                        "WDC4");
        assertNull(
                PreparedItemEffectProjection.compile(
                                new SourceSchema(
                                        s.stamp(), wrongFormat, s.schema(), s.columns(), s.state()))
                        .profile());
        var good = PreparedItemEffectProjection.compile(s).profile();
        var bindings = new ArrayList<>(good.bindings());
        var a = bindings.get(1);
        bindings.set(
                1,
                new PreparedItemEffectTypes.Binding(
                        a.source(),
                        a.fileDataId(),
                        a.field(),
                        a.columnIndex(),
                        16,
                        a.unsigned(),
                        a.relation(),
                        a.evidence(),
                        a.interpretation(),
                        a.fingerprint()));
        var forged =
                new SourceProfile(
                        good.stamp(),
                        good.state(),
                        good.identityFingerprint(),
                        good.columnCount(),
                        bindings,
                        good.omissions());
        assertEquals(
                List.of("ItemID"),
                PreparedItemEffectProjection.pair(good, forged).assessed().stream()
                        .map(b -> b.left().field())
                        .toList());
    }

    @Test
    void unknownReferenceCannotAcquireInventedProtectedTargetAndParentIsPartOfLinkIdentity() {
        var p = profile(SourceKind.BRIDGE, List.of());
        var row = row(1, "77", "123");
        var limited =
                PreparedItemEffectProjection.projectSource(
                        p,
                        new SourceRow(
                                1,
                                RowState.PRESENT,
                                row,
                                IssueChannel.KNOWN,
                                List.of(new IssueOverride(1, CellState.LIMITED, "REFERENCE_LIMIT")),
                                PresenceEvidence.unknown()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PreparedItemEffectProjection.relate(
                                limited,
                                new TargetObservation(
                                        77,
                                        TargetState.PROTECTED,
                                        null,
                                        new PresenceEvidence("some-other-row", "PROTECTED")),
                                RelationMode.BRIDGE));
        assertNotNull(
                PreparedItemEffectProjection.relate(
                                limited,
                                new TargetObservation(
                                        0, TargetState.UNKNOWN, null, PresenceEvidence.unknown()),
                                RelationMode.BRIDGE)
                        .identity());
        var a =
                PreparedItemEffectProjection.relate(
                        facts(p, row),
                        new TargetObservation(
                                77, TargetState.UNKNOWN, null, PresenceEvidence.unknown()),
                        RelationMode.BRIDGE);
        var b =
                PreparedItemEffectProjection.relate(
                        facts(p, row(1, "77", "124")), a.target(), RelationMode.BRIDGE);
        assertFalse(a.identity().equals(b.identity()));
    }
}
