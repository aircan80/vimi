package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import com.vibeminer.generated.RecordFile;
import com.vibeminer.generated.RecordFileOptions;
import com.vibeminer.generated.RecordFileWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Two generated record files; paths manifest commits last. Segments can be shared across releases.
 */
public final class ListfileStore implements LoadedNames {
    public record Release(String tag, String sha256, Instant publishedAt, Instant fetchedAt) {
        public Release {
            if (tag == null
                    || !tag.matches("[a-z0-9_-]{1,64}")
                    || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid listfile release identity");
            }
            Objects.requireNonNull(publishedAt);
            Objects.requireNonNull(fetchedAt);
        }
    }

    public record Open(ReadState state, ListfileStore index, String problem) {}

    @FunctionalInterface
    interface PathHasher {
        long hash(long seed, byte[] name);
    }

    private record Metadata(long seed, int count, Release release) {}

    private static final long EPOCH_TICKS = 621355968000000000L;
    private final RecordFile names, paths;
    private final Metadata metadata;
    private final PathHasher hasher;
    private volatile boolean damaged;

    private ListfileStore(
            RecordFile names, RecordFile paths, Metadata metadata, PathHasher hasher) {
        this.names = names;
        this.paths = paths;
        this.metadata = metadata;
        this.hasher = hasher;
    }

    @Override
    public String revision() {
        return metadata.release().sha256();
    }

    @Override
    public List<GeneratedKey> artifactKeys() {
        var keys = new LinkedHashSet<GeneratedKey>(names.keys());
        keys.addAll(paths.keys());
        return List.copyOf(keys);
    }

    public Release release() {
        return metadata.release;
    }

    public int count() {
        return metadata.count;
    }

    public List<GeneratedKey> keys() {
        LinkedHashSet<GeneratedKey> all = new LinkedHashSet<>(names.keys());
        all.addAll(paths.keys());
        return List.copyOf(all);
    }

    public static List<GeneratedKey> manifestKeys(String tag, String sha) {
        String id = "wowlistfile-names-v1|" + tag + "|" + sha + "|";
        return List.of(
                GeneratedKey.hash((id + "names").getBytes(StandardCharsets.UTF_8)),
                GeneratedKey.hash((id + "paths").getBytes(StandardCharsets.UTF_8)));
    }

    public static CompletableFuture<Open> open(
            GeneratedStore store, String tag, String sha, Cancellation c) {
        return NamesTasks.submit(c, () -> openSync(store, tag, sha, c, ListfileStore::pathHash));
    }

    static Open openSync(
            GeneratedStore store, String tag, String sha, Cancellation c, PathHasher hasher) {
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            List<GeneratedKey> keys = manifestKeys(tag, sha);
            RecordFile.Open p = RecordFile.open(store, keys.get(1), token.value).join();
            if (p.state() == ReadState.ABSENT) {
                return new Open(ReadState.ABSENT, null, null);
            }
            if (p.state() != ReadState.PRESENT) {
                return corrupt("Paths manifest corrupt");
            }
            RecordFile.Open n = RecordFile.open(store, keys.get(0), token.value).join();
            if (n.state() != ReadState.PRESENT) {
                return corrupt("Names manifest absent or corrupt");
            }
            if (!Arrays.equals(n.file().metadata(), p.file().metadata())) {
                return corrupt("Metadata differs");
            }
            Metadata m;
            try {
                m = decode(p.file().metadata());
            } catch (IllegalArgumentException e) {
                return corrupt(e.getMessage());
            }
            if (!m.release.tag.equals(tag)
                    || !m.release.sha256.equals(sha)
                    || n.file().count() != m.count
                    || p.file().count() < 1
                    || p.file().count() > m.count) {
                return corrupt("Release identity or counts differ");
            }
            return new Open(
                    ReadState.PRESENT, new ListfileStore(n.file(), p.file(), m, hasher), null);
        }
    }

    private static Open corrupt(String message) {
        return new Open(ReadState.CORRUPT, null, "Listfile index: " + message);
    }

    public static CompletableFuture<ListfileStore> build(
            GeneratedStore store,
            Release release,
            InputStream csv,
            boolean rebuild,
            Cancellation c) {
        return NamesTasks.submit(
                c, () -> buildSync(store, release, csv, rebuild, c, ListfileStore::pathHash));
    }

    static ListfileStore buildSync(
            GeneratedStore store,
            Release release,
            InputStream csv,
            boolean rebuild,
            Cancellation c,
            PathHasher hasher) {
        Open existing = openSync(store, release.tag, release.sha256, c, hasher);
        if (existing.state == ReadState.PRESENT && !rebuild) {
            return existing.index;
        }
        try (NamesTasks.Token token = new NamesTasks.Token(c)) {
            List<GeneratedKey> keys = manifestKeys(release.tag, release.sha256);
            for (GeneratedKey key : keys) {
                deleteLeftovers(store, key, token.value);
            }
            long seed = new SecureRandom().nextLong();
            LongPairs pairs = new LongPairs();
            MessageDigest sha = digest();
            Metadata[] metadata = new Metadata[1];
            RecordFileOptions namesOptions =
                    new RecordFileOptions(
                            ListfileParser.MAX_NAMES, 1024, 16 << 20, 16 << 10, 8 << 20, 9);
            try (RecordFileWriter writer = new RecordFileWriter(store, namesOptions)) {
                List<NamesTypes.NameEntry> pending = new ArrayList<>();
                ListfileParser parser =
                        new ListfileParser(
                                (id, bytes) -> {
                                    pairs.add((hasher.hash(seed, bytes) << 32) | id);
                                    pending.add(
                                            new NamesTypes.NameEntry(
                                                    id, new String(bytes, StandardCharsets.UTF_8)));
                                });
                byte[] buffer = new byte[64 << 10];
                int n;
                try {
                    while ((n = csv.read(buffer)) != -1) {
                        c.check();
                        sha.update(buffer, 0, n);
                        parser.feed(buffer, 0, n);
                        drain(writer, pending, token.value);
                    }
                    parser.finish();
                    drain(writer, pending, token.value);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (!HexFormat.of().formatHex(sha.digest()).equals(release.sha256)) {
                    throw new ListfileFormatException("CSV SHA-256 differs from release");
                }
                metadata[0] = new Metadata(seed, (int) parser.count(), release);
                writer.complete(keys.get(0), encode(metadata[0]), token.value).join();
            }
            pairs.sort();
            RecordFileOptions pathsOptions =
                    new RecordFileOptions(
                            ListfileParser.MAX_NAMES, 16 << 20, 16 << 20, 16 << 10, 8 << 20, 9);
            try (RecordFileWriter writer = new RecordFileWriter(store, pathsOptions)) {
                for (int i = 0; i < pairs.count; ) {
                    c.check();
                    long hash = pairs.values[i] >>> 32;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    long previous = 0;
                    while (i < pairs.count && (pairs.values[i] >>> 32) == hash) {
                        long id = pairs.values[i++] & 0xffff_ffffL;
                        writeVar(out, id - previous);
                        previous = id;
                    }
                    writer.add(hash, out.toByteArray(), token.value).join();
                }
                writer.complete(keys.get(1), encode(metadata[0]), token.value).join();
            }
            Open opened = openSync(store, release.tag, release.sha256, c, hasher);
            if (opened.state != ReadState.PRESENT) {
                throw damage(opened.problem);
            }
            return opened.index;
        }
    }

    private static void drain(
            RecordFileWriter writer,
            List<NamesTypes.NameEntry> pending,
            com.vibeminer.generated.Cancellation c) {
        for (NamesTypes.NameEntry e : pending) {
            writer.add(e.id(), e.name().getBytes(StandardCharsets.UTF_8), c).join();
        }
        pending.clear();
    }

    private static void deleteLeftovers(
            GeneratedStore store, GeneratedKey key, com.vibeminer.generated.Cancellation c) {
        RecordFile.Open open = RecordFile.open(store, key, c).join();
        if (open.state() == ReadState.ABSENT) {
            return;
        }
        List<GeneratedKey> doomed = new ArrayList<>();
        doomed.add(key);
        if (open.file() != null) {
            for (GeneratedKey segment : open.file().keys().subList(1, open.file().keys().size())) {
                if (store.read(segment, c).join().state() == ReadState.CORRUPT) {
                    doomed.add(segment);
                }
            }
        }
        store.delete(doomed, c).join();
    }

    public CompletableFuture<String> nameOf(long id, Cancellation c) {
        return safe(
                c,
                () -> {
                    try (NamesTasks.Token token = new NamesTasks.Token(c)) {
                        RecordFile.Record r = names.find(id, token.value).join();
                        return r == null ? null : text(r.bytes());
                    }
                },
                null);
    }

    public CompletableFuture<List<Long>> idsOf(String path, Cancellation c) {
        Objects.requireNonNull(path);
        return safe(
                c,
                () -> {
                    byte[] query;
                    try {
                        ByteBuffer b =
                                StandardCharsets.UTF_8
                                        .newEncoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .encode(CharBuffer.wrap(path));
                        query = new byte[b.remaining()];
                        b.get(query);
                    } catch (CharacterCodingException e) {
                        return List.of();
                    }
                    if (query.length < 1 || query.length > 1024) {
                        return List.of();
                    }
                    try (NamesTasks.Token token = new NamesTasks.Token(c)) {
                        RecordFile.Record r =
                                paths.find(hasher.hash(metadata.seed, query), token.value).join();
                        if (r == null) {
                            return List.of();
                        }
                        ByteBuffer b = ByteBuffer.wrap(r.bytes());
                        List<Long> found = new ArrayList<>();
                        long previous = -1;
                        while (b.hasRemaining()) {
                            long delta = readVar(b), id = previous < 0 ? delta : previous + delta;
                            if (previous >= 0 && delta == 0 || id > 0xffff_ffffL) {
                                throw damage("Path IDs do not ascend");
                            }
                            previous = id;
                            RecordFile.Record name = names.find(id, token.value).join();
                            if (name == null) {
                                throw damage("Path refers to missing name");
                            }
                            text(name.bytes());
                            if (NameFolding.same(name.bytes(), query)) {
                                found.add(id);
                            }
                        }
                        if (previous < 0) {
                            throw damage("Empty paths record");
                        }
                        return List.copyOf(found);
                    }
                },
                List.of());
    }

    public CompletableFuture<List<NamesTypes.NameEntry>> from(
            long fromId, int limit, Cancellation c) {
        if (fromId < 0
                || fromId > 0xffff_ffffL
                || limit < 1
                || limit > RecordFile.MAX_PAGE_RECORDS) {
            throw new IllegalArgumentException("Invalid names page");
        }
        return safe(
                c,
                () -> {
                    try (NamesTasks.Token token = new NamesTasks.Token(c)) {
                        RecordFile.Record first = names.findAtOrAfter(fromId, token.value).join();
                        if (first == null) {
                            return List.of();
                        }
                        List<NamesTypes.NameEntry> result = new ArrayList<>();
                        long previous = fromId - 1;
                        for (RecordFile.Record r :
                                names.read(first.ordinal(), limit, token.value).join()) {
                            if (r.id() <= previous) {
                                throw damage("Names not ascending");
                            }
                            previous = r.id();
                            result.add(new NamesTypes.NameEntry(r.id(), text(r.bytes())));
                        }
                        return List.copyOf(result);
                    }
                },
                List.of());
    }

    private <T> CompletableFuture<T> safe(
            Cancellation c, java.util.function.Supplier<T> action, T absent) {
        return NamesTasks.submit(
                c,
                () -> {
                    try {
                        return action.get();
                    } catch (RuntimeException e) {
                        Throwable cause = NamesTasks.unwrap(e);
                        if (cause instanceof GeneratedStoreException) {
                            damaged = true;
                            return absent;
                        }
                        throw e;
                    }
                });
    }

    public boolean isDamaged() {
        return damaged;
    }

    static long pathHash(long seed, byte[] bytes) {
        long hash = 0xcbf29ce484222325L ^ seed;
        for (byte b : bytes) {
            hash = (hash ^ NameFolding.fold(b & 255)) * 0x100000001b3L;
        }
        return (hash ^ (hash >>> 32)) & 0xffff_ffffL;
    }

    private static final class LongPairs {
        long[] values = new long[1024];
        int count;

        void add(long pair) {
            if (count == values.length) {
                values =
                        Arrays.copyOf(
                                values, Math.min(values.length * 2, ListfileParser.MAX_NAMES));
            }
            values[count++] = pair;
        }

        void sort() { // Flip sign bit so signed sort has unsigned order.
            for (int i = 0; i < count; i++) {
                values[i] ^= Long.MIN_VALUE;
            }
            Arrays.sort(values, 0, count);
            for (int i = 0; i < count; i++) {
                values[i] ^= Long.MIN_VALUE;
            }
        }
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String text(byte[] bytes) {
        if (bytes.length < 1 || bytes.length > 1024) {
            throw damage("Invalid name length");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw damage("Invalid name UTF-8");
        }
    }

    private static GeneratedStoreException damage(String message) {
        return new GeneratedStoreException(GeneratedTypes.CORRUPT, "Listfile index: " + message);
    }

    static void writeVar(ByteArrayOutputStream out, long value) {
        while (value >= 128) {
            out.write((int) value | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    static long readVar(ByteBuffer b) {
        long value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!b.hasRemaining()) {
                throw damage("Truncated path varint");
            }
            int x = b.get() & 255;
            if (shift == 28 && x > 15) {
                throw damage("Oversized path varint");
            }
            value |= (long) (x & 127) << shift;
            if (x < 128) {
                return value;
            }
        }
        throw damage("Oversized path varint");
    }

    private static long ticks(Instant at) {
        return Math.addExact(
                EPOCH_TICKS,
                Math.addExact(
                        Math.multiplyExact(at.getEpochSecond(), 10_000_000L), at.getNano() / 100));
    }

    private static Instant instant(long ticks) {
        if (ticks < 0 || ticks > 3155378975999999999L) {
            throw new IllegalArgumentException("Invalid ticks");
        }
        long x = ticks - EPOCH_TICKS;
        return Instant.ofEpochSecond(
                Math.floorDiv(x, 10_000_000), Math.floorMod(x, 10_000_000) * 100);
    }

    private static byte[] encode(Metadata m) {
        byte[] tag = m.release.tag.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(63 + tag.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1)
                .putLong(m.seed)
                .putInt(m.count)
                .putLong(ticks(m.release.publishedAt))
                .putLong(ticks(m.release.fetchedAt))
                .put(HexFormat.of().parseHex(m.release.sha256))
                .put((byte) tag.length)
                .put(tag)
                .array();
    }

    private static Metadata decode(byte[] bytes) {
        if (bytes.length < 63) {
            throw new IllegalArgumentException("Short metadata");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getShort() != 1) {
            throw new IllegalArgumentException("Unknown metadata version");
        }
        long seed = b.getLong();
        int count = b.getInt();
        Instant published = instant(b.getLong()), fetched = instant(b.getLong());
        byte[] sha = new byte[32];
        b.get(sha);
        int size = b.get() & 255;
        if (size != b.remaining() || count < 1 || count > ListfileParser.MAX_NAMES) {
            throw new IllegalArgumentException("Invalid metadata count/length");
        }
        byte[] tag = new byte[size];
        b.get(tag);
        return new Metadata(
                seed,
                count,
                new Release(
                        new String(tag, StandardCharsets.US_ASCII),
                        HexFormat.of().formatHex(sha),
                        published,
                        fetched));
    }
}
