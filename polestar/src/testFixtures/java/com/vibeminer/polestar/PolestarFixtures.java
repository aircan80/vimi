package com.vibeminer.polestar;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small authored package for component and composition contracts; never live game data. */
public final class PolestarFixtures {
    private PolestarFixtures() {}

    public static Map<String, byte[]> files() {
        var files = new LinkedHashMap<String, byte[]>();
        var counts = new LinkedHashMap<String, Object>();
        var resources = new ArrayList<Object>();
        for (var table : PolestarTypes.Table.values()) {
            String name = table.wire();
            byte[] data =
                    "{\"id\":1,\"name\":\"Authored record\"}\n".getBytes(StandardCharsets.UTF_8);
            files.put("data/" + name + ".jsonl", data);
            files.put("schema/" + name + ".schema.json", schema(name));
            counts.put(name, 1L);
            resources.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "name",
                                    name,
                                    "path",
                                    "data/" + name + ".jsonl",
                                    "schema",
                                    "schema/" + name + ".schema.json",
                                    "format",
                                    "jsonl",
                                    "encoding",
                                    "utf-8",
                                    "bytes",
                                    (long) data.length,
                                    "hash",
                                    "sha256:" + PolestarRepository.hash(data),
                                    "licenses",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    List.of("mechanic", "step", "note")
                                                                    .contains(name)
                                                            ? "CC-BY-4.0"
                                                            : "GPL-3.0-only")))));
        }
        files.put(
                "index.json",
                Json.bytes(
                        Map.of(
                                "dataset",
                                "polestar-data-forever",
                                "version",
                                "2026.11.1",
                                "clientBuild",
                                "1.60.1.70532",
                                "schemaVersion",
                                1L,
                                "producerCommit",
                                "b".repeat(40),
                                "counts",
                                counts,
                                "coverage",
                                List.of(Map.of("what", "npcs published", "with", 1L, "of", 2L)))));
        files.put(
                "datapackage.json",
                Json.bytes(
                        Map.of(
                                "name",
                                "polestar-data-forever",
                                "version",
                                "2026.11.1",
                                "clientBuild",
                                "1.60.1.70532",
                                "schemaVersion",
                                1L,
                                "producerCommit",
                                "b".repeat(40),
                                "resources",
                                resources)));
        for (String notice :
                List.of("NOTICE.md", "README.md", "LICENSE", "LICENSE-CC-BY-4.0.txt")) {
            files.put(notice, "Authored fixture notice".getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    static byte[] schema(String table) {
        return Json.bytes(
                Map.of(
                        "$id",
                        "schema/" + table + ".schema.json",
                        "type",
                        "object",
                        "required",
                        List.of("id"),
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "id",
                                Map.of("type", "integer", "minimum", 1L),
                                "name",
                                Map.of("type", "string", "minLength", 1L))));
    }
}
