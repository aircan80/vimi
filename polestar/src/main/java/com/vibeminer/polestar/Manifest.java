package com.vibeminer.polestar;

import com.vibeminer.polestar.PolestarTypes.Catalog;
import com.vibeminer.polestar.PolestarTypes.Coverage;
import com.vibeminer.polestar.PolestarTypes.Resource;
import com.vibeminer.polestar.PolestarTypes.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Exact schema-1 package profile. Manifest paths never become arbitrary URLs or local paths. */
final class Manifest {
    static Catalog parse(String revision, Instant checked, byte[] indexBytes, byte[] packageBytes) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid immutable commit");
        }
        var index = Json.object(Json.parse(indexBytes, 1 << 20));
        var pack = Json.object(Json.parse(packageBytes, 1 << 20));
        if (!"polestar-data-forever".equals(index.get("dataset"))
                || !index.get("dataset").equals(pack.get("name"))
                || Json.number(index.get("schemaVersion")) != 1
                || Json.number(pack.get("schemaVersion")) != 1) {
            throw new IllegalArgumentException("Unsupported Polestar dataset or schema");
        }
        String version = Json.string(index.get("version"));
        String build = Json.string(index.get("clientBuild"));
        String producer = Json.string(index.get("producerCommit"));
        if (!version.matches("[0-9]{4}\\.[0-9]{1,2}\\.[0-9]{1,5}")
                || !build.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,10}")
                || !producer.matches("[0-9a-f]{40}")
                || !version.equals(pack.get("version"))
                || !build.equals(pack.get("clientBuild"))
                || !producer.equals(pack.get("producerCommit"))) {
            throw new IllegalArgumentException("Inconsistent package provenance");
        }
        var counts = Json.object(index.get("counts"));
        var resources = new ArrayList<Resource>();
        var seen = new HashSet<Table>();
        long bytes = 0, rows = 0;
        for (var value : Json.array(pack.get("resources"))) {
            var resource = Json.object(value);
            Table table = Table.parse(Json.string(resource.get("name")));
            if (!seen.add(table)
                    || !resource.get("path").equals("data/" + table.wire() + ".jsonl")
                    || !resource.get("schema").equals("schema/" + table.wire() + ".schema.json")
                    || !"jsonl".equals(resource.get("format"))
                    || !"utf-8".equals(resource.get("encoding"))) {
                throw new IllegalArgumentException("Untrusted resource path or format");
            }
            long length = Json.number(resource.get("bytes")),
                    count = Json.number(counts.get(table.wire()));
            String hash = Json.string(resource.get("hash"));
            var licenses = Json.array(resource.get("licenses"));
            String expected =
                    Set.of(Table.MECHANIC, Table.STEP, Table.NOTE).contains(table)
                            ? "CC-BY-4.0"
                            : "GPL-3.0-only";
            if (length < 0
                    || length > PolestarRepository.MAX_FILE_BYTES
                    || count < 0
                    || count > 1_000_000
                    || !hash.matches("sha256:[0-9a-f]{64}")
                    || licenses.size() != 1
                    || !expected.equals(Json.object(licenses.getFirst()).get("name"))) {
                throw new IllegalArgumentException("Invalid resource bounds, hash or license");
            }
            bytes += length;
            rows += count;
            resources.add(new Resource(table, count, length, hash.substring(7), expected));
        }
        if (seen.size() != Table.values().length
                || counts.size() != seen.size()
                || bytes > PolestarRepository.MAX_SNAPSHOT_BYTES
                || rows > 2_000_000) {
            throw new IllegalArgumentException("Incomplete or oversized package");
        }
        var coverage = new ArrayList<Coverage>();
        for (var value : Json.array(index.get("coverage"))) {
            if (coverage.size() == 100) {
                throw new IllegalArgumentException("Too many coverage entries");
            }
            var row = Json.object(value);
            String what = Json.string(row.get("what"));
            long with = Json.number(row.get("with")), of = Json.number(row.get("of"));
            if (what.length() > 256 || with < 0 || of < 0) {
                throw new IllegalArgumentException("Invalid coverage");
            }
            coverage.add(new Coverage(what, with, of));
        }
        return new Catalog(revision, version, build, producer, checked, resources, coverage);
    }
}
