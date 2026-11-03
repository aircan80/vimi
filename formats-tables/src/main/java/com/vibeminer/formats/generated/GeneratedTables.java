package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.RecordFile;
import com.vibeminer.generated.RecordFileOptions;
import com.vibeminer.generated.RecordFileWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Durable generated table graph, content-shared record segments and independently canceled build
 * waiters.
 */
public final class GeneratedTables implements GeneratedTableStore {
    public static final int MAX_CHAIN_STEPS = 64;

    public record Identity(
            String format,
            String locale,
            String contentKey,
            String definition,
            String readerOptions,
            String reader,
            String rows,
            String encodingIn) {}

    public record Node(String format, Identity identity, List<String> keys) {
        public Node {
            keys = List.copyOf(keys);
        }
    }

    public record Metadata(
            String format,
            Identity identity,
            Db2Types.Header header,
            List<Db2Types.Section> sections,
            Db2Types.Completion completion,
            List<String> keysUsed,
            List<String> holeKeys) {
        public Metadata {
            java.util.Objects.requireNonNull(header);
            java.util.Objects.requireNonNull(completion);
            java.util.Objects.requireNonNull(identity);
            java.util.Objects.requireNonNull(format);
            sections = List.copyOf(sections);
            keysUsed = List.copyOf(keysUsed);
            holeKeys = List.copyOf(holeKeys);
        }
    }

    public record Layout(String format, String contentKey, String layout) {}

    private final GeneratedStore store;
    private final RecordFileOptions options;
    java.util.function.IntConsumer waiterProbe = ignored -> {};
    private final Object gate = new Object();
    private final Map<GeneratedKey, Flight> flights = new HashMap<>();
    private static final String NODE = "vibeminer-generated-table-node v1",
            META = "vibeminer-generated-table-metadata v1",
            LAYOUT = "vibeminer-generated-table-layout v1";

    public GeneratedTables(GeneratedStore store) {
        this(store, new RecordFileOptions(4 << 20, 16 << 20, 16 << 20, 16 << 10, 8 << 20, 9));
    }

    public GeneratedTables(GeneratedStore store, RecordFileOptions options) {
        this.store = java.util.Objects.requireNonNull(store);
        this.options = java.util.Objects.requireNonNull(options);
    }

    private record Lookup(
            GeneratedTableSource source,
            KeyStatus keys,
            boolean scoped,
            Map<String, String> statuses) {
        Lookup(GeneratedTableSource source, KeyStatus keys, boolean scoped) {
            this(source, keys, scoped, new HashMap<>());
        }

        String status(String name) {
            return statuses.computeIfAbsent(
                    name,
                    key -> {
                        String value = keys.fingerprintOf(key);
                        if (value != null
                                && (!value.matches("[!-~]{1,128}") || value.equals("?"))) {
                            throw new IllegalStateException("Invalid key fingerprint");
                        }
                        return value == null ? "?" : value;
                    });
        }

        Identity echo() {
            return new Identity(
                    GeneratedTableSource.IDENTITY_FORMAT,
                    source.locale(),
                    source.contentKey(),
                    source.definition(),
                    source.readerOptions(),
                    GeneratedTableSource.READER_REVISION,
                    GeneratedTableSource.ROW_ENCODING,
                    scoped ? source.encodingScope() : null);
        }

        GeneratedKey node(List<String> used) {
            var text = new StringBuilder();
            for (var name : used) {
                text.append(name).append('=').append(status(name)).append('\n');
            }
            byte[] base = HexFormat.of().parseHex(source.baseHash(scoped)),
                    digest =
                            HexFormat.of()
                                    .parseHex(
                                            Json.sha256(
                                                    text.toString()
                                                            .getBytes(StandardCharsets.UTF_8)));
            return GeneratedKey.hash(ByteBuffer.allocate(64).put(base).put(digest).array());
        }
    }

    private static List<String> used(List<String> names) {
        if (names.size() > 1024) {
            throw new IllegalArgumentException("At most1024 TACT keys");
        }
        for (var name : names) {
            TactKeyStatus.checkName(name);
        }
        return names.stream().distinct().sorted().toList();
    }

    @Override
    public String identityOf(
            GeneratedTableSource source, KeyStatus keys, List<String> names, boolean encrypted) {
        if (encrypted && source.encodingScope() == null) {
            throw new IllegalArgumentException("Encrypted source needs scope");
        }
        return new Lookup(source, keys, encrypted).node(used(names)).hex();
    }

    @Override
    public CompletableFuture<GeneratedTable> open(
            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
        return open(source, keys, token, true);
    }

    @Override
    public CompletableFuture<GeneratedTable> openPreparedLocal(
            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
        return open(source, keys, token, false);
    }

    private CompletableFuture<GeneratedTable> open(
            GeneratedTableSource source, KeyStatus keys, Cancellation token, boolean repair) {
        return async(
                () ->
                        withToken(
                                token,
                                t -> {
                                    var lookup = new Lookup(source, keys, false);
                                    var result = openChain(lookup, t, repair);
                                    return result != null || source.encodingScope() == null
                                            ? result
                                            : openChain(
                                                    new Lookup(
                                                            source, keys, true, lookup.statuses()),
                                                    t,
                                                    repair);
                                }));
    }

    @Override
    public CompletableFuture<List<GeneratedKey>> savedArtifacts(
            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
        return async(
                () ->
                        withToken(
                                token,
                                t -> {
                                    var lookup = new Lookup(source, keys, false);
                                    var result = inventory(lookup, t);
                                    if (result == null && source.encodingScope() != null) {
                                        result =
                                                inventory(
                                                        new Lookup(
                                                                source,
                                                                keys,
                                                                true,
                                                                lookup.statuses()),
                                                        t);
                                    }
                                    if (result == null) {
                                        throw new IllegalStateException(
                                                "Saved table graph unavailable");
                                    }
                                    return result;
                                }));
    }

    private List<GeneratedKey> inventory(
            Lookup lookup, com.vibeminer.generated.Cancellation token) {
        List<String> set = List.of();
        var objects = new java.util.LinkedHashSet<GeneratedKey>();
        objects.add(layoutKey(lookup.source().contentKey()));
        for (int step = 0; step < MAX_CHAIN_STEPS; step++) {
            token.check();
            var key = lookup.node(set);
            var read = store.read(key, token).join();
            if (read.state() == GeneratedTypes.ReadState.ABSENT) {
                return null;
            }
            if (read.state() != GeneratedTypes.ReadState.PRESENT) {
                throw new IllegalStateException("Unreadable saved node");
            }
            var node = Json.convert(Json.parse(read.bytes(), 1 << 20), Node.class);
            if (!NODE.equals(node.format())
                    || !lookup.echo().equals(node.identity())
                    || !used(node.keys()).equals(node.keys())) {
                throw new IllegalStateException("Invalid saved node");
            }
            objects.add(key);
            if (node.keys().equals(set)) {
                var file = RecordFile.open(store, manifest(key), token).join();
                if (file.file() == null || file.state() != GeneratedTypes.ReadState.PRESENT) {
                    throw new IllegalStateException("Unreadable saved manifest");
                }
                var metadata =
                        Json.convert(Json.parse(file.file().metadata(), 16 << 20), Metadata.class);
                if (!META.equals(metadata.format())
                        || !lookup.echo().equals(metadata.identity())
                        || !metadata.keysUsed().equals(set)
                        || !used(metadata.holeKeys()).equals(metadata.holeKeys())
                        || !set.containsAll(metadata.holeKeys())
                        || !metadata.holeKeys().isEmpty() && !lookup.scoped()) {
                    throw new IllegalStateException("Invalid saved graph metadata");
                }
                objects.addAll(file.file().keys());
                return List.copyOf(objects);
            }
            if (node.keys().size() <= set.size() || !node.keys().containsAll(set)) {
                throw new IllegalStateException("Invalid saved node chain");
            }
            set = node.keys();
        }
        throw new IllegalStateException("Saved node chain exceeds bound");
    }

    private GeneratedTable openChain(
            Lookup lookup, com.vibeminer.generated.Cancellation token, boolean repair) {
        List<String> set = List.of();
        for (int step = 0; step < MAX_CHAIN_STEPS; step++) {
            var key = lookup.node(set);
            var node = readNode(key, lookup, token, repair);
            if (node == null) {
                return null;
            }
            if (node.keys().equals(set)) {
                return openFile(lookup, key, set, token, repair);
            }
            if (node.keys().size() > set.size() && node.keys().containsAll(set)) {
                set = node.keys();
                continue;
            }
            if (!repair) {
                throw corruptCapture("Invalid saved node chain");
            }
            store.delete(List.of(key), token).join();
            return null;
        }
        if (!repair) {
            throw corruptCapture("Saved node chain exceeds bound");
        }
        store.delete(List.of(lookup.node(List.of())), token).join();
        return null;
    }

    private Node readNode(
            GeneratedKey key, Lookup lookup, com.vibeminer.generated.Cancellation token) {
        return readNode(key, lookup, token, true);
    }

    private Node readNode(
            GeneratedKey key,
            Lookup lookup,
            com.vibeminer.generated.Cancellation token,
            boolean repair) {
        var read = store.read(key, token).join();
        if (read.state() == GeneratedTypes.ReadState.ABSENT) {
            return null;
        }
        try {
            if (read.state() != GeneratedTypes.ReadState.PRESENT) {
                throw new IllegalArgumentException();
            }
            var node = Json.convert(Json.parse(read.bytes(), 1 << 20), Node.class);
            if (!NODE.equals(node.format())
                    || !lookup.echo().equals(node.identity())
                    || !used(node.keys()).equals(node.keys())) {
                throw new IllegalArgumentException();
            }
            return node;
        } catch (IllegalArgumentException | NullPointerException e) {
            if (!repair) {
                throw corruptCapture("Invalid saved node");
            }
            store.delete(List.of(key), token).join();
            return null;
        }
    }

    private GeneratedTable openFile(
            Lookup lookup,
            GeneratedKey node,
            List<String> keys,
            com.vibeminer.generated.Cancellation token) {
        return openFile(lookup, node, keys, token, true);
    }

    private GeneratedTable openFile(
            Lookup lookup,
            GeneratedKey node,
            List<String> keys,
            com.vibeminer.generated.Cancellation token,
            boolean repair) {
        var manifest = manifest(node);
        var open = RecordFile.open(store, manifest, token).join();
        if (open.state() == GeneratedTypes.ReadState.ABSENT) {
            if (!repair) {
                throw corruptCapture("Saved graph manifest missing");
            }
            return null;
        }
        if (open.file() == null) {
            if (!repair) {
                throw corruptCapture("Saved graph manifest corrupt");
            }
            store.delete(List.of(node, manifest), token).join();
            return null;
        }
        var file = open.file();
        Metadata metadata;
        try {
            metadata = Json.convert(Json.parse(file.metadata(), 16 << 20), Metadata.class);
            if (!META.equals(metadata.format())
                    || !lookup.echo().equals(metadata.identity())
                    || !metadata.keysUsed().equals(keys)
                    || !used(metadata.holeKeys()).equals(metadata.holeKeys())
                    || !keys.containsAll(metadata.holeKeys())
                    || !metadata.holeKeys().isEmpty() && !lookup.scoped()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            if (!repair) {
                throw corruptCapture("Invalid saved graph metadata");
            }
            store.delete(List.of(node, manifest), token).join();
            return null;
        }
        for (var segment : file.keys().subList(1, file.keys().size())) {
            if (store.readRange(segment, 0, 1, token).join().state()
                    != GeneratedTypes.ReadState.PRESENT) {
                if (!repair) {
                    throw corruptCapture("Saved graph segment unavailable");
                }
                discard(node, file, token);
                return null;
            }
        }
        return new GeneratedTable(
                lookup.source(),
                node,
                metadata,
                file,
                !repair
                        ? null
                        : cancel ->
                                async(
                                        () ->
                                                withToken(
                                                        cancel,
                                                        t -> {
                                                            discard(node, file, t);
                                                            return null;
                                                        })));
    }

    private static com.vibeminer.generated.GeneratedStoreException corruptCapture(String message) {
        return new com.vibeminer.generated.GeneratedStoreException(GeneratedTypes.CORRUPT, message);
    }

    private void discard(
            GeneratedKey node, RecordFile file, com.vibeminer.generated.Cancellation token) {
        var doomed = new ArrayList<GeneratedKey>();
        doomed.add(node);
        doomed.add(manifest(node));
        for (var segment : file.keys().subList(1, file.keys().size())) {
            if (store.read(segment, token).join().state() == GeneratedTypes.ReadState.CORRUPT) {
                doomed.add(segment);
            }
        }
        store.delete(doomed, token).join();
    }

    @Override
    public CompletableFuture<GeneratedTable> store(
            GeneratedTableSource source,
            KeyStatus keys,
            GeneratedTableContent content,
            Cancellation token) {
        return async(
                () -> {
                    try {
                        return withToken(token, t -> storeCore(source, keys, content, token, t));
                    } finally {
                        if (content.owner() != null) {
                            try {
                                content.owner().close();
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        }
                    }
                });
    }

    private GeneratedTable storeCore(
            GeneratedTableSource source,
            KeyStatus keys,
            GeneratedTableContent content,
            Cancellation token,
            com.vibeminer.generated.Cancellation lower) {
        var used = used(content.keysUsed());
        var holes = used(content.holeKeys());
        if (!used.containsAll(holes)
                || !used.containsAll(content.chunkKeys())
                || content.encrypted() && source.encodingScope() == null) {
            throw new IllegalArgumentException("Invalid encrypted key dependencies");
        }
        for (var section : content.sections()) {
            if (section.isEncrypted() && !used.contains(section.key())) {
                throw new IllegalArgumentException("Missing keyed section dependency");
            }
        }
        if (content.completion().excludedEncryptedSections()
                != content.sections().stream()
                        .filter(s -> s.isEncrypted() && !s.isDecrypted())
                        .count()) {
            throw new IllegalArgumentException("Excluded section counts disagree");
        }
        var lookup = new Lookup(source, keys, content.encrypted());
        var node = lookup.node(used);
        var existing = readNode(node, lookup, lower);
        if (existing != null && existing.keys().equals(used)) {
            var hit = openFile(lookup, node, used, lower);
            if (hit != null) {
                return hit;
            }
        }
        store.delete(List.of(node, manifest(node)), lower).join();
        var metadata =
                new Metadata(
                        META,
                        lookup.echo(),
                        content.header(),
                        content.sections(),
                        content.completion(),
                        used,
                        holes);
        try (var writer = new RecordFileWriter(store, options)) {
            long first = 0;
            long evidenceBytes = 0;
            while (true) {
                token.check();
                var rows = content.rows().read(first, 256, token).join();
                if (rows.isEmpty()) {
                    break;
                }
                for (var row : rows) {
                    for (var issue :
                            row.textIssues() == null
                                    ? List.<Db2Types.TextIssue>of()
                                    : row.textIssues()) {
                        long bytes = issue.rawBytesHex().length() / 2;
                        if (bytes
                                > source.readerLimits().maxRawTextEvidenceBytes() - evidenceBytes) {
                            throw new com.vibeminer.formats.Db2FormatException(
                                    "DB2_LIMIT",
                                    "Aggregate raw text evidence exceeds its emitted-byte bound");
                        }
                        evidenceBytes += bytes;
                    }
                    writer.add(row.id(), RowCodec.encode(row), lower).join();
                }
                first += rows.size();
            }
            writer.complete(manifest(node), Json.bytes(metadata), lower).join();
        }
        byte[] nodeBytes = Json.bytes(new Node(NODE, lookup.echo(), used));
        store.put(List.of(new GeneratedTypes.Put(node, nodeBytes)), lower).join();
        if (content.header().layoutHash().matches("[0-9a-fA-F]{8}")) {
            store.put(
                            List.of(
                                    new GeneratedTypes.Put(
                                            layoutKey(source.contentKey()),
                                            Json.bytes(
                                                    new Layout(
                                                            LAYOUT,
                                                            source.contentKey(),
                                                            content.header().layoutHash())))),
                            lower)
                    .join();
        }
        List<String> set = List.of();
        for (int step = 0; step < MAX_CHAIN_STEPS && !set.equals(used); step++) {
            var key = lookup.node(set);
            var entry = readNode(key, lookup, lower);
            if (entry != null
                    && entry.keys().size() > set.size()
                    && entry.keys().containsAll(set)
                    && used.containsAll(entry.keys())) {
                set = entry.keys();
                continue;
            }
            if (entry != null) {
                store.delete(List.of(key), lower).join();
            }
            store.put(List.of(new GeneratedTypes.Put(key, nodeBytes)), lower).join();
            break;
        }
        var result = openFile(lookup, node, used, lower);
        if (result == null) {
            throw new GeneratedStoreException(
                    GeneratedTypes.CORRUPT, "Stored table failed readback");
        }
        return result;
    }

    @Override
    public CompletableFuture<String> layoutOf(String contentKey, Cancellation token) {
        return layoutOf(contentKey, token, true);
    }

    @Override
    public CompletableFuture<String> savedLayoutOf(String contentKey, Cancellation token) {
        return layoutOf(contentKey, token, false);
    }

    private CompletableFuture<String> layoutOf(
            String contentKey, Cancellation token, boolean repair) {
        if (contentKey == null || !contentKey.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Canonical CKey required");
        }
        return async(
                () ->
                        withToken(
                                token,
                                t -> {
                                    var key = layoutKey(contentKey);
                                    var read = store.read(key, t).join();
                                    if (read.state() == GeneratedTypes.ReadState.ABSENT) {
                                        return null;
                                    }
                                    try {
                                        var layout =
                                                Json.convert(
                                                        Json.parse(read.bytes(), 1024),
                                                        Layout.class);
                                        if (!LAYOUT.equals(layout.format())
                                                || !contentKey.equals(layout.contentKey())
                                                || !layout.layout().matches("[0-9a-fA-F]{8}")) {
                                            throw new IllegalArgumentException();
                                        }
                                        return layout.layout();
                                    } catch (IllegalArgumentException | NullPointerException e) {
                                        if (repair) {
                                            store.delete(List.of(key), t).join();
                                        }
                                        return null;
                                    }
                                }));
    }

    private static GeneratedKey layoutKey(String content) {
        return GeneratedKey.hash((LAYOUT + "\n" + content).getBytes(StandardCharsets.UTF_8));
    }

    private static GeneratedKey manifest(GeneratedKey node) {
        return GeneratedKey.hash(
                ByteBuffer.allocate(21)
                        .put("table".getBytes(StandardCharsets.US_ASCII))
                        .put(node.bytes())
                        .array());
    }

    private static final class Flight {
        final Cancellation cancel = new Cancellation();
        final CompletableFuture<GeneratedTable> result = new CompletableFuture<>();
        int waiters;
        boolean done, abandoned;
    }

    @Override
    public CompletableFuture<GeneratedTable> getOrBuild(
            GeneratedTableSource source,
            KeyStatus keys,
            Function<Cancellation, CompletableFuture<GeneratedTableContent>> build,
            Cancellation token) {
        return async(
                () -> {
                    for (int round = 0; round < 8; round++) {
                        token.check();
                        var hit = open(source, keys, token).join();
                        if (hit != null) {
                            return hit;
                        }
                        var table = join(source, keys, build, token).join();
                        if (table.identity()
                                .equals(
                                        identityOf(
                                                source, keys, table.keysUsed(), table.scoped()))) {
                            return table;
                        }
                    }
                    return store(source, keys, build.apply(token).join(), token).join();
                });
    }

    private CompletableFuture<GeneratedTable> join(
            GeneratedTableSource source,
            KeyStatus keys,
            Function<Cancellation, CompletableFuture<GeneratedTableContent>> build,
            Cancellation token) {
        var key = new Lookup(source, keys, source.encodingScope() != null).node(List.of());
        Flight flight;
        boolean start = false;
        synchronized (gate) {
            flight = flights.get(key);
            if (flight == null || flight.abandoned) {
                flight = new Flight();
                flights.put(key, flight);
                start = true;
            }
            flight.waiters++;
            waiterProbe.accept(flight.waiters);
        }
        final Flight current = flight;
        if (start) {
            async(
                    () -> {
                        GeneratedTable table = null;
                        Throwable error = null;
                        try {
                            table = open(source, keys, current.cancel).join();
                            if (table == null) {
                                table =
                                        store(
                                                        source,
                                                        keys,
                                                        build.apply(current.cancel).join(),
                                                        current.cancel)
                                                .join();
                            }
                        } catch (Throwable e) {
                            error = e;
                        }
                        synchronized (gate) {
                            current.done = true;
                            flights.remove(key, current);
                        }
                        if (error == null) {
                            current.result.complete(table);
                        } else {
                            current.result.completeExceptionally(error);
                        }
                        return null;
                    });
        }
        var waiter = new CompletableFuture<GeneratedTable>();
        var registration = token.onCancel(() -> waiter.cancel(false));
        current.result.whenComplete(
                (value, error) -> {
                    if (error == null) {
                        waiter.complete(value);
                    } else {
                        waiter.completeExceptionally(error);
                    }
                });
        waiter.whenComplete(
                (v, e) -> {
                    registration.close();
                    boolean cancel;
                    synchronized (gate) {
                        cancel = --current.waiters == 0 && !current.done;
                        if (cancel) {
                            current.abandoned = true;
                        }
                    }
                    if (cancel) {
                        current.cancel.cancel();
                    }
                });
        return waiter;
    }

    @SuppressWarnings("try")
    private static <T> T withToken(
            Cancellation token, Function<com.vibeminer.generated.Cancellation, T> action) {
        token.check();
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            return action.apply(lower);
        }
    }

    private static <T> CompletableFuture<T> async(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, command -> Thread.ofVirtual().start(command));
    }
}
