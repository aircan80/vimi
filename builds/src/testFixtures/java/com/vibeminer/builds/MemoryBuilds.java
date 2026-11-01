package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.builds.formats.RootNameHash;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer fake with the same opening reuse/cancellation rules and lookup boundary as real builds.
 */
public final class MemoryBuilds implements Builds {
    private final Map<BuildRef, BuildOpenResult> scripts = new ConcurrentHashMap<>();
    private final OpenCache cache = new OpenCache(4, 2, 64);

    public void add(Build build) {
        scripts.put(
                build.build(),
                new BuildOpenResult(
                        build.build(), BuildOpenState.OPEN, build, null, null, null, null));
    }

    public void result(BuildOpenResult result) {
        scripts.put(result.build(), result);
    }

    @Override
    public CompletableFuture<BuildOpenResult> open(BuildRef build, Cancellation token) {
        return cache.open(
                build,
                ignored ->
                        scripts.getOrDefault(
                                build,
                                new BuildOpenResult(
                                        build,
                                        BuildOpenState.NEEDS_NETWORK,
                                        null,
                                        null,
                                        "BUILD_NOT_SCRIPTED",
                                        "Fixture has no build",
                                        null)),
                token);
    }

    public CompletableFuture<Void> closeAsync() {
        return cache.closeAsync();
    }

    public static final class Opened implements Build, LedgerSource {
        private final BuildRef ref;
        private final BuildMetadata metadata;
        private final CdnContext cdn;
        private final Map<Long, List<FileVariant>> files;
        private final Map<Long, Long> names;
        private final ObjectLedger ledger;

        public Opened(
                BuildRef ref,
                BuildMetadata metadata,
                CdnContext cdn,
                Map<Long, List<FileVariant>> files,
                Map<String, Long> paths,
                Map<com.vibeminer.network.CdnKey, Long> sizes) {
            this.ref = ref;
            this.metadata = metadata;
            this.cdn = cdn;
            Map<Long, List<FileVariant>> copy = new TreeMap<>();
            files.forEach((id, variants) -> copy.put(id, List.copyOf(variants)));
            this.files = Map.copyOf(copy);
            Map<Long, Long> hashes = new java.util.HashMap<>();
            paths.forEach((path, id) -> hashes.putIfAbsent(RootNameHash.of(path), id));
            names = Map.copyOf(hashes);
            var sorted = new TreeMap<>(sizes);
            ledger =
                    new ObjectLedger(
                            sorted.keySet().toArray(com.vibeminer.network.CdnKey[]::new),
                            sorted.values().stream().mapToLong(Long::longValue).toArray());
        }

        @Override
        public BuildRef build() {
            return ref;
        }

        @Override
        public BuildMetadata metadata() {
            return metadata;
        }

        @Override
        public CdnContext cdn() {
            return cdn;
        }

        @Override
        public FileResolution resolve(long id) {
            if (id < 0 || id > 0xffff_ffffL) {
                throw new IllegalArgumentException("Unsigned32 ID required");
            }
            List<FileVariant> variants = files.get(id);
            return variants == null
                    ? new FileResolution(FileResolutionState.UNKNOWN_FILE, null, List.of())
                    : new FileResolution(
                            variants.stream().anyMatch(v -> v.encodedKey() != null)
                                    ? FileResolutionState.FOUND
                                    : FileResolutionState.NOT_IN_ENCODING,
                            id,
                            variants);
        }

        @Override
        public FileResolution resolve(String path) {
            Long id = names.get(RootNameHash.of(path));
            return id == null
                    ? new FileResolution(FileResolutionState.UNKNOWN_FILE, null, List.of())
                    : resolve(id);
        }

        @Override
        public Iterable<Long> fileDataIds(String locale, long fromId) {
            if (fromId < 0 || fromId > 0xffff_ffffL) {
                throw new IllegalArgumentException("Unsigned32 ID required");
            }
            long bit = BuildLocales.bit(locale);
            return files.entrySet().stream()
                    .filter(
                            e ->
                                    e.getKey() >= fromId
                                            && e.getValue().stream()
                                                    .anyMatch(v -> (v.localeFlags() & bit) != 0))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }

        @Override
        public ObjectLedger ledger() {
            return ledger;
        }

        @Override
        public Located locate(com.vibeminer.network.CdnKey key) {
            for (var variants : files.values()) {
                for (FileVariant variant : variants) {
                    if (key.equals(variant.encodedKey()) && variant.location() != null) {
                        return new Located(
                                variant.location(),
                                cdn == null
                                        ? -1
                                        : cdn.archives().indexOf(variant.location().archive()));
                    }
                }
            }
            return null;
        }
    }
}
