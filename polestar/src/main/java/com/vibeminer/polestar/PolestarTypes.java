package com.vibeminer.polestar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Published source facts; client build provenance is never a server-compatibility claim. */
public final class PolestarTypes {
    private PolestarTypes() {}

    public enum Table {
        QUEST,
        QUEST_START,
        QUEST_END,
        QUEST_OBJECTIVE,
        QUEST_PREREQ,
        QUEST_REWARD,
        NPC,
        OBJECT,
        SPAWN,
        ITEM,
        ITEM_SOURCE,
        UI_MAP,
        MECHANIC,
        STEP,
        NOTE;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Table parse(String value) {
            for (var table : values()) {
                if (table.wire().equals(value)) {
                    return table;
                }
            }
            throw new IllegalArgumentException("Unsupported Polestar table");
        }
    }

    public record Resource(Table table, long rows, long bytes, String sha256, String license) {}

    public record Coverage(String what, long with, long of) {}

    public record Catalog(
            String revision,
            String version,
            String clientBuild,
            String producerCommit,
            Instant checkedAt,
            List<Resource> resources,
            List<Coverage> coverage) {
        public Catalog {
            resources = List.copyOf(resources);
            coverage = List.copyOf(coverage);
        }

        public long rows() {
            return resources.stream().mapToLong(Resource::rows).sum();
        }

        public long bytes() {
            return resources.stream().mapToLong(Resource::bytes).sum();
        }
    }

    public record Snapshot(
            String id,
            Catalog catalog,
            Instant downloadedAt,
            Instant preparedAt,
            long originalBytes,
            long databaseBytes,
            long records,
            List<String> issues) {
        public Snapshot {
            issues = List.copyOf(issues);
        }
    }

    public record Progress(String message, long done, long total) {}

    public record Record(
            Table table,
            long ordinal,
            String key,
            String name,
            Map<String, Object> fields,
            String sourceId,
            String clientBuild,
            String license) {
        public Record {
            fields = Map.copyOf(fields);
        }
    }

    public record Page(List<Record> records, long total, int offset, int limit) {
        public Page {
            records = List.copyOf(records);
        }
    }
}
