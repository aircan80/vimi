package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.concepts.items.PreparedItemTypes.Agreement;
import com.vibeminer.concepts.items.PreparedItemTypes.CellState;
import com.vibeminer.concepts.items.PreparedItemTypes.FactFamily;
import com.vibeminer.concepts.items.PreparedItemTypes.FactId;
import com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueChannel;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueOverride;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceEvidence;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.RowState;
import com.vibeminer.concepts.items.PreparedItemTypes.Selection;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceRow;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceSchema;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceStamp;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PreparedItemProjectionTest {
    private static final Selection DEFAULT = Selection.defaults();

    private static TableSchema.SchemaColumn integer(
            String name, int bits, boolean unsigned, int length) {
        return new TableSchema.SchemaColumn(
                name,
                TableSchema.ColumnKind.INT,
                bits,
                unsigned,
                length,
                null,
                name.equals("ID") ? TableSchema.KeyStorage.NONINLINE : TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                null,
                null,
                null);
    }

    private static TableSchema.SchemaColumn text(String name) {
        return new TableSchema.SchemaColumn(
                name,
                TableSchema.ColumnKind.LOCSTRING,
                null,
                null,
                1,
                null,
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                WowLocale.parse("enUS"),
                null,
                null);
    }

    private static SourceStamp stamp(SourceKind kind, String content, String layout) {
        return new SourceStamp(
                kind,
                ItemsWorld.BUILD,
                "enUS",
                kind.fileDataId(),
                kind.tableName(),
                content,
                layout,
                "d".repeat(64),
                "e".repeat(64),
                GeneratedTableSource.READER_REVISION,
                "f".repeat(64),
                GeneratedTableSource.ROW_ENCODING,
                "a".repeat(32),
                "b".repeat(64));
    }

    private static SourceSchema schema(SourceKind kind, List<TableSchema.SchemaColumn> fields) {
        return schema(kind, fields, DEFAULT, "c".repeat(32), "01020304", null);
    }

    private static SourceSchema schema(
            SourceKind kind,
            List<TableSchema.SchemaColumn> fields,
            Selection selection,
            String content,
            String layout,
            String comment) {
        var columns = new ArrayList<Db2Types.Column>();
        for (var field : fields) {
            for (int slot = 0; slot < field.arrayLength(); slot++) {
                columns.add(
                        new Db2Types.Column(
                                field.arrayLength() == 1
                                        ? field.name()
                                        : field.name() + "[" + slot + "]",
                                field.kind() == TableSchema.ColumnKind.INT ? "integer" : "string",
                                field.id() != TableSchema.KeyStorage.NONE,
                                null,
                                null));
            }
        }
        var s =
                new TableSchema(
                        fields,
                        List.of(layout),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.parse("enUS"),
                        comment);
        var header = new Db2Types.Header(5, kind.tableHash(), layout, 1, 0, 16, columns, "WDC5");
        return new SourceSchema(
                stamp(kind, content, layout), header, s, columns, SourceState.READABLE, selection);
    }

    private static SourceProfile profile(SourceSchema input) {
        var result = PreparedItemProjection.compile(input);
        assertEquals(IdentityAdmission.ADMITTED, result.identity());
        return result.profile();
    }

    private static SourceRow row(long id, String... values) {
        return row(id, List.of(values), List.of(), IssueChannel.KNOWN, List.of());
    }

    private static SourceRow row(
            long id,
            List<String> values,
            List<Db2Types.TextIssue> issues,
            IssueChannel channel,
            List<IssueOverride> overrides) {
        return new SourceRow(
                id,
                RowState.PRESENT,
                new Db2Types.Row(id, false, null, values, issues),
                channel,
                overrides,
                PresenceEvidence.unknown());
    }

    private static PreparedItemTypes.SourceCell cell(
            PreparedItemTypes.SourceFacts facts, FactId fact) {
        return facts.cells().stream()
                .filter(value -> value.key().fact() == fact)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void sharedFactsAndOrdinaryNamesRemainCompatibleForValidSources() {
        var itemSchema =
                schema(
                        SourceKind.ITEM,
                        List.of(
                                integer("ID", 32, true, 1),
                                integer("ClassID", 8, true, 1),
                                integer("InventoryType", 8, true, 1)));
        var sparseSchema =
                schema(
                        SourceKind.ITEM_SPARSE,
                        List.of(
                                integer("ID", 32, true, 1),
                                text("Display_lang"),
                                integer("OverallQualityID", 8, true, 1),
                                integer("InventoryType", 8, true, 1),
                                integer("ItemLevel", 16, true, 1)));
        var item = row(7, "7", "2", "13");
        var sparse = row(7, "7", "Authored blade", "4", "17", "60");
        var left = PreparedItemProjection.projectSource(profile(itemSchema), item);
        var right = PreparedItemProjection.projectSource(profile(sparseSchema), sparse);
        var legacy =
                ItemFacts.project(
                        itemSchema.columns(),
                        sparseSchema.columns(),
                        item.row(),
                        sparse.row(),
                        ItemSparseState.PRESENT);
        assertEquals(
                legacy.facts().stream()
                        .filter(f -> f.key().equals("quality"))
                        .findFirst()
                        .orElseThrow()
                        .values()
                        .getFirst()
                        .value(),
                cell(right, FactId.QUALITY).value());
        var legacyName =
                ItemLayout.resolve(itemSchema.columns(), sparseSchema.columns())
                        .summary(item.row(), sparse.row(), TableTypes.RowOutcome.FOUND, null)
                        .name();
        assertEquals(legacyName.value(), cell(right, FactId.NAME).value());
        var view = PreparedItemProjection.combine(7, left, right, DEFAULT);
        var slot =
                view.groups().stream()
                        .filter(g -> g.fact() == FactId.INVENTORY_TYPE)
                        .findFirst()
                        .orElseThrow();
        assertEquals(Agreement.DISAGREE, slot.agreement());
        assertEquals(
                List.of("13", "17"),
                slot.cells().stream().map(PreparedItemTypes.SourceCell::value).toList());
        assertEquals("Epic", ItemFacts.QUALITY.get(cell(right, FactId.QUALITY).value()));
    }

    @Test
    void sparseOnlyIdentityAndFactsSurviveWithoutSyntheticItemRow() {
        var s =
                schema(
                        SourceKind.ITEM_SPARSE,
                        List.of(
                                integer("ID", 32, true, 1),
                                text("Display_lang"),
                                integer("OverallQualityID", 8, true, 1)));
        var sparse =
                PreparedItemProjection.projectSource(
                        profile(s), row(7, "7", "Authored sparse-only", "3"));
        var item =
                PreparedItemProjection.unobservedSource(
                        7,
                        stamp(SourceKind.ITEM, "c".repeat(32), "01020304"),
                        SourceState.UNAVAILABLE,
                        RowState.UNKNOWN,
                        PresenceEvidence.unknown(),
                        DEFAULT);
        var view = PreparedItemProjection.combine(7, item, sparse, DEFAULT);
        assertEquals("Authored sparse-only", view.name().value());
        assertEquals("3", cell(view.sparse(), FactId.QUALITY).value());
        assertTrue(view.item().cells().isEmpty());
    }

    @Test
    void unsupportedNameDoesNotDiscardNumericFacts() {
        var s =
                schema(
                        SourceKind.ITEM_SPARSE,
                        List.of(
                                integer("ID", 32, true, 1),
                                integer("OverallQualityID", 8, true, 1)));
        var p = profile(s);
        var facts = PreparedItemProjection.projectSource(p, row(1, "1", "4"));
        assertEquals("4", cell(facts, FactId.QUALITY).value());
        assertTrue(p.omissions().stream().anyMatch(v -> v.fact() == FactId.NAME));
    }

    @Test
    void perRowUnknownProtectedAndCertifiedMissingRemainDistinct() {
        var p =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        text("Display_lang"),
                                        integer("OverallQualityID", 8, true, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SourceRow(
                                1,
                                RowState.MISSING,
                                null,
                                IssueChannel.UNKNOWN,
                                List.of(),
                                PresenceEvidence.unknown()));
        for (var state : List.of(RowState.UNKNOWN, RowState.PROTECTED, RowState.MISSING)) {
            var evidence =
                    state == RowState.MISSING
                            ? new PresenceEvidence(
                                    PresenceKind.ABSENCE_CERTIFIED,
                                    "authored-certificate",
                                    "authored-absence")
                            : state == RowState.PROTECTED
                                    ? new PresenceEvidence(
                                            PresenceKind.PROTECTION_OBSERVED,
                                            "authored-row",
                                            "authored-protection")
                                    : PresenceEvidence.unknown();
            var facts =
                    PreparedItemProjection.projectSource(
                            p,
                            new SourceRow(
                                    1, state, null, IssueChannel.UNKNOWN, List.of(), evidence));
            var expected =
                    state == RowState.MISSING
                            ? CellState.MISSING
                            : state == RowState.PROTECTED ? CellState.PROTECTED : CellState.UNKNOWN;
            assertEquals(expected, cell(facts, FactId.NAME).state());
            assertNull(cell(facts, FactId.NAME).rowId());
            assertNull(cell(facts, FactId.NAME).value());
        }
    }

    @Test
    void unsignedMaximumIdAcceptsSignedDbdIdButOtherNegativeValuesRemainSigned() {
        var p =
                profile(
                        schema(
                                SourceKind.ITEM,
                                List.of(
                                        integer("ID", 32, false, 1),
                                        integer("Flags", 32, false, 2),
                                        integer("ClassID", 8, true, 1))));
        var facts =
                PreparedItemProjection.projectSource(
                        p, row(0xffffffffL, "4294967295", "-1", "0", "2"));
        assertEquals(RowState.PRESENT, facts.rowState());
        assertEquals("-1", cell(facts, FactId.FLAGS_WORD).value());
        assertEquals(
                List.of(0, 1),
                facts.cells().stream()
                        .filter(c -> c.key().fact() == FactId.FLAGS_WORD)
                        .map(c -> c.key().arraySlot())
                        .toList());
        assertEquals(
                RowState.INVALID,
                PreparedItemProjection.projectSource(p, row(0xffffffffL, "-1", "-1", "0", "2"))
                        .rowState());
    }

    @Test
    void unknownIssueChannelAndLimitsPreserveIndependentNumericCells() {
        var p =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        text("Display_lang"),
                                        integer("OverallQualityID", 8, true, 1))));
        var unknown =
                PreparedItemProjection.projectSource(
                        p, row(1, List.of("1", "", "4"), null, IssueChannel.KNOWN, List.of()));
        assertEquals(CellState.UNKNOWN, cell(unknown, FactId.NAME).state());
        assertEquals("4", cell(unknown, FactId.QUALITY).value());
        var limited =
                PreparedItemProjection.projectSource(
                        p,
                        row(
                                1,
                                List.of("1", "", "4"),
                                List.of(),
                                IssueChannel.KNOWN,
                                List.of(
                                        new IssueOverride(
                                                1, CellState.LIMITED, "CAPTURE_CELL_LIMIT"))));
        assertEquals(CellState.LIMITED, cell(limited, FactId.NAME).state());
        assertEquals("4", cell(limited, FactId.QUALITY).value());
        var empty = PreparedItemProjection.projectSource(p, row(1, "1", "", "4"));
        assertEquals(CellState.KNOWN_EMPTY, cell(empty, FactId.NAME).state());
    }

    @Test
    void invalidNameAndMalformedIntegerDoNotEraseOtherFacts() {
        var p =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        text("Display_lang"),
                                        integer("OverallQualityID", 8, true, 1),
                                        integer("ItemLevel", 16, true, 1))));
        var facts =
                PreparedItemProjection.projectSource(
                        p,
                        row(
                                1,
                                List.of("1", "bad", "256", "60"),
                                List.of(
                                        new Db2Types.TextIssue(
                                                1, "DB2_TEXT_ENCODING_INVALID", "FF")),
                                IssueChannel.KNOWN,
                                List.of()));
        assertEquals(CellState.INVALID, cell(facts, FactId.NAME).state());
        assertEquals(CellState.INVALID, cell(facts, FactId.QUALITY).state());
        assertEquals("60", cell(facts, FactId.ITEM_LEVEL).value());
    }

    @Test
    void longTextAndSeventeenFlagsLimitOnlyTheirOwnFamily() {
        var selected =
                new Selection(
                        Set.of(
                                FactFamily.NAME,
                                FactFamily.QUALITY,
                                FactFamily.FLAGS,
                                FactFamily.RAW_DESCRIPTION),
                        PreparedItemProjection.POLICY_REVISION);
        var s =
                schema(
                        SourceKind.ITEM_SPARSE,
                        List.of(
                                integer("ID", 32, true, 1),
                                text("Display_lang"),
                                integer("OverallQualityID", 8, true, 1),
                                integer("Flags", 32, true, 17),
                                text("Description_lang")),
                        selected,
                        "c".repeat(32),
                        "01020304",
                        null);
        var values = new ArrayList<String>(List.of("1", "Name", "4"));
        for (int i = 0; i < 17; i++) {
            values.add(Integer.toString(i));
        }
        values.add("x".repeat(9000));
        var p = profile(s);
        var facts =
                PreparedItemProjection.projectSource(
                        p, row(1, values, List.of(), IssueChannel.KNOWN, List.of()));
        assertTrue(
                p.omissions().stream()
                        .anyMatch(
                                v ->
                                        v.fact() == FactId.FLAGS_WORD
                                                && v.state() == CellState.LIMITED));
        assertEquals(CellState.LIMITED, cell(facts, FactId.RAW_DESCRIPTION).state());
        assertEquals("Name", cell(facts, FactId.NAME).value());
        assertEquals("4", cell(facts, FactId.QUALITY).value());
    }

    @Test
    void compactProfilesIgnoreLargeCommentsAndEndpointAnnotationsButPinLayoutAndPolicy() {
        var fields = List.of(integer("ID", 32, false, 1), integer("ClassID", 8, true, 1));
        var a = profile(schema(SourceKind.ITEM, fields, DEFAULT, "a".repeat(32), "01020304", null));
        var b =
                profile(
                        schema(
                                SourceKind.ITEM,
                                fields,
                                DEFAULT,
                                "b".repeat(32),
                                "01020304",
                                "comment".repeat(100000)));
        assertEquals(a.fieldFingerprint(), b.fieldFingerprint());
        assertEquals(a.estimatedRetainedBytes(), b.estimatedRetainedBytes());
        assertTrue(a.estimatedRetainedBytes() <= 65536);
        var changed =
                profile(schema(SourceKind.ITEM, fields, DEFAULT, "a".repeat(32), "AABBCCDD", null));
        assertEquals(a.identityFingerprint(), changed.identityFingerprint());
        assertNotEquals(a.fieldFingerprint(), changed.fieldFingerprint());
        assertFalse(
                java.util.Arrays.stream(SourceProfile.class.getRecordComponents())
                        .anyMatch(
                                c ->
                                        c.getType() == SourceSchema.class
                                                || c.getType() == TableSchema.class
                                                || c.getType() == Db2Types.Header.class));
    }

    @Test
    void sourceIdentityMismatchIsNotAnEmptyItemInventory() {
        var s =
                schema(
                        SourceKind.ITEM,
                        List.of(integer("ID", 32, true, 1), integer("ClassID", 8, true, 1)));
        var badHeader =
                new Db2Types.Header(5, "919BE54E", "01020304", 1, 0, 16, s.columns(), "WDC5");
        var result =
                PreparedItemProjection.compile(
                        new SourceSchema(
                                s.stamp(),
                                badHeader,
                                s.schema(),
                                s.columns(),
                                s.state(),
                                s.selection()));
        assertEquals(IdentityAdmission.INVALID, result.identity());
        assertNull(result.profile());
    }

    @Test
    void sourceQualifiedFieldsAndImmutableViewsNeverChooseWinningValue() {
        var a =
                profile(
                        schema(
                                SourceKind.ITEM,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        integer("InventoryType", 8, true, 1))));
        var b =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        integer("InventoryType", 8, true, 1))));
        var left = PreparedItemProjection.projectSource(a, row(1, "1", "13"));
        var right = PreparedItemProjection.projectSource(b, row(1, "1", "17"));
        assertNotEquals(
                cell(left, FactId.INVENTORY_TYPE).key(), cell(right, FactId.INVENTORY_TYPE).key());
        var view = PreparedItemProjection.combine(1, left, right, DEFAULT);
        assertEquals(Agreement.DISAGREE, view.groups().getFirst().agreement());
        assertThrows(UnsupportedOperationException.class, () -> view.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> left.cells().clear());
    }

    @Test
    void unknownCounterpartMakesAgreementIndeterminateWithoutLosingKnownSource() {
        var fields = List.of(integer("ID", 32, true, 1), integer("InventoryType", 8, true, 1));
        var a = profile(schema(SourceKind.ITEM, fields));
        var b = profile(schema(SourceKind.ITEM_SPARSE, fields));
        var left = PreparedItemProjection.projectSource(a, row(1, "1", "13"));
        var right =
                PreparedItemProjection.projectSource(
                        b,
                        new SourceRow(
                                1,
                                RowState.UNKNOWN,
                                null,
                                IssueChannel.UNKNOWN,
                                List.of(),
                                PresenceEvidence.unknown()));
        var group = PreparedItemProjection.combine(1, left, right, DEFAULT).groups().getFirst();
        assertEquals(Agreement.INDETERMINATE, group.agreement());
        assertEquals("13", group.cells().getFirst().value());
        assertEquals(CellState.UNKNOWN, group.cells().get(1).state());
    }

    @Test
    void rowLocalMissingTailKeepsOtherAlignedCellsAndCopyFacts() {
        var p =
                profile(
                        schema(
                                SourceKind.ITEM,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        integer("ClassID", 8, true, 1),
                                        integer("SubclassID", 8, true, 1))));
        var raw = new Db2Types.Row(8, true, null, List.of("8", "2"), List.of());
        var facts =
                PreparedItemProjection.projectSource(
                        p,
                        new SourceRow(
                                8,
                                RowState.PRESENT,
                                raw,
                                IssueChannel.KNOWN,
                                List.of(),
                                PresenceEvidence.unknown()));
        assertEquals("2", cell(facts, FactId.CLASS_ID).value());
        assertEquals(CellState.UNKNOWN, cell(facts, FactId.SUBCLASS_ID).state());
        assertEquals(CellState.UNKNOWN, cell(facts, FactId.CLASS_ID).copy().state());
        assertEquals(8L, cell(facts, FactId.CLASS_ID).rowId());
    }

    @Test
    void ambiguousNameAndUnverifiedNumericFieldDoNotEraseVerifiedQuality() {
        var unknown =
                new TableSchema.SchemaColumn(
                        "ItemLevel",
                        TableSchema.ColumnKind.INT,
                        32,
                        true,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        false,
                        null,
                        null,
                        null);
        var p =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        text("Display_lang"),
                                        text("Display_lang"),
                                        integer("OverallQualityID", 8, true, 1),
                                        unknown)));
        var facts = PreparedItemProjection.projectSource(p, row(1, "1", "A", "B", "4", "60"));
        assertEquals("4", cell(facts, FactId.QUALITY).value());
        assertTrue(p.omissions().stream().anyMatch(v -> v.fact() == FactId.NAME));
        assertTrue(p.omissions().stream().anyMatch(v -> v.fact() == FactId.ITEM_LEVEL));
    }

    @Test
    void agreementDoesNotCollapseDifferentKnownSourceDomains() {
        var a =
                profile(
                        schema(
                                SourceKind.ITEM,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        integer("InventoryType", 8, true, 1))));
        var b =
                profile(
                        schema(
                                SourceKind.ITEM_SPARSE,
                                List.of(
                                        integer("ID", 32, true, 1),
                                        integer("InventoryType", 16, true, 1))));
        var view =
                PreparedItemProjection.combine(
                        1,
                        PreparedItemProjection.projectSource(a, row(1, "1", "13")),
                        PreparedItemProjection.projectSource(b, row(1, "1", "13")),
                        DEFAULT);
        assertEquals(Agreement.INDETERMINATE, view.groups().getFirst().agreement());
        assertEquals(
                List.of("13", "13"),
                view.groups().getFirst().cells().stream()
                        .map(PreparedItemTypes.SourceCell::value)
                        .toList());
    }
}
