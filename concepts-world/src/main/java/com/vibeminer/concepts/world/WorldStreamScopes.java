package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated worker-only source authority engine. No caller DTO can mint a heavy proof. */
final class WorldStreamScopes {
    static final CapturedRowsTypes.ReadLimits READ_LIMITS =
            new CapturedRowsTypes.ReadLimits(256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10);

    static final class Scope {
        private final long owner, generation, mapId, wdtId;
        private final String wdtKey, digest;

        private Scope(
                long owner, long generation, long mapId, long wdtId, String wdtKey, String digest) {
            this.owner = owner;
            this.generation = generation;
            this.mapId = mapId;
            this.wdtId = wdtId;
            this.wdtKey = wdtKey;
            this.digest = digest;
        }

        long mapId() {
            return mapId;
        }

        long wdtId() {
            return wdtId;
        }

        String wdtKey() {
            return wdtKey;
        }

        String digest() {
            return digest;
        }
    }

    private record MapBinding(long mapId, long wdtId, String identity) {}

    private static final class Source implements AutoCloseable {
        private final String key;
        private byte[] bytes;

        Source(String key, byte[] bytes) {
            this.key = key;
            this.bytes = bytes;
        }

        String key() {
            return key;
        }

        byte[] bytes() {
            return Objects.requireNonNull(bytes, "Source input retired");
        }

        @Override
        public void close() {
            bytes = null;
        }
    }

    private record Maid(String key, int[] tuples, BitSet present) {}

    private record Proof(Scope token, MapBinding map, int[] maid, BitSet present) {}

    private final BuildTables tables;
    private final BuildFiles files;
    private final long owner;
    private final AtomicBoolean working = new AtomicBoolean();
    private final Cancellation lifetime = new Cancellation();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closed;
    private long generation;
    private Proof current, replacement;

    WorldStreamScopes(BuildTables tables, BuildFiles files) {
        this.tables = Objects.requireNonNull(tables);
        this.files = Objects.requireNonNull(files);
        if (!Objects.equals(tables.build(), files.build())
                || !Objects.equals(tables.locale(), files.locale())) {
            throw new IllegalArgumentException("Source build/locale mismatch");
        }
        WowLocale.parse(files.locale());
        long nonce;
        do {
            nonce = new SecureRandom().nextLong();
        } while (nonce == 0);
        owner = nonce;
    }

    /** Synchronous preparation operation: caller must run it on a source worker, never FX. */
    @SuppressWarnings("try")
    Scope create(WorldService.MapInfo requested, Cancellation external) {
        begin();
        try (var registration = external.onCancel(lifetimeChild::cancel)) {
            var token = lifetimeChild;
            token.check();
            MapBinding map = map(requested.id(), token);
            synchronized (this) {
                replacement = null;
            }
            Maid maid = readMaid(map.wdtId(), token);
            // Recheck the qualified selected row and WDT identity before committing a proof.
            if (!map.equals(map(requested.id(), token))
                    || !maid.key().equals(stat(map.wdtId(), token).contentKey())) {
                throw new IllegalStateException("Scope source changed");
            }
            String digest = digest(map, maid.key());
            synchronized (this) {
                checkOpen();
                token.check();
                if (generation == Long.MAX_VALUE) {
                    throw new IllegalStateException("Scope generation exhausted");
                }
                var scope =
                        new Scope(
                                owner, ++generation, map.mapId(), map.wdtId(), maid.key(), digest);
                replacement = new Proof(scope, map, maid.tuples(), maid.present());
                return scope;
            }
        } finally {
            end();
        }
    }

    // Owns and retires source bytes/parsed inventory before any next Map capture is opened.
    private Maid readMaid(long id, Cancellation token) {
        try (var wdt = read(id, 1 << 20, token)) {

            var world = WorldFormats.wdt(wdt.bytes(), token::isCanceled);
            int[] maid = new int[4096 * 8];
            var present = new BitSet(4096);
            for (var tile : world.tiles()) {
                token.check();
                int ordinal = tile.y() * 64 + tile.x();
                if (present.get(ordinal)) {
                    throw new IllegalArgumentException("Duplicate MAID coordinate");
                }
                present.set(ordinal);
                long[] refs = references(tile);
                for (int i = 0; i < 8; i++) {
                    if (refs[i] < 0 || refs[i] > 0xffff_ffffL) {
                        throw new IllegalArgumentException("Invalid MAID reference");
                    }
                    maid[ordinal * 8 + i] = (int) refs[i];
                }
            }
            return new Maid(wdt.key(), maid, present);
        }
    }

    // Per-operation linked token is protected by the one-active CAS, never exposed in a Scope.
    private Cancellation lifetimeChild;

    private void begin() {
        if (!working.compareAndSet(false, true)) {
            throw new IllegalStateException("Source preparation busy");
        }
        boolean rejected;
        synchronized (this) {
            rejected = closed;
            if (!rejected) {
                lifetimeChild = new Cancellation();
            }
        }
        if (rejected) {
            working.set(false);
            drained.complete(null);
            throw new IllegalStateException("Scope source closed");
        }
        if (lifetime.isCanceled()) {
            lifetimeChild.cancel();
        }
    }

    private void end() {
        boolean finish;
        synchronized (this) {
            lifetimeChild = null;
            working.set(false);
            finish = closed;
        }
        if (finish) {
            drained.complete(null);
        }
    }

    synchronized void activate(Scope scope) {
        checkOpen();
        if (working.get()) {
            throw new IllegalStateException("Source preparation busy");
        }
        if (replacement == null || !replacement.token().equals(scope)) {
            throw new IllegalStateException("Stale scope");
        }
        current = replacement;
        replacement = null;
    }

    @SuppressWarnings("try")
    WorldFormats.Tile recorded(Scope scope, WorldFormats.Tile caller, Cancellation external) {
        begin();
        try (var registration = external.onCancel(lifetimeChild::cancel)) {
            var token = lifetimeChild;
            Proof proof;
            synchronized (this) {
                proof = resolve(scope);
            }
            if (!proof.map().equals(map(scope.mapId(), token))
                    || !scope.wdtKey().equals(stat(scope.wdtId(), token).contentKey())) {
                synchronized (this) {
                    invalidate(scope);
                }
                throw new IllegalStateException("Scope source changed");
            }
            int ordinal = caller.y() * 64 + caller.x();
            if (!proof.present().get(ordinal)) {
                throw new IllegalArgumentException("Unrecorded terrain coordinate");
            }
            long[] refs = references(caller);
            for (int i = 0; i < 8; i++) {
                if (refs[i] != Integer.toUnsignedLong(proof.maid()[ordinal * 8 + i])) {
                    throw new IllegalArgumentException("Caller differs from recorded MAID");
                }
            }
            token.check();
            synchronized (this) {
                resolve(scope);
            }
            return caller;
        } finally {
            end();
        }
    }

    synchronized int retainedProofs() {
        return (current == null ? 0 : 1) + (replacement == null ? 0 : 1);
    }

    synchronized int retainedMaidBytes() {
        return retainedProofs() * 131_584;
    }

    CompletableFuture<Void> closeAsync() {
        Cancellation active;
        boolean finish;
        synchronized (this) {
            if (closed) {
                return drained.copy();
            }
            closed = true;
            current = null;
            replacement = null;
            active = lifetimeChild;
            finish = !working.get();
        }
        if (finish) {
            drained.complete(null);
        }
        Thread.startVirtualThread(
                () -> {
                    lifetime.cancel();
                    if (active != null) {
                        active.cancel();
                    }
                });
        return drained.copy();
    }

    private Proof resolve(Scope scope) {
        checkOpen();
        if (scope == null || scope.owner != owner) {
            throw new IllegalStateException("Foreign scope");
        }
        if (current != null && current.token().equals(scope)) {
            return current;
        }
        if (replacement != null && replacement.token().equals(scope)) {
            return replacement;
        }
        throw new IllegalStateException("Stale scope");
    }

    private void invalidate(Scope scope) {
        if (current != null && current.token().equals(scope)) {
            current = null;
        }
        if (replacement != null && replacement.token().equals(scope)) {
            replacement = null;
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Scope source closed");
        }
    }

    private MapBinding map(long requestedId, Cancellation token) {
        var capture = tables.capturePreparedLocal(TableTypes.Ref.forName("Map"), token).join();
        if (capture.rows() == null) {
            throw new UnsupportedOperationException("Prepared native Map capture unavailable");
        }
        try (var rows = capture.rows()) {
            var receipt = rows.receipt();
            requireEntry(receipt.table());
            if (!receipt.build().equals(files.build())
                    || !receipt.locale().equals(files.locale())
                    || receipt.rowCount() > 10_000
                    || receipt.completion().excludedEncryptedSections() != 0
                    || receipt.completion().excludedPhysicalRecords() != 0
                    || receipt.completion().excludedCopyRecords() != 0) {
                throw new IllegalStateException("Unqualified Map capture scope");
            }
            validateNative(receipt);
            var schema =
                    new TableTypes.SchemaResult(
                            receipt.table(), receipt.columns(), receipt.schema());
            int column = WorldService.column(schema, "WdtFileDataID", true);
            if (column < 0) {
                throw new IllegalStateException("Unqualified Map WDT field");
            }
            String layout = mapLayout(schema);
            long ordinal = 0;
            MapBinding found = null;
            do {
                token.check();
                var page = rows.readOrdinalPage(ordinal, 1, READ_LIMITS, token).join();
                if (page.state() == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED) {
                    throw new UnsupportedOperationException(
                            "Map source exceeds bounded hot scope profile");
                }
                token.check();
                int expectedRows = (int) Math.min(1, receipt.rowCount() - ordinal);
                long expectedNext = ordinal + expectedRows;
                Long next = expectedNext < receipt.rowCount() ? expectedNext : null;
                if (page.state() != CapturedRowsTypes.ReadState.READ
                        || !page.identity().equals(receipt.graphIdentity())
                        || page.firstOrdinal() != ordinal
                        || page.rows().size() != expectedRows
                        || !Objects.equals(page.nextOrdinal(), next)) {
                    throw new IllegalStateException("Noncontiguous Map capture page");
                }
                for (var row : page.rows()) {
                    if (row.ordinal() != ordinal || row.ordinal() >= receipt.rowCount()) {
                        throw new IllegalStateException("Invalid Map row ordinal");
                    }
                    if (row.row().id() != requestedId) {
                        continue;
                    }
                    if (found != null) {
                        throw new IllegalStateException("Duplicate Map ID");
                    }
                    if (row.row().textIssues() == null
                            || row.row().textIssues().stream()
                                    .anyMatch(issue -> issue.columnIndex() == column)
                            || page.cellIssues().stream()
                                    .anyMatch(
                                            issue ->
                                                    issue.columnIndex() < 0
                                                            || issue.columnIndex() == column)) {
                        throw new IllegalStateException("Unqualified Map numeric issue channel");
                    }
                    Long wdt = WorldService.number(schema, row.row(), "WdtFileDataID");
                    if (wdt == null || wdt <= 0 || wdt > 0xffff_ffffL) {
                        throw new IllegalStateException("Missing Map WDT reference");
                    }
                    String binding =
                            receipt.table().fileDataId()
                                    + ":"
                                    + receipt.table().contentKey()
                                    + ":"
                                    + layout
                                    + ":"
                                    + receipt.graphIdentity()
                                    + ":"
                                    + receipt.definitionSha256()
                                    + ":"
                                    + receipt.blockIdentity()
                                    + ":"
                                    + receipt.issueChannelEvidence().sourceIdentitySha256()
                                    + ":"
                                    + row.ordinal()
                                    + ":"
                                    + requestedId
                                    + ":"
                                    + column
                                    + ":"
                                    + wdt;
                    found =
                            new MapBinding(
                                    requestedId,
                                    wdt,
                                    sha(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                if (page.nextOrdinal() == null) {
                    if (ordinal + page.rows().size() != receipt.rowCount()) {
                        throw new IllegalStateException("Truncated Map capture");
                    }
                    break;
                }
                ordinal = page.nextOrdinal();
            } while (true);
            if (found == null) {
                throw new IllegalStateException("Unknown Map ID");
            }
            return found;
        }
    }

    private static void validateNative(CapturedRowsTypes.Receipt r) {
        var e = r.issueChannelEvidence();
        if (e.state() != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                || !e.graphIdentity().equals(r.graphIdentity())
                || !e.resolvedBlockSha256().equals(r.blockIdentity())
                || !e.readerOptionsSha256().equals(r.readerOptions())
                || !e.actualColumnsSha256().equals(NativeIssueIdentity.columns(r.columns()))) {
            throw new IllegalStateException("Map capture lacks matching native issue proof");
        }
        for (var locale : new String[] {null, r.locale()}) {
            for (var encoding : new String[] {null, r.encodingScope()}) {
                byte[] tuple =
                        NativeIssueIdentity.tuple(
                                GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                                e.policyId(),
                                e.producerId(),
                                locale,
                                r.table().contentKey(),
                                r.blockIdentity(),
                                r.readerOptions(),
                                r.readerRevision(),
                                r.rowEncoding(),
                                encoding);
                if (tuple.length > 4096) {
                    throw new IllegalStateException("Map capture identity exceeds bound");
                }
                if (r.sourceIdentity().equals("native-tuple-hex=" + HexFormat.of().formatHex(tuple))
                        && e.sourceIdentitySha256().equals(sha(tuple))) {
                    return;
                }
            }
        }
        throw new IllegalStateException("Map native issue identity differs");
    }

    private static String mapLayout(TableTypes.SchemaResult schema) {
        if (schema.table().counts() == null
                || schema.table().counts().layoutHash() == null
                || !schema.table().counts().layoutHash().matches("[0-9a-fA-F]{8}")
                || !schema.schema().layoutHashes().contains(schema.table().counts().layoutHash())) {
            throw new IllegalStateException("Unbound Map layout");
        }
        var field =
                schema.schema().columns().stream()
                        .filter(c -> c.name().equals("WdtFileDataID"))
                        .toList();
        if (field.size() != 1) {
            throw new IllegalStateException("Ambiguous Map WDT field");
        }
        var column = field.getFirst();
        return schema.table().counts().layoutHash()
                + ":"
                + column.kind()
                + ":"
                + column.bits()
                + ":"
                + column.unsigned()
                + ":"
                + column.verifiedName()
                + ":"
                + column.arrayLength();
    }

    private static void requireEntry(TableTypes.Entry entry) {
        if (entry == null
                || !entry.checked()
                || entry.state() != TableTypes.State.READY
                || !"Map".equals(entry.name())
                || entry.contentKey() == null
                || !entry.contentKey().matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unqualified Map source");
        }
    }

    private FileTypes.BuildFile stat(long id, Cancellation token) {
        var result = files.stat(FileTypes.FileRef.id(id), token).join();
        if (result.file() == null
                || result.file().fileDataId() != id
                || result.file().contentKey() == null
                || !result.file().contentKey().matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unbound source");
        }
        return result.file();
    }

    private Source read(long id, int limit, Cancellation token) {
        var before = stat(id, token);
        if (before.size() != null && before.size() > limit) {
            throw new IllegalStateException("Source exceeds bound");
        }
        var opened = files.openRead(FileTypes.FileRef.id(id), token).join();
        if (opened.stream() == null) {
            throw new IllegalStateException("Source unavailable");
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()
                    || stream.length() < 12
                    || stream.length() > limit
                    || before.size() != null && before.size() != stream.length()) {
                throw new IllegalStateException("Incomplete source");
            }
            var bytes = ByteBuffer.allocate((int) stream.length());
            while (bytes.hasRemaining()) {
                token.check();
                if (stream.read(bytes, token).join() <= 0) {
                    throw new IllegalStateException("Truncated source");
                }
            }
            token.check();
            var after = stat(id, token);
            // Download/local metadata enrichment is allowed; only consumed source identity binds.
            if (before.fileDataId() != after.fileDataId()
                    || !before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != bytes.capacity()) {
                throw new IllegalStateException("Source changed during read");
            }
            return new Source(before.contentKey(), bytes.array());
        }
    }

    private String digest(MapBinding map, String key) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("vimi-world-stream-scope-v1");
                var build = tables.build();
                for (String value :
                        List.of(
                                build.product(),
                                build.buildConfig(),
                                build.cdnConfig(),
                                tables.locale())) {
                    out.writeUTF(value);
                }
                out.writeBoolean(build.region() != null);
                if (build.region() != null) {
                    out.writeUTF(build.region());
                }
                out.writeLong(map.mapId());
                out.writeLong(map.wdtId());
                out.writeUTF(map.identity());
                out.writeUTF(key);
            }
            return sha(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long[] references(WorldFormats.Tile tile) {
        return new long[] {
            tile.rootId(),
            tile.mapTextureId(),
            tile.minimapId(),
            tile.obj0Id(),
            tile.obj1Id(),
            tile.tex0Id(),
            tile.lodId(),
            tile.mapTextureNId()
        };
    }
}
