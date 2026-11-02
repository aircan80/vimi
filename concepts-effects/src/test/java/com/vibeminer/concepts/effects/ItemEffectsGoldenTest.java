package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.vibeminer.concepts.effects.EffectsTypes.Entry;
import com.vibeminer.concepts.effects.EffectsTypes.Field;
import com.vibeminer.concepts.effects.EffectsTypes.Page;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.formats.tables.MemoryTables.FakeTable;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Actual original C# ItemEffectsService outputs over original InMemoryTables and bundled
 * definitions.
 */
final class ItemEffectsGoldenTest {
    static byte[] golden(String name) throws Exception {
        try (var input = ItemEffectsGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }

    private static List<FakeTable> scenario(String name) {
        return switch (name) {
            case "retail" -> EffectsWorld.retail();
            case "classic" ->
                    List.of(
                            EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                            EffectsWorld.classicTable(
                                    List.of(
                                            EffectsWorld.classic(7, 1, 0),
                                            EffectsWorld.classic(8, 1, -1))),
                            EffectsWorld.nameTable(List.of(EffectsWorld.name(0, "Zero"))));
            case "partial" ->
                    List.of(
                            EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                            EffectsWorld.bridgeTable(
                                    List.of(
                                            EffectsWorld.bridge(1, 3, 1),
                                            EffectsWorld.bridge(2, 4, 1))),
                            EffectsWorld.retailTable(
                                    List.of(EffectsWorld.effect(0, 0)),
                                    TableTypes.State.PARTIAL,
                                    List.of(),
                                    Map.of(4L, EffectsWorld.KEY)),
                            EffectsWorld.nameTable(List.of()));
            case "raw" ->
                    List.of(
                            EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                            EffectsWorld.bridgeTable(List.of(EffectsWorld.bridge(1, 0, 1))),
                            EffectsWorld.retailTable(
                                    List.of(
                                            EffectsWorld.effect(
                                                    0,
                                                    0,
                                                    List.of(
                                                            "255",
                                                            "0",
                                                            "4294967295",
                                                            "-5",
                                                            "-6",
                                                            "65535",
                                                            "0",
                                                            "65535",
                                                            "-1"),
                                                    null)),
                                    TableTypes.State.READY,
                                    List.of(3),
                                    Map.of()),
                            EffectsWorld.nameTable(List.of(EffectsWorld.name(0, "Zero"))));
            case "field" ->
                    List.of(
                            EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                            EffectsWorld.bridgeTable(List.of(EffectsWorld.bridge(1, 0, 1))),
                            EffectsWorld.retailTable(
                                    List.of(
                                            EffectsWorld.effect(
                                                    0,
                                                    0,
                                                    List.of(
                                                            "1", "2", "32768", "4", "5", "6", "0",
                                                            "8", "9"),
                                                    null))),
                            EffectsWorld.nameTable(List.of(EffectsWorld.name(0, "Zero"))));
            case "invalid-name" ->
                    List.of(
                            EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                            EffectsWorld.bridgeTable(List.of(EffectsWorld.bridge(1, 0, 1))),
                            EffectsWorld.retailTable(List.of(EffectsWorld.effect(0, 0))),
                            EffectsWorld.nameTable(
                                    List.of(
                                            new Db2Types.Row(
                                                    0,
                                                    false,
                                                    null,
                                                    List.of("0", ""),
                                                    List.of(
                                                            new Db2Types.TextIssue(
                                                                    1,
                                                                    "DB2_TEXT_ENCODING_INVALID",
                                                                    "ff"))))));
            default -> throw new IllegalArgumentException(name);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"retail", "classic", "partial", "raw", "field", "invalid-name"})
    void actualOriginalServiceKeepsSameGameFactsAndRawEvidence(String name) throws Exception {
        var world = new EffectsWorld().with(scenario(name).toArray(FakeTable[]::new));
        Page page = ItemEffectsRulesTest.page(world);
        assertEquals(
                Json.parse(golden(name + ".json"), 1 << 20),
                Json.parse(Json.bytes(projection(page)), 1 << 20));
    }

    @Test
    void originalBindingAndCanonicalWebCursorAreExact() throws Exception {
        String binding =
                new String(golden("binding.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String cursor =
                new String(golden("cursor.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(binding, ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 1, 2));
        var decoded = ItemEffectQueries.decodeCursor(cursor, binding);
        assertNotNull(decoded);
        assertEquals(2, decoded.a());
        assertEquals("fake:identity", decoded.i());
        assertEquals("ItemXItemEffect", decoded.l());
        assertEquals(cursor, ItemEffectQueries.encodeCursor(decoded));
    }

    private static String name(Enum<?> value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String word : value.name().split("_")) {
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    private static Map<String, Object> map(Object... fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            map.put((String) fields[i], fields[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> projection(Page p) {
        List<Object> entries = new ArrayList<>();
        for (Entry e : p.entries()) {
            var effect = e.effect();
            var text = e.name();
            List<Object> fields = null;
            if (effect.fields() != null) {
                fields = new ArrayList<>();
                for (Field f : effect.fields()) {
                    fields.add(
                            map(
                                    "name",
                                    f.name(),
                                    "column",
                                    f.columnIndex(),
                                    "value",
                                    f.value(),
                                    "evidence",
                                    name(f.evidence()),
                                    "reference",
                                    f.candidateReference() == null
                                            ? null
                                            : f.candidateReference().table()));
                }
            }
            entries.add(
                    map(
                            "linkId",
                            e.link().row().id(),
                            "parent",
                            e.parentItemId(),
                            "effectId",
                            e.itemEffectId(),
                            "copy",
                            e.link().row().isCopy(),
                            "effectState",
                            name(effect.state()),
                            "fields",
                            fields,
                            "spell",
                            effect.spellId(),
                            "effectAbsence",
                            name(effect.absence()),
                            "effectKey",
                            effect.protectedKey(),
                            "nameState",
                            name(text.state()),
                            "text",
                            text.text(),
                            "textUnits",
                            text.textUnits(),
                            "issue",
                            text.issueCode(),
                            "nameAbsence",
                            name(text.absence()),
                            "nameKey",
                            text.protectedKey()));
        }
        return map(
                "state",
                name(p.state()),
                "itemId",
                p.itemId(),
                "link",
                p.link() == null ? null : p.link().cursorName(),
                "matches",
                p.matches(),
                "bridge",
                name(p.bridge().state()),
                "effect",
                name(p.effect().state()),
                "name",
                name(p.name().state()),
                "entries",
                entries);
    }
}
