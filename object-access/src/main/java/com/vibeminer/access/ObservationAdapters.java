package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.ListfileClient;
import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.network.VersionClient;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Layer 2 adapters normalize raw source replies; ObjectSource owns persistence and history. */
public final class ObservationAdapters {
    private ObservationAdapters() {}

    public static ObservationSource versions(VersionClient client) {
        Objects.requireNonNull(client);
        return new ObservationSource() {
            private final Set<ObservationKind> supported =
                    Set.of(
                            ObservationKind.VERSIONS,
                            ObservationKind.CDNS,
                            ObservationKind.BGDL,
                            ObservationKind.SUMMARY);

            @Override
            public Set<ObservationKind> kinds() {
                return supported;
            }

            @Override
            public CompletableFuture<Observation> fetch(
                    ObservationRequest request, Cancellation cancellation) {
                return call(
                        () -> {
                            Objects.requireNonNull(request);
                            cancellation.check();
                            DocumentKind kind =
                                    switch (request.kind()) {
                                        case VERSIONS -> DocumentKind.VERSIONS;
                                        case CDNS -> DocumentKind.CDNS;
                                        case BGDL -> DocumentKind.BGDL;
                                        case SUMMARY -> DocumentKind.SUMMARY;
                                        default ->
                                                throw new IllegalArgumentException(
                                                        "Version service does not serve "
                                                                + request.kind());
                                    };
                            return client.fetch(
                                            request.product(),
                                            request.region(),
                                            kind,
                                            cancellation.networkToken())
                                    .thenApply(
                                            reply ->
                                                    new Observation(
                                                            request.kind(),
                                                            reply.product(),
                                                            reply.region(),
                                                            reply.url(),
                                                            reply.observedAt(),
                                                            reply.bytes()));
                        });
            }
        };
    }

    public static ObservationSource listfile(ListfileClient client) {
        Objects.requireNonNull(client);
        return new ObservationSource() {
            private final Set<ObservationKind> supported =
                    Set.of(ObservationKind.LISTFILE_RELEASE, ObservationKind.LISTFILE_PART);

            @Override
            public Set<ObservationKind> kinds() {
                return supported;
            }

            @Override
            public CompletableFuture<Observation> fetch(
                    ObservationRequest request, Cancellation cancellation) {
                return call(
                        () -> {
                            require(request, supported);
                            cancellation.check();
                            if (request.kind() == ObservationKind.LISTFILE_RELEASE) {
                                return client.fetchRelease(cancellation.networkToken())
                                        .thenApply(
                                                reply ->
                                                        new Observation(
                                                                request.kind(),
                                                                request.product(),
                                                                request.region(),
                                                                reply.url(),
                                                                reply.observedAt(),
                                                                reply.bytes()));
                            }
                            int index =
                                    Objects.requireNonNull(
                                            ObservationRequest.partIndex(request.region()));
                            URI asset = SourceUrls.asset(request.product());
                            return client.fetchPart(
                                            request.product(), index, cancellation.networkToken())
                                    .thenApply(
                                            part -> {
                                                long offset = (long) index * SourceUrls.PART_BYTES;
                                                byte[] bytes = part.bytes();
                                                if (part.url() == null
                                                        || !part.url().equals(asset)) {
                                                    throw protocol(
                                                            asset,
                                                            part.observedAt(),
                                                            "Listfile part came from another address");
                                                }
                                                if (part.offset() != offset) {
                                                    throw protocol(
                                                            asset,
                                                            part.observedAt(),
                                                            "Listfile part has another offset");
                                                }
                                                if (part.totalLength() <= offset
                                                        || part.totalLength()
                                                                > SourceUrls.MAX_ASSET_BYTES) {
                                                    throw protocol(
                                                            asset,
                                                            part.observedAt(),
                                                            "Listfile part has an invalid total length");
                                                }
                                                if (bytes == null
                                                        || bytes.length
                                                                != Math.min(
                                                                        SourceUrls.PART_BYTES,
                                                                        part.totalLength()
                                                                                - offset)) {
                                                    throw protocol(
                                                            asset,
                                                            part.observedAt(),
                                                            "Listfile part has the wrong byte count");
                                                }
                                                return new Observation(
                                                        request.kind(),
                                                        request.product(),
                                                        request.region(),
                                                        part.url(),
                                                        part.observedAt(),
                                                        bytes);
                                            });
                        });
            }
        };
    }

    private static void require(ObservationRequest request, Set<ObservationKind> kinds) {
        Objects.requireNonNull(request);
        if (!kinds.contains(request.kind())) {
            throw new IllegalArgumentException("Source does not serve " + request.kind());
        }
        if (request.problem() != null) {
            throw new IllegalArgumentException(request.problem());
        }
    }

    private static CdnException protocol(URI uri, Instant observed, String message) {
        return new CdnException(
                List.of(
                        new Attempt(
                                uri.getHost(),
                                uri,
                                Failure.PROTOCOL,
                                null,
                                observed,
                                message,
                                null)));
    }

    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            work.get()
                    .whenComplete(
                            (value, error) -> {
                                if (error == null) {
                                    result.complete(value);
                                } else {
                                    while (error instanceof java.util.concurrent.CompletionException
                                            && error.getCause() != null) {
                                        error = error.getCause();
                                    }
                                    result.completeExceptionally(error);
                                }
                            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
        return result;
    }
}
