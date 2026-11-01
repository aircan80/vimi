package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.RangePlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Build boundary contract run against the real opener and reusable memory fake. */
@Timeout(20)
public abstract class BuildsContract {
    protected SyntheticBuild source;
    protected Builds builds;

    protected abstract boolean real();

    @BeforeEach
    final void setup() {
        source = new SyntheticBuild();
        if (real()) {
            builds = new BuildOpener(source);
        } else {
            MemoryBuilds memory = new MemoryBuilds();
            memory.add(expected(source));
            builds = memory;
        }
    }

    @AfterEach
    final void close() {
        if (builds instanceof BuildOpener opener) {
            opener.closeAsync().join();
        } else {
            ((MemoryBuilds) builds).closeAsync().join();
        }
    }

    public static MemoryBuilds.Opened expected(SyntheticBuild source) {
        CdnKey rootContent = new CdnKey(MetadataFixtures.md5(source.rootBytes)),
                encodingContent = new CdnKey(MetadataFixtures.md5(source.encodingBytes));
        BuildMetadata metadata =
                new BuildMetadata(
                        "WOW-1000patch1.0.0_Synthetic",
                        rootContent,
                        encodingContent,
                        new CdnKey(source.encodingKey.bytes()),
                        1,
                        2,
                        List.of("enUS", "deDE"));
        FileVariant a =
                new FileVariant(
                        2,
                        0,
                        source.aContent,
                        12L,
                        source.aEncoded,
                        123L,
                        new RangePlanner.Location(
                                new CdnKey(source.archiveIndexKey.bytes()), 42, 123));
        FileVariant b = new FileVariant(32, 8, source.bContent, 34L, source.bEncoded, 321L, null);
        FileVariant missing = new FileVariant(2, 0, source.missingContent, null, null, null, null);
        return new MemoryBuilds.Opened(
                source.ref,
                metadata,
                null,
                Map.of(100L, List.of(a, b), 200L, List.of(missing)),
                Map.of("interface/icons/one.blp", 100L, "world/missing.m2", 200L),
                Map.of(
                        source.aEncoded,
                        123L,
                        source.bEncoded,
                        321L,
                        new CdnKey(source.rootKey.bytes()),
                        (long) source.objects.get(source.rootKey).length));
    }

    @Test
    void metadataAndAllLocaleVariantsAreExposedWithoutFlatteningStates() {
        var opened = builds.open(source.ref).join();
        assertEquals(BuildOpenState.OPEN, opened.state());
        Build build = opened.opened();
        assertEquals(source.ref, build.build());
        assertEquals(2, build.metadata().fileCount());
        assertEquals(List.of("enUS", "deDE"), build.metadata().locales());
        var file = build.resolve(100);
        assertEquals(FileResolutionState.FOUND, file.state());
        assertEquals(2, file.variants().size());
        assertEquals(2, file.variants().get(0).localeFlags());
        assertEquals(32, file.variants().get(1).localeFlags());
        assertEquals(42, file.variants().getFirst().location().offset());
        assertEquals(321L, file.variants().get(1).encodedSize());
        assertEquals(FileResolutionState.NOT_IN_ENCODING, build.resolve(200).state());
        assertEquals(FileResolutionState.UNKNOWN_FILE, build.resolve(999).state());
    }

    @Test
    void pathHashIgnoresCaseAndSlashDirectionAndLookupsNeverFetch() {
        Build build = builds.open(source.ref).join().opened();
        int before = source.reads.get();
        assertEquals(build.resolve(100), build.resolve("INTERFACE\\ICONS\\ONE.BLP"));
        assertEquals(FileResolutionState.UNKNOWN_FILE, build.resolve("unknown/file.blp").state());
        assertEquals(before, source.reads.get());
    }

    @Test
    void localeEnumerationIsSortedDistinctAndStartsAtRequestedId() {
        Build build = builds.open(source.ref).join().opened();
        assertEquals(List.of(100L, 200L), list(build.fileDataIds("enUS")));
        assertEquals(List.of(100L), list(build.fileDataIds("deDE")));
        assertEquals(List.of(200L), list(build.fileDataIds("enUS", 101)));
        assertThrows(IllegalArgumentException.class, () -> build.fileDataIds("unknown"));
        assertThrows(IllegalArgumentException.class, () -> build.resolve(-1));
    }

    @Test
    void openedBuildsAreReusedAndConcurrentCallersShareOneInstance() {
        var first = builds.open(source.ref);
        var second = builds.open(source.ref);
        assertSame(first.join().opened(), second.join().opened());
        assertSame(first.join().opened(), builds.open(source.ref).join().opened());
    }

    @Test
    void canceledCallDoesNotPoisonSubsequentOpen() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        assertTrue(builds.open(source.ref, canceled).isCompletedExceptionally());
        assertEquals(BuildOpenState.OPEN, builds.open(source.ref).join().state());
    }

    @Test
    void ledgerUsesEncodingSizesAndOwnsItsKeys() {
        Build build = builds.open(source.ref).join().opened();
        ObjectLedger ledger = ((LedgerSource) build).ledger();
        assertEquals(3, ledger.count());
        assertEquals(123, ledger.size(ledger.indexOf(source.aEncoded)));
        assertEquals(321, ledger.size(ledger.indexOf(source.bEncoded)));
        assertEquals(444 + source.objects.get(source.rootKey).length, ledger.totalBytes());
    }

    private static List<Long> list(Iterable<Long> ids) {
        List<Long> result = new ArrayList<>();
        ids.forEach(result::add);
        return result;
    }
}
