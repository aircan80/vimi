package com.vibeminer.concepts.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.npcs.NpcCatalog.Input;
import com.vibeminer.concepts.npcs.NpcCatalog.Levels;
import com.vibeminer.concepts.npcs.NpcCatalog.Npc;
import com.vibeminer.concepts.npcs.NpcCatalog.Observation;
import com.vibeminer.concepts.npcs.NpcCatalog.Provider;
import com.vibeminer.concepts.npcs.NpcCatalog.Source;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class NpcCatalogTest {
    private static Observation row(
            long id, Provider provider, String name, String title, Levels levels) {
        return new Observation(
                id,
                name,
                title,
                levels,
                new Source(provider, "snapshot", "product", "1.60.1.70532", "enUS"));
    }

    @Test
    void mergesEachFieldByPriorityAndPreservesAllAlternatives() {
        var blizzard = row(1, Provider.BLIZZARD, "Build name", null, null);
        var questie = row(1, Provider.QUESTIE, "Questie name", "", new Levels(10, 12));
        var polestar = row(1, Provider.POLESTAR, "Polestar name", "Vendor", new Levels(20, 20));
        var eg = row(1, Provider.EG_LINK, "EG name", "Trainer", null);
        var result =
                NpcCatalog.merge(
                                List.of(eg, polestar, questie, blizzard),
                                List.of(),
                                Cancellation.NONE)
                        .npcs()
                        .getFirst();
        assertEquals("Build name", result.name());
        assertEquals("", result.subname());
        assertEquals(new Levels(10, 12), result.levels());
        assertEquals(List.of(blizzard, questie, polestar, eg), result.evidence());
    }

    @Test
    void unionsIdsAndKeepsUnknownValuesInsteadOfInventingThem() {
        var result =
                NpcCatalog.merge(
                        List.of(
                                row(9, Provider.EG_LINK, null, null, null),
                                row(2, Provider.QUESTIE, "Other", null, null)),
                        List.of(),
                        Cancellation.NONE);
        assertEquals(List.of(2L, 9L), result.npcs().stream().map(Npc::id).toList());
        assertNull(result.npcs().getLast().name());
    }

    @Test
    void failedProviderDoesNotDiscardOtherSources() {
        var result =
                NpcCatalog.load(
                        List.of(
                                new Input(
                                        Provider.BLIZZARD,
                                        t -> {
                                            throw new java.io.IOException("offline");
                                        }),
                                new Input(
                                        Provider.QUESTIE,
                                        t ->
                                                List.of(
                                                        row(
                                                                1,
                                                                Provider.QUESTIE,
                                                                "Name",
                                                                null,
                                                                null)))),
                        Cancellation.NONE);
        assertEquals(1, result.npcs().size());
        assertTrue(result.diagnostics().getFirst().contains("unavailable"));
    }

    @Test
    void cancellationIsNotTreatedAsUnavailable() {
        var token = new Cancellation();
        token.cancel();
        assertThrows(CancellationException.class, () -> NpcCatalog.load(List.of(), token));
    }

    @Test
    void normalizesProviderFieldsAndKeepsMissingTitleDistinctFromEmpty() {
        var source = new Source(Provider.QUESTIE, "v", "Forever", null, "enUS");
        var known =
                NpcSources.from(
                        1,
                        Map.of("name", "NPC", "subName", "", "minLevel", 5L, "maxLevel", 8L),
                        "subName",
                        "minLevel",
                        "maxLevel",
                        source);
        assertEquals("", known.subname());
        assertEquals(new Levels(5, 8), known.levels());
        var unknown =
                NpcSources.from(
                        2,
                        Map.of("minLevel", 10, "maxLevel", 5),
                        "subName",
                        "minLevel",
                        "maxLevel",
                        source);
        assertNull(unknown.subname());
        assertNull(unknown.levels());
    }

    @Test
    void egParserUsesNpcLevelsAndIgnoresNestedQuestTitles() throws Exception {
        var fields =
                NpcSources.egFields(
                        "{\"name\":\"NPC\",\"levels\":[12,10],\"offers\":{\"4\":{\"title\":\"Quest\"}}}");
        assertEquals("NPC", fields.get("name"));
        assertEquals(10, fields.get("minLevel"));
        assertEquals(12, fields.get("maxLevel"));
        assertFalse(fields.containsKey("title"));
    }
}
