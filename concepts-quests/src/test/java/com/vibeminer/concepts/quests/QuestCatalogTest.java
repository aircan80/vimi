package com.vibeminer.concepts.quests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.quests.QuestCatalog.Observation;
import com.vibeminer.concepts.quests.QuestCatalog.Provider;
import com.vibeminer.concepts.quests.QuestCatalog.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestCatalogTest {
    private static Source source(Provider p) {
        return new Source(p, "snapshot", "Forever", "1.60.1.70532", "enUS");
    }

    @Test
    void combinesBlizzardIdsWithProviderTitlesAndSelectsEachFieldIndependently() {
        var result =
                QuestCatalog.merge(
                        List.of(
                                new Observation(9, null, null, null, source(Provider.BLIZZARD)),
                                new Observation(
                                        9, "Questie title", 8, 15, source(Provider.QUESTIE)),
                                new Observation(
                                        9, "Polestar title", 10, 16, source(Provider.POLESTAR)),
                                new Observation(9, "EG title", 5, 20, source(Provider.EG_LINK)),
                                new Observation(1, null, null, null, source(Provider.BLIZZARD))),
                        List.of(),
                        Cancellation.NONE);
        assertEquals(2, result.quests().size());
        var quest = result.quests().getLast();
        assertEquals("Questie title", quest.title());
        assertEquals(8, quest.requiredLevel());
        assertEquals(15, quest.questLevel());
        assertEquals(4, quest.evidence().size());
        assertNull(result.quests().getFirst().title());
    }

    @Test
    void selectedBuildTaskTitleWinsWhileExternalProvidersFillLevels() {
        var result =
                QuestCatalog.merge(
                                List.of(
                                        new Observation(
                                                1,
                                                "Build task title",
                                                null,
                                                null,
                                                source(Provider.BLIZZARD)),
                                        new Observation(
                                                1, "Other", 3, -1, source(Provider.QUESTIE))),
                                List.of(),
                                Cancellation.NONE)
                        .quests()
                        .getFirst();
        assertEquals("Build task title", result.title());
        assertEquals(-1, result.questLevel());
    }

    @Test
    void sourceNormalizerDoesNotConfuseRequiredAndQuestLevels() {
        var row =
                QuestSources.from(
                        9,
                        Map.of("title", "The Killing Fields", "min_level", 8, "level", 15),
                        "min_level",
                        "level",
                        "title",
                        source(Provider.POLESTAR));
        assertEquals(8, row.requiredLevel());
        assertEquals(15, row.questLevel());
        assertEquals("The Killing Fields", row.title());
    }

    @Test
    void egParserKeepsOnlyTopLevelQuestFacts() throws Exception {
        var fields =
                QuestSources.egFields(
                        "{\"title\":\"Quest\",\"level\":15,\"minLevel\":8,\"rewards\":{\"level\":99}}");
        assertEquals("Quest", fields.get("title"));
        assertEquals(15L, fields.get("level"));
        assertEquals(8L, fields.get("minLevel"));
        assertFalse(fields.containsKey("rewards"));
    }
}
