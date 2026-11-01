package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Origin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Complete synthetic offline build over a scripted layer2 boundary. No store/network implementation
 * dependency.
 */
public final class SyntheticBuild implements ObjectSource {
    public final CdnKey aContent = MetadataFixtures.key(101),
            bContent = MetadataFixtures.key(102),
            missingContent = MetadataFixtures.key(103),
            aEncoded = MetadataFixtures.key(201),
            bEncoded = MetadataFixtures.key(202);
    public final BuildRef ref;
    public final ObjectKey buildKey, cdnKey, encodingKey, rootKey, archiveIndexKey, fileIndexKey;
    public final Map<ObjectKey, byte[]> objects = new HashMap<>();
    public final Map<ObjectKey, ObjectResult> failures = new HashMap<>();
    public final AtomicInteger reads = new AtomicInteger(), ranges = new AtomicInteger();
    public volatile boolean rangesOnly;
    public final byte[] rootBytes, encodingBytes;

    public SyntheticBuild() {
        this('Z', false);
    }

    public SyntheticBuild(int rootMode, boolean rootAbsent) {
        rootBytes =
                MetadataFixtures.root(
                        2,
                        List.of(
                                new MetadataFixtures.RootBlock(
                                        2,
                                        0,
                                        0,
                                        0,
                                        List.of(
                                                new MetadataFixtures.RootRow(
                                                        100, aContent, "interface/icons/one.blp"),
                                                new MetadataFixtures.RootRow(
                                                        200, missingContent, "world/missing.m2"))),
                                new MetadataFixtures.RootBlock(
                                        32,
                                        8,
                                        0,
                                        0,
                                        List.of(
                                                new MetadataFixtures.RootRow(
                                                        100,
                                                        bContent,
                                                        "interface/icons/one.blp")))));
        var root = MetadataFixtures.chunked(rootBytes, rootMode);
        rootKey = new ObjectKey(ObjectKey.ENCODED, root.encodedKey().bytes());
        objects.put(rootKey, root.encoded());
        List<MetadataFixtures.EncodingRow> rows = new ArrayList<>();
        rows.add(new MetadataFixtures.EncodingRow(aContent, 12, aEncoded, 123));
        rows.add(new MetadataFixtures.EncodingRow(bContent, 34, bEncoded, 321));
        if (!rootAbsent) {
            rows.add(
                    new MetadataFixtures.EncodingRow(
                            root.contentKey(),
                            rootBytes.length,
                            root.encodedKey(),
                            root.encoded().length));
        }
        encodingBytes = MetadataFixtures.encoding(rows);
        var encoding = MetadataFixtures.chunked(encodingBytes, 'Z');
        encodingKey = new ObjectKey(ObjectKey.ENCODED, encoding.encodedKey().bytes());
        objects.put(encodingKey, encoding.encoded());
        var archive =
                MetadataFixtures.index(
                        List.of(new MetadataFixtures.IndexRow(aEncoded, 123, 42)), false);
        archiveIndexKey = new ObjectKey(ObjectKey.ARCHIVE_INDEX, archive.key().bytes());
        objects.put(archiveIndexKey, archive.bytes());
        var loose =
                MetadataFixtures.index(
                        List.of(new MetadataFixtures.IndexRow(bEncoded, 321, 0)), true);
        fileIndexKey = new ObjectKey(ObjectKey.FILE_INDEX, loose.key().bytes());
        objects.put(fileIndexKey, loose.bytes());
        byte[] build =
                MetadataFixtures.text(
                        "root = "
                                + root.contentKey().hex()
                                + "\ninstall = "
                                + MetadataFixtures.key(1).hex()
                                + "\nencoding = "
                                + encoding.contentKey().hex()
                                + " "
                                + encoding.encodedKey().hex()
                                + "\nencoding-size = "
                                + encodingBytes.length
                                + " "
                                + encoding.encoded().length
                                + "\nbuild-name = WOW-1000patch1.0.0_Synthetic\n");
        byte[] cdn =
                MetadataFixtures.text(
                        "archives = "
                                + archive.key().hex()
                                + "\nfile-index = "
                                + loose.key().hex()
                                + "\n");
        buildKey = new ObjectKey(ObjectKey.CONFIG, MetadataFixtures.md5(build));
        cdnKey = new ObjectKey(ObjectKey.CONFIG, MetadataFixtures.md5(cdn));
        objects.put(buildKey, build);
        objects.put(cdnKey, cdn);
        ref = new BuildRef("wow", buildKey.hex(), cdnKey.hex());
    }

    private ObjectResult result(ObjectRequest request, boolean bytes) {
        ObjectResult scripted = failures.get(request.key());
        if (scripted != null) {
            return scripted;
        }
        byte[] body = objects.get(request.key());
        return body == null
                ? new ObjectResult(
                        request.key(),
                        ObjectState.NEEDS_NETWORK,
                        null,
                        null,
                        null,
                        null,
                        "OBJECT_ACCESS_NEEDS_NETWORK",
                        "Not stored",
                        null)
                : new ObjectResult(
                        request.key(),
                        ObjectState.PRESENT,
                        SourceTypes.ServedFrom.STORE,
                        Origin.UNKNOWN,
                        (long) body.length,
                        bytes ? body : null,
                        null,
                        null,
                        null);
    }

    @Override
    public CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext cdn, FetchPriority priority, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    reads.incrementAndGet();
                    return result(request, !rangesOnly);
                });
    }

    @Override
    public CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext cdn,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return call(
                () -> {
                    token.check();
                    return requests.stream().map(r -> result(r, false)).toList();
                });
    }

    @Override
    public CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext cdn,
            FetchPriority priority,
            Cancellation token) {
        return call(
                () -> {
                    token.check();
                    ranges.incrementAndGet();
                    ObjectResult whole = result(request, true);
                    if (!whole.isPresent()) {
                        return whole;
                    }
                    byte[] body = whole.bytes();
                    return new ObjectResult(
                            request.key(),
                            ObjectState.PRESENT,
                            SourceTypes.ServedFrom.STORE,
                            Origin.UNKNOWN,
                            (long) body.length,
                            Arrays.copyOfRange(body, (int) offset, (int) offset + length),
                            null,
                            null,
                            null);
                });
    }

    @Override
    public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    return keys.stream().map(objects::containsKey).toList();
                });
    }

    @Override
    public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
        return hasKeys(keys, token)
                .thenApply(found -> found.stream().filter(Boolean::booleanValue).count());
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    return List.of();
                });
    }

    @Override
    public CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Fixture has no observations"));
    }

    @Override
    public CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Fixture has no observations"));
    }

    private static <T> CompletableFuture<T> call(java.util.function.Supplier<T> action) {
        try {
            return CompletableFuture.completedFuture(action.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
