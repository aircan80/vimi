package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureFactsState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.formats.db2.Db2Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CreatureRulesTest {
    private CreatureLayout layout() {
        var t = CreatureWorld.creatures(List.of());
        return CreatureLayout.resolve(t.columns(), t.schema());
    }

    private Db2Types.Row cell(int column, String value) {
        var row = CreatureWorld.creature(7, "Hogger", 10, 0, -1, Integer.MAX_VALUE);
        var values = new ArrayList<>(row.values());
        values.set(column, value);
        return new Db2Types.Row(7, false, null, values, null);
    }

    @Test
    void exactProjectionPreservesSourceFactsSlotsAndCopy() {
        var original = CreatureWorld.creature(7, "  ", 10, 0, -1, Integer.MAX_VALUE);
        var row = new Db2Types.Row(7, true, 6L, original.values(), null);
        var result = layout().summary(row);
        assertEquals(CreatureNameState.VALID, result.name().state());
        assertEquals("  ", result.name().value());
        assertEquals(6L, result.copySourceId());
        assertEquals(
                List.of("-128", "255", "65535", "127"),
                result.facts().stream().map(f -> f.value()).toList());
        assertEquals(
                List.of(
                        CreatureSlotState.DECLARED,
                        CreatureSlotState.NO_DECLARED_DISPLAY,
                        CreatureSlotState.INVALID_DISPLAY_REFERENCE,
                        CreatureSlotState.DECLARED),
                result.slots().stream().map(s -> s.state()).toList());
    }

    @Test
    void rejectsWidthSignNoncanonicalAndNonfinite() {
        for (var test :
                List.of(
                        new Object[] {5, "128"},
                        new Object[] {6, "-1"},
                        new Object[] {7, "65536"},
                        new Object[] {8, "+1"},
                        new Object[] {9, "2147483648"},
                        new Object[] {13, "NaN"},
                        new Object[] {13, "1.0"},
                        new Object[] {13, "Infinity"})) {
            assertThrows(
                    CreatureLayout.Failure.class,
                    () -> layout().summary(cell((int) test[0], (String) test[1])));
        }
        assertEquals(-0.0f, layout().summary(cell(13, "-0")).slots().getFirst().probability());
    }

    @Test
    void malformedRowAndPrimaryNameIssues() {
        var row = CreatureWorld.creature(7, "");
        assertEquals(CreatureNameState.EMPTY, layout().summary(row).name().state());
        var invalid =
                new Db2Types.Row(
                        7,
                        false,
                        null,
                        row.values(),
                        List.of(new Db2Types.TextIssue(1, "DB2_TEXT_ENCODING_INVALID", "FF")));
        assertEquals(CreatureNameState.INVALID, layout().summary(invalid).name().state());
        assertNull(layout().summary(invalid).name().value());
        assertThrows(
                CreatureLayout.Failure.class,
                () -> layout().summary(new Db2Types.Row(7, true, null, row.values(), null)));
        assertThrows(
                CreatureLayout.Failure.class,
                () ->
                        layout().summary(
                                        new Db2Types.Row(
                                                7,
                                                false,
                                                null,
                                                row.values(),
                                                List.of(new Db2Types.TextIssue(99, "bad", "FF")))));
    }

    @Test
    void duplicateHeaderAndMissingDefinitionRefused() {
        var t = CreatureWorld.creatures(List.of());
        var cols = new ArrayList<>(t.columns());
        cols.add(cols.get(1));
        assertThrows(CreatureLayout.Failure.class, () -> CreatureLayout.resolve(cols, t.schema()));
        assertThrows(CreatureLayout.Failure.class, () -> CreatureLayout.resolve(t.columns(), null));
    }

    @Test
    void classificationIsExactReviewedScalar() {
        assertEquals(
                List.of("Normal", "Elite", "Rare elite", "World boss", "Rare", "Trivial", "Minus"),
                java.util.stream.IntStream.range(0, 7)
                        .mapToObj(i -> CreatureLayout.classificationLabel(Integer.toString(i)))
                        .toList());
        for (String text : List.of("-1", "7", "01", " 1", "1 ")) {
            assertNull(CreatureLayout.classificationLabel(text));
        }
    }

    @Test
    void reviewedEdgesFactsAndTextureEvidence() {
        var t = CreatureWorld.displays(List.of());
        var row = CreatureWorld.display(100, "65535");
        var role = CreatureDisplayRules.Role.DISPLAY;
        var edge = CreatureDisplayRules.edgeColumn(role, "7275F5F6", t.columns(), t.schema());
        assertEquals(1, edge);
        assertEquals(65535L, CreatureDisplayRules.edge(row, edge, role, 100));
        assertNull(CreatureDisplayRules.edge(row, edge, role, 101));
        assertNull(CreatureDisplayRules.edge(CreatureWorld.display(100, "65536"), edge, role, 100));
        assertNull(CreatureDisplayRules.edgeColumn(role, "FFFFFFFF", t.columns(), t.schema()));
        var facts = CreatureDisplayRules.facts(role, t.columns(), row);
        assertEquals(CreatureFactsState.READY, facts.state());
        assertEquals(
                List.of("model", "scale", "textureVariations"),
                facts.facts().stream().map(f -> f.key()).toList());
        var evidence = CreatureDisplayRules.textureEvidence(t.columns(), row);
        assertNotNull(evidence);
        assertEquals(List.of(800, 0, 0, 0), evidence.textureVariations());
    }

    @Test
    void factsRefuseIncompleteOrUnboundedEvidence() {
        var t = CreatureWorld.displays(List.of());
        var row = CreatureWorld.display(100, "1");
        assertEquals(
                CreatureFactsState.ROW_NOT_WHOLE,
                CreatureDisplayRules.facts(
                                CreatureDisplayRules.Role.DISPLAY,
                                t.columns(),
                                new Db2Types.Row(100, false, null, List.of("100"), null))
                        .state());
        var many = new ArrayList<Db2Types.Column>();
        for (int i = 0; i < 513; i++) {
            many.add(t.columns().getFirst());
        }
        assertEquals(
                CreatureFactsState.LIMIT_EXCEEDED,
                CreatureDisplayRules.facts(CreatureDisplayRules.Role.DISPLAY, many, row).state());
    }
}
