package com.vibeminer.questie.data;

import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.Flavor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Values from retained static QuestieDB snapshots; no live character or drop-rate inference. */
public final class QuestieDataTypes {
    private QuestieDataTypes() {}

    public record PreparedVersion(
            String id,
            String sourceId,
            String sourceSha256,
            String releaseTag,
            String producerCommit,
            Flavor flavor,
            String schemaProfile,
            Instant preparedAt,
            long entityCount,
            List<String> locales,
            List<String> diagnostics) {
        public PreparedVersion {
            locales = List.copyOf(locales);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Nested map keys representing CBOR integer keys use their decimal spelling. */
    public record EntityRecord(
            String versionId,
            EntityKind kind,
            long id,
            String locale,
            Map<String, Object> fields,
            List<String> translatedFields,
            String provenanceKey) {
        public EntityRecord {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
            translatedFields = List.copyOf(translatedFields);
        }
    }

    public record EntityPage(List<EntityRecord> entities, long total, int offset, int limit) {
        public EntityPage {
            entities = List.copyOf(entities);
        }
    }

    public enum SourceEvidence {
        DECLARED,
        REVERSE
    }

    public enum Relation {
        DROP,
        VENDOR,
        QUEST_REWARD,
        QUEST_START,
        QUEST_PROVIDED,
        QUEST_REQUIREMENT,
        RELATED_QUEST
    }

    /** Target IDs are entity IDs, never FileDataIds or display IDs. */
    public record ItemSource(
            Relation relation,
            EntityKind kind,
            long id,
            SourceEvidence evidence,
            String evidenceField,
            Optional<EntityRecord> entity) {}

    /** Empty sources means no recorded sources in this static snapshot, not proof of no sources. */
    public record ItemSources(
            String versionId,
            long itemId,
            boolean itemPresent,
            List<ItemSource> sources,
            String coverage) {
        public ItemSources {
            sources = List.copyOf(sources);
        }
    }
}
