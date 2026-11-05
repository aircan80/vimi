package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SpellTextsTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final List<String> FIELDS =
            List.of(
                    "ID",
                    "EffectAura",
                    "DifficultyID",
                    "EffectIndex",
                    "Effect",
                    "EffectAmplitude",
                    "EffectAttributes",
                    "EffectAuraPeriod",
                    "EffectBonusCoefficient",
                    "EffectChainAmplitude",
                    "EffectChainTargets",
                    "EffectItemType",
                    "EffectMechanic",
                    "EffectPointsPerResource",
                    "EffectPos_facing",
                    "EffectRealPointsPerLevel",
                    "EffectTriggerSpell",
                    "BonusCoefficientFromAP",
                    "PvpMultiplier",
                    "Coefficient",
                    "Variance",
                    "ResourceCoefficient",
                    "GroupSizeBasePointsCoefficient",
                    "EffectBasePointsF",
                    "ScalingClass",
                    "Unknown25",
                    "EffectMiscValue[0]",
                    "EffectMiscValue[1]",
                    "EffectRadiusIndex[0]",
                    "EffectRadiusIndex[1]",
                    "EffectSpellClassMask[0]",
                    "EffectSpellClassMask[1]",
                    "EffectSpellClassMask[2]",
                    "EffectSpellClassMask[3]",
                    "ImplicitTarget[0]",
                    "ImplicitTarget[1]",
                    "SpellID");
    private static final java.util.Set<String> FLOATS =
            java.util.Set.of(
                    "EffectAmplitude",
                    "EffectBonusCoefficient",
                    "EffectChainAmplitude",
                    "EffectPointsPerResource",
                    "EffectPos_facing",
                    "EffectRealPointsPerLevel",
                    "BonusCoefficientFromAP",
                    "PvpMultiplier",
                    "Coefficient",
                    "Variance",
                    "ResourceCoefficient",
                    "GroupSizeBasePointsCoefficient",
                    "EffectBasePointsF",
                    "MinDuration",
                    "Speed",
                    "LaunchDelay",
                    "Radius",
                    "RadiusPerLevel",
                    "RadiusMin",
                    "RadiusMax");

    private static Db2Types.Row row(long id, String... cells) {
        return new Db2Types.Row(id, false, null, List.of(cells), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id,
            String name,
            List<String> names,
            List<Db2Types.Row> rows,
            TableTypes.State state) {
        var columns = new ArrayList<Db2Types.Column>();
        var fields = new ArrayList<TableSchema.SchemaColumn>();
        for (String field : names) {
            boolean text = field.endsWith("_lang");
            boolean floating = FLOATS.contains(field);
            var kind =
                    text
                            ? TableSchema.ColumnKind.LOCSTRING
                            : floating ? TableSchema.ColumnKind.FLOAT : TableSchema.ColumnKind.INT;
            columns.add(
                    new Db2Types.Column(
                            field,
                            text ? "string" : floating ? "float" : "integer",
                            false,
                            null,
                            null));
            // Array elements X[0..n] form one definition field X of length n, as in real data.
            var element = java.util.regex.Pattern.compile("(.+)\\[(\\d+)\\]").matcher(field);
            String fieldName = field;
            int length = 1;
            if (element.matches()) {
                if (!element.group(2).equals("0")) {
                    continue;
                }
                fieldName = element.group(1);
                String prefix = fieldName + "[";
                length = (int) names.stream().filter(n -> n.startsWith(prefix)).count();
            }
            fields.add(
                    new TableSchema.SchemaColumn(
                            fieldName,
                            kind,
                            text || floating ? null : 32,
                            text || floating ? null : false,
                            length,
                            null,
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            !field.equals("ScalingClass") && !field.equals("PvPDurationIndex"),
                            WowLocale.parse("enUS"),
                            null,
                            null));
        }
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        id,
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(
                                rows.size(), rows.size(), 0, 0, "fixture", "5362E3D4"),
                        "c".repeat(32)),
                columns,
                rows,
                new TableSchema(
                        fields,
                        List.of("5362E3D4"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static Db2Types.Row effect(long id, String base, String contextField) {
        var cells = new ArrayList<>(java.util.Collections.nCopies(FIELDS.size(), "0"));
        cells.set(FIELDS.indexOf("ID"), Long.toString(id));
        cells.set(FIELDS.indexOf("SpellID"), "16929");
        cells.set(FIELDS.indexOf("Effect"), "6");
        cells.set(FIELDS.indexOf("EffectAura"), "108");
        cells.set(FIELDS.indexOf("EffectBasePointsF"), base);
        for (String field :
                List.of(
                        "EffectBonusCoefficient",
                        "EffectChainAmplitude",
                        "GroupSizeBasePointsCoefficient",
                        "PvpMultiplier")) {
            cells.set(FIELDS.indexOf(field), "1");
        }
        if (contextField != null) {
            cells.set(FIELDS.indexOf(contextField), "2");
        }
        return new Db2Types.Row(id, false, null, cells, List.of());
    }

    private static MemoryTables world(List<Db2Types.Row> effects, boolean legacy) {
        var names = new ArrayList<>(FIELDS);
        if (legacy) {
            names.add("EffectBasePoints");
        }
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                table(
                                        1,
                                        "Spell",
                                        List.of("Description_lang"),
                                        List.of(row(16929, "Increases armor by $s1%.")),
                                        TableTypes.State.PARTIAL),
                                table(2, "SpellEffect", names, effects, TableTypes.State.PARTIAL)));
    }

    private static MemoryTables.FakeTable layout(MemoryTables.FakeTable table, String hash) {
        var entry = table.entry();
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        entry.fileDataId(),
                        entry.name(),
                        entry.state(),
                        true,
                        null,
                        new TableTypes.Counts(
                                table.rows().size(), table.rows().size(), 0, 0, "fixture", hash),
                        entry.contentKey()),
                table.columns(),
                table.rows(),
                new TableSchema(
                        table.schema().columns(),
                        List.of(hash),
                        table.schema().match(),
                        table.schema().locale(),
                        null),
                table.protectedIds(),
                true);
    }

    private static Db2Types.Row scalar(long id, int slot, String changed) {
        var values = new ArrayList<>(effect(id, slot == 0 ? "-50" : "-80", null).values());
        values.set(FIELDS.indexOf("EffectAura"), slot == 0 ? "33" : "0");
        values.set(FIELDS.indexOf("Effect"), slot == 0 ? "6" : "3");
        values.set(FIELDS.indexOf("EffectIndex"), Integer.toString(slot));
        values.set(FIELDS.indexOf("EffectBonusCoefficient"), "0");
        if (changed != null) {
            values.set(FIELDS.indexOf(changed), "2");
        }
        return new Db2Types.Row(id, false, null, values, List.of());
    }

    private static MemoryTables.FakeTable miscTable(List<Db2Types.Row> rows) {
        var names = new ArrayList<String>();
        names.add("ID");
        for (int i = 0; i < 17; i++) {
            names.add("Attributes[" + i + "]");
        }
        names.addAll(
                List.of(
                        "DifficultyID",
                        "CastingTimeIndex",
                        "DurationIndex",
                        "PvPDurationIndex",
                        "RangeIndex",
                        "SchoolMask",
                        "Speed",
                        "LaunchDelay",
                        "MinDuration",
                        "SpellIconFileDataID",
                        "ActiveIconFileDataID",
                        "ContentTuningID",
                        "ShowFutureSpellPlayerConditionID",
                        "SpellVisualScript",
                        "ActiveSpellVisualScript",
                        "SpellID"));
        var expanded = new ArrayList<Db2Types.Row>();
        for (var row : rows) {
            var cells = new ArrayList<>(java.util.Collections.nCopies(names.size(), "0"));
            cells.set(0, Long.toString(row.id()));
            for (int i = 0; i < 5; i++) {
                cells.set(
                        names.indexOf(
                                List.of(
                                                "SpellID",
                                                "DifficultyID",
                                                "DurationIndex",
                                                "PvPDurationIndex",
                                                "MinDuration")
                                        .get(i)),
                        row.values().get(i));
            }
            expanded.add(new Db2Types.Row(row.id(), false, null, cells, List.of()));
        }
        return layout(table(3, "SpellMisc", names, expanded, TableTypes.State.PARTIAL), "434B3607");
    }

    private static SpellTexts.Description scalarPreview(
            String raw,
            List<Db2Types.Row> effects,
            List<Db2Types.Row> misc,
            List<Db2Types.Row> duration) {
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16929, raw)),
                                                TableTypes.State.PARTIAL),
                                        table(
                                                2,
                                                "SpellEffect",
                                                FIELDS,
                                                effects,
                                                TableTypes.State.PARTIAL),
                                        miscTable(misc),
                                        layout(
                                                table(
                                                        4,
                                                        "SpellDuration",
                                                        List.of(
                                                                "Duration",
                                                                "DurationPerLevel",
                                                                "MaxDuration",
                                                                "DurationPerResource"),
                                                        duration,
                                                        TableTypes.State.PARTIAL),
                                                "4FDF0951")));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70755")) {
            return service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join();
        }
    }

    @Test
    void staticSlowAndDummyPercentagesAndFixedDurationUseRecordedMagnitudeAndMilliseconds() {
        var result =
                scalarPreview(
                        "Slow by $s1% for $d; special $s2%.",
                        List.of(scalar(101, 0, null), scalar(102, 1, null)),
                        List.of(row(90, "16929", "0", "1", "0", "0")),
                        List.of(row(1, "10000", "0", "10000", "0")));
        assertEquals("Slow by 50% for 10 sec; special 80%.", result.display());
        assertEquals(SpellTexts.State.RESOLVED, result.state());
        assertEquals(
                "-50",
                result.substitutions().getFirst().sources().stream()
                        .filter(source -> source.column().equals("EffectBasePointsF"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        assertTrue(
                result.substitutions().get(1).sources().stream()
                        .anyMatch(
                                source ->
                                        source.table().equals("SpellDuration")
                                                && source.rowId() == 1
                                                && source.value().equals("10000")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("PARTIAL")));
    }

    @Test
    void tokenizedDescriptionsResolveUppercaseCrossSpellAndMarkedUpValuesBesideUnknowns() {
        var other = new ArrayList<>(scalar(103, 0, null).values());
        other.set(FIELDS.indexOf("SpellID"), "777");
        other.set(FIELDS.indexOf("EffectBasePointsF"), "-30");
        var result =
                scalarPreview(
                        "|cffffffffSlow by $S1%|r, then $777s1% for $D; ${$s1*2} $?a1[x][y] done.",
                        List.of(
                                scalar(101, 0, null),
                                new Db2Types.Row(103, false, null, other, List.of())),
                        List.of(row(90, "16929", "0", "1", "0", "0")),
                        List.of(row(1, "10000", "0", "10000", "0")));
        assertEquals("Slow by 50%, then 30% for 10 sec; ? ? done.", result.display());
        assertEquals(SpellTexts.State.UNRESOLVED, result.state());
        assertTrue(
                result.substitutions().get(1).sources().stream()
                        .anyMatch(source -> source.rowId() == 103));
    }

    @Test
    void effectAndDurationIndexesDoNotShareOneRelationBudget() {
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16929, "Slow by $s1% for $d.")),
                                                TableTypes.State.READY),
                                        table(
                                                2,
                                                "SpellEffect",
                                                FIELDS,
                                                List.of(
                                                        scalar(101, 0, null),
                                                        scalar(102, 1, null),
                                                        scalar(103, 2, null)),
                                                TableTypes.State.READY),
                                        miscTable(
                                                List.of(
                                                        row(90, "16929", "0", "1", "0", "0"),
                                                        row(91, "5", "0", "1", "0", "0"),
                                                        row(92, "6", "0", "1", "0", "0"))),
                                        layout(
                                                table(
                                                        4,
                                                        "SpellDuration",
                                                        List.of(
                                                                "Duration",
                                                                "DurationPerLevel",
                                                                "MaxDuration",
                                                                "DurationPerResource"),
                                                        List.of(row(1, "10000", "0", "10000", "0")),
                                                        TableTypes.State.READY),
                                                "4FDF0951")));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70755", 4)) {
            var result =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals("Slow by 50% for 10 sec.", result.display());
        }
    }

    @Test
    void forgettingCachedPreviewsResolvesAgainAfterReload() {
        var world = world(List.of(effect(99, "4.0", null)), false);
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70707")) {
            var first =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertTrue(
                    first
                            == service.description(
                                            16929,
                                            TableTypes.Priority.INTERACTIVE,
                                            Cancellation.NONE)
                                    .join());
            service.forgetCached();
            var again =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertFalse(first == again);
            assertEquals(first, again);
        }
    }

    private static MemoryTables.FakeTable auraOptions(List<Db2Types.Row> rows) {
        return layout(
                table(
                        5,
                        "SpellAuraOptions",
                        List.of(
                                "ID",
                                "DifficultyID",
                                "ProcChance",
                                "SpellProcsPerMinuteID",
                                "SpellID"),
                        rows,
                        TableTypes.State.READY),
                "95F4D5E1");
    }

    private static SpellTexts.Description procPreview(String raw, List<Db2Types.Row> options) {
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16929, raw)),
                                                TableTypes.State.READY),
                                        auraOptions(options)));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70790")) {
            return service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join();
        }
    }

    private static MemoryTables.FakeTable fullAuraOptions(List<Db2Types.Row> rows) {
        return layout(
                table(
                        5,
                        "SpellAuraOptions",
                        List.of(
                                "ID",
                                "DifficultyID",
                                "CumulativeAura",
                                "ProcChance",
                                "ProcCharges",
                                "SpellProcsPerMinuteID",
                                "SpellID"),
                        rows,
                        TableTypes.State.READY),
                "95F4D5E1");
    }

    private static SpellTexts.Description auraPreview(
            String raw,
            List<Db2Types.Row> effects,
            List<Db2Types.Row> options,
            List<Db2Types.Row> radii) {
        return auraPreview(raw, effects, options, radii, TableTypes.State.READY, List.of());
    }

    private static SpellTexts.Description auraPreview(
            String raw,
            List<Db2Types.Row> effects,
            List<Db2Types.Row> options,
            List<Db2Types.Row> radii,
            TableTypes.State optionsState,
            List<SpellTexts.EffectOverride> overrides) {
        var optionsTable = fullAuraOptions(options);
        var e = optionsTable.entry();
        optionsTable =
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                e.fileDataId(),
                                e.name(),
                                optionsState,
                                true,
                                null,
                                e.counts(),
                                e.contentKey()),
                        optionsTable.columns(),
                        optionsTable.rows(),
                        optionsTable.schema(),
                        Map.of(),
                        true);
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16929, raw)),
                                                TableTypes.State.READY),
                                        table(
                                                2,
                                                "SpellEffect",
                                                FIELDS,
                                                effects,
                                                TableTypes.State.READY),
                                        optionsTable,
                                        layout(
                                                table(
                                                        6,
                                                        "SpellRadius",
                                                        List.of(
                                                                "ID",
                                                                "Radius",
                                                                "RadiusPerLevel",
                                                                "RadiusMin",
                                                                "RadiusMax"),
                                                        radii,
                                                        TableTypes.State.READY),
                                                "F9A913EE")));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70790")) {
            return service.description(
                            16929, overrides, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join();
        }
    }

    private static Db2Types.Row areaEffect(long id, String period, String first, String second) {
        var values = new ArrayList<>(scalar(id, 0, null).values());
        values.set(FIELDS.indexOf("EffectAuraPeriod"), period);
        values.set(FIELDS.indexOf("EffectRadiusIndex[0]"), first);
        values.set(FIELDS.indexOf("EffectRadiusIndex[1]"), second);
        return new Db2Types.Row(id, false, null, values, List.of());
    }

    @Test
    void countsOutOfRangeMissingFieldsPartialLookupsAndRankOverridesStayExact() {
        var over =
                auraPreview(
                        "Next $n swings, up to $u stacks.",
                        List.of(),
                        List.of(row(7, "7", "0", "1001", "0", "5000", "0", "16929")),
                        List.of());
        assertEquals("Next ? swings, up to ? stacks.", over.display());
        assertEquals(
                "SpellAuraOptions records no proc charges (1-1000)",
                over.substitutions().getFirst().issue());
        var partial =
                auraPreview(
                        "A $h% chance for $n swings.",
                        List.of(),
                        List.of(row(7, "7", "0", "5", "30", "3", "0", "16929")),
                        List.of(),
                        TableTypes.State.PARTIAL,
                        List.of());
        // Like $h, $n uses the readable records of a PARTIAL table, with a notice.
        assertEquals("A 30% chance for 3 swings.", partial.display());
        assertTrue(partial.issues().stream().anyMatch(i -> i.contains("PARTIAL")));
        var missing = procPreview("Next $n swings.", List.of(row(7, "7", "0", "30", "0", "16929")));
        assertEquals("Next ? swings.", missing.display());
        assertEquals(
                "SpellAuraOptions has no verified ProcCharges field",
                missing.substitutions().getFirst().issue());
        var none = procPreview("Next $n swings, $h%.", List.of());
        assertEquals(
                "SpellAuraOptions records no DifficultyID 0 row for this spell",
                none.substitutions().getFirst().issue());
        // A rank override replaces the amount but keeps the effect's radius.
        var ranked =
                auraPreview(
                        "Grants $s1% within $a1 yds.",
                        List.of(areaEffect(101, "0", "11", "11")),
                        List.of(),
                        List.of(row(11, "11", "45", "0", "0", "45")),
                        TableTypes.State.READY,
                        List.of(
                                new SpellTexts.EffectOverride(
                                        0, new BigDecimal("8"), null, List.of())));
        assertEquals("Grants 8% within 45 yds.", ranked.display());
    }

    @Test
    void chargesStacksPeriodAndRadiusComeFromTheirRecordedFields() {
        var result =
                auraPreview(
                        "Next $n swings, up to $u stacks, every $t1 sec, within $a1 yds.",
                        List.of(areaEffect(101, "3000", "11", "11")),
                        List.of(row(7, "7", "0", "5", "0", "3", "0", "16929")),
                        List.of(row(11, "11", "45", "0", "0", "45")));
        assertEquals(
                "Next 3 swings, up to 5 stacks, every 3 sec, within 45 yds.", result.display());
        assertEquals(SpellTexts.State.RESOLVED, result.state());
        assertEquals(
                "Every 1.5 sec.",
                auraPreview(
                                "Every $t1 sec.",
                                List.of(areaEffect(101, "1500", "0", "0")),
                                List.of(),
                                List.of())
                        .display());
        // Unknown: no charges or stacks, not periodic, a period with thousandths, no radius,
        // a different second radius, per-level radius and a radius range.
        assertEquals(
                "Next ? swings, up to ? stacks.",
                auraPreview(
                                "Next $n swings, up to $u stacks.",
                                List.of(),
                                List.of(row(7, "7", "0", "0", "0", "0", "0", "16929")),
                                List.of())
                        .display());
        for (var effect :
                List.of(areaEffect(101, "0", "0", "0"), areaEffect(101, "1234", "0", "0"))) {
            assertEquals(
                    "Every ? sec.",
                    auraPreview("Every $t1 sec.", List.of(effect), List.of(), List.of()).display());
        }
        for (var radius :
                List.of(
                        List.of(
                                areaEffect(101, "0", "0", "13"),
                                row(13, "13", "10", "0", "0", "10")),
                        List.of(
                                areaEffect(101, "0", "11", "13"),
                                row(11, "11", "45", "0", "0", "45")),
                        List.of(
                                areaEffect(101, "0", "11", "0"),
                                row(11, "11", "45", "1", "0", "45")),
                        List.of(
                                areaEffect(101, "0", "11", "0"),
                                row(11, "11", "15", "0", "10", "15")))) {
            assertEquals(
                    "Within ? yds.",
                    auraPreview(
                                    "Within $a1 yds.",
                                    List.of(radius.get(0)),
                                    List.of(),
                                    List.of(radius.get(1)))
                            .display(),
                    radius.toString());
        }
    }

    @Test
    void procChanceReadsTheSingleDifficultyZeroAuraOptionsRow() {
        var result =
                procPreview(
                        "A $h% chance.",
                        List.of(
                                row(7, "7", "0", "30", "0", "16929"),
                                row(8, "8", "1", "50", "0", "16929")));
        assertEquals("A 30% chance.", result.display());
        assertTrue(
                result.substitutions().getFirst().sources().stream()
                        .anyMatch(s -> s.table().equals("SpellAuraOptions") && s.rowId() == 7));
        for (var rows :
                List.of(
                        List.of(row(7, "7", "0", "0", "0", "16929")),
                        List.of(row(7, "7", "0", "101", "0", "16929")),
                        List.of(row(7, "7", "0", "30", "4", "16929")),
                        List.of(
                                row(7, "7", "0", "30", "0", "16929"),
                                row(9, "9", "0", "20", "0", "16929")),
                        List.<Db2Types.Row>of())) {
            assertEquals(
                    "A ?% chance.", procPreview("A $h% chance.", rows).display(), rows.toString());
        }
    }

    private static Db2Types.Row typed(long id, String effect, String aura, String coefficient) {
        var values = new ArrayList<>(scalar(id, 0, null).values());
        values.set(FIELDS.indexOf("Effect"), effect);
        values.set(FIELDS.indexOf("EffectAura"), aura);
        values.set(FIELDS.indexOf("EffectBasePointsF"), "7");
        values.set(FIELDS.indexOf("EffectBonusCoefficient"), coefficient);
        return new Db2Types.Row(id, false, null, values, List.of());
    }

    @Test
    void aCoefficientOfOneIsInertOnlyForReviewedAuraTypes() {
        // Spell power scaled or unreviewed: health leech (9), school damage (2), heal (10), and
        // periodic damage/heal, mana shield, periodic dummy and heal absorb auras.
        for (var type :
                List.of(
                        List.of("9", "0"),
                        List.of("2", "0"),
                        List.of("10", "0"),
                        List.of("3", "0"),
                        List.of("6", "3"),
                        List.of("6", "8"),
                        List.of("6", "4"),
                        List.of("6", "97"),
                        List.of("6", "226"),
                        List.of("6", "301"))) {
            var scaled =
                    scalarPreview(
                            "Value $s1.",
                            List.of(typed(101, type.get(0), type.get(1), "1")),
                            List.of(),
                            List.of());
            assertEquals("Value ?.", scaled.display(), type.toString());
            var unscaled =
                    scalarPreview(
                            "Value $s1.",
                            List.of(typed(101, type.get(0), type.get(1), "0")),
                            List.of(),
                            List.of());
            assertEquals("Value 7.", unscaled.display(), type.toString());
        }
        for (int aura : SpellTexts.INERT_COEFFICIENT_AURAS) {
            assertEquals(
                    "Value 7.",
                    scalarPreview(
                                    "Value $s1.",
                                    List.of(typed(101, "6", Integer.toString(aura), "1")),
                                    List.of(),
                                    List.of())
                            .display(),
                    Integer.toString(aura));
        }
    }

    @Test
    void totalPeriodicValueRefusesSpellsThatTickOnApplication() {
        var tick = new ArrayList<>(scalar(101, 0, null).values());
        tick.set(FIELDS.indexOf("EffectAura"), "3");
        tick.set(FIELDS.indexOf("EffectBasePointsF"), "4");
        tick.set(FIELDS.indexOf("EffectAuraPeriod"), "3000");
        var misc = miscTable(List.of(row(90, "16929", "0", "1", "0", "0")));
        var rows = new ArrayList<Db2Types.Row>();
        for (var row : misc.rows()) {
            var values = new ArrayList<>(row.values());
            values.set(6, "512");
            rows.add(new Db2Types.Row(row.id(), false, null, values, List.of()));
        }
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16929, "Bleeds $o1 over $d.")),
                                                TableTypes.State.READY),
                                        table(
                                                2,
                                                "SpellEffect",
                                                FIELDS,
                                                List.of(
                                                        new Db2Types.Row(
                                                                101, false, null, tick, List.of())),
                                                TableTypes.State.READY),
                                        new MemoryTables.FakeTable(
                                                misc.entry(),
                                                misc.columns(),
                                                rows,
                                                misc.schema(),
                                                Map.of(),
                                                true),
                                        layout(
                                                table(
                                                        4,
                                                        "SpellDuration",
                                                        List.of(
                                                                "Duration",
                                                                "DurationPerLevel",
                                                                "MaxDuration",
                                                                "DurationPerResource"),
                                                        List.of(row(1, "15000", "0", "15000", "0")),
                                                        TableTypes.State.READY),
                                                "4FDF0951")));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70790")) {
            var result =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals("Bleeds ? over 15 sec.", result.display());
            assertEquals(
                    "The spell also ticks on application; the total is not computed",
                    result.substitutions().getFirst().issue());
        }
    }

    @Test
    void totalPeriodicValueMultipliesAFixedTickByWholeTicks() {
        var tick = new ArrayList<>(scalar(101, 0, null).values());
        tick.set(FIELDS.indexOf("EffectAura"), "3");
        tick.set(FIELDS.indexOf("EffectBasePointsF"), "4");
        tick.set(FIELDS.indexOf("EffectAuraPeriod"), "3000");
        var result =
                scalarPreview(
                        "Bleeds $o1 over $d, $m1 a tick.",
                        List.of(new Db2Types.Row(101, false, null, tick, List.of())),
                        List.of(row(90, "16929", "0", "1", "0", "0")),
                        List.of(row(1, "15000", "0", "15000", "0")));
        assertEquals("Bleeds 20 over 15 sec, 4 a tick.", result.display());
        tick.set(FIELDS.indexOf("EffectBonusCoefficient"), "0.129");
        assertEquals(
                "Bleeds ? over 15 sec, ? a tick.",
                scalarPreview(
                                "Bleeds $o1 over $d, $m1 a tick.",
                                List.of(new Db2Types.Row(101, false, null, tick, List.of())),
                                List.of(row(90, "16929", "0", "1", "0", "0")),
                                List.of(row(1, "15000", "0", "15000", "0")))
                        .display());
    }

    @Test
    void rankOverridesReplaceTheBaseAndAreNeverCached() {
        var world = world(List.of(effect(99, "2", null)), false);
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70707")) {
            var source = new SpellTexts.Source("CurvePoint", 85348, "Pos at rank 1", "3");
            var ranked =
                    service.description(
                                    16929,
                                    List.of(
                                            new SpellTexts.EffectOverride(
                                                    0, new BigDecimal("3"), null, List.of(source))),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals("Increases armor by 3%.", ranked.display());
            // The reverse: a cached plain preview never answers a later override request.
            assertEquals(
                    "Increases armor by 2%.",
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join()
                            .display());
            assertEquals(
                    "Increases armor by 4%.",
                    service.description(
                                    16929,
                                    List.of(
                                            new SpellTexts.EffectOverride(
                                                    0, new BigDecimal("4"), null, List.of())),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .display());
            assertTrue(ranked.substitutions().getFirst().sources().contains(source));
            var unknown =
                    service.description(
                                    16929,
                                    List.of(
                                            new SpellTexts.EffectOverride(
                                                    0, null, "No rank value", List.of())),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals("Increases armor by ?%.", unknown.display());
            assertEquals("No rank value", unknown.substitutions().getFirst().issue());
            assertEquals(
                    "Increases armor by 2%.",
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join()
                            .display());
        }
    }

    @Test
    void fixedAmountsNeedEveryReviewedContextGuardButNoUnit() {
        for (String guard :
                List.of(
                        "Variance",
                        "EffectRealPointsPerLevel",
                        "EffectPointsPerResource",
                        "BonusCoefficientFromAP",
                        "Coefficient",
                        "ResourceCoefficient",
                        "EffectBonusCoefficient",
                        "GroupSizeBasePointsCoefficient",
                        "PvpMultiplier",
                        "ScalingClass",
                        "EffectAttributes")) {
            var result =
                    scalarPreview(
                            "Slow by $s1%.", List.of(scalar(101, 0, guard)), List.of(), List.of());
            assertEquals("Slow by ?%.", result.display(), guard);
            assertEquals(SpellTexts.State.UNRESOLVED, result.state(), guard);
        }
        var unitless =
                scalarPreview(
                        "Amount $s1; dummy $s2.",
                        List.of(scalar(101, 0, null), scalar(102, 1, null)),
                        List.of(),
                        List.of());
        // The recorded number does not depend on a unit; a unitless use shows it as well.
        assertEquals("Amount 50; dummy 80.", unitless.display());
        var duplicate =
                scalarPreview(
                        "$s1%",
                        List.of(scalar(101, 0, null), scalar(102, 0, null)), List.of(), List.of());
        assertEquals("?%", duplicate.display());
    }

    @Test
    void durationOnlyRejectsScalingIndefiniteFractionalAndAmbiguousSources() {
        for (var row :
                List.of(
                        row(1, "-1", "0", "-1", "0"),
                        row(1, "10000", "1", "10000", "0"),
                        row(1, "10000", "0", "10000", "1"),
                        row(1, "1500", "0", "1500", "0"),
                        row(1, "10000", "0", "20000", "0"))) {
            var result =
                    scalarPreview(
                            "Lasts $d.",
                            List.of(),
                            List.of(row(90, "16929", "0", "1", "0", "0")),
                            List.of(row));
            assertEquals("Lasts ?.", result.display(), row.toString());
        }
        var fixed =
                scalarPreview(
                        "Lasts $d.",
                        List.of(),
                        List.of(row(90, "16929", "0", "1", "0", "0")),
                        List.of(row(1, "10000", "0", "10000", "0")));
        assertEquals("Lasts 10 sec.", fixed.display());
        for (var misc :
                List.of(
                        List.of(row(90, "16929", "0", "1", "1", "0")),
                        List.of(row(90, "16929", "0", "1", "0", "1")),
                        List.of(
                                row(90, "16929", "0", "1", "0", "0"),
                                row(91, "16929", "0", "1", "0", "0")))) {
            assertEquals(
                    "Lasts ?.",
                    scalarPreview(
                                    "Lasts $d.",
                                    List.of(),
                                    misc,
                                    List.of(row(1, "10000", "0", "10000", "0")))
                            .display());
        }
    }

    @Test
    void resolvesSameBuildEffectIndexZeroFromPartialReadableRowsWithoutAddingOne() {
        var world = world(List.of(effect(99, "4.0", null)), false);
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70707")) {
            var result =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals("Increases armor by 4%.", result.display());
            assertEquals(SpellTexts.State.RESOLVED, result.state());
            assertEquals(99, result.substitutions().getFirst().sources().getFirst().rowId());
            assertEquals(
                    "SpellID", result.substitutions().getFirst().sources().getFirst().column());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("PARTIAL")));
            int reads = world.calls("get");
            assertEquals(
                    result,
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join());
            assertEquals(reads, world.calls("get"));
            assertEquals(
                    SpellTexts.State.UNAVAILABLE,
                    service.description(1000, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void reviewedForeverCritAuraMapsTheFirstTokenToTwoPercent() {
        var sample = effect(693970, "2", null);
        var values = new ArrayList<>(sample.values());
        values.set(FIELDS.indexOf("SpellID"), "16942");
        values.set(FIELDS.indexOf("EffectAura"), "290");
        var world =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Spell",
                                                List.of("Description_lang"),
                                                List.of(row(16942, "Chance rises by $s1%.")),
                                                TableTypes.State.PARTIAL),
                                        table(
                                                2,
                                                "SpellEffect",
                                                FIELDS,
                                                List.of(
                                                        new Db2Types.Row(
                                                                693970, false, null, values,
                                                                List.of())),
                                                TableTypes.State.PARTIAL)));
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70707")) {
            var result =
                    service.description(16942, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals("Chance rises by 2%.", result.display());
            assertEquals("2", result.substitutions().getFirst().value());
            assertEquals(0, result.substitutions().getFirst().effectIndex());
            assertTrue(
                    result.substitutions().getFirst().sources().stream()
                            .anyMatch(
                                    source ->
                                            source.rowId() == 693970
                                                    && source.column().equals("EffectBasePointsF")
                                                    && source.value().equals("2")));
        }
    }

    @Test
    void dynamicAmbiguousLegacyAndMissingEffectsRemainUnresolved() {
        for (String context :
                List.of(
                        "Variance",
                        "EffectRealPointsPerLevel",
                        "Coefficient",
                        "EffectPointsPerResource",
                        "ScalingClass",
                        "Effect",
                        "GroupSizeBasePointsCoefficient",
                        "EffectBonusCoefficient",
                        "PvpMultiplier")) {
            assertUnknown(world(List.of(effect(99, "5", context)), false));
        }
        assertUnknown(world(List.of(effect(99, "5", null), effect(100, "5", null)), false));
        // A periodic damage aura scales with spell power when its coefficient is not zero.
        var periodic = new ArrayList<>(effect(99, "5", null).values());
        periodic.set(FIELDS.indexOf("EffectAura"), "3");
        assertUnknown(
                world(List.of(new Db2Types.Row(99, false, null, periodic, List.of())), false));
        assertUnknown(world(List.of(effect(99, "5", null)), true));
        assertUnknown(world(List.of(), false));
    }

    private static void assertUnknown(MemoryTables world) {
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
                var service = new SpellTexts(tables, "1.60.1.70707")) {
            var result =
                    service.description(16929, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals(SpellTexts.State.UNRESOLVED, result.state());
            assertEquals("Increases armor by ?%.", result.display());
            assertFalse(result.display().contains("$"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void admissionIsBoundedAndCloseCancelsAndDrainsAcceptedReads() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(4);
        var waiting =
                new java.util.concurrent.CopyOnWriteArrayList<
                        java.util.concurrent.CompletableFuture<TableTypes.SchemaResult>>();
        var tables =
                (com.vibeminer.formats.tables.BuildTables)
                        java.lang.reflect.Proxy.newProxyInstance(
                                com.vibeminer.formats.tables.BuildTables.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.formats.tables.BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("build")) {
                                        return BUILD;
                                    }
                                    if (method.getName().equals("locale")) {
                                        return "enUS";
                                    }
                                    if (method.getName().equals("schema")) {
                                        var future =
                                                new java.util.concurrent.CompletableFuture<
                                                        TableTypes.SchemaResult>();
                                        var token = (Cancellation) args[2];
                                        token.onCancel(
                                                () ->
                                                        future.completeExceptionally(
                                                                new java.util.concurrent
                                                                        .CancellationException()));
                                        waiting.add(future);
                                        entered.countDown();
                                        return future;
                                    }
                                    throw new AssertionError(
                                            "Unexpected borrowed-table call: " + method.getName());
                                });
        var caller = new Cancellation();
        var service = new SpellTexts(tables, "1.60.1.70707");
        var accepted =
                new ArrayList<java.util.concurrent.CompletableFuture<SpellTexts.Description>>();
        for (int i = 0; i < 4; i++) {
            accepted.add(service.description(16929 + i, TableTypes.Priority.INTERACTIVE, caller));
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.description(17000, TableTypes.Priority.INTERACTIVE, caller).join());
        service.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        for (var future : accepted) {
            assertThrows(RuntimeException.class, future::join);
        }
        assertFalse(caller.isCanceled());
        assertTrue(
                waiting.stream()
                        .allMatch(
                                java.util.concurrent.CompletableFuture::isCompletedExceptionally));
    }

    @Test
    void closeDoesNotCloseBorrowedTablesOrCancelCallerToken() {
        var world = world(List.of(effect(99, "5", null)), false);
        try (var tables = world.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var token = new Cancellation();
            var service = new SpellTexts(tables, "1.60.1.70707");
            service.description(16929, TableTypes.Priority.INTERACTIVE, token).join();
            service.close();
            assertFalse(token.isCanceled());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            service.description(16929, TableTypes.Priority.INTERACTIVE, token)
                                    .join());
            assertEquals(
                    TableTypes.State.PARTIAL,
                    tables.schema(
                                    TableTypes.Ref.forName("Spell"),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join()
                            .table()
                            .state());
        }
    }
}
