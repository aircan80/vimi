package com.vibeminer.concepts.effects;

import static com.vibeminer.concepts.effects.EffectsWorld.KEY;
import static com.vibeminer.concepts.effects.EffectsWorld.NOW;
import static com.vibeminer.concepts.effects.EffectsWorld.bridge;
import static com.vibeminer.concepts.effects.EffectsWorld.bridgeTable;
import static com.vibeminer.concepts.effects.EffectsWorld.classic;
import static com.vibeminer.concepts.effects.EffectsWorld.classicTable;
import static com.vibeminer.concepts.effects.EffectsWorld.effect;
import static com.vibeminer.concepts.effects.EffectsWorld.item;
import static com.vibeminer.concepts.effects.EffectsWorld.itemTable;
import static com.vibeminer.concepts.effects.EffectsWorld.name;
import static com.vibeminer.concepts.effects.EffectsWorld.nameTable;
import static com.vibeminer.concepts.effects.EffectsWorld.retailTable;
import static com.vibeminer.concepts.effects.EffectsWorld.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.effects.EffectsTypes.Absence;
import com.vibeminer.concepts.effects.EffectsTypes.EffectState;
import com.vibeminer.concepts.effects.EffectsTypes.Entry;
import com.vibeminer.concepts.effects.EffectsTypes.Evidence;
import com.vibeminer.concepts.effects.EffectsTypes.Field;
import com.vibeminer.concepts.effects.EffectsTypes.Link;
import com.vibeminer.concepts.effects.EffectsTypes.NameState;
import com.vibeminer.concepts.effects.EffectsTypes.Options;
import com.vibeminer.concepts.effects.EffectsTypes.Page;
import com.vibeminer.concepts.effects.EffectsTypes.PageState;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import com.vibeminer.concepts.effects.EffectsTypes.Source;
import com.vibeminer.concepts.effects.EffectsTypes.SourceState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import com.vibeminer.formats.tables.MemoryTables.FakeTable;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class ItemEffectsRulesTest {
    static Page page(EffectsWorld world) {
        try (var handle = world.open()) {
            return handle.get(1, new Query(), NOW, Cancellation.NONE).join();
        }
    }

    static FakeTable copy(
            FakeTable source,
            TableTypes.Entry entry,
            List<Db2Types.Column> columns,
            List<Db2Types.Row> rows,
            TableSchema schema) {
        return new FakeTable(entry, columns, rows, schema, source.protectedIds(), true);
    }

    static FakeTable match(FakeTable source, SchemaMatch match) {
        var s = source.schema();
        return copy(
                source,
                source.entry(),
                source.columns(),
                source.rows(),
                new TableSchema(s.columns(), s.layoutHashes(), match, s.locale(), s.comment()));
    }

    @Test
    void strictAndRawInputsKeepAllNineValuesAndCandidateHintsWithoutReadingHintTables() {
        List<String> inputs =
                List.of("255", "0", "4294967295", "-5", "-6", "65535", "0", "65535", "-1");
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 0, 1))),
                                retailTable(
                                        List.of(effect(0, 0, inputs, null)),
                                        TableTypes.State.PARTIAL,
                                        List.of(3),
                                        Map.of()),
                                nameTable(List.of(name(0, "Zero"))));
        var result = page(world);
        var entry = result.entries().getFirst();
        assertEquals(EffectState.OBSERVED_READY, entry.effect().state());
        assertEquals(inputs, entry.effect().fields().stream().map(Field::value).toList());
        assertEquals(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
                entry.effect().fields().stream().map(Field::columnIndex).toList());
        assertEquals(Evidence.RAW_PHYSICAL_INTEGER, entry.effect().fields().get(2).evidence());
        assertEquals(32, entry.effect().fields().get(2).interpretation().physicalBits());
        assertEquals(Evidence.UNVERIFIED_CANDIDATE, entry.effect().fields().get(8).evidence());
        assertEquals("SpellCategory", entry.effect().fields().get(5).candidateReference().table());
        assertEquals(
                "PlayerCondition", entry.effect().fields().get(8).candidateReference().table());
        assertEquals(3, world.tables.calls("get"));
        assertEquals("Zero", entry.name().text());
    }

    @ParameterizedTest
    @CsvSource({
        "3,4294901760",
        "3,32768",
        "6,65536",
        "8,-1",
        "1,256",
        "2,-1",
        "4,2147483648",
        "9,01",
        "5,issue"
    })
    void unavailableInputDoesNotEraseUsableSpellName(int column, String value) {
        List<String> inputs = new ArrayList<>(List.of("1", "2", "3", "4", "5", "6", "0", "8", "9"));
        List<Db2Types.TextIssue> issues = null;
        if (value.equals("issue")) {
            issues = List.of(new Db2Types.TextIssue(column, "DB2_TEXT_ENCODING_INVALID", "ff"));
        } else {
            inputs.set(column - 1, value);
        }
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 0, 1))),
                                retailTable(List.of(effect(0, 0, inputs, issues))),
                                nameTable(List.of(name(0, "Zero"))));
        var entry = page(world).entries().getFirst();
        assertEquals(EffectState.FIELD_UNAVAILABLE, entry.effect().state());
        assertNull(entry.effect().fields());
        assertNotNull(entry.effect().row());
        assertEquals("0", entry.effect().spellId());
        assertEquals("Zero", entry.name().text());
    }

    @Test
    void spellOutsideInt32AndNegativeSpellRemainDifferentStates() {
        List<String> bad = new ArrayList<>(effect(0, 0).values().subList(1, 10));
        bad.set(6, "2147483648");
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 0, 1), bridge(2, 1, 1))),
                                retailTable(List.of(effect(0, 0, bad, null), effect(1, -1))),
                                nameTable(List.of()));
        var p = page(world);
        assertEquals(EffectState.FIELD_UNAVAILABLE, p.entries().get(0).effect().state());
        assertEquals(NameState.EFFECT_NOT_READY, p.entries().get(0).name().state());
        assertEquals(NameState.SPELL_KEY_OUT_OF_RANGE, p.entries().get(1).name().state());
        assertEquals(SourceState.NOT_CHECKED, p.name().state());
    }

    @Test
    void namesKeepWhitespaceInvalidEmptyOversizedProtectedAndCopyEvidence() {
        List<Db2Types.Row> bridges = new ArrayList<>(), effects = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bridges.add(bridge(500 + i, i, 1));
            effects.add(effect(i, i));
        }
        List<Db2Types.Row> names =
                List.of(
                        name(0, " "),
                        name(1, ""),
                        new Db2Types.Row(
                                2,
                                false,
                                null,
                                List.of("2", ""),
                                List.of(
                                        new Db2Types.TextIssue(
                                                1, "DB2_TEXT_ENCODING_INVALID", "ff"))),
                        name(3, "x".repeat(4097)),
                        name(4, "y".repeat(4096)),
                        name(6, "valid"),
                        new Db2Types.Row(7, true, 0L, List.of("7", " "), null));
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(bridges),
                                retailTable(effects),
                                nameTable(names, TableTypes.State.PARTIAL, Map.of(5L, KEY)));
        var p = page(world);
        assertEquals(
                List.of(
                        NameState.READY,
                        NameState.EMPTY_TEXT,
                        NameState.INVALID_TEXT,
                        NameState.TEXT_TOO_LARGE,
                        NameState.READY,
                        NameState.PROTECTED,
                        NameState.READY,
                        NameState.READY),
                p.entries().stream().map(e -> e.name().state()).toList());
        assertEquals(" ", p.entries().getFirst().name().text());
        assertEquals("", p.entries().get(1).name().text());
        assertNull(p.entries().get(2).name().text());
        assertEquals("DB2_TEXT_ENCODING_INVALID", p.entries().get(2).name().issueCode());
        assertEquals(4097, p.entries().get(3).name().textUnits());
        assertEquals(KEY, p.entries().get(5).name().protectedKey());
        assertTrue(p.entries().get(7).name().row().row().isCopy());
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "surrogate", "issue", "duplicateIssue"})
    void malformedNameMakesOnlyNameSourceCorruptAndKeepsDefinitionProvenance(String defect) {
        Db2Types.Row row =
                switch (defect) {
                    case "long" -> name(0, "x".repeat(65537));
                    case "surrogate" -> name(0, "\ud800");
                    case "issue" ->
                            new Db2Types.Row(
                                    0,
                                    false,
                                    null,
                                    List.of("0", ""),
                                    List.of(new Db2Types.TextIssue(1, "DB2_TEXT_OTHER", "ff")));
                    default ->
                            new Db2Types.Row(
                                    0,
                                    false,
                                    null,
                                    List.of("0", ""),
                                    List.of(
                                            new Db2Types.TextIssue(
                                                    1, "DB2_TEXT_ENCODING_INVALID", "ff"),
                                            new Db2Types.TextIssue(
                                                    1, "DB2_TEXT_ENCODING_INVALID", "fe")));
                };
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 0, 1))),
                                retailTable(List.of(effect(0, 0))),
                                match(nameTable(List.of(row)), SchemaMatch.LAYOUT));
        var p = page(world);
        assertEquals(SourceState.CORRUPT, p.name().state());
        assertEquals("ITEM_EFFECTS_SOURCE_CORRUPT", p.name().problem().code());
        assertEquals(SchemaMatch.LAYOUT, p.name().definition().match());
        assertEquals(
                ItemEffectProfiles.LAYOUT_MATCH_WARNING,
                p.name().definition().warnings().getFirst().code());
        assertEquals(EffectState.OBSERVED_READY, p.entries().getFirst().effect().state());
        assertEquals(NameState.SOURCE_NOT_READY, p.entries().getFirst().name().state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absenceIsProvenOnlyForCompleteEffectAndNameSources(boolean partial) {
        TableTypes.State state = partial ? TableTypes.State.PARTIAL : TableTypes.State.READY;
        EffectsWorld effects =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 3, 1), bridge(2, 4, 1))),
                                retailTable(
                                        List.of(effect(0, 0)),
                                        state,
                                        List.of(),
                                        partial ? Map.of(4L, KEY) : Map.of()),
                                nameTable(List.of()));
        var p = page(effects);
        assertEquals(
                partial
                        ? Absence.UNKNOWN_OUTSIDE_READABLE_SCOPE
                        : Absence.ABSENT_FROM_COMPLETE_SOURCE,
                p.entries().getFirst().effect().absence());
        assertEquals(SourceState.NOT_CHECKED, p.name().state());
        if (partial) {
            assertEquals(EffectState.PROTECTED, p.entries().get(1).effect().state());
            assertEquals(KEY, p.entries().get(1).effect().protectedKey());
        }
        EffectsWorld names =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(List.of(bridge(1, 0, 1))),
                                retailTable(List.of(effect(0, 9))),
                                nameTable(List.of(), state, Map.of()));
        assertEquals(
                partial
                        ? Absence.UNKNOWN_OUTSIDE_READABLE_SCOPE
                        : Absence.ABSENT_FROM_COMPLETE_SOURCE,
                page(names).entries().getFirst().name().absence());
    }

    @Test
    void classicParentLinksAreEffectRowsThemselvesAndKeepNegativeChargesAndCopy() {
        Db2Types.Row base = classic(7, 1, 0), negative = classic(8, 1, -1);
        List<String> values = new ArrayList<>(negative.values());
        values.set(3, "-32768");
        negative = new Db2Types.Row(8, false, null, values, null);
        Db2Types.Row copy =
                new Db2Types.Row(9, true, 8L, new ArrayList<>(classic(9, 1, -1).values()), null);
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1))),
                                classicTable(List.of(base, negative, copy)),
                                nameTable(List.of(name(0, "Zero"))));
        var p = page(world);
        assertEquals(Link.PARENT_ITEM_ID, p.link());
        assertEquals(
                List.of(7L, 8L, 9L), p.entries().stream().map(e -> e.link().row().id()).toList());
        assertEquals(
                List.of("7", "8", "9"), p.entries().stream().map(Entry::itemEffectId).toList());
        assertEquals("-32768", p.entries().get(1).effect().fields().get(2).value());
        assertTrue(p.entries().get(2).effect().row().row().isCopy());
    }

    @ParameterizedTest
    @CsvSource({
        "12.1.5.70448,4CA77678,ITEM_X_ITEM_EFFECT",
        "1.60.1.70450,4CA77678,ITEM_X_ITEM_EFFECT",
        "1.15.9.70003,1BF9CF3A,PARENT_ITEM_ID",
        "5.5.4.70449,1BF9CF3A,PARENT_ITEM_ID",
        "2.5.6.70006,1BF9CF3A,PARENT_ITEM_ID",
        "1.15.10.71000,1BF9CF3A,PARENT_ITEM_ID",
        "1.15.10.71000,4CA77678,ITEM_X_ITEM_EFFECT"
    })
    void actualBundledDefinitionChoosesLinkBeforeMeaningIsInterpreted(
            String version, String layout, Link expected) {
        assertEquals(
                expected, ItemEffectProfiles.choose(schema("4002a5b1", version, layout), null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "rawSpell",
                "rawUnsignedCharges",
                "raw16Physical",
                "layout",
                "format",
                "columnName",
                "fieldWidth"
            })
    void unmatchedProfilesKeepLinksButDoNotGuessEffectInputs(String defect) {
        FakeTable effect = retailTable(List.of(effect(0, 0)));
        List<Db2Types.Column> cols = new ArrayList<>(effect.columns());
        TableTypes.Entry entry = effect.entry();
        TableSchema schema = effect.schema();
        if (defect.startsWith("raw")) {
            int at = defect.equals("rawSpell") ? 7 : 3;
            var old = cols.get(at);
            cols.set(
                    at,
                    new Db2Types.Column(
                            old.name(),
                            old.kind(),
                            old.isId(),
                            new Db2Types.Interpretation(
                                    "raw-physical-integer-v1",
                                    defect.equals("raw16Physical") ? 16 : 32,
                                    false,
                                    16,
                                    defect.equals("rawUnsignedCharges"),
                                    "reviewed"),
                            null));
        }
        if (defect.equals("layout") || defect.equals("format")) {
            var c = entry.counts();
            entry =
                    new TableTypes.Entry(
                            entry.fileDataId(),
                            entry.name(),
                            entry.state(),
                            true,
                            null,
                            new TableTypes.Counts(
                                    c.declaredRows(),
                                    c.readableRows(),
                                    c.excludedSections(),
                                    c.excludedRecords(),
                                    defect.equals("format") ? "WDC4" : "WDC5",
                                    defect.equals("layout") ? "EB717848" : c.layoutHash()),
                            null);
        }
        if (defect.equals("columnName") || defect.equals("nullColumnName")) {
            var old = cols.get(5);
            cols.set(
                    5,
                    new Db2Types.Column(
                            defect.equals("nullColumnName") ? null : "Cooldown",
                            old.kind(),
                            false,
                            null,
                            null));
        }
        if (defect.equals("fieldWidth")) {
            var fields = new ArrayList<>(schema.columns());
            var f = fields.get(3);
            fields.set(
                    3,
                    new TableSchema.SchemaColumn(
                            f.name(),
                            f.kind(),
                            32,
                            f.unsigned(),
                            f.arrayLength(),
                            f.foreignKey(),
                            f.id(),
                            f.relation(),
                            f.verifiedName(),
                            f.locale(),
                            f.columnComment(),
                            f.fieldComment()));
            schema =
                    new TableSchema(
                            fields,
                            schema.layoutHashes(),
                            SchemaMatch.LAYOUT,
                            schema.locale(),
                            schema.comment());
        }
        effect = copy(effect, entry, cols, effect.rows(), schema);
        var p =
                page(
                        new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridgeTable(List.of(bridge(1, 0, 1))),
                                        effect,
                                        nameTable(List.of())));
        assertEquals(PageState.OK, p.state());
        assertEquals(1, p.entries().size());
        assertEquals(SourceState.UNSUPPORTED, p.effect().state());
        assertEquals(EffectState.SOURCE_NOT_READY, p.entries().getFirst().effect().state());
        assertEquals(SourceState.NOT_CHECKED, p.name().state());
    }

    @Test
    void layoutProvenanceWarningIsAdditiveAndBuildMatchIsExplicit() {
        for (SchemaMatch match : SchemaMatch.values()) {
            var p =
                    page(
                            new EffectsWorld()
                                    .with(
                                            itemTable(List.of(item(1))),
                                            match(bridgeTable(List.of(bridge(1, 0, 1))), match),
                                            match(retailTable(List.of(effect(0, 0))), match),
                                            match(nameTable(List.of(name(0, "Zero"))), match)));
            assertEquals(PageState.OK, p.state());
            assertEquals("Zero", p.entries().getFirst().name().text());
            for (Source source : List.of(p.bridge(), p.effect(), p.name())) {
                assertEquals(match, source.definition().match());
                assertNotNull(source.definition().observedLayoutHash());
                assertFalse(source.definition().declaredLayoutHashes().isEmpty());
                assertEquals(
                        match == SchemaMatch.LAYOUT ? 1 : 0, source.definition().warnings().size());
                if (match == SchemaMatch.LAYOUT) {
                    assertEquals(
                            ItemEffectProfiles.LAYOUT_MATCH_WARNING,
                            source.definition().warnings().getFirst().code());
                }
            }
        }
    }

    @Test
    void nameLimitsCountUtf16UnitsAndNeverNormalizeOriginalText() {
        String text = "😀".repeat(2048);
        var ready =
                page(new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridgeTable(List.of(bridge(1, 0, 1))),
                                        retailTable(List.of(effect(0, 0))),
                                        nameTable(List.of(name(0, text)))))
                        .entries()
                        .getFirst()
                        .name();
        assertEquals(NameState.READY, ready.state());
        assertEquals(text, ready.text());
        var tooLarge =
                page(new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridgeTable(List.of(bridge(1, 0, 1))),
                                        retailTable(List.of(effect(0, 0))),
                                        nameTable(List.of(name(0, text + "x")))))
                        .entries()
                        .getFirst()
                        .name();
        assertEquals(NameState.TEXT_TOO_LARGE, tooLarge.state());
        assertEquals(4097, tooLarge.textUnits());
        assertNull(tooLarge.text());
    }

    @Test
    void partialBridgeEmptyMatchesRetainScopeAndDoNotClaimFurtherSourcesWereRead() {
        FakeTable bridge = bridgeTable(List.of());
        var entry = bridge.entry();
        bridge =
                copy(
                        bridge,
                        new TableTypes.Entry(
                                entry.fileDataId(),
                                entry.name(),
                                TableTypes.State.PARTIAL,
                                true,
                                entry.reason(),
                                entry.counts(),
                                entry.contentKey()),
                        bridge.columns(),
                        bridge.rows(),
                        bridge.schema());
        var p =
                page(
                        new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridge,
                                        retailTable(List.of(effect(0, 0))),
                                        nameTable(List.of())));
        assertEquals(PageState.OK, p.state());
        assertEquals(0, p.matches());
        assertTrue(p.entries().isEmpty());
        assertEquals(TableTypes.State.PARTIAL, p.bridge().table().state());
        assertEquals(SourceState.NOT_CHECKED, p.effect().state());
        assertEquals(SourceState.NOT_CHECKED, p.name().state());
    }

    @Test
    void malformedEffectRowCorruptsEffectSourceButPreservesItsLayoutSelectionWarning() {
        var wrong = new Db2Types.Row(0, true, null, effect(0, 0).values(), null);
        var p =
                page(
                        new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridgeTable(List.of(bridge(1, 0, 1))),
                                        match(retailTable(List.of(wrong)), SchemaMatch.LAYOUT),
                                        nameTable(List.of())));
        assertEquals(SourceState.CORRUPT, p.effect().state());
        assertEquals(SchemaMatch.LAYOUT, p.effect().definition().match());
        assertEquals(1, p.effect().definition().warnings().size());
        assertEquals(EffectState.SOURCE_NOT_READY, p.entries().getFirst().effect().state());
        assertEquals(SourceState.NOT_CHECKED, p.name().state());
    }

    @Test
    void indexBudgetRefusalNeverClaimsNoEffectsAndIsKeptForHandleLife() {
        List<Db2Types.Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(bridge(100 + i, 0, 1));
        }
        EffectsWorld world =
                new EffectsWorld(new Options(10))
                        .with(
                                itemTable(List.of(item(1))),
                                bridgeTable(rows),
                                retailTable(List.of(effect(0, 0))),
                                nameTable(List.of()));
        try (var handle = world.open()) {
            for (int n = 0; n < 2; n++) {
                var p = handle.get(1, new Query(), NOW, Cancellation.NONE).join();
                assertEquals(PageState.INDEX_LIMIT, p.state());
                assertEquals("RELATION_INDEX_LIMIT", p.code());
                assertTrue(p.entries().isEmpty());
                assertNull(p.next());
                assertEquals(SourceState.READY, p.bridge().state());
                assertEquals(SourceState.NOT_CHECKED, p.effect().state());
            }
        }
    }

    @Test
    void linksBeyondOnePageUseCompleteRelationIndexRatherThanFilteredScan() {
        List<Db2Types.Row> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(bridge(100 + i, 0, i % 500 == 499 ? 1 : 2));
        }
        EffectsWorld world =
                new EffectsWorld()
                        .with(
                                itemTable(List.of(item(1), item(2))),
                                bridgeTable(rows),
                                retailTable(List.of(effect(0, 0))),
                                nameTable(List.of(name(0, "Zero"))));
        try (var handle = world.open()) {
            var p = handle.get(1, new Query(), NOW, Cancellation.NONE).join();
            assertEquals(4, p.matches());
            assertEquals(
                    List.of(599L, 1099L, 1599L, 2099L),
                    p.entries().stream().map(e -> e.link().row().id()).toList());
            int before = world.tables.calls("page");
            var other = handle.get(2, new Query(3), NOW, Cancellation.NONE).join();
            assertEquals(1997, other.matches());
            assertEquals(3, other.entries().size());
            assertEquals(before + 3, world.tables.calls("page"));
        }
    }

    @Test
    void bridgeCellMustNameCanonicalInt32Effect() {
        var p =
                page(
                        new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridgeTable(List.of(bridge(1, 2147483648L, 1))),
                                        retailTable(List.of(effect(0, 0))),
                                        nameTable(List.of())));
        assertEquals(PageState.BRIDGE_NOT_READY, p.state());
        assertEquals(SourceState.CORRUPT, p.bridge().state());
        assertTrue(p.entries().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "NOT_IN_BUILD,NOT_IN_BUILD,false",
        "NOT_IN_LOCALE,NOT_IN_LOCALE,false",
        "NEEDS_NETWORK,UNAVAILABLE,true",
        "MISSING,UNAVAILABLE,true",
        "ENCRYPTED,PROTECTED,false",
        "REJECTED,CORRUPT,false",
        "TOO_LARGE,RESOURCE_LIMIT,false",
        "UNSUPPORTED,UNSUPPORTED,false"
    })
    void failedBridgePreservesTypedStateAndFixedProblem(
            TableTypes.State state, SourceState expected, boolean retryable) {
        FakeTable bridge = bridgeTable(List.of());
        bridge =
                copy(
                        bridge,
                        new TableTypes.Entry(
                                bridge.entry().fileDataId(),
                                bridge.entry().name(),
                                state,
                                true,
                                null,
                                bridge.entry().counts(),
                                null),
                        bridge.columns(),
                        bridge.rows(),
                        bridge.schema());
        var p =
                page(
                        new EffectsWorld()
                                .with(
                                        itemTable(List.of(item(1))),
                                        bridge,
                                        retailTable(List.of(effect(0, 0))),
                                        nameTable(List.of())));
        assertEquals(PageState.BRIDGE_NOT_READY, p.state());
        assertEquals(expected, p.bridge().state());
        assertEquals(state, p.bridge().table().state());
        assertEquals(retryable, p.bridge().problem().retryable());
        assertNull(p.bridge().problem().definition());
    }

    @Test
    void definitionFetchProblemCarriesDefinitionStateOnlyOnUnavailableOrUnsupported() {
        for (String code :
                List.of(
                        "DEFINITION_FETCH_FAILED",
                        "DEFINITION_FETCH_OFFLINE",
                        "DEFINITION_FETCH_INVALID",
                        "DEFINITION_NOT_FOUND")) {
            var original = retailTable(List.of());
            var reason =
                    new TableTypes.Reason(
                            code,
                            "definition",
                            null,
                            TableDefinitions.State.FETCH_FAILED,
                            null,
                            List.of());
            var missing =
                    copy(
                            original,
                            new TableTypes.Entry(
                                    969941,
                                    "ItemEffect",
                                    TableTypes.State.DEFINITION_UNUSABLE,
                                    true,
                                    reason,
                                    null,
                                    null),
                            original.columns(),
                            List.of(),
                            original.schema());
            var p =
                    page(
                            new EffectsWorld()
                                    .with(
                                            itemTable(List.of(item(1))),
                                            bridgeTable(List.of(bridge(1, 0, 1))),
                                            missing,
                                            nameTable(List.of())));
            boolean retry =
                    code.equals("DEFINITION_FETCH_FAILED")
                            || code.equals("DEFINITION_FETCH_OFFLINE");
            assertEquals(
                    retry ? SourceState.UNAVAILABLE : SourceState.UNSUPPORTED, p.effect().state());
            assertEquals(retry, p.effect().problem().retryable());
            assertEquals(TableDefinitions.State.FETCH_FAILED, p.effect().problem().definition());
            assertEquals(SourceState.NOT_CHECKED, p.name().state());
        }
    }
}
