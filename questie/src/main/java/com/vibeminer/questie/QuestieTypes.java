package com.vibeminer.questie;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Shared source identity and measured job progress. Questie preparation is independent of WoW
 * builds.
 */
public final class QuestieTypes {
    private QuestieTypes() {}

    public enum Flavor {
        VANILLA("Vanilla"),
        TBC("TBC"),
        WRATH("Wrath"),
        CATA("Cata"),
        MISTS("Mists"),
        FOREVER("Forever");
        private final String upstream;

        Flavor(String upstream) {
            this.upstream = upstream;
        }

        public String upstream() {
            return upstream;
        }

        public String tocName() {
            return "QuestieDB_" + upstream + ".toc";
        }

        public static Flavor parse(String text) {
            for (var value : values()) {
                if (value.name().equalsIgnoreCase(text) || value.upstream.equalsIgnoreCase(text)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unsupported Questie flavor: " + text);
        }
    }

    public enum Stage {
        DISCOVERING,
        DOWNLOADING,
        VERIFYING,
        IMPORTING,
        INDEXING,
        VALIDATING,
        READY
    }

    public record Progress(Stage stage, long done, Long total, String unit, String detail) {
        public Progress {
            if (stage == null
                    || done < 0
                    || total != null && (total < 0 || done > total)
                    || unit == null) {
                throw new IllegalArgumentException("Invalid Questie progress");
            }
        }
    }

    /**
     * Exact official package bytes and their provenance; source directory is owned by the archive
     * repository.
     */
    public record SourceSnapshot(
            String id,
            String releaseTag,
            String producerCommit,
            Flavor flavor,
            String sha256,
            long bytes,
            Instant publishedAt,
            Path directory) {
        public SourceSnapshot {
            if (id == null
                    || !id.matches("[0-9a-f]{64}")
                    || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")
                    || releaseTag == null
                    || producerCommit == null
                    || !producerCommit.matches("[0-9a-f]{40}")
                    || flavor == null
                    || bytes < 0
                    || directory == null) {
                throw new IllegalArgumentException("Invalid Questie source identity");
            }
            directory = directory.toAbsolutePath().normalize();
        }

        public Path archive() {
            return directory.resolve("archive.zip");
        }
    }

    public enum EntityKind {
        ITEM("Item"),
        NPC("Npc"),
        QUEST("Quest"),
        OBJECT("Object");
        private final String upstream;

        EntityKind(String upstream) {
            this.upstream = upstream;
        }

        public String upstream() {
            return upstream;
        }

        public static EntityKind parse(String text) {
            for (var value : values()) {
                if (value.name().equalsIgnoreCase(text) || value.upstream.equalsIgnoreCase(text)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unsupported entity kind: " + text);
        }
    }
}
