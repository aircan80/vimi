package com.vibeminer.questie.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.generated.DerivedDatabase;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import com.vibeminer.questie.QuestieTypes.Stage;
import com.vibeminer.questie.data.QuestieDataTypes.Relation;
import com.vibeminer.questie.data.QuestieDataTypes.SourceEvidence;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestiePersistenceTest {
    @TempDir Path directory;

    @Test
    void persistsVersionsLocalesAndTypedItemRelationshipsAcrossReopen() throws Exception {
        var source = fixture("first", false);
        String id;
        try (var repository = new QuestieDataRepository(directory.resolve("derived"))) {
            var version = repository.prepare(source, p -> {}, Cancellation.NONE).join();
            id = version.id();
            assertEquals(5, version.entityCount());
            try (var handle = repository.open(id, Cancellation.NONE).join()) {
                var item =
                        handle.lookup(EntityKind.ITEM, 117, "deDE", Cancellation.NONE)
                                .join()
                                .orElseThrow();
                assertEquals("Fleisch", item.fields().get("name"));
                assertEquals(List.of("name"), item.translatedFields());
                assertEquals(
                        "Hearthstone",
                        handle.lookup(EntityKind.ITEM, 6948, "deDE", Cancellation.NONE)
                                .join()
                                .orElseThrow()
                                .fields()
                                .get("name"));
                assertEquals(
                        1L,
                        handle.lookup(EntityKind.NPC, 54, "enUS", Cancellation.NONE)
                                .join()
                                .orElseThrow()
                                .fields()
                                .get("maxLevelHealth"));
                assertTrue(
                        handle.lookup(EntityKind.OBJECT, 54, "enUS", Cancellation.NONE)
                                .join()
                                .isEmpty());
                var sources = handle.itemSources(117, "deDE", Cancellation.NONE).join();
                assertTrue(sources.itemPresent());
                assertTrue(
                        sources.sources().stream()
                                .anyMatch(
                                        s ->
                                                s.kind() == EntityKind.NPC
                                                        && s.id() == 54
                                                        && s.relation() == Relation.DROP
                                                        && s.evidence()
                                                                == SourceEvidence.DECLARED));
                assertTrue(
                        sources.sources().stream()
                                .anyMatch(
                                        s ->
                                                s.relation() == Relation.QUEST_PROVIDED
                                                        && s.evidence() == SourceEvidence.REVERSE));
                assertTrue(
                        sources.sources().stream()
                                .anyMatch(
                                        s ->
                                                s.relation() == Relation.QUEST_START
                                                        && s.evidence() == SourceEvidence.REVERSE));
                assertEquals(
                        1,
                        handle.search(EntityKind.ITEM, "Flei", "deDE", 0, 1, Cancellation.NONE)
                                .join()
                                .total());
                assertEquals(
                        2,
                        handle.search(EntityKind.ITEM, "", "enUS", 0, 1, Cancellation.NONE)
                                .join()
                                .total());
                assertEquals(
                        1,
                        handle.search(EntityKind.ITEM, "", "enUS", 1, 1, Cancellation.NONE)
                                .join()
                                .entities()
                                .size());
                assertFalse(
                        handle.itemSources(999, "enUS", Cancellation.NONE).join().itemPresent());
            }
            assertEquals(id, repository.prepare(source, p -> {}, Cancellation.NONE).join().id());
            var other = fixture("second", false);
            assertFalse(
                    id.equals(repository.prepare(other, p -> {}, Cancellation.NONE).join().id()));
        }
        Files.delete(source.archive()); // Queries require only the completed private projection.
        try (var reopened = new QuestieDataRepository(directory.resolve("derived"))) {
            assertEquals(2, reopened.listVersions(Cancellation.NONE).join().size());
            try (var handle = reopened.open(id, Cancellation.NONE).join()) {
                assertEquals(
                        "Meat",
                        handle.lookup(EntityKind.ITEM, 117, "enUS", Cancellation.NONE)
                                .join()
                                .orElseThrow()
                                .fields()
                                .get("name"));
            }
        }
    }

    @Test
    void itemSourcesReturnLocalizedSummariesWithoutReadingUnrelatedFullFields() throws Exception {
        var source = fixture("summary", false);
        var root = directory.resolve("summaries");
        String version;
        try (var repository = new QuestieDataRepository(root)) {
            version = repository.prepare(source, p -> {}, Cancellation.NONE).join().id();
        }
        // The summary path must use projected names, not deserialize full entity or translation
        // documents. Poison those unrelated blobs so an accidental N+1 lookup fails this test.
        try (var database =
                DerivedDatabase.open(root.resolve(version + ".sqlite"), Cancellation.NONE).join()) {
            database.transaction(
                            session -> {
                                session.execute(
                                        "UPDATE entities SET fields=? WHERE kind='NPC' AND id=54",
                                        new byte[] {'{'});
                                session.execute(
                                        "UPDATE translations SET fields=? WHERE locale='deDE' AND kind='NPC' AND id=54",
                                        new byte[] {'{'});
                                session.execute(
                                        "INSERT INTO item_sources(item_id,kind,entity_id,relation,evidence,field) VALUES(117,'NPC',9999,'DROP','DECLARED','Item.npcDrops')");
                                return null;
                            },
                            Cancellation.NONE)
                    .join();
        }
        try (var repository = new QuestieDataRepository(root);
                var handle = repository.open(version, Cancellation.NONE).join()) {
            var result = handle.itemSources(117, "deDE", Cancellation.NONE).join();
            var localized =
                    result.sources().stream()
                            .filter(s -> s.kind() == EntityKind.NPC && s.id() == 54)
                            .findFirst()
                            .orElseThrow()
                            .entity()
                            .orElseThrow();
            assertEquals("Händlerin", localized.name());
            assertTrue(localized.translated());
            assertEquals(version, localized.versionId());
            assertEquals("X-Npc-54", localized.provenanceKey());
            assertEquals("deDE", localized.locale());
            assertTrue(
                    result.sources().stream()
                            .filter(s -> s.id() == 9999)
                            .findFirst()
                            .orElseThrow()
                            .entity()
                            .isEmpty());
            var english =
                    handle.itemSources(117, "enUS", Cancellation.NONE).join().sources().stream()
                            .filter(s -> s.kind() == EntityKind.NPC && s.id() == 54)
                            .findFirst()
                            .orElseThrow()
                            .entity()
                            .orElseThrow();
            assertEquals("Vendor", english.name());
            assertFalse(english.translated());
            assertTrue(
                    handle.itemSources(6948, "enUS", Cancellation.NONE).join().sources().isEmpty());
            assertTrue(handle.itemSources(6948, "enUS", Cancellation.NONE).join().itemPresent());
            assertFalse(handle.itemSources(99999, "enUS", Cancellation.NONE).join().itemPresent());
        }
    }

    @Test
    void cancellationAndCorruptionPublishNoReadyVersionAndCloseDrainsAcceptedWork()
            throws Exception {
        var repository = new QuestieDataRepository(directory.resolve("cancelled"));
        var token = new Cancellation();
        var preparing =
                repository.prepare(
                        fixture("cancel", false),
                        p -> {
                            if (p.stage() == Stage.IMPORTING) {
                                token.cancel();
                            }
                        },
                        token);
        var closing = repository.closeAsync();
        assertThrows(CompletionException.class, preparing::join);
        closing.join();
        assertThrows(
                CompletionException.class, () -> repository.listVersions(Cancellation.NONE).join());
        try (var reopened = new QuestieDataRepository(directory.resolve("cancelled"))) {
            assertTrue(reopened.listVersions(Cancellation.NONE).join().isEmpty());
        }
        try (var corrupted = new QuestieDataRepository(directory.resolve("bad"))) {
            assertThrows(
                    CompletionException.class,
                    () ->
                            corrupted
                                    .prepare(
                                            fixtureUnchecked("bad", true),
                                            p -> {},
                                            Cancellation.NONE)
                                    .join());
            assertTrue(corrupted.listVersions(Cancellation.NONE).join().isEmpty());
        }
    }

    @Test
    void rejectsChunksSchemaAndTablePresenceFailuresWithoutPublishing() throws Exception {
        var missingChunk =
                mutate(
                        fixture("missing-chunk", false),
                        "QuestieDB/QuestieDB_Forever.toc",
                        text ->
                                text.replaceFirst(
                                        "(?m)^## X-Item-6948-S:.*$",
                                        "## X-Item-6948-S: ~2~\n## X-Item-6948-S-1: AA"));
        var badSchema =
                mutate(
                        fixture("schema", false),
                        "QuestieDB/src/meta/itemMeta.lua",
                        text -> text.replace("[1]='name'", "[1]='renamed'"));
        var presence =
                mutate(
                        fixture("presence", false),
                        "QuestieDB/QuestieDB_Forever.toc",
                        text -> text.replaceFirst("(?m)^## X-Item-117-2:.*\n", ""));
        var contract =
                mutate(
                        fixture("contract", false),
                        "QuestieDB/QuestieDB_Forever.toc",
                        text -> text.replace("X-Contract-Version: 4", "X-Contract-Version: 5"));
        var unsorted =
                mutate(
                        fixture("unsorted", false),
                        "QuestieDB/QuestieDB_Forever.toc",
                        text -> {
                            try {
                                var replacement = new StringBuilder();
                                metadata(replacement, "X-Item-IDS", List.of(6948, 117), true);
                                return text.replaceFirst(
                                        "(?m)^## X-Item-IDS:.*\n", replacement.toString());
                            } catch (IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                        });
        try (var repository = new QuestieDataRepository(directory.resolve("invalid"))) {
            for (var invalid : List.of(missingChunk, badSchema, presence, contract, unsorted)) {
                assertThrows(
                        CompletionException.class,
                        () -> repository.prepare(invalid, p -> {}, Cancellation.NONE).join());
            }
            assertTrue(repository.listVersions(Cancellation.NONE).join().isEmpty());
        }
    }

    private static SourceSnapshot mutate(
            SourceSnapshot source,
            String entryName,
            java.util.function.UnaryOperator<String> mutation)
            throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new java.util.zip.ZipFile(source.archive().toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        entries.put(
                entryName,
                mutation.apply(new String(entries.get(entryName), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8));
        try (var zip = new ZipOutputStream(Files.newOutputStream(source.archive()))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        var bytes = Files.readAllBytes(source.archive());
        var hash = HexFormat.of().formatHex(BakedArchive.sha256().digest(bytes));
        return new SourceSnapshot(
                hash,
                source.releaseTag(),
                source.producerCommit(),
                source.flavor(),
                hash,
                bytes.length,
                source.publishedAt(),
                source.directory());
    }

    private SourceSnapshot fixtureUnchecked(String name, boolean corrupt) {
        try {
            return fixture(name, corrupt);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private SourceSnapshot fixture(String name, boolean corrupt) throws IOException {
        var sourceDir = Files.createDirectories(directory.resolve(name));
        var archive = sourceDir.resolve("archive.zip");
        var toc =
                new StringBuilder(
                        "## Version: fixture-"
                                + name
                                + "\n## X-Contract-Version: 4\n## X-Flavor: Forever\n## X-BUILD-COMMIT: "
                                + "a".repeat(40)
                                + "\n## X-l10n-Version: 1\n");
        metadata(toc, "X-Item-IDS", List.of(117, 6948), true);
        metadata(toc, "X-Npc-IDS", List.of(54), true);
        metadata(toc, "X-Quest-IDS", List.of(2), true);
        metadata(toc, "X-Object-IDS", List.of(5), true);
        metadata(
                toc,
                "X-Item-117-S",
                Map.of(1, "Meat", "p", 8226),
                false); // npcDrops(2), questRewards(6), vendors(14)
        metadata(toc, "X-Item-117-2", List.of(54), false);
        metadata(toc, "X-Item-117-6", List.of(2), false);
        metadata(toc, "X-Item-117-14", List.of(54), false);
        metadata(toc, "X-Item-6948-S", List.of("Hearthstone"), false);
        metadata(toc, "X-Npc-54-S", Map.of(1, "Vendor"), false);
        metadata(toc, "X-Object-5-S", Map.of(1, "Chest"), false);
        metadata(
                toc,
                "X-Quest-2-S",
                Map.of(1, "Quest", 11, 117, "p", 2562),
                false); // startedBy, objectives, preQuestGroup
        metadata(toc, "X-Quest-2-2", Map.of(3, List.of(117)), false);
        metadata(
                toc,
                "X-Quest-2-10",
                Map.of(3, List.of(List.of(117, 1)), 5, List.of(List.of(List.of(54), 54))),
                false);
        metadata(toc, "X-Quest-2-12", List.of(-2), false);
        metadata(toc, "X-l10n-deDE-Item", List.of(Map.of(1, "Fleisch")), true);
        metadata(toc, "X-l10n-deDE-Npc", List.of(Map.of(1, "Händlerin")), true);
        if (corrupt) {
            toc.append("## X-Item-6948-S: ~2~\n## X-Item-6948-S-1: AA\n");
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "QuestieDB/QuestieDB_Forever.toc", toc.toString());
            entry(zip, "QuestieDB/src/config.lua", "config.contractVersion = 4\n");
            for (var kind : EntityKind.values()) {
                entry(
                        zip,
                        "QuestieDB/src/meta/"
                                + kind.name().toLowerCase(java.util.Locale.ROOT)
                                + "Meta.lua",
                        declaration(kind));
            }
        }
        byte[] bytes = Files.readAllBytes(archive);
        String hash = HexFormat.of().formatHex(BakedArchive.sha256().digest(bytes));
        return new SourceSnapshot(
                hash,
                "fixture-" + name,
                "a".repeat(40),
                Flavor.FOREVER,
                hash,
                bytes.length,
                Instant.EPOCH,
                sourceDir);
    }

    private static String declaration(EntityKind kind) {
        String names =
                switch (kind) {
                    case ITEM ->
                            "name npcDrops objectDrops itemDrops startQuest questRewards flags foodType itemLevel requiredLevel ammoType class subClass vendors relatedQuests teachesSpell";
                    case NPC ->
                            "name minLevelHealth maxLevelHealth minLevel maxLevel rank spawns waypoints zoneID questStarts questEnds factionID friendlyToFaction subName npcFlags";
                    case OBJECT -> "name questStarts questEnds spawns zoneID factionID waypoints";
                    case QUEST ->
                            "name startedBy finishedBy requiredLevel questLevel requiredRaces requiredClasses objectivesText triggerEnd objectives sourceItemId preQuestGroup preQuestSingle childQuests inGroupWith exclusiveTo zoneOrSort requiredSkill requiredMinRep requiredMaxRep requiredSourceItems nextQuestInChain questFlags specialFlags parentQuest reputationReward breadcrumbForQuestId breadcrumbs extraObjectives requiredSpell requiredSpecialization requiredMaxLevel availableUntilCompleted availableStartingWith requiredRanks disabledByQuest";
                };
        var tables =
                switch (kind) {
                    case ITEM -> List.of(2, 3, 4, 6, 14, 15);
                    case NPC -> List.of(7, 8, 10, 11);
                    case OBJECT -> List.of(2, 3, 4, 7);
                    case QUEST ->
                            List.of(
                                    2, 3, 8, 9, 10, 12, 13, 14, 15, 16, 18, 19, 20, 21, 26, 28, 29,
                                    35);
                };
        var n = new StringBuilder("names = {\n");
        var t = new StringBuilder("types = {\n");
        var split = names.split(" ");
        for (int i = 1; i <= split.length; i++) {
            n.append('[').append(i).append("]='").append(split[i - 1]).append("',\n");
            String type =
                    tables.contains(i)
                            ? "table"
                            : i == 1 || kind == EntityKind.NPC && (i == 13 || i == 14)
                                    ? "string"
                                    : "number";
            t.append('[').append(i).append("]='").append(type).append("',\n");
        }
        return n.append("}\n").toString()
                + t.append("}\n")
                + (kind == EntityKind.NPC ? "constantValues = {[2]=0,[3]=1}\n" : "");
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void metadata(StringBuilder toc, String key, Object value, boolean compressed)
            throws IOException {
        byte[] bytes = cbor(value);
        if (compressed) {
            var out = new ByteArrayOutputStream();
            try (var deflate = new DeflaterOutputStream(out)) {
                deflate.write(bytes);
            }
            bytes = out.toByteArray();
        }
        toc.append("## ")
                .append(key)
                .append(": ")
                .append(Base64.getEncoder().encodeToString(bytes))
                .append('\n');
    }

    private static byte[] cbor(Object value) throws IOException {
        var out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.write(246);
        } else if (value instanceof Number n) {
            long v = n.longValue();
            argument(out, v < 0 ? 1 : 0, v < 0 ? -1 - v : v);
        } else if (value instanceof String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            argument(out, 2, bytes.length);
            out.write(bytes);
        } else if (value instanceof List<?> list) {
            argument(out, 4, list.size());
            for (Object entry : list) {
                write(out, entry);
            }
        } else if (value instanceof Map<?, ?> map) {
            argument(out, 5, map.size());
            for (var entry : map.entrySet()) {
                write(out, entry.getKey());
                write(out, entry.getValue());
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static void argument(ByteArrayOutputStream out, int major, long value) {
        if (value < 24) {
            out.write((major << 5) | (int) value);
        } else if (value < 256) {
            out.write((major << 5) | 24);
            out.write((int) value);
        } else if (value < 65536) {
            out.write((major << 5) | 25);
            out.write((int) (value >>> 8));
            out.write((int) value);
        } else {
            out.write((major << 5) | 26);
            for (int i = 3; i >= 0; i--) {
                out.write((int) (value >>> (8 * i)));
            }
        }
    }
}
