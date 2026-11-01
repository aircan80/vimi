package com.vibeminer.builds;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.CatalogTypes.BuildList;
import com.vibeminer.builds.CatalogTypes.BuildRegion;
import com.vibeminer.builds.CatalogTypes.CatalogBuild;
import com.vibeminer.builds.CatalogTypes.CatalogProblem;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Actor-owned native SQLite cache, schema-compatible with C# CatalogStore version 1. */
final class CatalogStore implements AutoCloseable {
    static final int SCHEMA_VERSION = 1, APPLICATION_ID = 0x564d6373, MAX_PROBLEMS = 1000;
    private static final Set<String> TABLES = Set.of("builds", "entries", "problems", "sightings");
    private static final String SCHEMA =
            """
        CREATE TABLE entries(id INTEGER PRIMARY KEY, record BLOB NOT NULL CHECK(length(record) = 16), record_type INTEGER NOT NULL,
            record_ns INTEGER NOT NULL, kind INTEGER NOT NULL, product TEXT NOT NULL, region TEXT NOT NULL, observed_at INTEGER NOT NULL,
            url TEXT NOT NULL, document BLOB NOT NULL CHECK(length(document) = 16), document_type INTEGER NOT NULL,
            document_ns INTEGER NOT NULL, seqn INTEGER, readable INTEGER NOT NULL, UNIQUE(record, record_type, record_ns));
        CREATE INDEX entries_order ON entries(product, observed_at, record);
        CREATE INDEX entries_time ON entries(observed_at, record);
        CREATE TABLE builds(id TEXT PRIMARY KEY, product TEXT NOT NULL, canonical TEXT NOT NULL, fields TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE sightings(entry INTEGER NOT NULL REFERENCES entries(id), row INTEGER NOT NULL, build TEXT NOT NULL REFERENCES builds(id),
            region TEXT NOT NULL, cdn_config TEXT NOT NULL, PRIMARY KEY(entry, row)) WITHOUT ROWID;
        CREATE INDEX sightings_build ON sightings(build);
        CREATE TABLE problems(entry INTEGER NOT NULL REFERENCES entries(id), ordinal INTEGER NOT NULL, code TEXT NOT NULL,
            message TEXT NOT NULL, row INTEGER, PRIMARY KEY(entry, ordinal)) WITHOUT ROWID;
        """;
    private final NativeSqlite db;
    private boolean failed;

    private CatalogStore(NativeSqlite db) {
        this.db = db;
    }

    static CatalogStore open(Path input, Cancellation token) {
        token.check();
        SqliteLibrary.lookup();
        Path path = input.toAbsolutePath();
        NativeSqlite db = null;
        try {
            if (Files.exists(path) && Files.size(path) > 0) {
                try (NativeSqlite probe = new NativeSqlite(path, true)) {
                    checkHeader(probe);
                }
            } else {
                Files.createDirectories(path.getParent());
            }
            token.check();
            db = new NativeSqlite(path, false);
            NativeSqlite opened = db;
            db.withCancellation(
                    token,
                    () -> {
                        dbTransaction(
                                opened,
                                () -> {
                                    if (checkHeader(opened)) {
                                        for (String sql : SCHEMA.split(";")) {
                                            if (!sql.isBlank()) {
                                                opened.exec(sql);
                                            }
                                        }
                                        opened.exec("PRAGMA application_id=" + APPLICATION_ID);
                                        opened.exec("PRAGMA user_version=" + SCHEMA_VERSION);
                                    }
                                });
                        return null;
                    });
            db.exec("PRAGMA journal_mode=WAL");
            db.exec("PRAGMA synchronous=NORMAL");
            db.exec("PRAGMA foreign_keys=ON");
            return new CatalogStore(db);
        } catch (Throwable e) {
            if (db != null) {
                db.close();
            }
            if (e instanceof java.util.concurrent.CancellationException canceled) {
                throw canceled;
            }
            if (e instanceof CatalogStoreException catalog) {
                throw catalog;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "The catalog store cannot be opened", e);
        }
    }

    /**
     * Returns true only for an empty database; existing foreign/newer files are never rewritten.
     */
    private static boolean checkHeader(NativeSqlite db) {
        long version = db.scalar("PRAGMA user_version"), app = db.scalar("PRAGMA application_id");
        Set<String> tables = new java.util.HashSet<>();
        for (Object[] row : db.query("SELECT name FROM sqlite_master WHERE type='table'")) {
            tables.add((String) row[0]);
        }
        if (version == 0 && app == 0 && tables.isEmpty()) {
            return true;
        }
        if (app != APPLICATION_ID) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "The file is not a VibeMiner catalog store");
        }
        if (version > SCHEMA_VERSION) {
            throw new CatalogStoreException(
                    "CATALOG_NEWER", "A newer VibeMiner wrote this catalog");
        }
        if (version != SCHEMA_VERSION || !tables.containsAll(TABLES)) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "The catalog schema is not readable");
        }
        return false;
    }

    private static void dbTransaction(NativeSqlite db, Runnable action) {
        db.transaction(false, action);
    }

    private <T> T use(Cancellation token, Supplier<T> work) {
        token.check();
        if (failed) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE",
                    "Reopen the catalog after an uncertain transaction failure");
        }
        try {
            return db.withCancellation(token, work::get);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (CatalogStoreException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof NativeSqlite.TransactionFailure) {
                failed = true;
            }
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "The catalog cache cannot be read or updated", e);
        }
    }

    boolean has(ObjectKey record, Cancellation token) {
        return use(
                token,
                () ->
                        !db.query(
                                        "SELECT 1 FROM entries WHERE record=? AND record_type=? AND record_ns=?",
                                        record.bytes(),
                                        record.type(),
                                        record.namespace())
                                .isEmpty());
    }

    void add(History.Entry entry, VersionsReply.Read read, Cancellation token) {
        if (entry.kind() != 1) {
            throw new IllegalArgumentException("Only versions entries belong in the catalog");
        }
        use(
                token,
                () -> {
                    dbTransaction(
                            db,
                            () -> {
                                List<Object[]> inserted =
                                        db.query(
                                                "INSERT INTO entries(record,record_type,record_ns,kind,product,region,observed_at,url,document,document_type,document_ns,seqn,readable) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING id",
                                                entry.record().bytes(),
                                                entry.record().type(),
                                                entry.record().namespace(),
                                                entry.kind(),
                                                entry.product(),
                                                entry.region(),
                                                entry.observedAt().toEpochMilli(),
                                                entry.url(),
                                                entry.document().bytes(),
                                                entry.document().type(),
                                                entry.document().namespace(),
                                                read.sequenceNumber(),
                                                read.readable() ? 1 : 0);
                                if (inserted.isEmpty()) {
                                    return;
                                }
                                long id = number(inserted.getFirst()[0]);
                                for (var sighting : read.sightings()) {
                                    token.check();
                                    BuildIdentity identity = sighting.identity();
                                    db.exec(
                                            "INSERT INTO builds(id,product,canonical,fields) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                                            identity.id(),
                                            identity.productId(),
                                            identity.canonical(),
                                            encodeFields(identity.fields()));
                                    db.exec(
                                            "INSERT INTO sightings(entry,row,build,region,cdn_config) VALUES(?,?,?,?,?)",
                                            id,
                                            sighting.row(),
                                            identity.id(),
                                            sighting.region(),
                                            sighting.cdnConfig());
                                }
                                for (int i = 0; i < read.problems().size(); i++) {
                                    var p = read.problems().get(i);
                                    db.exec(
                                            "INSERT INTO problems(entry,ordinal,code,message,row) VALUES(?,?,?,?,?)",
                                            id,
                                            i,
                                            p.code(),
                                            p.message(),
                                            p.row());
                                }
                            });
                    return null;
                });
    }

    BuildList list(String product, Cancellation token) {
        return use(
                token,
                () -> {
                    db.exec("BEGIN");
                    try {
                        List<Object[]> rows =
                                db.query(
                                        """
                    WITH mine AS (SELECT id, region, observed_at, record, record_type, record_ns, seqn, readable FROM entries WHERE product=? AND kind=1),
                    newest AS (SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY region ORDER BY seqn IS NULL,seqn DESC,observed_at DESC,record DESC) AS n FROM mine WHERE readable) WHERE n=1),
                    seen AS (SELECT s.build,s.region,s.cdn_config,e.observed_at,e.record,e.record_type,e.record_ns,
                        MIN(e.observed_at) OVER g AS first_seen,MAX(s.entry IN (SELECT id FROM newest)) OVER g AS cur,
                        ROW_NUMBER() OVER (PARTITION BY s.build,s.region,s.cdn_config ORDER BY e.observed_at DESC,e.record DESC) AS n
                        FROM sightings s JOIN mine e ON e.id=s.entry WINDOW g AS (PARTITION BY s.build,s.region,s.cdn_config))
                    SELECT seen.build,seen.region,seen.cdn_config,seen.first_seen,seen.observed_at,seen.cur,seen.record,seen.record_type,seen.record_ns,b.fields
                    FROM seen JOIN builds b ON b.id=seen.build WHERE seen.n=1
                    """,
                                        product);
                        Map<String, List<BuildRegion>> regions = new HashMap<>();
                        Map<String, Map<String, String>> fields = new HashMap<>();
                        for (Object[] row : rows) {
                            token.check();
                            String id = text(row[0]);
                            regions.computeIfAbsent(id, ignored -> new ArrayList<>())
                                    .add(
                                            new BuildRegion(
                                                    text(row[1]),
                                                    text(row[2]),
                                                    time(row[3]),
                                                    time(row[4]),
                                                    number(row[5]) != 0,
                                                    key(row, 6, 7, 8)));
                            fields.computeIfAbsent(id, ignored -> decodeFields(text(row[9])));
                        }
                        List<CatalogBuild> builds = new ArrayList<>();
                        for (var group : regions.entrySet()) {
                            var listings = group.getValue();
                            listings.sort(
                                    Comparator.comparing(BuildRegion::region)
                                            .thenComparing(BuildRegion::firstSeen)
                                            .thenComparing(BuildRegion::cdnConfig));
                            var f = fields.get(group.getKey());
                            BuildIdentity identity = BuildIdentity.fromFields(product, f);
                            if (!identity.id().equals(group.getKey())) {
                                throw new CatalogStoreException(
                                        "CATALOG_UNREADABLE",
                                        "Stored build identity differs from its fields");
                            }
                            String pc = f.get("ProductConfig");
                            if (pc != null && pc.isEmpty()) {
                                pc = null;
                            }
                            builds.add(
                                    new CatalogBuild(
                                            product,
                                            group.getKey(),
                                            f.get("VersionsName"),
                                            f.get("BuildConfig"),
                                            pc,
                                            f,
                                            listings.stream()
                                                    .map(BuildRegion::firstSeen)
                                                    .min(Comparator.naturalOrder())
                                                    .orElseThrow(),
                                            listings.stream()
                                                    .map(BuildRegion::lastSeen)
                                                    .max(Comparator.naturalOrder())
                                                    .orElseThrow(),
                                            listings.stream().anyMatch(BuildRegion::current),
                                            listings));
                        }
                        builds.sort(
                                Comparator.comparing(CatalogBuild::firstSeen)
                                        .reversed()
                                        .thenComparing(CatalogBuild::id));
                        List<CatalogProblem> problems = new ArrayList<>();
                        for (Object[] row :
                                db.query(
                                        "SELECT p.code,p.message,p.row,e.record,e.record_type,e.record_ns FROM problems p JOIN entries e ON e.id=p.entry WHERE e.product=? AND e.kind=1 ORDER BY e.observed_at DESC,e.record DESC,coalesce(p.row,-1),p.ordinal LIMIT ?",
                                        product,
                                        MAX_PROBLEMS)) {
                            problems.add(
                                    new CatalogProblem(
                                            text(row[0]),
                                            text(row[1]),
                                            key(row, 3, 4, 5),
                                            row[2] == null
                                                    ? null
                                                    : Math.toIntExact(number(row[2]))));
                        }
                        Object latest =
                                db.query(
                                                "SELECT MAX(observed_at) FROM entries WHERE product=? AND kind=1",
                                                product)
                                        .getFirst()[0];
                        BuildList result =
                                new BuildList(
                                        product,
                                        builds,
                                        problems,
                                        latest == null ? null : time(latest));
                        db.uncancelled(() -> db.exec("COMMIT"));
                        return result;
                    } catch (Throwable e) {
                        db.uncancelled(
                                () -> {
                                    if (db.inTransaction()) {
                                        db.exec("ROLLBACK");
                                    }
                                });
                        throw e;
                    }
                });
    }

    private static long number(Object value) {
        if (!(value instanceof Long n)) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "Expected an integer in catalog metadata");
        }
        return n;
    }

    private static String text(Object value) {
        if (!(value instanceof String s)) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "Expected text in catalog metadata");
        }
        return s;
    }

    private static Instant time(Object value) {
        return Instant.ofEpochMilli(number(value));
    }

    private static ObjectKey key(Object[] row, int bytes, int type, int namespace) {
        return new ObjectKey(
                Math.toIntExact(number(row[type])),
                Math.toIntExact(number(row[namespace])),
                (byte[]) row[bytes]);
    }

    private static String encodeFields(Map<String, String> fields) {
        StringWriter output = new StringWriter();
        try (var generator = new JsonFactory().createGenerator(output)) {
            generator.writeStartObject();
            for (var field : new TreeMap<>(fields).entrySet()) {
                generator.writeStringField(field.getKey(), field.getValue());
            }
            generator.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return output.toString();
    }

    private static Map<String, String> decodeFields(String json) {
        if (json.length() > VersionsTable.MAX_BYTES) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "Stored build fields exceed the source bound");
        }
        Map<String, String> fields = new TreeMap<>();
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected fields object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("Expected field name");
                }
                String key = parser.currentName();
                if (parser.nextToken() != JsonToken.VALUE_STRING
                        || fields.put(key, parser.getText()) != null) {
                    throw new IOException("Invalid or duplicate field");
                }
            }
            if (parser.nextToken() != null) {
                throw new IOException("Trailing fields data");
            }
        } catch (IOException e) {
            throw new CatalogStoreException(
                    "CATALOG_UNREADABLE", "Stored build fields cannot be read", e);
        }
        return Map.copyOf(fields);
    }

    @Override
    public void close() {
        db.close();
    }
}
