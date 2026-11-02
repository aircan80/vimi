package com.vibeminer.questie.sources;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.Progress;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import com.vibeminer.questie.QuestieTypes.Stage;
import com.vibeminer.questie.internal.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Independent, immutable official QuestieDB packages. No WoW build or GUI dependencies. */
public final class QuestieSourceRepository implements AutoCloseable {
    private static final String REPOSITORY = "https://github.com/Questie/QuestieDB";
    private static final int METADATA_LIMIT = 8 << 20;
    private static final long ARCHIVE_LIMIT = 256L << 20;
    private static final long EXPANDED_LIMIT = 1L << 30;
    private static final String MARKER = "vibeminer-questie-sources-v1";
    private final Path root;
    private final URI api;
    private final boolean testing;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofVirtual().name("questie-source-", 0).factory());
    private FileChannel lockChannel;
    private FileLock lock;
    private CompletableFuture<Void> closing;

    public record Asset(String name, URI url, long bytes) {}

    /** Exact release response retained for reproducible acquisition. */
    public record Release(String tag, Instant publishedAt, List<Asset> assets, byte[] metadata) {
        public Release {
            assets = List.copyOf(assets);
            metadata = metadata.clone();
        }

        @Override
        public byte[] metadata() {
            return metadata.clone();
        }
    }

    public record ReleaseCheck(
            Release latest, String previousTag, boolean changed, boolean offline, String message) {}

    private QuestieSourceRepository(Path root, URI api, boolean testing) {
        this.root = root.toAbsolutePath().normalize();
        this.api = api;
        this.testing = testing;
    }

    public static CompletableFuture<QuestieSourceRepository> open(
            Path directory, Cancellation token) {
        return open(
                directory,
                URI.create("https://api.github.com/repos/Questie/QuestieDB/"),
                false,
                token);
    }

    static CompletableFuture<QuestieSourceRepository> open(
            Path directory, URI api, boolean testing, Cancellation token) {
        final Path canonical;
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new IOException("Refusing symbolic source directory");
            }
            // Resolve platform aliases such as macOS /tmp, while rejecting a linked owned root.
            canonical = directory.toFile().getCanonicalFile().toPath();
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var repository = new QuestieSourceRepository(canonical, api, testing);
        var result =
                repository.submit(
                        token,
                        () -> {
                            repository.initialize();
                            return repository;
                        });
        result.whenComplete(
                (value, failure) -> {
                    if (failure != null) {
                        repository.closeAsync();
                    }
                });
        return result;
    }

    private void initialize() throws IOException {
        rejectSymlink(root);
        Path marker = root.resolve("repository-format");
        if (Files.exists(root)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Questie source path is not a directory");
            }
            try (var contents = Files.list(root)) {
                if (contents.findAny().isPresent()
                        && !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing foreign source directory");
                }
            }
        }
        Files.createDirectories(root);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(marker);
            if (!Files.readString(marker).equals(MARKER)) {
                throw new IOException("Unsupported source directory format");
            }
        } else {
            Files.writeString(marker, MARKER, StandardOpenOption.CREATE_NEW);
        }
        Path lockPath = root.resolve("writer.lock");
        rejectSymlink(lockPath);
        lockChannel =
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            throw new IOException("Questie source directory already open");
        }
        for (String name : List.of("sources-v1", "staging-v1", "releases-v1")) {
            Path path = root.resolve(name);
            rejectSymlink(path);
            Files.createDirectories(path);
        }
    }

    public CompletableFuture<List<SourceSnapshot>> snapshots(Cancellation token) {
        return submit(
                token,
                () -> {
                    var snapshots = new ArrayList<SourceSnapshot>();
                    try (var paths = Files.list(root.resolve("sources-v1"))) {
                        for (Path path : paths.sorted().toList()) {
                            token.check();
                            if (!path.getFileName().toString().matches("[0-9a-f]{64}")) {
                                continue;
                            }
                            rejectSymlink(path);
                            snapshots.add(readSnapshot(path));
                        }
                    }
                    snapshots.sort(
                            Comparator.comparing(SourceSnapshot::releaseTag)
                                    .thenComparing(SourceSnapshot::id));
                    return List.copyOf(snapshots);
                });
    }

    /** Cached release observations, available offline; not inferred from a WoW build. */
    public CompletableFuture<List<Release>> releases(Cancellation token) {
        return submit(
                token,
                () -> {
                    var unique = new LinkedHashMap<String, Release>();
                    try (var paths = Files.list(root.resolve("releases-v1"))) {
                        for (Path path : paths.sorted().toList()) {
                            token.check();
                            if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.json")) {
                                continue;
                            }
                            rejectSymlink(path);
                            Release release = parseRelease(readSmall(path));
                            unique.put(releaseIdentity(release), release);
                        }
                    }
                    var values = new ArrayList<>(unique.values());
                    values.sort(Comparator.comparing(Release::publishedAt).reversed());
                    return List.copyOf(values);
                });
    }

    /** Refresh latest stable release with ETag; network failures preserve the local observation. */
    public CompletableFuture<ReleaseCheck> refresh(Cancellation token) {
        return submit(
                token,
                () -> {
                    Path cache = root.resolve("latest.json");
                    Release previous = Files.exists(cache) ? parseRelease(readSmall(cache)) : null;
                    String etag = null;
                    Path validator = root.resolve("latest-validator.json");
                    if (previous != null && Files.exists(validator)) {
                        var cachedValidator =
                                Json.object(Json.parse(readSmall(validator), METADATA_LIMIT));
                        if (hash(previous.metadata())
                                .equals(cachedValidator.get("metadataSha256"))) {
                            etag = Json.string(cachedValidator.get("etag"));
                        }
                    }
                    try {
                        Response response = request(api.resolve("releases/latest"), etag, token);
                        if (response.status == 304) {
                            response.body.close();
                            if (previous == null) {
                                throw new IOException("304 without cached release");
                            }
                            return new ReleaseCheck(
                                    previous, previous.tag(), false, false, "Unchanged");
                        }
                        byte[] bytes = responseBytes(response, METADATA_LIMIT, token);
                        Release latest = parseRelease(bytes);
                        saveRelease(latest);
                        atomicFile(cache, bytes);
                        atomicFile(
                                validator,
                                Json.bytes(
                                        Map.of(
                                                "metadataSha256",
                                                hash(bytes),
                                                "etag",
                                                response.etag == null ? "" : response.etag)));
                        return new ReleaseCheck(
                                latest,
                                previous == null ? null : previous.tag(),
                                previous == null
                                        || !releaseIdentity(latest)
                                                .equals(releaseIdentity(previous)),
                                false,
                                "Refreshed");
                    } catch (IOException failure) {
                        token.check();
                        return new ReleaseCheck(
                                previous,
                                previous == null ? null : previous.tag(),
                                false,
                                true,
                                failure.getMessage());
                    }
                });
    }

    /** Fetch a named stable release, retaining its exact response in the local version catalog. */
    public CompletableFuture<Release> release(String tag, Cancellation token) {
        if (tag == null || !tag.matches("[A-Za-z0-9._-]{1,100}")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid release tag"));
        }
        return submit(
                token,
                () -> {
                    Release release =
                            parseRelease(
                                    responseBytes(
                                            request(
                                                    api.resolve("releases/tags/" + tag),
                                                    null,
                                                    token),
                                            METADATA_LIMIT,
                                            token));
                    if (!tag.equals(release.tag())) {
                        throw new IOException("Release tag mismatch");
                    }
                    saveRelease(release);
                    return release;
                });
    }

    private void saveRelease(Release release) throws IOException {
        atomicFile(
                root.resolve("releases-v1").resolve(hash(release.metadata()) + ".json"),
                release.metadata());
    }

    public CompletableFuture<SourceSnapshot> acquire(
            Release release, Consumer<Progress> progress, Cancellation token) {
        return acquire(release, Flavor.FOREVER, progress, token);
    }

    public CompletableFuture<SourceSnapshot> acquire(
            Release release, Flavor flavor, Consumer<Progress> progress, Cancellation token) {
        return submit(token, () -> acquireNow(release, flavor, progress, token));
    }

    private SourceSnapshot acquireNow(
            Release supplied, Flavor flavor, Consumer<Progress> progress, Cancellation token)
            throws Exception {
        Release release = parseRelease(supplied.metadata());
        Asset manifestAsset = asset(release, "release.json");
        byte[] upstream =
                responseBytes(request(manifestAsset.url(), null, token), METADATA_LIMIT, token);
        var metadata = Json.object(Json.parse(upstream, METADATA_LIMIT));
        var provider = Json.object(metadata.get("questiedb"));
        if (!REPOSITORY.equals(Json.string(provider.get("repository")))) {
            throw new IOException("Manifest repository mismatch");
        }
        String commit = Json.string(provider.get("producerCommit"));
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IOException("Invalid producer commit");
        }
        String filename = "QuestieDB-" + flavor.upstream() + ".zip";
        Map<String, Object> selected = null;
        for (Object entry : Json.array(provider.get("artifacts"))) {
            var artifact = Json.object(entry);
            if (flavor.upstream().equals(artifact.get("flavor"))
                    && filename.equals(artifact.get("file"))) {
                if (selected != null) {
                    throw new IOException("Duplicate artifact");
                }
                selected = artifact;
            }
        }
        if (selected == null) {
            throw new IOException("Flavor package absent in release manifest");
        }
        String digest = Json.string(selected.get("sha256"));
        long bytes = Json.number(selected.get("bytes"));
        if (!digest.matches("[0-9a-f]{64}") || bytes < 1 || bytes > ARCHIVE_LIMIT) {
            throw new IOException("Invalid artifact digest or size");
        }
        Asset packageAsset = asset(release, filename);
        if (packageAsset.bytes() != bytes) {
            throw new IOException("Release asset size disagreement");
        }
        String identity =
                hash(
                        (REPOSITORY + "\n" + commit + "\n" + flavor.upstream() + "\n" + digest)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path destination = root.resolve("sources-v1").resolve(identity);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            var existing = readSnapshot(destination);
            if (!verifyNow(existing, token)) {
                throw new IOException("Existing source is corrupt; preserving it");
            }
            return existing;
        }
        Path staging = Files.createTempDirectory(root.resolve("staging-v1"), "acquire-");
        try {
            Path archive = staging.resolve("archive.zip");
            emit(progress, Stage.DOWNLOADING, 0, bytes, "bytes", filename);
            Response response = request(packageAsset.url(), null, token);
            requireSuccess(response);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream input = response.body;
                    var channel =
                            FileChannel.open(
                                    archive,
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE)) {
                byte[] block = new byte[65536];
                int count;
                while ((count = input.read(block)) != -1) {
                    token.check();
                    done += count;
                    if (done > bytes) {
                        throw new IOException("Archive exceeds declared size");
                    }
                    sha.update(block, 0, count);
                    var buffer = java.nio.ByteBuffer.wrap(block, 0, count);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    emit(progress, Stage.DOWNLOADING, done, bytes, "bytes", filename);
                }
                channel.force(true);
            }
            if (done != bytes || !HexFormat.of().formatHex(sha.digest()).equals(digest)) {
                throw new IOException("Archive SHA-256 or size mismatch");
            }
            emit(progress, Stage.VERIFYING, done, bytes, "bytes", "Checking ZIP and notices");
            validateZip(archive, flavor, staging.resolve("notices"), token);
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("format", MARKER);
            manifest.put("id", identity);
            manifest.put("repository", REPOSITORY);
            manifest.put("releaseTag", release.tag());
            manifest.put("producerCommit", commit);
            manifest.put("flavor", flavor.upstream());
            manifest.put("sha256", digest);
            manifest.put("bytes", bytes);
            manifest.put("publishedAt", release.publishedAt().toString());
            manifest.put("acquiredAt", Instant.now().toString());
            manifest.put("assetUrl", packageAsset.url().toString());
            manifest.put("authoringSourceUrl", REPOSITORY + "/archive/" + commit + ".zip");
            manifest.put("releaseMetadataSha256", hash(release.metadata()));
            manifest.put("upstreamManifestSha256", hash(upstream));
            durableFile(staging.resolve("release-api.json"), release.metadata());
            durableFile(staging.resolve("release.json"), upstream);
            durableFile(staging.resolve("source-manifest.json"), Json.bytes(manifest));
            token.check();
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            saveRelease(release);
            emit(progress, Stage.READY, bytes, bytes, "bytes", identity);
            return readSnapshot(destination);
        } finally {
            removeOwnedStaging(staging);
        }
    }

    public CompletableFuture<Boolean> verify(SourceSnapshot snapshot, Cancellation token) {
        return submit(token, () -> verifyNow(snapshot, token));
    }

    private boolean verifyNow(SourceSnapshot supplied, Cancellation token) throws Exception {
        Path expected = root.resolve("sources-v1").resolve(supplied.id());
        if (!expected.equals(supplied.directory())) {
            throw new IOException("Source is not owned by this repository");
        }
        SourceSnapshot source = readSnapshot(expected);
        if (!source.equals(supplied)) {
            return false;
        }
        rejectSymlink(source.archive());
        if (Files.size(source.archive()) != source.bytes()) {
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(source.archive())) {
            byte[] block = new byte[65536];
            int count;
            while ((count = input.read(block)) != -1) {
                token.check();
                digest.update(block, 0, count);
            }
        }
        if (!source.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
            return false;
        }
        try (var zip = new ZipFile(source.archive().toFile())) {
            for (String notice : List.of("LICENSE.md", "gpl-3.0.md")) {
                var entry = zip.getEntry("QuestieDB/" + notice);
                if (entry == null || entry.getSize() < 0 || entry.getSize() > METADATA_LIMIT) {
                    return false;
                }
                try (var input = zip.getInputStream(entry)) {
                    byte[] original = input.readNBytes(METADATA_LIMIT + 1);
                    if (original.length > METADATA_LIMIT
                            || !Arrays.equals(
                                    original,
                                    readSmall(expected.resolve("notices").resolve(notice)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private SourceSnapshot readSnapshot(Path directory) throws IOException {
        rejectSymlink(directory);
        var map =
                Json.object(
                        Json.parse(
                                readSmall(directory.resolve("source-manifest.json")),
                                METADATA_LIMIT));
        if (!MARKER.equals(map.get("format")) || !REPOSITORY.equals(map.get("repository"))) {
            throw new IOException("Foreign source manifest");
        }
        var snapshot =
                new SourceSnapshot(
                        Json.string(map.get("id")),
                        Json.string(map.get("releaseTag")),
                        Json.string(map.get("producerCommit")),
                        Flavor.parse(Json.string(map.get("flavor"))),
                        Json.string(map.get("sha256")),
                        Json.number(map.get("bytes")),
                        Instant.parse(Json.string(map.get("publishedAt"))),
                        directory);
        String identity =
                hash(
                        (REPOSITORY
                                        + "\n"
                                        + snapshot.producerCommit()
                                        + "\n"
                                        + snapshot.flavor().upstream()
                                        + "\n"
                                        + snapshot.sha256())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!identity.equals(snapshot.id())
                || !directory.getFileName().toString().equals(identity)) {
            throw new IOException("Source identity mismatch");
        }
        validateProvenance(snapshot, map);
        return snapshot;
    }

    private void validateProvenance(SourceSnapshot snapshot, Map<String, Object> local)
            throws IOException {
        byte[] releaseBytes = readSmall(snapshot.directory().resolve("release-api.json"));
        byte[] upstreamBytes = readSmall(snapshot.directory().resolve("release.json"));
        if (!hash(releaseBytes).equals(local.get("releaseMetadataSha256"))
                || !hash(upstreamBytes).equals(local.get("upstreamManifestSha256"))) {
            throw new IOException("Source provenance checksum mismatch");
        }
        Release release = parseRelease(releaseBytes);
        if (!release.tag().equals(snapshot.releaseTag())
                || !release.publishedAt().equals(snapshot.publishedAt())) {
            throw new IOException("Source release identity mismatch");
        }
        var provider =
                Json.object(
                        Json.object(Json.parse(upstreamBytes, METADATA_LIMIT)).get("questiedb"));
        if (!REPOSITORY.equals(provider.get("repository"))
                || !snapshot.producerCommit().equals(provider.get("producerCommit"))) {
            throw new IOException("Source producer identity mismatch");
        }
        String filename = "QuestieDB-" + snapshot.flavor().upstream() + ".zip";
        int matches = 0;
        for (Object item : Json.array(provider.get("artifacts"))) {
            var artifact = Json.object(item);
            if (snapshot.flavor().upstream().equals(artifact.get("flavor"))
                    && filename.equals(artifact.get("file"))) {
                matches++;
                if (!snapshot.sha256().equals(artifact.get("sha256"))
                        || snapshot.bytes() != Json.number(artifact.get("bytes"))) {
                    throw new IOException("Source artifact identity mismatch");
                }
            }
        }
        if (matches != 1 || asset(release, filename).bytes() != snapshot.bytes()) {
            throw new IOException("Source artifact provenance missing or ambiguous");
        }
    }

    /** Download counters and presentation changes do not identify a new downloadable revision. */
    private static String releaseIdentity(Release release) {
        var raw = Json.object(Json.parse(release.metadata(), METADATA_LIMIT));
        var identity = new LinkedHashMap<String, Object>();
        for (String field : List.of("id", "tag_name", "target_commitish", "published_at")) {
            identity.put(field, raw.get(field));
        }
        var assets = new ArrayList<Map<String, Object>>();
        for (Object value : Json.array(raw.get("assets"))) {
            var asset = Json.object(value);
            var stable = new LinkedHashMap<String, Object>();
            for (String field :
                    List.of("id", "name", "size", "digest", "browser_download_url", "updated_at")) {
                stable.put(field, asset.get(field));
            }
            assets.add(stable);
        }
        assets.sort(Comparator.comparing(value -> Json.string(value.get("name"))));
        identity.put("assets", assets);
        return hash(Json.bytes(identity));
    }

    private Release parseRelease(byte[] bytes) throws IOException {
        try {
            var map = Json.object(Json.parse(bytes, METADATA_LIMIT));
            if (Boolean.TRUE.equals(map.get("draft"))
                    || Boolean.TRUE.equals(map.get("prerelease"))) {
                throw new IOException("Only stable public releases are supported");
            }
            String tag = Json.string(map.get("tag_name"));
            if (!tag.matches("[A-Za-z0-9._-]{1,100}")) {
                throw new IOException("Invalid release tag");
            }
            var assets = new ArrayList<Asset>();
            Set<String> names = new HashSet<>();
            for (Object item : Json.array(map.get("assets"))) {
                var value = Json.object(item);
                String name = Json.string(value.get("name"));
                URI url = URI.create(Json.string(value.get("browser_download_url")));
                validateUrl(url);
                if (!testing
                        && (!url.getHost().equals("github.com")
                                || !url.getPath()
                                        .startsWith(
                                                "/Questie/QuestieDB/releases/download/"
                                                        + tag
                                                        + "/"))) {
                    throw new IOException("Nonofficial release asset");
                }
                if (!names.add(name)) {
                    throw new IOException("Duplicate release asset");
                }
                assets.add(new Asset(name, url, Json.number(value.get("size"))));
            }
            return new Release(
                    tag, Instant.parse(Json.string(map.get("published_at"))), assets, bytes);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Malformed release metadata", failure);
        }
    }

    private static Asset asset(Release release, String name) throws IOException {
        return release.assets().stream()
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IOException("Release asset missing: " + name));
    }

    private void validateUrl(URI url) throws IOException {
        if (testing
                && url.getHost() != null
                && url.getHost().equals(api.getHost())
                && url.getPort() == api.getPort()) {
            return;
        }
        if (!"https".equals(url.getScheme())
                || url.getUserInfo() != null
                || url.getFragment() != null
                || url.getHost() == null
                || !Set.of(
                                "api.github.com",
                                "github.com",
                                "release-assets.githubusercontent.com",
                                "objects.githubusercontent.com")
                        .contains(url.getHost())) {
            throw new IOException("Refusing nonofficial download URL");
        }
    }

    private record Response(int status, String etag, String retryAfter, InputStream body) {}

    private Response request(URI uri, String etag, Cancellation token) throws IOException {
        for (int redirect = 0; redirect < 6; redirect++) {
            token.check();
            validateUrl(uri);
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .header("User-Agent", "VibeMiner-Questie/1.0")
                            .header("Accept", "application/vnd.github+json");
            if (etag != null && !etag.isEmpty()) {
                builder.header("If-None-Match", etag);
            }
            var future =
                    http.sendAsync(
                            builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                while (true) {
                    token.check();
                    try {
                        response = future.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException pending) {
                        /* Poll explicit cancellation. */
                    }
                }
            } catch (InterruptedException failure) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted download", failure);
            } catch (ExecutionException failure) {
                throw new IOException("Download failed", failure.getCause());
            } catch (RuntimeException failure) {
                future.whenComplete(
                        (late, error) -> {
                            if (late != null) {
                                try {
                                    late.body().close();
                                } catch (IOException ignored) {
                                    /* Release late body. */
                                }
                            }
                        });
                future.cancel(true);
                throw failure;
            }
            if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                response.body().close();
                String location =
                        response.headers()
                                .firstValue("location")
                                .orElseThrow(() -> new IOException("Redirect without location"));
                uri = uri.resolve(location);
                etag = null;
                continue;
            }
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("etag").orElse(null),
                    response.headers().firstValue("retry-after").orElse(null),
                    new GuardedBody(response.body(), token, testing ? 2000 : 120000));
        }
        throw new IOException("Too many redirects");
    }

    /** Closes a stalled HTTP body on explicit cancellation or a finite transfer deadline. */
    private static final class GuardedBody extends java.io.FilterInputStream {
        private final Cancellation token;
        private final java.util.concurrent.atomic.AtomicBoolean ended =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean expired;
        private final Thread watcher;

        GuardedBody(InputStream body, Cancellation token, long deadlineMillis) {
            super(body);
            this.token = token;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
            watcher =
                    Thread.startVirtualThread(
                            () -> {
                                try {
                                    while (!ended.get()) {
                                        if (token.isCanceled() || System.nanoTime() >= deadline) {
                                            expired = !token.isCanceled();
                                            close();
                                            return;
                                        }
                                        Thread.sleep(25);
                                    }
                                } catch (InterruptedException stopped) {
                                    Thread.currentThread().interrupt();
                                } catch (IOException ignored) {
                                    /* The waiting read reports the closed-body failure. */
                                }
                            });
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            token.check();
            if (expired) {
                throw new IOException("HTTP body transfer deadline exceeded");
            }
            try {
                int count = in.read(bytes, offset, length);
                token.check();
                if (expired) {
                    throw new IOException("HTTP body transfer deadline exceeded");
                }
                return count;
            } catch (IOException failure) {
                token.check();
                if (expired) {
                    throw new IOException("HTTP body transfer deadline exceeded", failure);
                }
                throw failure;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public void close() throws IOException {
            if (ended.compareAndSet(false, true)) {
                try {
                    in.close();
                } finally {
                    if (watcher != null && watcher != Thread.currentThread()) {
                        watcher.interrupt();
                    }
                }
            }
        }
    }

    private static void requireSuccess(Response response) throws IOException {
        if (response.status != 200) {
            response.body.close();
            throw new IOException(
                    "Upstream HTTP "
                            + response.status
                            + (response.retryAfter == null
                                    ? ""
                                    : "; Retry-After " + response.retryAfter));
        }
    }

    private static byte[] responseBytes(Response response, int maximum, Cancellation token)
            throws IOException {
        requireSuccess(response);
        try (var body = response.body;
                var output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int count;
            while ((count = body.read(block)) != -1) {
                token.check();
                if (output.size() + count > maximum) {
                    throw new IOException("Metadata byte limit exceeded");
                }
                output.write(block, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void validateZip(Path archive, Flavor flavor, Path notices, Cancellation token)
            throws IOException {
        var names = new HashSet<String>();
        long total = 0;
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                token.check();
                var entry = entries.nextElement();
                String name = entry.getName();
                if (names.size() >= 100000
                        || name.contains("\\")
                        || name.startsWith("/")
                        || name.contains(":")
                        || Arrays.asList(name.split("/", -1)).contains("..")
                        || !name.startsWith("QuestieDB/")
                        || !names.add(name)) {
                    throw new IOException("Unsafe or duplicate ZIP entry");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getSize() < 0 || entry.getSize() > 256L << 20) {
                    throw new IOException("ZIP member size limit");
                }
                boolean notice =
                        name.equals("QuestieDB/LICENSE.md") || name.equals("QuestieDB/gpl-3.0.md");
                if (notice && entry.getSize() > METADATA_LIMIT) {
                    throw new IOException("Notice size limit");
                }
                try (var input = zip.getInputStream(entry);
                        var output = new ByteArrayOutputStream()) {
                    byte[] block = new byte[65536];
                    long member = 0;
                    var crc = new java.util.zip.CRC32();
                    int count;
                    while ((count = input.read(block)) != -1) {
                        token.check();
                        member += count;
                        crc.update(block, 0, count);
                        total += count;
                        if (total > EXPANDED_LIMIT || member > entry.getSize()) {
                            throw new IOException("ZIP expansion limit");
                        }
                        if (notice) {
                            output.write(block, 0, count);
                        }
                    }
                    if (member != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        throw new IOException("Truncated ZIP member");
                    }
                    if (notice) {
                        Files.createDirectories(notices);
                        durableFile(
                                notices.resolve(Path.of(name).getFileName()), output.toByteArray());
                    }
                }
            }
        }
        if (!names.contains("QuestieDB/" + flavor.tocName())
                || !names.contains("QuestieDB/LICENSE.md")
                || !names.contains("QuestieDB/gpl-3.0.md")) {
            throw new IOException("Missing flavor TOC or license notices");
        }
    }

    private static void emit(
            Consumer<Progress> observer,
            Stage stage,
            long done,
            Long total,
            String unit,
            String detail) {
        if (observer != null) {
            observer.accept(new Progress(stage, done, total, unit, detail));
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] readSmall(Path path) throws IOException {
        rejectSymlink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > METADATA_LIMIT) {
            throw new IOException("Invalid metadata file");
        }
        return Files.readAllBytes(path);
    }

    private static void rejectSymlink(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing symbolic link: " + current);
            }
        }
    }

    private static void durableFile(Path path, byte[] bytes) throws IOException {
        rejectSymlink(path);
        try (var file =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                file.write(buffer);
            }
            file.force(true);
        }
    }

    private static void atomicFile(Path path, byte[] bytes) throws IOException {
        rejectSymlink(path);
        Path temp = Files.createTempFile(path.getParent(), ".metadata-", ".tmp");
        try {
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void removeOwnedStaging(Path staging) throws IOException {
        if (!staging.getParent().equals(root.resolve("staging-v1"))
                || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private synchronized <T> CompletableFuture<T> submit(Cancellation token, Work<T> work) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Questie repository closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        try {
                            token.check();
                            result.complete(work.run());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Questie source queue full", full));
        }
        return result.copy();
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        worker.shutdown();
        var result = closing;
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                            /* Drain accepted work. */
                        }
                        if (lock != null) {
                            lock.release();
                        }
                        if (lockChannel != null) {
                            lockChannel.close();
                        }
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
