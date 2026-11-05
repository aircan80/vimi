package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.internal.Json;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/** Complete pinned WoWDBDefs provider; bytes are repacked losslessly with the JDK gzip codec. */
public final class BundledDefinitions {
    public static final String REVISION = "b9a63096cdd99b26967f17f9515377ae62cadfd7",
            MANIFEST_SHA256 = "f7b752a991d6a0321d07ecb7f06e62cd94694c1594d2fe5ce0cb7b243c71559a",
            SOURCES_SHA256 = "cc3119202a5f74b5af9318212d2468d3e51659cf7f9f85446cb72bb9d15555dd",
            ORIGIN = "https://raw.githubusercontent.com/wowdev/WoWDBDefs/" + REVISION + "/";

    public record DefinitionTable(
            String tableId, String name, String db2FileDataId, boolean bundled) {}

    public record ProviderInfo(
            String name,
            String revision,
            String manifestSha256,
            String license,
            String sourceUrl) {}

    public record Definition(String sha256, String sourceUrl, DbdDocument document) {}

    public record Lookup(Definition definition, DefinitionException failure) {}

    record File(String name, String sha, int length, long offset) {}

    private static final String DECODED_SHA256 =
            "73d47b200e9ddc94c67c7f3e3b3c5832c8ab80b554ca06353348986eb6af7dbe";
    private static final long MAX_CACHED_BYTES = 2L << 20, MAX_DECODED_BYTES = 64L << 20;

    private final List<DefinitionTable> tables;
    private final Map<String, File> files;
    private final Map<String, Definition> supplied;
    // The verified decoded stream (about 22 MB for the pinned provider), retained so a lookup
    // slices it instead of re-inflating the gzip resource up to the definition's offset.
    private final byte[] decoded;
    // Guarded by this; parsing happens outside the lock.
    private final LinkedHashMap<String, Lookup> cache = new LinkedHashMap<>(64, .75f, true);
    private final int maxEntries;
    private long cachedBytes;

    BundledDefinitions(
            List<DefinitionTable> tables,
            Map<String, File> files,
            byte[] decoded,
            Map<String, Definition> supplied,
            int maxEntries) {
        this.tables = List.copyOf(tables);
        this.files = Map.copyOf(files);
        this.decoded = decoded;
        this.supplied = Map.copyOf(supplied);
        this.maxEntries = maxEntries;
        for (var file : this.files.values()) {
            if (decoded == null
                    || file.offset() < 0
                    || file.offset() > decoded.length - (long) file.length()) {
                throw invalid("Definition slice outside decoded stream");
            }
        }
    }

    /** A custom immutable provider for consumer tests or explicitly supplied definitions. */
    public static BundledDefinitions of(
            List<DefinitionTable> tables, Map<String, Definition> definitions) {
        return new BundledDefinitions(tables, Map.of(), new byte[0], definitions, 64);
    }

    private static final class Shared {
        static final CompletableFuture<BundledDefinitions> VALUE =
                CompletableFuture.supplyAsync(
                        BundledDefinitions::load, command -> Thread.ofVirtual().start(command));
    }

    public static CompletableFuture<BundledDefinitions> open(Cancellation token) {
        token.check();
        var result = new CompletableFuture<BundledDefinitions>();
        var registration = token.onCancel(() -> result.cancel(false));
        Shared.VALUE.whenComplete(
                (value, error) -> {
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
        result.whenComplete((v, e) -> registration.close());
        return result;
    }

    public List<DefinitionTable> tables() {
        return tables;
    }

    public ProviderInfo info() {
        return new ProviderInfo(
                "WoWDBDefs contributors",
                REVISION,
                MANIFEST_SHA256,
                "CC-BY-SA-4.0",
                ORIGIN + "manifest.json");
    }

    public Lookup tryGet(String tableId) {
        if (supplied.containsKey(tableId)) {
            return new Lookup(supplied.get(tableId), null);
        }
        var file = files.get(tableId);
        if (file == null) {
            return new Lookup(null, null);
        }
        synchronized (this) {
            var hit = cache.get(tableId);
            if (hit != null) {
                return hit;
            }
        }
        Lookup result = read(file);
        synchronized (this) {
            // A concurrent miss may have parsed the same definition; keep the first result so
            // callers observe one stable Lookup per cached table.
            var raced = cache.putIfAbsent(tableId, result);
            if (raced != null) {
                return raced;
            }
            cachedBytes += file.length();
            while (cache.size() > 1
                    && (cache.size() > maxEntries || cachedBytes > MAX_CACHED_BYTES)) {
                String oldest = cache.keySet().iterator().next();
                cache.remove(oldest);
                cachedBytes -= files.get(oldest).length();
            }
        }
        return result;
    }

    private Lookup read(File file) {
        byte[] bytes =
                Arrays.copyOfRange(
                        decoded, (int) file.offset(), (int) file.offset() + file.length());
        if (!Json.sha256(bytes).equals(file.sha())) {
            return new Lookup(
                    null,
                    new DefinitionException(
                            "DEFINITION_CORRUPT", "Bundled file failed pinned hash"));
        }
        try {
            return new Lookup(
                    new Definition(
                            file.sha(),
                            ORIGIN + "definitions/" + file.name() + ".dbd",
                            DbdDefinitionReader.parse(bytes)),
                    null);
        } catch (Db2FormatException e) {
            return new Lookup(
                    null,
                    new DefinitionException(
                            "DEFINITION_UNSUPPORTED", e.code() + ": " + e.getMessage()));
        } catch (DefinitionException e) {
            return new Lookup(null, e);
        }
    }

    File file(String tableId) {
        return files.get(tableId);
    }

    synchronized int cachedEntries() {
        return cache.size();
    }

    public Definition require(String tableId) {
        var result = tryGet(tableId);
        if (result.definition() != null) {
            return result.definition();
        }
        if (result.failure() != null) {
            throw result.failure();
        }
        throw new DefinitionException("DEFINITION_NOT_BUNDLED", "No bundled definition for table");
    }

    private static BundledDefinitions load() {
        try {
            byte[] manifest = readResource("manifest.json", MANIFEST_SHA256),
                    sources = readResource("sources.json", SOURCES_SHA256);
            var root = Json.object(Json.parse(sources, 1 << 20));
            if (!REVISION.equals(root.get("revision"))
                    || !"CC-BY-SA-4.0".equals(root.get("definitionLicense"))
                    || !ORIGIN.equals(root.get("sourceBase"))) {
                throw invalid("Wrong provider provenance");
            }
            var definitions = Json.object(root.get("definitions"));
            if (!"definitions.br".equals(definitions.get("path"))
                    || !"brotli".equals(definitions.get("compression"))) {
                throw invalid("Unsupported pinned listing");
            }
            var entries = new ArrayList<DefinitionTable>();
            var ids = new HashSet<String>();
            var names = new HashMap<String, DefinitionTable>();
            for (var item : Json.array(Json.parse(manifest, 1 << 20))) {
                var m = Json.object(item);
                if (m.keySet().stream()
                        .anyMatch(
                                k ->
                                        !List.of(
                                                        "tableHash",
                                                        "tableName",
                                                        "db2FileDataID",
                                                        "dbcFileDataID")
                                                .contains(k))) {
                    throw invalid("Unexpected manifest property");
                }
                String id = Json.string(m.get("tableHash")).toLowerCase(java.util.Locale.ROOT);
                if (!id.matches("[0-9a-f]{8}") || !ids.add(id)) {
                    throw invalid("Malformed or duplicate table identity");
                }
                String name = m.containsKey("tableName") ? Json.string(m.get("tableName")) : null;
                if (name != null && !name.matches("[A-Za-z0-9_-]{1,128}")) {
                    throw invalid("Invalid table name");
                }
                String fileId = fileId(m.get("db2FileDataID"));
                fileId(m.get("dbcFileDataID"));
                var entry = new DefinitionTable(id, name, fileId, false);
                entries.add(entry);
                if (name != null && names.putIfAbsent(name, entry) != null) {
                    throw invalid("Duplicate table name");
                }
            }
            if (entries.size() > 4096) {
                throw invalid("Manifest count exceeds bound");
            }
            var files = new HashMap<String, File>();
            long offset = 0;
            var listed = Json.array(definitions.get("files"));
            if (listed.isEmpty() || listed.size() > 4096) {
                throw invalid("File count outside bounds");
            }
            for (var item : listed) {
                var m = Json.object(item);
                if (m.size() != 3) {
                    throw invalid("Unexpected file listing fields");
                }
                String path = Json.string(m.get("path"));
                if (!path.matches("definitions/[A-Za-z0-9_-]{1,128}\\.dbd")) {
                    throw invalid("Invalid definition path");
                }
                String name = path.substring(12, path.length() - 4);
                var entry = names.get(name);
                if (entry == null) {
                    throw invalid("Unowned definition file");
                }
                String hash = Json.string(m.get("sha256"));
                long length = Json.number(m.get("byteLength"));
                if (!hash.matches("[0-9a-f]{64}")
                        || length < 1
                        || length > 1 << 20
                        || files.putIfAbsent(
                                        entry.tableId(), new File(name, hash, (int) length, offset))
                                != null) {
                    throw invalid("Invalid definition slice");
                }
                offset += length;
            }
            if (offset != Json.number(definitions.get("decodedLength"))
                    || offset > MAX_DECODED_BYTES) {
                throw invalid("Decoded length mismatch");
            }
            byte[] decoded;
            try (var input = resource("definitions.gz")) {
                decoded = inflateVerified(input, offset, DECODED_SHA256);
            }
            var complete =
                    entries.stream()
                            .map(
                                    e ->
                                            new DefinitionTable(
                                                    e.tableId(),
                                                    e.name(),
                                                    e.db2FileDataId(),
                                                    files.containsKey(e.tableId())))
                            .sorted(
                                    Comparator.comparing((DefinitionTable e) -> !e.bundled())
                                            .thenComparing(
                                                    e -> e.name() == null ? e.tableId() : e.name(),
                                                    String.CASE_INSENSITIVE_ORDER)
                                            .thenComparing(DefinitionTable::tableId))
                            .toList();
            if (complete.size() != 1346 || files.size() != 1346) {
                throw invalid("Pinned provider count mismatch");
            }
            return new BundledDefinitions(complete, files, decoded, Map.of(), 64);
        } catch (IOException | IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    /**
     * Inflates a gzip stream that must decode to exactly {@code length} bytes with the given
     * SHA-256; the stream is never read past {@code length} decoded bytes plus one.
     */
    static byte[] inflateVerified(InputStream compressed, long length, String sha256)
            throws IOException {
        if (length < 0 || length > MAX_DECODED_BYTES) {
            throw invalid("Decoded length outside bounds");
        }
        try (var input = new GZIPInputStream(compressed)) {
            byte[] decoded = input.readNBytes((int) length);
            if (decoded.length != length) {
                throw invalid("Truncated decoded stream");
            }
            if (input.read() != -1) {
                throw invalid("Oversized decoded stream");
            }
            if (!Json.sha256(decoded).equals(sha256)) {
                throw invalid("Repacked stream failed hash");
            }
            return decoded;
        }
    }

    private static String fileId(Object value) {
        if (value == null) {
            return null;
        }
        long id = Json.number(value);
        if (id < 1 || id > 0xffffffffL) {
            throw invalid("Positive unsigned32 file identity required");
        }
        return Long.toString(id);
    }

    private static InputStream resource(String name) {
        var result = BundledDefinitions.class.getResourceAsStream(name);
        if (result == null) {
            throw invalid("Missing pinned resource " + name);
        }
        return result;
    }

    private static byte[] readResource(String name, String hash) throws IOException {
        try (var input = resource(name)) {
            var bytes = input.readNBytes((1 << 20) + 1);
            if (bytes.length > 1 << 20 || !Json.sha256(bytes).equals(hash)) {
                throw invalid("Pinned resource failed hash");
            }
            return bytes;
        }
    }

    private static DefinitionException invalid(String message) {
        return new DefinitionException("DEFINITION_PROVIDER_INVALID", message);
    }
}
