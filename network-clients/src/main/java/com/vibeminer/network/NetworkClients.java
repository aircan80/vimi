package com.vibeminer.network;

import static com.vibeminer.network.HttpEngine.fault;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.vibeminer.network.HttpEngine.Budget;
import com.vibeminer.network.HttpEngine.Exchange;
import com.vibeminer.network.HttpEngine.Fault;
import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.DefinitionReply;
import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ListfilePart;
import com.vibeminer.network.NetworkTypes.ListfileReply;
import com.vibeminer.network.NetworkTypes.Reply;
import com.vibeminer.network.NetworkTypes.Request;
import com.vibeminer.network.NetworkTypes.Sink;
import com.vibeminer.network.NetworkTypes.VersionReply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Layer 1 HTTP clients. Owns bounded queues/pools; does not cache, store or interpret observations.
 */
public final class NetworkClients
        implements CdnClient,
                VersionClient,
                DefinitionClient,
                ListfileClient,
                TactKeyClient,
                EgLinkClient {
    private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private static final Set<String> CDN_MEDIA =
            Set.of(
                    "application/octet-stream",
                    "binary/octet-stream",
                    "application/binary",
                    "text/plain");
    private final NetworkOptions options;
    private final HttpEngine cdn, definitions, listfile;

    public NetworkClients() {
        this(new NetworkOptions());
    }

    public NetworkClients(NetworkOptions options) {
        this(
                options,
                new HttpEngine(options, "cdn", options.concurrentFetches()),
                new HttpEngine(options, "definitions", 2),
                new HttpEngine(options, "listfile", 1));
    }

    NetworkClients(
            NetworkOptions options, HttpEngine cdn, HttpEngine definitions, HttpEngine listfile) {
        this.options = Objects.requireNonNull(options);
        this.cdn = cdn;
        this.definitions = definitions;
        this.listfile = listfile;
    }

    @Override
    public CompletableFuture<Reply> fetch(CdnRoute route, Request request, Cancellation token) {
        return fetchObject(route, request, null, token);
    }

    @Override
    public CompletableFuture<Reply> fetchTo(
            CdnRoute route, Request request, Sink sink, Cancellation token) {
        return fetchObject(route, request, Objects.requireNonNull(sink), token);
    }

    private CompletableFuture<Reply> fetchObject(
            CdnRoute route, Request request, Sink sink, Cancellation token) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(request);
        long ceiling = sink == null ? options.maxInMemoryBytes() : options.maxBytes();
        if (request.length() != null && request.length() > ceiling) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Request exceeds this fetch method's byte bound"));
        }
        return cdn.queue.submit(
                token,
                () -> {
                    List<Attempt> attempts = new ArrayList<>();
                    Budget budget = new Budget(null);
                    for (String host : route.hosts()) {
                        token.check();
                        URI uri = route.urlFor(host, request.kind(), request.key());
                        long start = System.nanoTime();
                        boolean[] delivered = {false};
                        try (Exchange exchange =
                                cdn.send(
                                        uri,
                                        request.isRange()
                                                ? Map.of(
                                                        "Range",
                                                        rangeHeader(
                                                                request.offset(), request.length()))
                                                : Map.of(),
                                        token,
                                        budget,
                                        false)) {
                            Duration headersAfter = Duration.ofNanos(System.nanoTime() - start);
                            long limit =
                                    Math.min(
                                            ceiling,
                                            request.maxLength() == null
                                                    ? Long.MAX_VALUE
                                                    : request.maxLength());
                            Long length = checkObjectHeaders(exchange, uri, request, limit);
                            ByteArrayOutputStream bytes =
                                    sink == null
                                            ? new ByteArrayOutputStream(
                                                    (int)
                                                            Math.min(
                                                                    length == null ? 65536 : length,
                                                                    limit))
                                            : null;
                            long received = 0, bound = length == null ? limit : length;
                            byte[] buffer = new byte[65536];
                            while (true) {
                                int n =
                                        exchange.read(
                                                buffer,
                                                (int)
                                                        Math.min(
                                                                buffer.length,
                                                                bound - received + 1));
                                if (n < 0) {
                                    break;
                                }
                                if (received + n > bound) {
                                    throw fault(
                                            length == null
                                                    ? Failure.TOO_LARGE
                                                    : Failure.SIZE_CONFLICT,
                                            "Reply exceeds its byte bound",
                                            exchange.response.code());
                                }
                                if (sink == null) {
                                    bytes.write(buffer, 0, n);
                                } else {
                                    delivered[0] = true;
                                    waitForSink(
                                            sink.accept(Arrays.copyOf(buffer, n), token), token);
                                }
                                received += n;
                            }
                            boolean declared = request.isRange() || exchange.length() != null;
                            if (declared && received < (length == null ? 0 : length)) {
                                throw fault(
                                        Failure.NETWORK,
                                        "Reply ended before its declared length",
                                        exchange.response.code());
                            }
                            if (request.length() != null && received != request.length()
                                    || received == 0) {
                                throw fault(
                                        Failure.SIZE_CONFLICT,
                                        "Reply length differs from the requested length",
                                        exchange.response.code());
                            }
                            byte[] body = bytes == null ? null : bytes.toByteArray();
                            if (body != null && request.check() != null) {
                                String code = request.check().refusal(body.clone());
                                if (code != null) {
                                    attempts.add(
                                            cdn.attempt(
                                                    uri,
                                                    fault(
                                                            Failure.VERIFICATION_FAILED,
                                                            "Reply failed the caller's key check",
                                                            exchange.response.code()),
                                                    code));
                                    continue;
                                }
                            }
                            Long total = request.isRange() ? contentRange(exchange)[2] : null;
                            return new Reply(
                                    host,
                                    uri,
                                    exchange.response.protocol().toString(),
                                    received,
                                    total,
                                    body,
                                    exchange.observedAt,
                                    headersAfter,
                                    Duration.ofNanos(System.nanoTime() - start),
                                    attempts);
                        } catch (Fault failure) {
                            attempts.add(cdn.attempt(uri, failure, null));
                            if (delivered[0]) {
                                break;
                            }
                        }
                    }
                    throw new CdnException(attempts);
                });
    }

    @SuppressWarnings("try")
    private static void waitForSink(CompletableFuture<Void> result, Cancellation token) {
        Objects.requireNonNull(result);
        CompletableFuture<Void> canceled = new CompletableFuture<>();
        try (Cancellation.Registration ignored =
                token.onCancel(
                        () ->
                                canceled.completeExceptionally(
                                        new java.util.concurrent.CancellationException()))) {
            CompletableFuture.anyOf(result, canceled).join();
            token.check();
        }
    }

    private static String rangeHeader(long offset, long length) {
        return "bytes=" + offset + "-" + (offset + length - 1);
    }

    private static String media(Exchange e) {
        String type = e.header("Content-Type");
        return type == null ? null : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static void noEncoding(Exchange e) {
        if (e.header("Content-Encoding") != null) {
            throw fault(Failure.PROTOCOL, "Encoded replies are refused", e.response.code());
        }
    }

    private static Long checkObjectHeaders(Exchange e, URI uri, Request request, long limit) {
        int status = e.response.code();
        if (status >= 300 && status < 400) {
            throw fault(Failure.PROTOCOL, "CDN redirects are refused", status);
        }
        if (status == 404
                || status == 403
                        && (uri.getHost().equals("level3.blizzard.com")
                                || uri.getHost().equals("level3.ssl.blizzard.com"))) {
            throw fault(Failure.NOT_FOUND, "This host does not have the key now", status);
        }
        if (request.isRange() && status == 200) {
            throw fault(Failure.RANGE_IGNORED, "Range ignored; body refused at headers", status);
        }
        if (!request.isRange() && status == 206) {
            throw fault(Failure.RANGE_MISMATCH, "Whole request answered with a range", status);
        }
        if (status != 200 && status != 206) {
            throw fault(Failure.HTTP_STATUS, "HTTP " + status, status);
        }
        noEncoding(e);
        String media = media(e);
        if (media != null && !CDN_MEDIA.contains(media)) {
            throw fault(Failure.PROTOCOL, "Unexpected CDN media type", status);
        }
        Long declared = e.length();
        if (request.isRange()) {
            long[] range = contentRange(e);
            long end = request.offset() + request.length() - 1;
            if (range[0] != request.offset() || range[1] != end || range[2] <= end) {
                throw fault(
                        Failure.RANGE_MISMATCH,
                        "Response is not the requested single range",
                        status);
            }
            if (request.expectedTotal() != null && range[2] != request.expectedTotal()) {
                throw fault(
                        Failure.SIZE_CONFLICT, "Range total differs from expected total", status);
            }
            if (declared != null && !declared.equals(request.length())) {
                throw fault(Failure.RANGE_MISMATCH, "Content-Length differs from range", status);
            }
            return request.length();
        }
        if (e.header("Content-Range") != null) {
            throw fault(Failure.RANGE_MISMATCH, "Whole reply has a range", status);
        }
        if (declared != null) {
            if (request.length() != null && !declared.equals(request.length())) {
                throw fault(
                        Failure.SIZE_CONFLICT,
                        "Declared length differs from expected length",
                        status);
            }
            if (declared > limit) {
                throw fault(Failure.TOO_LARGE, "Reply exceeds the byte limit", status);
            }
            if (declared == 0) {
                throw fault(Failure.SIZE_CONFLICT, "Empty CDN reply", status);
            }
            return declared;
        }
        return request.length();
    }

    private static long[] contentRange(Exchange e) {
        String value = e.header("Content-Range");
        var match = RANGE.matcher(value == null ? "" : value);
        if (!match.matches()) {
            throw fault(Failure.RANGE_MISMATCH, "Invalid Content-Range", e.response.code());
        }
        try {
            return new long[] {
                Long.parseLong(match.group(1)),
                Long.parseLong(match.group(2)),
                Long.parseLong(match.group(3))
            };
        } catch (NumberFormatException ex) {
            throw fault(Failure.RANGE_MISMATCH, "Oversized Content-Range", e.response.code());
        }
    }

    @Override
    public CompletableFuture<VersionReply> fetch(
            String product, String region, DocumentKind kind, Cancellation token) {
        URI uri = SourceUrls.version(product, region, kind);
        return cdn.queue.submit(
                token,
                () -> {
                    long start = System.nanoTime();
                    Budget budget = new Budget(options.timeouts().headers());
                    try (Exchange e = cdn.send(uri, Map.of(), token, budget, false)) {
                        if (e.response.code() == 404) {
                            throw fault(Failure.NOT_FOUND, "Version document not found", 404);
                        }
                        if (e.response.code() >= 300 && e.response.code() < 400) {
                            throw fault(
                                    Failure.PROTOCOL,
                                    "Version redirects are refused",
                                    e.response.code());
                        }
                        if (e.response.code() != 200) {
                            throw fault(
                                    Failure.HTTP_STATUS,
                                    "Version HTTP " + e.response.code(),
                                    e.response.code());
                        }
                        noEncoding(e);
                        if (e.header("Content-Range") != null
                                || media(e) != null
                                        && !Set.of("text/plain", "application/octet-stream")
                                                .contains(media(e))) {
                            throw fault(
                                    Failure.PROTOCOL,
                                    "Unexpected version response headers",
                                    e.response.code());
                        }
                        return new VersionReply(
                                product,
                                region,
                                kind,
                                uri,
                                e.observedAt,
                                e.bytes(SourceUrls.DOCUMENT_BYTES, Failure.TOO_LARGE, false),
                                Duration.ofNanos(System.nanoTime() - start));
                    } catch (Fault failure) {
                        throw cdn.failure(uri, failure);
                    }
                });
    }

    @Override
    public CompletableFuture<DefinitionReply> fetch(String tableName, Cancellation token) {
        URI first = SourceUrls.commits(tableName);
        return definitions.queue.submit(
                token,
                () -> {
                    URI[] at = {first};
                    Budget budget = new Budget(options.timeouts().definitions());
                    try {
                        byte[] json;
                        try (Exchange e =
                                definitions.send(first, githubHeaders(true), token, budget, true)) {
                            checkGithub(e);
                            noEncoding(e);
                            json = e.bytes(SourceUrls.COMMIT_BYTES, Failure.PROTOCOL, true);
                        }
                        String revision = revision(json);
                        at[0] = SourceUrls.definition(revision, tableName);
                        try (Exchange e =
                                definitions.send(
                                        at[0], githubHeaders(false), token, budget, true)) {
                            checkGithub(e);
                            noEncoding(e);
                            return new DefinitionReply(
                                    tableName,
                                    revision,
                                    at[0],
                                    e.observedAt,
                                    e.bytes(SourceUrls.DOCUMENT_BYTES, Failure.TOO_LARGE, true));
                        }
                    } catch (Fault failure) {
                        throw definitions.failure(at[0], failure);
                    }
                });
    }

    @Override
    public CompletableFuture<NetworkTypes.TactKeyReply> fetchTactKeys(Cancellation token) {
        URI url = SourceUrls.TACT_KEYS;
        return definitions.queue.submit(
                token,
                () -> {
                    Budget budget = new Budget(options.timeouts().definitions());
                    try (Exchange exchange =
                            definitions.send(url, githubHeaders(false), token, budget, true)) {
                        checkGithub(exchange);
                        noEncoding(exchange);
                        return new NetworkTypes.TactKeyReply(
                                url,
                                exchange.observedAt,
                                exchange.bytes(SourceUrls.TACT_KEY_BYTES, Failure.TOO_LARGE, true));
                    } catch (Fault failure) {
                        throw definitions.failure(url, failure);
                    }
                });
    }

    @Override
    public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkHead(Cancellation token) {
        return egLink(SourceUrls.EG_LINK_HEAD, 64 << 10, true, token);
    }

    @Override
    public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkFile(
            String revision, String path, Cancellation token) {
        return egLink(
                SourceUrls.egLink(revision, path),
                path.equals("data/index.json") ? 1 << 20 : 16 << 20,
                false,
                token);
    }

    private CompletableFuture<NetworkTypes.EgLinkReply> egLink(
            URI url, int limit, boolean api, Cancellation token) {
        return definitions.queue.submit(
                token,
                () -> {
                    Budget budget = new Budget(options.timeouts().definitions());
                    try (Exchange exchange =
                            definitions.send(url, githubHeaders(api), token, budget, true)) {
                        checkGithub(exchange);
                        noEncoding(exchange);
                        return new NetworkTypes.EgLinkReply(
                                url,
                                exchange.observedAt,
                                exchange.bytes(limit, Failure.TOO_LARGE, true));
                    } catch (Fault failure) {
                        throw definitions.failure(url, failure);
                    }
                });
    }

    private static Map<String, String> githubHeaders(boolean api) {
        return api
                ? Map.of(
                        "Accept",
                        "application/vnd.github+json",
                        "X-GitHub-Api-Version",
                        "2022-11-28")
                : Map.of("Accept", "text/plain");
    }

    private static void checkGithub(Exchange e) {
        int status = e.response.code();
        if (status == 404) {
            throw fault(Failure.NOT_FOUND, "GitHub source not found", status);
        }
        if (status >= 300 && status < 400) {
            throw fault(Failure.PROTOCOL, "GitHub redirect refused", status);
        }
        if (status < 200 || status >= 300) {
            throw fault(Failure.HTTP_STATUS, "GitHub HTTP " + status, status);
        }
    }

    private static String revision(byte[] json) {
        JsonFactory factory =
                JsonFactory.builder()
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(64)
                                        .maxStringLength(SourceUrls.COMMIT_BYTES)
                                        .build())
                        .build();
        try (JsonParser parser = factory.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw fault(Failure.PROTOCOL, "Commit response is not an array", null);
            }
            JsonToken first = parser.nextToken();
            if (first == JsonToken.END_ARRAY) {
                if (parser.nextToken() != null) {
                    throw fault(Failure.PROTOCOL, "Trailing JSON content", null);
                }
                throw fault(Failure.NOT_FOUND, "No commit changes this definition", null);
            }
            if (first != JsonToken.START_OBJECT) {
                throw fault(Failure.PROTOCOL, "Commit response has no object", null);
            }
            String revision = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw fault(Failure.PROTOCOL, "Malformed commit object", null);
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("sha".equals(name)) {
                    revision = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                }
                parser.skipChildren();
            }
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) {
                    throw fault(Failure.PROTOCOL, "Truncated commits array", null);
                }
                parser.skipChildren();
            }
            if (parser.nextToken() != null
                    || revision == null
                    || !revision.matches("[0-9a-f]{40}")) {
                throw fault(Failure.PROTOCOL, "Invalid definition commit SHA", null);
            }
            return revision;
        } catch (IOException e) {
            throw fault(Failure.PROTOCOL, "Unreadable commit response", null);
        }
    }

    @Override
    public CompletableFuture<ListfileReply> fetchRelease(Cancellation token) {
        URI uri = SourceUrls.LATEST_RELEASE;
        return listfile.queue.submit(
                token,
                () -> {
                    Budget budget = new Budget(options.timeouts().listfile());
                    try (Exchange e =
                            listfile.send(uri, githubHeaders(true), token, budget, true)) {
                        checkGithub(e);
                        noEncoding(e);
                        return new ListfileReply(
                                uri,
                                e.observedAt,
                                e.bytes(SourceUrls.DOCUMENT_BYTES, Failure.PROTOCOL, true));
                    } catch (Fault failure) {
                        throw listfile.failure(uri, failure);
                    }
                });
    }

    @Override
    public CompletableFuture<ListfilePart> fetchPart(String tag, int index, Cancellation token) {
        URI asset = SourceUrls.asset(tag);
        if (index < 0 || index >= SourceUrls.MAX_PARTS) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listfile index is 0–63"));
        }
        return listfile.queue.submit(
                token,
                () -> {
                    URI uri = asset;
                    long start = (long) index * SourceUrls.PART_BYTES;
                    Budget budget = new Budget(options.timeouts().listfile());
                    try {
                        for (int hop = 0; ; hop++) {
                            try (Exchange e =
                                    listfile.send(
                                            uri,
                                            Map.of(
                                                    "Accept",
                                                    "application/octet-stream",
                                                    "Range",
                                                    rangeHeader(start, SourceUrls.PART_BYTES)),
                                            token,
                                            budget,
                                            true)) {
                                int status = e.response.code();
                                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                                    URI location;
                                    try {
                                        location =
                                                e.header("Location") == null
                                                        ? null
                                                        : URI.create(e.header("Location"));
                                    } catch (IllegalArgumentException bad) {
                                        throw fault(
                                                Failure.PROTOCOL, "Invalid asset redirect", status);
                                    }
                                    if (hop > 0 || !SourceUrls.assetRedirect(location)) {
                                        throw fault(
                                                Failure.PROTOCOL,
                                                "Asset redirect is not permitted",
                                                status);
                                    }
                                    uri = location;
                                    continue;
                                }
                                checkGithub(e);
                                if (status == 200) {
                                    throw fault(
                                            Failure.RANGE_IGNORED,
                                            "Listfile range ignored; body refused",
                                            status);
                                }
                                if (status != 206) {
                                    throw fault(
                                            Failure.HTTP_STATUS, "Listfile HTTP " + status, status);
                                }
                                if (media(e) != null && media(e).startsWith("multipart/")) {
                                    throw fault(
                                            Failure.RANGE_MISMATCH,
                                            "Multipart listfile reply refused",
                                            status);
                                }
                                long[] range = contentRange(e);
                                long total = range[2];
                                if (total > SourceUrls.MAX_ASSET_BYTES) {
                                    throw fault(
                                            Failure.TOO_LARGE, "Listfile exceeds 512 MiB", status);
                                }
                                long end = Math.min(start + SourceUrls.PART_BYTES, total) - 1;
                                if (total <= start || range[0] != start || range[1] != end) {
                                    throw fault(
                                            Failure.RANGE_MISMATCH,
                                            "Listfile part is not the requested range",
                                            status);
                                }
                                noEncoding(e);
                                int expected = (int) (end - start + 1);
                                Long declared = e.length();
                                if (declared != null && declared != expected) {
                                    throw fault(
                                            Failure.SIZE_CONFLICT,
                                            "Listfile part length differs from range",
                                            status);
                                }
                                byte[] bytes = e.bytes(expected, Failure.SIZE_CONFLICT, true);
                                if (bytes.length < expected) {
                                    throw fault(
                                            Failure.NETWORK, "Listfile part ended early", status);
                                }
                                return new ListfilePart(asset, e.observedAt, bytes, start, total);
                            }
                        }
                    } catch (Fault failure) {
                        throw listfile.failure(uri, failure);
                    }
                });
    }

    public CompletableFuture<Void> closeAsync() {
        return CompletableFuture.allOf(
                cdn.queue.closeAsync(),
                definitions.queue.closeAsync(),
                listfile.queue.closeAsync());
    }
}
