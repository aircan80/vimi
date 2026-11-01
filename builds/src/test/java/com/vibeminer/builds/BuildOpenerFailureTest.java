package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildPart;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class BuildOpenerFailureTest {
    @Test
    void sourceFailuresKeepTheirSpecificStatePartCodeAndObservationTime() {
        for (ObjectState state :
                new ObjectState[] {
                    ObjectState.NEEDS_NETWORK,
                    ObjectState.UNAVAILABLE,
                    ObjectState.NOT_FOUND,
                    ObjectState.REFUSED,
                    ObjectState.STORE_FAILED,
                    ObjectState.UNSUPPORTED
                }) {
            SyntheticBuild source = new SyntheticBuild();
            Instant observed = Instant.parse("2026-11-01T00:00:00Z");
            source.failures.put(
                    source.encodingKey,
                    new ObjectResult(
                            source.encodingKey,
                            state,
                            null,
                            null,
                            null,
                            null,
                            "TEST_" + state,
                            "scripted",
                            observed));
            BuildOpener opener = new BuildOpener(source);
            try {
                var result = opener.open(source.ref).join();
                assertEquals(
                        switch (state) {
                            case NEEDS_NETWORK -> BuildOpenState.NEEDS_NETWORK;
                            case UNAVAILABLE -> BuildOpenState.UNAVAILABLE;
                            case NOT_FOUND -> BuildOpenState.MISSING;
                            case REFUSED -> BuildOpenState.CORRUPT;
                            case UNSUPPORTED -> BuildOpenState.UNSUPPORTED;
                            default -> BuildOpenState.STORE_FAILED;
                        },
                        result.state());
                assertEquals(BuildPart.ENCODING, result.part());
                assertEquals("TEST_" + state, result.code());
                assertEquals(observed, result.observedAt());
            } finally {
                opener.closeAsync().join();
            }
        }
    }

    @Test
    void offlineCdnCodeBecomesNeedsNetworkAndCanBeRetriedAfterFailure() {
        SyntheticBuild source = new SyntheticBuild();
        source.failures.put(
                source.buildKey,
                new ObjectResult(
                        source.buildKey,
                        ObjectState.UNAVAILABLE,
                        null,
                        null,
                        null,
                        null,
                        "CDN_OFFLINE",
                        "offline",
                        null));
        BuildOpener opener = new BuildOpener(source);
        try {
            assertEquals(BuildOpenState.NEEDS_NETWORK, opener.open(source.ref).join().state());
            source.failures.clear();
            assertEquals(BuildOpenState.OPEN, opener.open(source.ref).join().state());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void encryptedUnsupportedAndCorruptMetadataRemainDifferentStates() {
        for (int mode : new int[] {'E', 'F'}) {
            SyntheticBuild source = new SyntheticBuild(mode, false);
            BuildOpener opener = new BuildOpener(source);
            try {
                var result = opener.open(source.ref).join();
                assertEquals(
                        mode == 'E' ? BuildOpenState.ENCRYPTED : BuildOpenState.UNSUPPORTED,
                        result.state());
                assertEquals(BuildPart.ROOT, result.part());
            } finally {
                opener.closeAsync().join();
            }
        }
        SyntheticBuild source = new SyntheticBuild();
        source.objects.get(source.encodingKey)[40] ^= 1;
        BuildOpener opener = new BuildOpener(source);
        try {
            var result = opener.open(source.ref).join();
            assertEquals(BuildOpenState.CORRUPT, result.state());
            assertEquals(BuildPart.ENCODING, result.part());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void missingRootEncodingAndCorruptIndexReturnStructuredFailures() {
        SyntheticBuild missing = new SyntheticBuild('Z', true);
        BuildOpener first = new BuildOpener(missing);
        try {
            var result = first.open(missing.ref).join();
            assertEquals("ROOT_NOT_IN_ENCODING", result.code());
            assertEquals(BuildPart.ROOT, result.part());
        } finally {
            first.closeAsync().join();
        }
        SyntheticBuild source = new SyntheticBuild();
        source.objects.get(source.archiveIndexKey)[0] ^= 1;
        BuildOpener opener = new BuildOpener(source);
        try {
            var result = opener.open(source.ref).join();
            assertEquals(BuildOpenState.CORRUPT, result.state());
            assertEquals(BuildPart.ARCHIVE_INDEX, result.part());
            assertEquals("ARCHIVE_INDEX_INTEGRITY", result.code());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void metadataCanBeReadInBoundedRangesAndLimitsRefuseBeforeLargeAllocation() {
        SyntheticBuild source = new SyntheticBuild();
        source.rangesOnly = true;
        BuildOpener opener =
                new BuildOpener(
                        source, new BuildOpener.Options(4, 2, 512L << 20, 1L << 30, 32, 2, 64));
        try {
            assertEquals(BuildOpenState.OPEN, opener.open(source.ref).join().state());
            assertTrue(source.ranges.get() > 10);
        } finally {
            opener.closeAsync().join();
        }
        BuildOpener small =
                new BuildOpener(source, new BuildOpener.Options(4, 2, 32, 1L << 30, 32, 2, 64));
        try {
            var result = small.open(source.ref).join();
            assertEquals(BuildOpenState.UNSUPPORTED, result.state());
            assertEquals("BUILD_METADATA_TOO_LARGE", result.code());
        } finally {
            small.closeAsync().join();
        }
    }
}
