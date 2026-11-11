package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ListfilePart;
import com.vibeminer.network.NetworkTypes.ListfileReply;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import com.vibeminer.network.NetworkTypes.Reply;
import com.vibeminer.network.NetworkTypes.Request;
import com.vibeminer.network.NetworkTypes.Sink;
import com.vibeminer.network.NetworkTypes.VersionReply;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Consumer fake: in-memory replies and scripted host failures; no HTTP implementation is invoked.
 */
public final class MemorySources implements CdnClient, VersionClient, ListfileClient {
    private record Address(ObjectKind kind, CdnKey key) {}

    private final Map<Address, byte[]> objects = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private final Map<URI, byte[]> documents = new HashMap<>();
    private final Map<URI, Failure> sourceFailures = new HashMap<>();
    private final Map<String, byte[]> assets = new HashMap<>();
    private final NetworkOptions options;
    private final List<URI> requested = new ArrayList<>();
    private byte[] release = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public MemorySources() {
        this(new NetworkOptions());
    }

    public MemorySources(NetworkOptions options) {
        this.options = options;
    }

    public synchronized void add(ObjectKind kind, CdnKey key, byte[] bytes) {
        objects.put(new Address(kind, key), bytes.clone());
    }

    public synchronized void fail(String host, Failure failure) {
        if (failure == null) {
            failures.remove(host);
        } else {
            failures.put(host, failure);
        }
    }

    /** Scripts a failure of one version, definition or listfile address. */
    public synchronized void fail(URI url, Failure failure) {
        if (failure == null) {
            sourceFailures.remove(url);
        } else {
            sourceFailures.put(url, failure);
        }
    }

    private void sourceFailure(URI url) {
        Failure failure = sourceFailures.get(url);
        if (failure != null) {
            throw new CdnException(List.of(attempt(url, failure, null)));
        }
    }

    private void bound(
            URI url, byte[] bytes, int maximum, Failure oversized, boolean emptyAllowed) {
        if (bytes.length > maximum) {
            throw new CdnException(List.of(attempt(url, oversized, null)));
        }
        if (bytes.length == 0 && !emptyAllowed) {
            throw new CdnException(List.of(attempt(url, Failure.SIZE_CONFLICT, null)));
        }
    }

    public synchronized void version(
            String product, String region, DocumentKind kind, byte[] bytes) {
        documents.put(SourceUrls.version(product, region, kind), bytes.clone());
    }

    public synchronized void release(byte[] bytes) {
        release = bytes.clone();
    }

    public synchronized void asset(String tag, byte[] bytes) {
        SourceUrls.asset(tag);
        assets.put(tag, bytes.clone());
    }

    public synchronized List<URI> requested() {
        return List.copyOf(requested);
    }

    @Override
    public CompletableFuture<Reply> fetch(CdnRoute route, Request request, Cancellation token) {
        return object(route, request, true, token);
    }

    private synchronized CompletableFuture<Reply> object(
            CdnRoute route, Request request, boolean check, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    List<Attempt> attempts = new ArrayList<>();
                    if (request.length() != null
                            && request.length()
                                    > (check ? options.maxInMemoryBytes() : options.maxBytes())) {
                        throw new IllegalArgumentException("Request over bound");
                    }
                    for (String host : route.hosts()) {
                        token.check();
                        URI uri = route.urlFor(host, request.kind(), request.key());
                        requested.add(uri);
                        byte[] body = objects.get(new Address(request.kind(), request.key()));
                        Failure failure = failures.get(host);
                        if (failure != null || body == null) {
                            attempts.add(
                                    attempt(
                                            uri,
                                            failure == null ? Failure.NOT_FOUND : failure,
                                            null));
                            continue;
                        }
                        if (request.expectedTotal() != null
                                        && request.expectedTotal() != body.length
                                || !request.isRange()
                                        && request.length() != null
                                        && request.length() != body.length) {
                            attempts.add(attempt(uri, Failure.SIZE_CONFLICT, null));
                            continue;
                        }
                        long limit =
                                Math.min(
                                        check ? options.maxInMemoryBytes() : options.maxBytes(),
                                        request.maxLength() == null
                                                ? Long.MAX_VALUE
                                                : request.maxLength());
                        if (request.isRange()) {
                            if (request.offset() > body.length - request.length()) {
                                attempts.add(attempt(uri, Failure.HTTP_STATUS, null));
                                continue;
                            }
                            body =
                                    Arrays.copyOfRange(
                                            body,
                                            request.offset().intValue(),
                                            (int) (request.offset() + request.length()));
                        }
                        if (body.length > limit) {
                            attempts.add(attempt(uri, Failure.TOO_LARGE, null));
                            continue;
                        }
                        if (body.length == 0) {
                            attempts.add(attempt(uri, Failure.SIZE_CONFLICT, null));
                            continue;
                        }
                        if (check && request.check() != null) {
                            String code = request.check().refusal(body.clone());
                            if (code != null) {
                                attempts.add(attempt(uri, Failure.VERIFICATION_FAILED, code));
                                continue;
                            }
                        }
                        Long total =
                                request.isRange()
                                        ? (long)
                                                objects.get(
                                                                new Address(
                                                                        request.kind(),
                                                                        request.key()))
                                                        .length
                                        : null;
                        return new Reply(
                                host,
                                uri,
                                "http/1.1",
                                body.length,
                                total,
                                body,
                                options.clock().instant(),
                                Duration.ZERO,
                                Duration.ZERO,
                                attempts);
                    }
                    throw new CdnException(attempts);
                });
    }

    @Override
    public CompletableFuture<Reply> fetchTo(
            CdnRoute route, Request request, Sink sink, Cancellation token) {
        return object(route, request, false, token)
                .thenCompose(
                        reply -> {
                            byte[] bytes = reply.bytes();
                            CompletableFuture<Void> sequence =
                                    CompletableFuture.completedFuture(null);
                            for (int i = 0; i < bytes.length; i += 65536) {
                                byte[] part =
                                        Arrays.copyOfRange(
                                                bytes, i, Math.min(i + 65536, bytes.length));
                                sequence =
                                        sequence.thenCompose(
                                                ignored -> {
                                                    token.check();
                                                    return sink.accept(part, token);
                                                });
                            }
                            return sequence.thenApply(
                                    ignored ->
                                            new Reply(
                                                    reply.host(),
                                                    reply.url(),
                                                    reply.httpVersion(),
                                                    reply.length(),
                                                    reply.total(),
                                                    null,
                                                    reply.observedAt(),
                                                    reply.headersAfter(),
                                                    reply.elapsed(),
                                                    reply.failed()));
                        });
    }

    @Override
    public synchronized CompletableFuture<VersionReply> fetch(
            String product, String region, DocumentKind kind, Cancellation token) {
        URI uri = SourceUrls.version(product, region, kind);
        return call(
                () -> {
                    token.check();
                    requested.add(uri);
                    sourceFailure(uri);
                    byte[] bytes = documents.get(uri);
                    if (bytes == null) {
                        throw missing(uri);
                    }
                    bound(uri, bytes, SourceUrls.DOCUMENT_BYTES, Failure.TOO_LARGE, false);
                    return new VersionReply(
                            product,
                            region,
                            kind,
                            uri,
                            options.clock().instant(),
                            bytes,
                            Duration.ZERO);
                });
    }

    @Override
    public synchronized CompletableFuture<ListfileReply> fetchRelease(Cancellation token) {
        return call(
                () -> {
                    token.check();
                    requested.add(SourceUrls.LATEST_RELEASE);
                    sourceFailure(SourceUrls.LATEST_RELEASE);
                    bound(
                            SourceUrls.LATEST_RELEASE,
                            release,
                            SourceUrls.DOCUMENT_BYTES,
                            Failure.PROTOCOL,
                            true);
                    return new ListfileReply(
                            SourceUrls.LATEST_RELEASE, options.clock().instant(), release);
                });
    }

    @Override
    public synchronized CompletableFuture<ListfilePart> fetchPart(
            String tag, int index, Cancellation token) {
        URI uri = SourceUrls.asset(tag);
        return call(
                () -> {
                    if (index < 0 || index >= 64) {
                        throw new IllegalArgumentException("Invalid part");
                    }
                    token.check();
                    requested.add(uri);
                    sourceFailure(uri);
                    byte[] bytes = assets.get(tag);
                    if (bytes == null) {
                        throw missing(uri);
                    }
                    long offset = (long) index * SourceUrls.PART_BYTES;
                    if (offset >= bytes.length) {
                        throw new CdnException(List.of(attempt(uri, Failure.HTTP_STATUS, null)));
                    }
                    return new ListfilePart(
                            uri,
                            options.clock().instant(),
                            Arrays.copyOfRange(
                                    bytes,
                                    (int) offset,
                                    (int) Math.min(bytes.length, offset + SourceUrls.PART_BYTES)),
                            offset,
                            bytes.length);
                });
    }

    private CdnException missing(URI uri) {
        return new CdnException(List.of(attempt(uri, Failure.NOT_FOUND, null)));
    }

    private Attempt attempt(URI uri, Failure failure, String code) {
        return new Attempt(
                uri.getHost(),
                uri,
                failure,
                failure == Failure.NOT_FOUND ? 404 : null,
                options.clock().instant(),
                "Scripted " + failure,
                code);
    }

    private static <T> CompletableFuture<T> call(Supplier<T> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
