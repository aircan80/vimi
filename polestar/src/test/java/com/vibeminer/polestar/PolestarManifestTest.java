package com.vibeminer.polestar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.network.SourceUrls;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PolestarManifestTest {
    @Test
    void publishedMetadataPreservesExactBuildCoverageAndPerTableLicenses() {
        var fixture = PolestarFixtures.files();
        var catalog =
                PolestarRepository.inspect(
                        "a".repeat(40),
                        Instant.EPOCH,
                        fixture.get("index.json"),
                        fixture.get("datapackage.json"));
        assertEquals("1.60.1.70532", catalog.clientBuild());
        assertEquals(15, catalog.resources().size());
        assertEquals("CC-BY-4.0", catalog.resources().getLast().license());
        assertEquals(15, catalog.rows());
    }

    @Test
    void fixedGitHubPathsRejectMutableRevisionsTraversalAndExternalUrls() {
        assertEquals(
                "raw.githubusercontent.com",
                SourceUrls.polestar("a".repeat(40), "data/npc.jsonl").getHost());
        for (String path :
                List.of(
                        "../LICENSE",
                        "data/../npc.jsonl",
                        "https://example.com/npc.jsonl",
                        "data/npc.jsonl?token=x",
                        "data/%2e%2e/npc.jsonl")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SourceUrls.polestar("a".repeat(40), path));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceUrls.polestar("next", "data/npc.jsonl"));
    }

    @Test
    void malformedMetadataCannotChangePathsLicensesOrDeclaredBounds() {
        var files = PolestarFixtures.files();
        var pack = Json.object(Json.parse(files.get("datapackage.json"), 1 << 20));
        var resource = Json.object(Json.array(pack.get("resources")).getFirst());
        for (var change :
                List.of(
                        Map.entry("path", "../quest.jsonl"),
                        Map.entry("bytes", (Object) (17L << 20)),
                        Map.entry("hash", (Object) "sha256:bad"))) {
            Object old = resource.put(change.getKey(), change.getValue());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PolestarRepository.inspect(
                                    "a".repeat(40),
                                    Instant.EPOCH,
                                    files.get("index.json"),
                                    Json.bytes(pack)));
            resource.put(change.getKey(), old);
        }
        resource.put("licenses", List.of(Map.of("name", "unknown")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PolestarRepository.inspect(
                                "a".repeat(40),
                                Instant.EPOCH,
                                files.get("index.json"),
                                Json.bytes(pack)));
    }

    @Test
    void strictSchemaChecksRequiredFieldsTypesAndUnknownSchemaFeatures() {
        var schema = new RowSchema(PolestarFixtures.schema("npc"), "npc");
        schema.validate(Map.of("id", 1L, "name", "Test"));
        for (var row :
                List.of(
                        Map.of("name", "No id"),
                        Map.of("id", "1"),
                        Map.of("id", 0L),
                        Map.of("id", 1L, "unknown", 1L))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> schema.validate(new java.util.HashMap<String, Object>(row)));
        }
        var changed = Json.object(Json.parse(PolestarFixtures.schema("npc"), 1 << 20));
        changed.put("$ref", "https://example.com/schema");
        assertThrows(
                IllegalArgumentException.class, () -> new RowSchema(Json.bytes(changed), "npc"));
    }
}
