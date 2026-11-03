package com.vibeminer.store;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner associations are committed with the physical pack index, never by a presence mirror. */
final class LibraryOwnership {
    private LibraryOwnership() {}

    record Claim(
            String owner, LibraryCatalog.Scope scope, String source, LibraryCatalog.Role role) {
        Claim {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(role);
            if (owner == null
                    || owner.isBlank()
                    || owner.length() > 1024
                    || source == null
                    || source.isBlank()
                    || source.length() > 1000) {
                throw new IllegalArgumentException("Invalid publication owner/source");
            }
        }
    }

    record Attachment(Claim owner, ObjectKey key, long length) {}

    static void initialize(NativeSqlite db) {
        db.transaction(
                true,
                () -> {
                    db.exec(
                            "CREATE TABLE IF NOT EXISTS storage_metadata(version INTEGER NOT"
                                    + " NULL)");
                    if (db.scalar("SELECT count(*) FROM storage_metadata") == 0) {
                        db.exec("INSERT INTO storage_metadata VALUES(1)");
                    } else if (db.scalar("SELECT count(*) FROM storage_metadata") != 1
                            || db.scalar("SELECT version FROM storage_metadata") != 1) {
                        throw new StoreException(
                                "OBJECT_STORE_FORMAT", "Unsupported library metadata version");
                    }
                    db.exec(
                            "CREATE VIEW IF NOT EXISTS storage_objects AS SELECT k,length,CASE WHEN"
                                    + " hex(substr(k,1,1))='07' THEN 'DERIVED' ELSE 'ORIGINAL' END role"
                                    + " FROM objects");
                });
    }

    static boolean owned(NativeSqlite db, Claim owner, ObjectKey key) {
        return db.scalar(
                        "SELECT count(*) FROM catalog_refs r JOIN catalog_expected e ON"
                                + " e.active=r.generation JOIN catalog_owners o ON o.id=e.owner WHERE"
                                + " e.owner=? AND e.source=? AND o.retained=1 AND r.k=?",
                        owner.owner(),
                        "storage:" + owner.source(),
                        key.indexBytes())
                != 0;
    }

    static void attach(NativeSqlite db, Claim owner, ObjectKey key, long length) {
        // Catalog schema is initialized before exposing owner views at composition startup.
        List<Object[]> previous =
                db.query("SELECT scope FROM catalog_owners WHERE id=?", owner.owner());
        if (!previous.isEmpty() && !owner.scope().name().equals(previous.getFirst()[0])) {
            throw new IllegalArgumentException("Canonical owner scope cannot change");
        }
        db.exec(
                "INSERT INTO catalog_owners VALUES(?,?,1) ON CONFLICT(id) DO UPDATE SET retained=1",
                owner.owner(),
                owner.scope().name());
        String source = "storage:" + owner.source();
        List<Object[]> expected =
                db.query(
                        "SELECT role,active,pending FROM catalog_expected WHERE owner=? AND"
                                + " source=?",
                        owner.owner(),
                        source);
        String generation;
        if (expected.isEmpty()) {
            if (db.scalar("SELECT count(*) FROM catalog_expected WHERE owner=?", owner.owner())
                    >= 128) {
                throw new StoreException(
                        "OBJECT_STORE_LIMIT", "Too many publication sources for owner");
            }
            generation = UUID.randomUUID().toString();
            db.exec(
                    "INSERT INTO catalog_expected(owner,source,role,active,pending,version)"
                            + " VALUES(?,?,?,NULL,NULL,'live-v1')",
                    owner.owner(),
                    source,
                    owner.role().name());
            db.exec(
                    "INSERT INTO catalog_generations VALUES(?,?,?,?,'ACTIVE')",
                    generation,
                    owner.owner(),
                    source,
                    "live-v1");
            db.exec(
                    "UPDATE catalog_expected SET active=? WHERE owner=? AND source=?",
                    generation,
                    owner.owner(),
                    source);
        } else {
            Object[] row = expected.getFirst();
            if (!owner.role().name().equals(row[0]) || row[1] == null || row[2] != null) {
                throw new IllegalStateException(
                        "Publication source is not a stable live inventory");
            }
            generation = (String) row[1];
        }
        Object[] stored =
                db.query("SELECT length FROM objects WHERE k=?", key.indexBytes()).getFirst();
        long actualLength = ((Number) stored[0]).longValue();
        // First committed generated value wins; do not publish a duplicate caller's different size.
        db.exec(
                "INSERT INTO catalog_refs VALUES(?,?,?,?) ON CONFLICT(generation,k) DO UPDATE SET"
                        + " declared=excluded.declared",
                generation,
                key.indexBytes(),
                owner.role().name(),
                actualLength);
    }
}
