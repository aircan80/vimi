package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.WalFrames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only child process for {@link TableLostCommitCrashTest}. It runs one preparation session on
 * a library with a saved previous state and, after a sync point, writes every kind of record the
 * application keeps in the library database, each step acknowledged before the next:
 *
 * <ol>
 *   <li>the known-table pass (tables 100 and 200 commit; the store fsyncs each pack first);
 *   <li>the saved table inventory, replaced for the same identity (a delete, then a put);
 *   <li>a catalog inventory naming the new graphs (begin, append, publish: three commits);
 *   <li>a preparation receipt, written like the application's stores through its own connection.
 * </ol>
 *
 * It then blocks until killed, so nothing is checkpointed or closed. The parent decides which of
 * the unsynced WAL frames the OS "kept".
 */
public final class TableLostCommitWorker {
    static final String SOURCE = "tables-inventory", OWNER = "build-a";
    static final String PREVIOUS = "v1", NEXT = "v2", RECEIPT = "fixture-receipt";

    private TableLostCommitWorker() {}

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]), control = Path.of(args[1]);
        int snapshotAt = Integer.parseInt(args[2]);
        var scenario = TableCommitScenario.open(folder);
        Path database = scenario.library.databasePath();
        syncPoint(database);
        var wal = WalFrames.of(database);
        Files.writeString(
                control.resolve("sync-point.txt"),
                Files.size(wal.path())
                        + "\n"
                        + HexFormat.of().formatHex(wal.header())
                        + "\n"
                        + sha256(database)
                        + "\n");
        // A pre-commit snapshot: the index as it was before the table commit that synced its pack
        // at this point. Later pack bytes keep reaching the disk.
        var seen = new AtomicInteger();
        scenario.probe(
                (point, key) -> {
                    if (point.equals("PACK_SYNCED") && seen.incrementAndGet() == snapshotAt) {
                        try {
                            Path snapshot = Files.createDirectories(control.resolve("snapshot"));
                            for (String suffix : List.of("", "-wal")) {
                                Path file =
                                        database.resolveSibling(database.getFileName() + suffix);
                                Files.copy(
                                        file,
                                        snapshot.resolve(file.getFileName()),
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                });
        prepare(scenario);
        scenario.probe(null);
        claim(scenario);
        download(scenario);
        System.out.println("READY LOST");
        System.out.flush();
        System.in.read();
        System.exit(2);
    }

    /** One preparation session's records, in the application's order. */
    static TableTypes.PreloadResult prepare(TableCommitScenario scenario) throws Exception {
        var pass = scenario.tables().prepareKnown(null, Cancellation.NONE).join();
        if (pass.outcome() != TableTypes.PreloadOutcome.COMPLETED) {
            throw new IllegalStateException("Known pass did not complete: " + pass);
        }
        new PreparedTablesCache(scenario.generated)
                .save(TableCommitScenario.INVENTORY, pass, Cancellation.NONE)
                .join();
        publishCatalog(scenario, NEXT, TableCommitScenario.IDS);
        receipt(scenario.library.databasePath(), "TABLES");
        return pass;
    }

    static final String CLAIMANT = "build-b";
    static final byte[] DOWNLOAD = download();

    private static byte[] download() {
        byte[] bytes = new byte[70_001];
        new java.util.Random(7).nextBytes(bytes);
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        return bytes;
    }

    static ObjectKey downloadKey() {
        try {
            return new ObjectKey(
                    ObjectKey.ENCODED, MessageDigest.getInstance("MD5").digest(DOWNLOAD));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A second build claims table 100's stored node: an ownership attach, no payload write. */
    static void claim(TableCommitScenario scenario) {
        var node =
                new ObjectKey(
                        ObjectKey.GENERATED,
                        GeneratedKey.parse(scenario.page(100).identity()).bytes());
        byte[] bytes = scenario.library.read(node).join().bytes();
        var outcome =
                scenario.library
                        .forOwner(
                                CLAIMANT,
                                LibraryCatalog.Scope.BUILD,
                                "tables",
                                LibraryCatalog.Role.DERIVED)
                        .put(List.of(new com.vibeminer.store.StoreTypes.PutObject(node, bytes)))
                        .join()
                        .getFirst()
                        .outcome();
        if (outcome != com.vibeminer.store.StoreTypes.PutOutcome.ALREADY_PRESENT) {
            throw new IllegalStateException("Claim stored a new object: " + outcome);
        }
    }

    static boolean claimed(TableCommitScenario scenario) {
        try {
            return scenario.catalog
                            .figures(CLAIMANT, com.vibeminer.store.Cancellation.NONE)
                            .join()
                            .derived()
                            .storedObjects()
                    > 0;
        } catch (java.util.concurrent.CompletionException unknownOwner) {
            return false;
        }
    }

    /** A staged download: the staged file is copied into the pack, committed, then deleted. */
    static void download(TableCommitScenario scenario) {
        var stage =
                scenario.original
                        .stage(
                                downloadKey(),
                                DOWNLOAD.length,
                                com.vibeminer.store.StoreTypes.Origin.CDN,
                                com.vibeminer.store.Cancellation.NONE)
                        .join();
        stage.append(DOWNLOAD, com.vibeminer.store.Cancellation.NONE).join();
        var outcome = stage.commit(com.vibeminer.store.Cancellation.NONE).join().outcome();
        if (outcome != com.vibeminer.store.StoreTypes.PutOutcome.STORED) {
            throw new IllegalStateException("Download not stored: " + outcome);
        }
    }

    static boolean downloaded(TableCommitScenario scenario) {
        return scenario.library.has(downloadKey()).join();
    }

    /**
     * The objects a catalog generation names: each table's node. (Not the saved inventory, a
     * pointer that its own replacement deletes before it puts the new one.)
     */
    static List<GeneratedKey> catalogKeys(TableCommitScenario.Reference reference, List<Long> ids) {
        var keys = new ArrayList<GeneratedKey>();
        for (long id : ids) {
            keys.add(GeneratedKey.parse(reference.identities().get(id)));
        }
        return keys;
    }

    /** Publishes a catalog generation naming each table's node. */
    static void publishCatalog(TableCommitScenario scenario, String version, List<Long> ids) {
        var keys = new ArrayList<GeneratedKey>();
        for (long id : ids) {
            keys.add(GeneratedKey.parse(scenario.page(id).identity()));
        }
        var lengths =
                scenario.generated
                        .storedLengths(keys, com.vibeminer.generated.Cancellation.NONE)
                        .join();
        var references = new ArrayList<LibraryCatalog.Reference>();
        for (int i = 0; i < keys.size(); i++) {
            if (lengths.get(i) < 0) {
                throw new IllegalStateException("Catalog key is not stored: " + keys.get(i));
            }
            references.add(
                    new LibraryCatalog.Reference(
                            new ObjectKey(ObjectKey.GENERATED, keys.get(i).bytes()),
                            lengths.get(i)));
        }
        var pages = new AtomicInteger();
        scenario.catalog
                .replace(
                        OWNER,
                        SOURCE,
                        version,
                        (limit, token) -> pages.getAndIncrement() == 0 ? references : List.of(),
                        com.vibeminer.store.Cancellation.NONE)
                .join();
    }

    /** A synchronous=FULL commit: every WAL frame before it is on disk. */
    static void syncPoint(Path database) throws Exception {
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    sql -> {
                        sql.execute("PRAGMA synchronous=FULL");
                        return null;
                    });
            connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS fixture_sync(id INTEGER PRIMARY KEY,"
                                        + " at INTEGER NOT NULL)");
                        sql.execute(
                                "INSERT INTO fixture_sync VALUES(1,?) ON CONFLICT(id) DO UPDATE"
                                        + " SET at=excluded.at",
                                System.nanoTime());
                        return null;
                    });
        }
    }

    /** Written like the application's receipts: its own connection, the library's WAL, NORMAL. */
    static void receipt(Path database, String ready) throws Exception {
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS fixture_receipts(identity TEXT PRIMARY"
                                        + " KEY, ready TEXT NOT NULL) WITHOUT ROWID");
                        sql.execute(
                                "INSERT INTO fixture_receipts VALUES(?,?) ON CONFLICT(identity) DO"
                                        + " UPDATE SET ready=excluded.ready",
                                RECEIPT,
                                ready);
                        return null;
                    });
        }
    }

    /** The saved receipt's readiness, or null. */
    static String receipt(Path database) throws Exception {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    sql -> {
                        if (sql.query(
                                        "SELECT 1 FROM sqlite_master WHERE type='table' AND"
                                                + " name='fixture_receipts'")
                                .isEmpty()) {
                            return null;
                        }
                        var rows =
                                sql.query(
                                        "SELECT ready FROM fixture_receipts WHERE identity=?",
                                        RECEIPT);
                        return rows.isEmpty() ? null : (String) rows.getFirst()[0];
                    });
        }
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
