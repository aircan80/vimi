package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildPart;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.builds.formats.ArchiveIndex;
import com.vibeminer.builds.formats.ArchiveLocations;
import com.vibeminer.builds.formats.Blte;
import com.vibeminer.builds.formats.ConfigFiles;
import com.vibeminer.builds.formats.EncodingTable;
import com.vibeminer.builds.formats.RootNameHash;
import com.vibeminer.builds.formats.RootTable;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.io.Serial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Opens authenticated metadata through layer 2; every I/O wait and parse runs on bounded workers.
 */
public final class BuildOpener implements Builds {
    public record Options(
            int maxOpenBuilds,
            int indexParallelism,
            long maxEncodedBytes,
            long maxDecodedBytes,
            int readChunkBytes,
            int maxConcurrentOpens,
            int maxQueuedOpens) {
        public Options {
            if (maxOpenBuilds < 1
                    || indexParallelism < 1
                    || indexParallelism > 64
                    || maxEncodedBytes < 1
                    || maxEncodedBytes > Integer.MAX_VALUE - 8
                    || maxDecodedBytes < 1
                    || maxDecodedBytes > Blte.MAX_DECODED_BYTES
                    || readChunkBytes < 1
                    || readChunkBytes > (64 << 20)
                    || maxConcurrentOpens < 1
                    || maxConcurrentOpens > 32
                    || maxQueuedOpens < 1) {
                throw new IllegalArgumentException("Invalid build opener bounds");
            }
        }

        public Options() {
            this(4, 4, 512L << 20, 1L << 30, 8 << 20, 2, 64);
        }
    }

    private final ObjectSource source;
    private final Options options;
    private final BuildResolutionCache resolutionCache;
    private final BuildResolutionCache.Storage resolutionStorage;
    private final OpenCache cache;
    private final MetadataCache<MetadataContext> metadataCache;
    private CompletableFuture<Void> closeResult;
    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor indexes;

    public BuildOpener(ObjectSource source) {
        this(source, new Options());
    }

    public BuildOpener(ObjectSource source, Options options) {
        this(source, options, null, null);
    }

    public BuildOpener(
            ObjectSource source,
            Options options,
            BuildResolutionCache resolutionCache,
            BuildResolutionCache.Storage resolutionStorage) {
        this.resolutionCache = resolutionCache;
        this.resolutionStorage = resolutionStorage;
        this.source = Objects.requireNonNull(source);
        this.options = Objects.requireNonNull(options);
        cache =
                new OpenCache(
                        options.maxOpenBuilds(),
                        options.maxConcurrentOpens(),
                        options.maxQueuedOpens());
        metadataCache =
                new MetadataCache<>(
                        options.maxOpenBuilds(),
                        options.maxConcurrentOpens(),
                        options.maxQueuedOpens());
        indexes =
                new ThreadPoolExecutor(
                        options.indexParallelism(),
                        options.indexParallelism(),
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(64),
                        Thread.ofPlatform().daemon().name("build-index-", 0).factory());
        indexes.allowCoreThreadTimeOut(true);
    }

    @Override
    public CompletableFuture<BuildOpenResult> open(BuildRef build, Cancellation cancellation) {
        Objects.requireNonNull(build);
        if (closing.get()) {
            return CompletableFuture.failedFuture(
                    new BuildOpenException("BUILD_OPEN_CLOSED", "Build opener is closed"));
        }
        return cache.open(build, token -> openOnce(build, token), cancellation);
    }

    @Override
    public CompletableFuture<BuildTypes.MetadataOpenResult> metadata(
            BuildRef build, Cancellation token) {
        Objects.requireNonNull(build);
        if (closing.get()) {
            return CompletableFuture.failedFuture(
                    new BuildOpenException("BUILD_OPEN_CLOSED", "Build opener is closed"));
        }
        return metadataCache
                .open(build, cancellation -> readMetadata(build, cancellation), token)
                .handle(
                        (context, error) -> {
                            if (error == null) {
                                return new BuildTypes.MetadataOpenResult(
                                        build,
                                        BuildOpenState.OPEN,
                                        context.metadata(),
                                        null,
                                        null,
                                        null,
                                        null);
                            }
                            Throwable cause = error;
                            while (cause instanceof CompletionException
                                    && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            if (cause instanceof OpenFailed failed) {
                                return new BuildTypes.MetadataOpenResult(
                                        build,
                                        failed.state,
                                        null,
                                        failed.part,
                                        failed.code,
                                        failed.getMessage(),
                                        failed.observedAt);
                            }
                            if (cause instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            if (cause instanceof Error fatal) {
                                throw fatal;
                            }
                            throw new CompletionException(cause);
                        });
    }

    /**
     * Authenticates ROOT/ENCODING and exposes the object ledger without acquiring archive indexes.
     */
    public CompletableFuture<BuildOpenResult> openMetadataBuild(
            BuildRef build, Cancellation token) {
        return metadata(build, token)
                .thenCompose(
                        result -> {
                            if (result.state() != BuildOpenState.OPEN) {
                                return CompletableFuture.completedFuture(
                                        new BuildOpenResult(
                                                build,
                                                result.state(),
                                                null,
                                                result.part(),
                                                result.code(),
                                                result.message(),
                                                result.observedAt()));
                            }
                            return metadataCache
                                    .open(
                                            build,
                                            cancellation -> readMetadata(build, cancellation),
                                            token)
                                    .thenApply(
                                            context -> {
                                                token.check();
                                                ArchiveLocations empty =
                                                        ArchiveLocations.build(
                                                                List.of(),
                                                                null,
                                                                new ArchiveLocations.Limits(),
                                                                token::check);
                                                Build opened =
                                                        new OpenedBuild(
                                                                build,
                                                                context.metadata(),
                                                                context.cdn(),
                                                                context.cdnNote(),
                                                                context.encoding(),
                                                                context.root(),
                                                                empty);
                                                return new BuildOpenResult(
                                                        build,
                                                        BuildOpenState.OPEN,
                                                        opened,
                                                        null,
                                                        null,
                                                        null,
                                                        null);
                                            });
                        });
    }

    private record MetadataContext(
            CdnContext cdn,
            CdnNote cdnNote,
            ConfigFiles.CdnConfig config,
            EncodingTable encoding,
            RootTable root,
            BuildMetadata metadata) {}

    private Build readBuild(BuildRef build, Cancellation token) {
        MetadataContext context =
                metadataCache
                        .open(build, cancellation -> readMetadata(build, cancellation), token)
                        .join();
        token.check();
        ArchiveLocations locations =
                resolutionCache == null ? null : resolutionCache.memory(build, options);
        if (locations == null && resolutionCache != null) {
            locations =
                    resolutionCache.restore(
                            build, options, context.config().archives(), resolutionStorage, token);
        }
        if (locations == null) {
            locations = readIndexes(context.config(), context.cdn(), token);
            if (resolutionCache != null) {
                resolutionCache.save(
                        build, options, context.config().archives(), locations, resolutionStorage);
            }
        }
        token.check();
        if (resolutionCache != null) {
            resolutionCache.remember(build, options, locations);
        }
        return new OpenedBuild(
                build,
                context.metadata(),
                context.cdn(),
                context.cdnNote(),
                context.encoding(),
                context.root(),
                locations);
    }

    private BuildOpenResult openOnce(BuildRef build, Cancellation token) {
        try {
            return new BuildOpenResult(
                    build, BuildOpenState.OPEN, readBuild(build, token), null, null, null, null);
        } catch (OpenFailed e) {
            return new BuildOpenResult(
                    build, e.state, null, e.part, e.code, e.getMessage(), e.observedAt);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof OpenFailed failed) {
                return new BuildOpenResult(
                        build,
                        failed.state,
                        null,
                        failed.part,
                        failed.code,
                        failed.getMessage(),
                        failed.observedAt);
            }
            throw e;
        }
    }

    private MetadataContext readMetadata(BuildRef build, Cancellation token) {
        token.check();
        CdnsRoutes.Resolution resolution = resolveRoute(build, token);
        try {
            return readMetadata(build, resolution, token);
        } catch (OpenFailed e) {
            if (resolution.code() != null && "OBJECT_ACCESS_NO_CDN_CONTEXT".equals(e.code)) {
                throw new OpenFailed(
                        BuildOpenState.UNAVAILABLE,
                        e.part,
                        resolution.code(),
                        resolution.note(),
                        e.observedAt);
            }
            throw e;
        }
    }

    /**
     * The build's own region first; a region with no accepted host borrows the route of another
     * region only when a stored versions reply shows that region serving this build with the same
     * CDN config.
     */
    private CdnsRoutes.Resolution resolveRoute(BuildRef build, Cancellation token) {
        var cdns = CdnsRoutes.newestFirst(source, build.product(), token).join();
        if (build.region() == null
                || cdns.isEmpty()
                || CdnsRoutes.route(cdns, build.region()) != null) {
            return CdnsRoutes.resolve(cdns, build.region(), java.util.Set.of());
        }
        return CdnsRoutes.resolve(
                cdns,
                build.region(),
                CdnsRoutes.regionsServing(
                                source,
                                build.product(),
                                build.buildConfig(),
                                build.cdnConfig(),
                                token)
                        .join());
    }

    private MetadataContext readMetadata(
            BuildRef build, CdnsRoutes.Resolution resolution, Cancellation token) {
        var route = resolution.route();
        CdnContext configContext = route == null ? null : new CdnContext(route);
        ObjectRequest
                buildRequest =
                        new ObjectRequest(ObjectKey.parse(ObjectKey.CONFIG, build.buildConfig())),
                cdnRequest =
                        new ObjectRequest(ObjectKey.parse(ObjectKey.CONFIG, build.cdnConfig()));
        List<ObjectResult> configs =
                source.getKeys(
                                List.of(buildRequest, cdnRequest),
                                configContext,
                                FetchPriority.INTERACTIVE,
                                null,
                                token)
                        .join();
        if (configs.size() != 2) {
            throw new OpenFailed(
                    BuildOpenState.CORRUPT,
                    BuildPart.BUILD_CONFIG,
                    "BUILD_SOURCE_INVALID",
                    "Layer 2 returned a malformed config batch",
                    null);
        }
        check(configs.get(0), BuildPart.BUILD_CONFIG);
        check(configs.get(1), BuildPart.CDN_CONFIG);
        var buildConfig =
                parse(
                        BuildPart.BUILD_CONFIG,
                        () ->
                                ConfigFiles.build(
                                        read(
                                                buildRequest,
                                                BuildPart.BUILD_CONFIG,
                                                configContext,
                                                token),
                                        token::check));
        var cdnConfig =
                parse(
                        BuildPart.CDN_CONFIG,
                        () ->
                                ConfigFiles.cdn(
                                        read(
                                                cdnRequest,
                                                BuildPart.CDN_CONFIG,
                                                configContext,
                                                token),
                                        token::check));
        CdnContext context = route == null ? null : new CdnContext(route, cdnConfig.archives());
        byte[] encodingBytes =
                decoded(
                        BuildPart.ENCODING,
                        buildConfig.encoding().encoded(),
                        buildConfig.encodingSize().encoded(),
                        buildConfig.encoding().content(),
                        buildConfig.encodingSize().decoded(),
                        context,
                        token);
        EncodingTable encoding =
                parse(
                        BuildPart.ENCODING,
                        () ->
                                EncodingTable.open(
                                        encodingBytes,
                                        new EncodingTable.Limits(options.maxDecodedBytes()),
                                        token::check));
        var rootRow = encoding.findContent(buildConfig.root());
        if (rootRow == null || rootRow.encodedKeys().isEmpty()) {
            throw new OpenFailed(
                    BuildOpenState.CORRUPT,
                    BuildPart.ROOT,
                    "ROOT_NOT_IN_ENCODING",
                    "ENCODING does not list ROOT",
                    null);
        }
        CdnKey rootEncoded = rootRow.encodedKeys().getFirst();
        var encodedRow = encoding.findEncoded(rootEncoded);
        RootTable.Limits rootLimits = new RootTable.Limits();
        if (rootRow.decodedSize() > rootLimits.maxDecodedBytes()) {
            throw tooLarge(BuildPart.ROOT);
        }
        byte[] rootBytes =
                decoded(
                        BuildPart.ROOT,
                        rootEncoded,
                        encodedRow == null ? null : encodedRow.encodedSize(),
                        buildConfig.root(),
                        rootRow.decodedSize(),
                        context,
                        token);
        RootTable root =
                parse(BuildPart.ROOT, () -> RootTable.parse(rootBytes, rootLimits, token::check));
        BuildMetadata metadata =
                new BuildMetadata(
                        buildConfig.buildName(),
                        buildConfig.root(),
                        buildConfig.encoding().content(),
                        buildConfig.encoding().encoded(),
                        cdnConfig.archives().size(),
                        root.fileCount(),
                        BuildLocales.of(root.localeUnion()));
        token.check();
        return new MetadataContext(
                context,
                new CdnNote(resolution.code(), resolution.note()),
                cdnConfig,
                encoding,
                root,
                metadata);
    }

    record CdnNote(String code, String note) {}

    private record IndexRequest(ObjectRequest request, BuildPart part, CdnKey key) {}

    private ArchiveLocations readIndexes(
            ConfigFiles.CdnConfig config, CdnContext context, Cancellation token) {
        List<IndexRequest> requests = new ArrayList<>();
        for (CdnKey archive : config.archives()) {
            requests.add(
                    new IndexRequest(
                            new ObjectRequest(
                                    new ObjectKey(ObjectKey.ARCHIVE_INDEX, archive.bytes())),
                            BuildPart.ARCHIVE_INDEX,
                            archive));
        }
        if (config.fileIndex() != null) {
            requests.add(
                    new IndexRequest(
                            new ObjectRequest(
                                    new ObjectKey(
                                            ObjectKey.FILE_INDEX, config.fileIndex().bytes())),
                            BuildPart.FILE_INDEX,
                            config.fileIndex()));
        }
        if (!requests.isEmpty()) {
            List<ObjectResult> results =
                    source.getKeys(
                                    requests.stream().map(IndexRequest::request).toList(),
                                    context,
                                    FetchPriority.INTERACTIVE,
                                    null,
                                    token)
                            .join();
            if (results.size() != requests.size()) {
                throw new OpenFailed(
                        BuildOpenState.CORRUPT,
                        BuildPart.ARCHIVE_INDEX,
                        "BUILD_SOURCE_INVALID",
                        "Layer 2 returned a malformed index batch",
                        null);
            }
            for (int i = 0; i < results.size(); i++) {
                check(results.get(i), requests.get(i).part());
            }
        }
        List<List<ArchiveIndex.Entry>> entries = new ArrayList<>();
        for (int start = 0; start < requests.size(); start += options.indexParallelism()) {
            token.check();
            List<CompletableFuture<List<ArchiveIndex.Entry>>> batch = new ArrayList<>();
            for (int i = start;
                    i < Math.min(start + options.indexParallelism(), requests.size());
                    i++) {
                IndexRequest request = requests.get(i);
                batch.add(
                        CompletableFuture.supplyAsync(
                                () ->
                                        parse(
                                                request.part(),
                                                () ->
                                                        ArchiveIndex.read(
                                                                read(
                                                                        request.request(),
                                                                        request.part(),
                                                                        context,
                                                                        token),
                                                                request.key(),
                                                                request.part()
                                                                                == BuildPart
                                                                                        .ARCHIVE_INDEX
                                                                        ? ArchiveIndex.Kind.ARCHIVE
                                                                        : ArchiveIndex.Kind
                                                                                .FILE_INDEX,
                                                                new ArchiveIndex.Limits(),
                                                                token::check)),
                                indexes));
            }
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
            for (var result : batch) {
                entries.add(result.join());
            }
        }
        List<ArchiveLocations.Indexed> archives = new ArrayList<>();
        for (int i = 0; i < config.archives().size(); i++) {
            archives.add(new ArchiveLocations.Indexed(config.archives().get(i), entries.get(i)));
        }
        return parse(
                BuildPart.ARCHIVE_INDEX,
                () ->
                        ArchiveLocations.build(
                                archives,
                                config.fileIndex() == null ? null : entries.getLast(),
                                new ArchiveLocations.Limits(),
                                token::check));
    }

    private byte[] decoded(
            BuildPart part,
            CdnKey encodedKey,
            Long encodedSize,
            CdnKey contentKey,
            long decodedSize,
            CdnContext context,
            Cancellation token) {
        if (encodedSize != null && encodedSize > options.maxEncodedBytes()
                || decodedSize > options.maxDecodedBytes()) {
            throw tooLarge(part);
        }
        byte[] encoded =
                read(
                        new ObjectRequest(
                                new ObjectKey(ObjectKey.ENCODED, encodedKey.bytes()), encodedSize),
                        part,
                        context,
                        token);
        return parse(
                part,
                () ->
                        Blte.decode(
                                encoded,
                                encodedKey,
                                contentKey,
                                decodedSize,
                                options.maxDecodedBytes(),
                                token::check));
    }

    private byte[] read(
            ObjectRequest request, BuildPart part, CdnContext context, Cancellation token) {
        token.check();
        ObjectResult result =
                source.getKey(request, context, FetchPriority.INTERACTIVE, token).join();
        check(result, part);
        byte[] bytes = result.bytes();
        if (bytes != null) {
            if (bytes.length > options.maxEncodedBytes()) {
                throw tooLarge(part);
            }
            return bytes;
        }
        Long length = result.length() == null ? request.declaredLength() : result.length();
        if (length == null || length < 0 || length > options.maxEncodedBytes()) {
            throw tooLarge(part);
        }
        byte[] output = new byte[length.intValue()];
        for (int at = 0; at < output.length; ) {
            token.check();
            int take = Math.min(options.readChunkBytes(), output.length - at);
            ObjectResult range =
                    source.getRange(request, at, take, context, FetchPriority.INTERACTIVE, token)
                            .join();
            check(range, part);
            byte[] body = range.bytes();
            if (body == null || body.length != take) {
                throw new OpenFailed(
                        BuildOpenState.CORRUPT,
                        part,
                        "BUILD_SOURCE_INVALID",
                        "Layer 2 returned a malformed metadata range",
                        null);
            }
            System.arraycopy(body, 0, output, at, take);
            at += take;
        }
        return output;
    }

    private static void check(ObjectResult result, BuildPart part) {
        if (result.isPresent()) {
            return;
        }
        BuildOpenState state =
                switch (result.state()) {
                    case NEEDS_NETWORK -> BuildOpenState.NEEDS_NETWORK;
                    case UNAVAILABLE ->
                            "CDN_OFFLINE".equals(result.code())
                                            || "OBJECT_ACCESS_NO_CDN_CONTEXT".equals(result.code())
                                    ? BuildOpenState.NEEDS_NETWORK
                                    : BuildOpenState.UNAVAILABLE;
                    case NOT_FOUND -> BuildOpenState.MISSING;
                    case REFUSED -> BuildOpenState.CORRUPT;
                    case UNSUPPORTED -> BuildOpenState.UNSUPPORTED;
                    default -> BuildOpenState.STORE_FAILED;
                };
        throw new OpenFailed(
                state,
                part,
                result.code() == null ? "OBJECT_ACCESS_" + result.state().name() : result.code(),
                result.message() == null
                        ? "Metadata object is " + result.state()
                        : result.message(),
                result.observedAt());
    }

    private static <T> T parse(BuildPart part, Supplier<T> parse) {
        try {
            return parse.get();
        } catch (BuildFormatException e) {
            throw new OpenFailed(
                    switch (e.failure()) {
                        case ENCRYPTED -> BuildOpenState.ENCRYPTED;
                        case UNSUPPORTED -> BuildOpenState.UNSUPPORTED;
                        default -> BuildOpenState.CORRUPT;
                    },
                    part,
                    e.code(),
                    e.getMessage(),
                    null);
        }
    }

    private static OpenFailed tooLarge(BuildPart part) {
        return new OpenFailed(
                BuildOpenState.UNSUPPORTED,
                part,
                "BUILD_METADATA_TOO_LARGE",
                "Metadata exceeds admitted byte bounds or has no known length",
                null);
    }

    private static final class OpenFailed extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final BuildOpenState state;
        final BuildPart part;
        final String code;
        final Instant observedAt;

        OpenFailed(
                BuildOpenState state,
                BuildPart part,
                String code,
                String message,
                Instant observedAt) {
            super(message);
            this.state = state;
            this.part = part;
            this.code = code;
            this.observedAt = observedAt;
        }
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeResult != null) {
            return closeResult.copy();
        }
        closing.set(true);
        closeResult =
                cache.closeAsync()
                        .thenCompose(ignored -> metadataCache.closeAsync())
                        .thenCompose(
                                ignored -> {
                                    indexes.shutdown();
                                    CompletableFuture<Void> done = new CompletableFuture<>();
                                    Thread.startVirtualThread(
                                            () -> {
                                                try {
                                                    while (!indexes.awaitTermination(
                                                            1, TimeUnit.DAYS)) {
                                                        /* Drain index work. */
                                                    }
                                                    done.complete(null);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    done.completeExceptionally(e);
                                                }
                                            });
                                    return done;
                                });
        return closeResult.copy();
    }
}

final class OpenedBuild implements Build, LedgerSource {
    private final BuildTypes.BuildRef build;
    private final BuildTypes.BuildMetadata metadata;
    private final CdnContext cdn;
    private final BuildOpener.CdnNote cdnNote;
    private final EncodingTable encoding;
    private final RootTable root;
    private final ArchiveLocations locations;
    private volatile ObjectLedger ledger;

    OpenedBuild(
            BuildTypes.BuildRef build,
            BuildTypes.BuildMetadata metadata,
            CdnContext cdn,
            BuildOpener.CdnNote cdnNote,
            EncodingTable encoding,
            RootTable root,
            ArchiveLocations locations) {
        this.build = build;
        this.metadata = metadata;
        this.cdn = cdn;
        this.cdnNote = cdnNote;
        this.encoding = encoding;
        this.root = root;
        this.locations = locations;
    }

    @Override
    public BuildTypes.BuildRef build() {
        return build;
    }

    @Override
    public BuildTypes.BuildMetadata metadata() {
        return metadata;
    }

    @Override
    public CdnContext cdn() {
        return cdn;
    }

    @Override
    public String cdnNote() {
        return cdnNote.note();
    }

    @Override
    public String cdnProblem() {
        return cdnNote.code();
    }

    @Override
    public ObjectLedger ledger() {
        ObjectLedger current = ledger;
        if (current == null) {
            synchronized (this) {
                current = ledger;
                if (current == null) {
                    current = ObjectLedger.from(encoding);
                    ledger = current;
                }
            }
        }
        return current;
    }

    @Override
    public Located locate(CdnKey key) {
        var found = locations.locate(key);
        return found == null ? null : new Located(found.location(), found.archiveRank());
    }

    @Override
    public FileResolution resolve(String path) {
        Long id = root.findByNameHash(RootNameHash.of(path));
        return id == null
                ? new FileResolution(FileResolutionState.UNKNOWN_FILE, null, List.of())
                : resolve(id);
    }

    @Override
    public Iterable<Long> fileDataIds(String locale, long fromId) {
        return root.fileDataIds(BuildLocales.bit(locale), fromId);
    }

    @Override
    public FileResolution resolve(long id) {
        var entries = root.find(id);
        if (entries.isEmpty()) {
            return new FileResolution(FileResolutionState.UNKNOWN_FILE, null, List.of());
        }
        boolean any = false;
        List<FileVariant> variants = new ArrayList<>();
        for (RootTable.Entry entry : entries) {
            var content = encoding.findContent(entry.contentKey());
            if (content == null || content.encodedKeys().isEmpty()) {
                variants.add(
                        new FileVariant(
                                entry.localeFlags(),
                                entry.contentFlags(),
                                entry.contentKey(),
                                null,
                                null,
                                null,
                                null));
                continue;
            }
            any = true;
            CdnKey encoded = content.encodedKeys().getFirst();
            var location = locations.locate(encoded);
            var row = encoding.findEncoded(encoded);
            Long size =
                    row != null
                            ? row.encodedSize()
                            : location != null
                                    ? location.location().size()
                                    : locations.looseSize(encoded);
            variants.add(
                    new FileVariant(
                            entry.localeFlags(),
                            entry.contentFlags(),
                            entry.contentKey(),
                            content.decodedSize(),
                            encoded,
                            size,
                            location == null ? null : location.location()));
        }
        return new FileResolution(
                any ? FileResolutionState.FOUND : FileResolutionState.NOT_IN_ENCODING,
                id,
                variants);
    }
}
