package com.vibeminer.files;

import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.network.CdnKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthetic immutable resolver, with counters proving cache restoration avoids file enumeration.
 */
public final class PreparedCatalogFixture implements Build {
    public final AtomicInteger enumerations = new AtomicInteger(),
            resolutions = new AtomicInteger();
    private final int count;
    private final BuildTypes.BuildRef ref =
            new BuildTypes.BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
    private final BuildTypes.BuildMetadata metadata;

    public PreparedCatalogFixture(int count) {
        this.count = count;
        metadata =
                new BuildTypes.BuildMetadata(
                        "fixture", key(1), key(2), key(3), 0, count, List.of("enUS", "deDE"));
    }

    public static CdnKey key(long id) {
        long a = mix(id), b = mix(id ^ 0x9e3779b97f4a7c15L);
        return new CdnKey(java.nio.ByteBuffer.allocate(16).putLong(a).putLong(b).array());
    }

    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return n ^ (n >>> 31);
    }

    public BuildTypes.BuildRef build() {
        return ref;
    }

    public BuildTypes.BuildMetadata metadata() {
        return metadata;
    }

    public com.vibeminer.access.SourceTypes.CdnContext cdn() {
        throw new AssertionError("Catalog touched CDN context");
    }

    public BuildTypes.FileResolution resolve(long id) {
        resolutions.incrementAndGet();
        return new BuildTypes.FileResolution(
                BuildTypes.FileResolutionState.FOUND,
                id,
                List.of(
                        new BuildTypes.FileVariant(
                                FileLocales.bitOf("enUS"),
                                0,
                                key(id),
                                id % 2 == 0 ? null : id * 10,
                                key(id + count),
                                id * 5,
                                null)));
    }

    public BuildTypes.FileResolution resolve(String path) {
        throw new AssertionError("Catalog touched community names");
    }

    public Iterable<Long> fileDataIds(String locale, long from) {
        enumerations.incrementAndGet();
        return () ->
                java.util.stream.LongStream.rangeClosed(Math.max(1, from), count)
                        .boxed()
                        .iterator();
    }

    public PreparedFileCatalog.Identity identity() {
        return new PreparedFileCatalog.Identity(ref, "enUS", metadata);
    }
}
