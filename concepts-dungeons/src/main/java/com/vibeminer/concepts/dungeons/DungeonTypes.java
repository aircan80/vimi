package com.vibeminer.concepts.dungeons;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

/** Selected-build instance facts, independent of complete server NPC/loot or entrance knowledge. */
public final class DungeonTypes {
    private DungeonTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public enum Kind {
        DUNGEON,
        RAID
    }

    public record Dungeon(
            long mapId, String name, Kind kind, Long maxPlayers, Long areaId, Long expansionId) {}

    public record Difficulty(
            long recordId, long difficultyId, String name, Long maxPlayers, String message) {}

    public record Queue(
            long recordId,
            String name,
            Long difficultyId,
            Long contentTuningId,
            Long minLevel,
            Long maxLevel) {}

    public record Encounter(
            long id,
            String name,
            Long difficultyId,
            String difficultyName,
            Long order,
            Long displayId,
            Long iconFileDataId) {}

    public record Area(long id, String name, Long parentId, Long mapId) {}

    public record Description(
            String title, String text, String sourceTable, long recordId, String sourceField) {}

    public record Catalog(
            State state,
            List<Dungeon> dungeons,
            List<TableTypes.Entry> sources,
            List<String> issues,
            String message) {
        public Catalog {
            dungeons = List.copyOf(dungeons);
            sources = List.copyOf(sources);
            issues = List.copyOf(issues);
        }
    }

    public record Overview(
            State state,
            Dungeon dungeon,
            List<Area> areas,
            List<Description> descriptions,
            List<Difficulty> difficulties,
            List<Queue> queues,
            List<Encounter> encounters,
            List<TableTypes.Entry> sources,
            List<String> issues,
            String message) {
        public Overview {
            areas = List.copyOf(areas);
            descriptions = List.copyOf(descriptions);
            difficulties = List.copyOf(difficulties);
            queues = List.copyOf(queues);
            encounters = List.copyOf(encounters);
            sources = List.copyOf(sources);
            issues = List.copyOf(issues);
        }
    }
}
