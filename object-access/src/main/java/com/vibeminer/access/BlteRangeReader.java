package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.NetworkTypes.Check;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.store.BlteChunkTable;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Refusal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * C# D340: authenticate a loose BLTE header, then only complete chunks covering the requested
 * bytes.
 */
final class BlteRangeReader {
    static final int HEADER_PROBE_BYTES = 64 << 10,
            MAX_TABLES = 16,
            MAX_HEADER_CACHE_BYTES = 8 << 20;

    @FunctionalInterface
    interface Fetch {
        CompletableFuture<byte[]> range(
                ObjectKey key,
                long offset,
                int length,
                long total,
                Check check,
                CdnContext context,
                FetchPriority priority,
                Cancellation token);
    }

    private record Identity(ObjectKey key, long declared) {}

    private final Fetch fetch;
    private final long maxRangeBytes;
    private final LinkedHashMap<Identity, BlteChunkTable> headers =
            new LinkedHashMap<>(16, 0.75f, true);
    private long headerBytes;

    BlteRangeReader(Fetch fetch, long maxRangeBytes) {
        this.fetch = Objects.requireNonNull(fetch);
        if (maxRangeBytes < 1 || maxRangeBytes > (64L << 20)) {
            throw new IllegalArgumentException("Range bound is 1 byte to 64 MiB");
        }
        this.maxRangeBytes = maxRangeBytes;
    }

    CompletableFuture<byte[]> read(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        try {
            Objects.requireNonNull(request);
            Objects.requireNonNull(context);
            Objects.requireNonNull(priority);
            token.check();
            if (offset < 0 || length < 0 || length > (64 << 20)) {
                throw new IllegalArgumentException("Invalid range bounds");
            }
            if (request.declaredLength() == null
                    || request.declaredLength() < 1
                    || request.key().type() != ObjectKey.ENCODED
                    || request.key().namespace() != 0
                    || !request.locations().isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            long declared = request.declaredLength();
            if (offset > declared - length) {
                throw new IllegalArgumentException("Range runs past the object");
            }
            BlteChunkTable cached = get(request.key(), declared);
            CompletableFuture<BlteChunkTable> table =
                    cached == null
                            ? header(request.key(), declared, context, priority, token)
                            : CompletableFuture.completedFuture(cached);
            return table.thenCompose(
                    proven -> {
                        token.check();
                        if (proven == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return chunks(proven, offset, length, context, priority, token);
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<BlteChunkTable> header(
            ObjectKey key,
            long declared,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        if (maxRangeBytes < BlteChunkTable.PREFIX_BYTES) {
            return CompletableFuture.completedFuture(null);
        }
        int probe = (int) Math.min(Math.min(HEADER_PROBE_BYTES, maxRangeBytes), declared);
        Check check =
                bytes -> {
                    var claim = BlteChunkTable.readHeaderLength(bytes, declared);
                    if (claim.refusal() != null) {
                        return claim.refusal().code();
                    }
                    if (claim.length() == 0 || claim.length() > bytes.length) {
                        return null;
                    }
                    return refusal(
                            BlteChunkTable.prove(
                                            key,
                                            declared,
                                            Arrays.copyOf(bytes, (int) claim.length()))
                                    .refusal());
                };
        return fetch.range(key, 0, probe, declared, check, context, priority, token)
                .thenCompose(
                        bytes -> {
                            token.check();
                            requireReply(check, bytes, probe);
                            long claimed =
                                    BlteChunkTable.readHeaderLength(bytes, declared).length();
                            if (claimed == 0 || claimed > maxRangeBytes) {
                                return CompletableFuture.completedFuture(null);
                            }
                            if (claimed <= bytes.length) {
                                return CompletableFuture.completedFuture(
                                        remember(
                                                proof(
                                                        key,
                                                        declared,
                                                        Arrays.copyOf(bytes, (int) claimed))));
                            }
                            Check wholeHeader =
                                    header ->
                                            refusal(
                                                    BlteChunkTable.prove(key, declared, header)
                                                            .refusal());
                            // The first host's long-header claim is unproven. A failed second proof
                            // falls back to whole acquisition.
                            return fetch.range(
                                            key,
                                            0,
                                            (int) claimed,
                                            declared,
                                            wholeHeader,
                                            context,
                                            priority,
                                            token)
                                    .handle(
                                            (header, error) -> {
                                                if (error != null) {
                                                    Throwable cause = error;
                                                    while (cause instanceof CompletionException
                                                            && cause.getCause() != null) {
                                                        cause = cause.getCause();
                                                    }
                                                    if (cause instanceof CdnException failure
                                                            && failure.overall()
                                                                    == Failure
                                                                            .VERIFICATION_FAILED) {
                                                        return null;
                                                    }
                                                    throw new CompletionException(cause);
                                                }
                                                token.check();
                                                requireReply(wholeHeader, header, (int) claimed);
                                                return remember(proof(key, declared, header));
                                            });
                        });
    }

    private CompletableFuture<byte[]> chunks(
            BlteChunkTable table,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        long end = offset + length;
        int first =
                length > 0 && end > table.headerLength()
                        ? table.chunkAt(Math.max(offset, table.headerLength()))
                        : -1;
        int last = first < 0 ? -1 : table.chunkAt(end - 1);
        for (int i = first; i >= 0 && i <= last; i++) {
            if (table.chunkLength(i) > maxRangeBytes) {
                return CompletableFuture.completedFuture(null);
            }
        }
        byte[] output = new byte[length];
        if (offset < table.headerLength()) {
            int n = (int) (Math.min(end, table.headerLength()) - offset);
            System.arraycopy(table.header(), (int) offset, output, 0, n);
        }
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
        while (first >= 0 && first <= last) {
            int to = first;
            while (to < last && span(table, first, to + 1) <= maxRangeBytes) {
                to++;
            }
            int fromChunk = first, toChunk = to;
            long start = table.chunkOffset(first);
            int n = (int) span(table, first, to);
            Check check =
                    bytes -> {
                        for (int i = fromChunk; i <= toChunk; i++) {
                            Refusal refused =
                                    table.checkChunk(
                                            i,
                                            bytes,
                                            (int) (table.chunkOffset(i) - start),
                                            (int) table.chunkLength(i));
                            if (refused != null) {
                                return refused.code();
                            }
                        }
                        return null;
                    };
            sequence =
                    sequence.thenCompose(
                            ignored -> {
                                token.check();
                                return fetch.range(
                                                table.key(),
                                                start,
                                                n,
                                                table.declaredLength(),
                                                check,
                                                context,
                                                priority,
                                                token)
                                        .thenAccept(
                                                bytes -> {
                                                    token.check();
                                                    requireReply(check, bytes, n);
                                                    long from = Math.max(offset, start),
                                                            until =
                                                                    Math.min(
                                                                            end,
                                                                            start + bytes.length);
                                                    System.arraycopy(
                                                            bytes,
                                                            (int) (from - start),
                                                            output,
                                                            (int) (from - offset),
                                                            (int) (until - from));
                                                });
                            });
            first = to + 1;
        }
        return sequence.thenApply(
                ignored -> {
                    token.check();
                    return output;
                });
    }

    private static long span(BlteChunkTable table, int first, int last) {
        return table.chunkOffset(last) + table.chunkLength(last) - table.chunkOffset(first);
    }

    private static String refusal(Refusal refusal) {
        return refusal == null ? null : refusal.code();
    }

    private static BlteChunkTable proof(ObjectKey key, long declared, byte[] bytes) {
        var proof = BlteChunkTable.prove(key, declared, bytes);
        if (proof.refusal() != null) {
            throw new IllegalStateException("Fetcher returned bytes that failed its header check");
        }
        return proof.table();
    }

    private static void requireReply(Check check, byte[] bytes, int expected) {
        if (bytes == null || bytes.length != expected || check.refusal(bytes) != null) {
            throw new IllegalStateException(
                    "Fetcher returned an unverified or incorrectly sized range");
        }
    }

    private synchronized BlteChunkTable get(ObjectKey key, long declared) {
        return headers.get(new Identity(key, declared));
    }

    private synchronized BlteChunkTable remember(BlteChunkTable table) {
        if (table.headerLength() > MAX_HEADER_CACHE_BYTES) {
            return table;
        }
        Identity id = new Identity(table.key(), table.declaredLength());
        BlteChunkTable previous = headers.put(id, table);
        if (previous != null) {
            headerBytes -= previous.headerLength();
        }
        headerBytes += table.headerLength();
        while (headers.size() > MAX_TABLES || headerBytes > MAX_HEADER_CACHE_BYTES) {
            Identity oldest = headers.keySet().iterator().next();
            headerBytes -= headers.remove(oldest).headerLength();
        }
        return table;
    }
}
