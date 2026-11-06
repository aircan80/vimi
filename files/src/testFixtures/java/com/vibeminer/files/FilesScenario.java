package com.vibeminer.files;

import com.vibeminer.access.MemoryObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.store.ObjectKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Same content fixture for independent Files fake and real Files over fake lower boundaries. */
public final class FilesScenario {
    public static final BuildRef REF = new BuildRef("wow", "a".repeat(32), "b".repeat(32));
    public final MemoryObjectSource objects = new MemoryObjectSource();
    public final Files files;
    public final Map<Long, FileFixtures.Encoded> encoded = new TreeMap<>();
    private final Map<Long, List<FileVariant>> variants = new TreeMap<>();
    private final Map<String, Long> paths = new TreeMap<>();
    private final MemoryFiles fake;

    public FilesScenario(boolean independent) {
        this(independent, FileSystemOptions.defaults());
    }

    public FilesScenario(boolean independent, FileSystemOptions options) {
        this(independent, options, java.util.function.UnaryOperator.identity());
    }

    public FilesScenario(
            boolean independent,
            FileSystemOptions options,
            java.util.function.UnaryOperator<com.vibeminer.access.ObjectSource> sourceAdapter) {
        fake = independent ? new MemoryFiles() : null;
        Build built =
                new Build() {
                    @Override
                    public BuildRef build() {
                        return REF;
                    }

                    @Override
                    public BuildMetadata metadata() {
                        return new BuildMetadata(
                                "Synthetic",
                                new CdnKey(new byte[16]),
                                new CdnKey(new byte[16]),
                                new CdnKey(new byte[16]),
                                0,
                                variants.size(),
                                List.of("enUS", "deDE"));
                    }

                    @Override
                    public CdnContext cdn() {
                        return new CdnContext(
                                new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));
                    }

                    @Override
                    public FileResolution resolve(long id) {
                        var got = variants.get(id);
                        return got == null
                                ? new FileResolution(
                                        FileResolutionState.UNKNOWN_FILE, null, List.of())
                                : new FileResolution(
                                        got.stream().anyMatch(v -> v.encodedKey() != null)
                                                ? FileResolutionState.FOUND
                                                : FileResolutionState.NOT_IN_ENCODING,
                                        id,
                                        got);
                    }

                    @Override
                    public FileResolution resolve(String path) {
                        Long id = paths.get(BuildFilesHandle.fold(path));
                        return id == null
                                ? new FileResolution(
                                        FileResolutionState.UNKNOWN_FILE, null, List.of())
                                : resolve(id);
                    }

                    @Override
                    public Iterable<Long> fileDataIds(String locale, long from) {
                        long bit = FileLocales.bitOf(locale);
                        return variants.entrySet().stream()
                                .filter(
                                        e ->
                                                e.getKey() >= from
                                                        && e.getValue().stream()
                                                                .anyMatch(
                                                                        v ->
                                                                                (v.localeFlags()
                                                                                                & bit)
                                                                                        != 0))
                                .map(Map.Entry::getKey)
                                .toList();
                    }
                };
        files =
                independent
                        ? fake
                        : new FileSystem(
                                (ref, token) ->
                                        CompletableFuture.completedFuture(
                                                new BuildOpenResult(
                                                        ref,
                                                        BuildOpenState.OPEN,
                                                        built,
                                                        null,
                                                        null,
                                                        null,
                                                        null)),
                                sourceAdapter.apply(objects),
                                options);
    }

    public FilesScenario add(
            long id, String path, byte[] decoded, boolean stored, List<FileHole> holes) {
        return add(id, path, decoded, stored, holes, FileLocales.bitOf("enUS"));
    }

    public FilesScenario add(
            long id,
            String path,
            byte[] decoded,
            boolean stored,
            List<FileHole> holes,
            long localeFlags) {
        List<FileFixtures.Chunk> chunks = new ArrayList<>();
        long at = 0;
        for (var hole : holes) {
            if (at < hole.offset()) {
                chunks.add(
                        FileFixtures.raw(
                                java.util.Arrays.copyOfRange(
                                        decoded, (int) at, (int) hole.offset())));
            }
            long name = Long.parseUnsignedLong(hole.keyName(), 16);
            chunks.add(
                    FileFixtures.encrypted(
                            name,
                            new byte[16],
                            FileFixtures.zlib(
                                    java.util.Arrays.copyOfRange(
                                            decoded,
                                            (int) hole.offset(),
                                            (int) (hole.offset() + hole.length()))),
                            chunks.size()));
            at = hole.offset() + hole.length();
        }
        if (at < decoded.length || chunks.isEmpty()) {
            chunks.add(
                    FileFixtures.zlib(
                            java.util.Arrays.copyOfRange(decoded, (int) at, decoded.length)));
        }
        return add(id, path, decoded, stored, holes, localeFlags, FileFixtures.chunked(chunks));
    }

    /** Adds a stored, unencrypted file whose BLTE body has exactly {@code chunks}. */
    public FilesScenario addChunks(long id, List<FileFixtures.Chunk> chunks, byte[] decoded) {
        return add(
                id,
                null,
                decoded,
                true,
                List.of(),
                FileLocales.bitOf("enUS"),
                FileFixtures.chunked(chunks));
    }

    private FilesScenario add(
            long id,
            String path,
            byte[] decoded,
            boolean stored,
            List<FileHole> holes,
            long localeFlags,
            FileFixtures.Encoded body) {
        encoded.put(id, body);
        var variant =
                new FileVariant(
                        localeFlags,
                        holes.isEmpty() ? 0 : 0x8000000,
                        new CdnKey(FileFixtures.md5(decoded)),
                        (long) decoded.length,
                        body.key(),
                        (long) body.bytes().length,
                        null);
        variants.put(id, List.of(variant));
        if (path != null) {
            paths.put(BuildFilesHandle.fold(path), id);
        }
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, body.key().bytes());
        if (stored) {
            objects.put(key, body.bytes());
        } else {
            objects.remote(key, body.bytes());
        }
        if (fake != null) {
            for (String locale : FileLocales.NAMES) {
                if ((localeFlags & FileLocales.bitOf(locale)) == 0) {
                    continue;
                }
                fake.add(
                        REF,
                        locale,
                        new BuildFile(
                                id,
                                null,
                                FilePathSource.NONE,
                                (long) decoded.length,
                                variant.contentKey().hex(),
                                FormatDetection.magic(decoded),
                                FormatDetection.magic(decoded) == FileFormat.UNKNOWN
                                        ? FileFormatSource.NONE
                                        : FileFormatSource.MAGIC,
                                stored ? FileStorage.STORED : FileStorage.NOT_STORED,
                                !holes.isEmpty()),
                        decoded,
                        holes);
            }
        }
        return this;
    }

    public FilesScenario noEncoding(long id) {
        var variant = new FileVariant(2, 0, new CdnKey(new byte[16]), null, null, null, null);
        variants.put(id, List.of(variant));
        if (fake != null) {
            fake.add(
                    REF,
                    "enUS",
                    new BuildFile(
                            id,
                            null,
                            FilePathSource.NONE,
                            null,
                            variant.contentKey().hex(),
                            FileFormat.UNKNOWN,
                            FileFormatSource.NONE,
                            FileStorage.NO_ENCODED_COPY,
                            false),
                    new byte[0],
                    List.of());
        }
        return this;
    }

    public FilesScenario variants(long id, List<FileVariant> value) {
        variants.put(id, List.copyOf(value));
        return this;
    }
}
