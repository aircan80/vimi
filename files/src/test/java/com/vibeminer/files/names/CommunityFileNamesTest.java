package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.MemoryObjectSource;
import com.vibeminer.access.ObservationSource;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.History;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class CommunityFileNamesTest {
    static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    static final class Remote implements ObservationSource {
        String tag = "v1";
        byte[] csv = "1;A.blp\n9;B.m2\n".getBytes(StandardCharsets.UTF_8);
        byte[] sent;
        boolean fail;
        int releases, parts;
        int sequence;

        public Set<ObservationKind> kinds() {
            return Set.of(ObservationKind.LISTFILE_RELEASE, ObservationKind.LISTFILE_PART);
        }

        public CompletableFuture<Observation> fetch(ObservationRequest r, Cancellation c) {
            c.check();
            if (fail) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Synthetic failure"));
            }
            Instant at = NOW.plusSeconds(sequence++);
            if (r.kind() == ObservationKind.LISTFILE_RELEASE) {
                releases++;
                String json =
                        "{\"tag_name\":\""
                                + tag
                                + "\",\"published_at\":\"2026-10-31T00:00:00Z\",\"assets\":[{\"name\":\""
                                + SourceUrls.ASSET_NAME
                                + "\",\"size\":"
                                + csv.length
                                + ",\"digest\":\"sha256:"
                                + ListfileDeltaTest.sha(csv)
                                + "\",\"browser_download_url\":\""
                                + SourceUrls.asset(tag)
                                + "\"}]}";
                return CompletableFuture.completedFuture(
                        new Observation(
                                r.kind(),
                                "wowlistfile",
                                "latest",
                                SourceUrls.LATEST_RELEASE,
                                at,
                                json.getBytes(StandardCharsets.UTF_8)));
            }
            parts++;
            return CompletableFuture.completedFuture(
                    new Observation(
                            r.kind(),
                            tag,
                            r.region(),
                            SourceUrls.asset(tag),
                            at,
                            sent == null ? csv : sent));
        }
    }

    static CommunityFileNames names(MemoryObjectSource source, MemoryGeneratedStore store) {
        return new CommunityFileNames(
                source,
                store,
                c -> CompletableFuture.completedFuture(true),
                Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));
    }

    @Test
    void consentFailsClosedWithoutNetwork() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        for (FileNameSwitch toggle :
                Arrays.<FileNameSwitch>asList(
                        null,
                        c -> null,
                        c -> CompletableFuture.completedFuture(false),
                        c -> {
                            throw new IllegalStateException();
                        })) {
            CommunityFileNames names = new CommunityFileNames(source, store, toggle);
            assertEquals(
                    NamesTypes.FileNamesState.OFF,
                    names.update(true, Cancellation.NONE).join().state());
            assertNull(names.current(Cancellation.NONE).join());
        }
        assertEquals(0, remote.releases);
    }

    @Test
    void updateKeepsHistoryBeforeIndexAndRebuildsAfterGeneratedReset() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        assertEquals(
                NamesTypes.FileNamesState.NOT_READY,
                names.status(Cancellation.NONE).join().state());
        assertEquals(
                NamesTypes.FileNamesState.READY,
                names.update(false, Cancellation.NONE).join().state());
        assertEquals(
                "A.blp",
                names.current(Cancellation.NONE).join().nameOf(1, Cancellation.NONE).join());
        assertEquals(1, remote.parts);
        assertEquals(
                1,
                source.readHistory(
                                new History.Query(
                                        null,
                                        null,
                                        ObservationKind.LISTFILE_PART.historyKind(),
                                        null,
                                        1000),
                                Cancellation.NONE)
                        .join()
                        .size());
        assertEquals(
                NamesTypes.FileNamesState.READY,
                names.update(false, Cancellation.NONE).join().state());
        assertEquals(1, remote.releases);
        store.clear();
        CommunityFileNames restarted = names(source, store);
        assertEquals(
                NamesTypes.FileNamesState.READY,
                restarted.status(Cancellation.NONE).join().state());
        assertEquals(
                "B.m2",
                restarted.current(Cancellation.NONE).join().nameOf(9, Cancellation.NONE).join());
        assertEquals(1, remote.parts);
        assertEquals(1, remote.releases);
    }

    @Test
    void secondReleaseUsesDeltaAndOfflineReplayRebuild() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        names.update(true, Cancellation.NONE).join();
        remote.tag = "v2";
        remote.csv = "1;A.blp\n9;C.m2\n10;D.skin\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("v2", names.update(true, Cancellation.NONE).join().tag());
        assertEquals(
                1,
                source.readHistory(
                                new History.Query("v2", "delta", 8, null, 1000), Cancellation.NONE)
                        .join()
                        .size());
        store.clear();
        CommunityFileNames restarted = names(source, store);
        assertEquals("v2", restarted.status(Cancellation.NONE).join().tag());
        assertEquals(
                "C.m2",
                restarted.current(Cancellation.NONE).join().nameOf(9, Cancellation.NONE).join());
        assertEquals(2, remote.parts);
    }

    @Test
    void identicalNewTagNeverDownloadsAgain() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        names.update(true, Cancellation.NONE).join();
        remote.tag = "v2";
        assertEquals("v2", names.update(true, Cancellation.NONE).join().tag());
        assertEquals(1, remote.parts);
        store.clear();
        assertEquals("v2", names(source, store).status(Cancellation.NONE).join().tag());
    }

    @Test
    void failedRefreshKeepsOldCopyAndBacksOff() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        names.update(true, Cancellation.NONE).join();
        remote.fail = true;
        NamesTypes.FileNamesStatus failed = names.update(true, Cancellation.NONE).join();
        assertEquals(NamesTypes.FileNamesState.READY, failed.state());
        assertEquals("LISTFILE_FETCH_FAILED", failed.code());
        assertEquals(
                "A.blp",
                names.current(Cancellation.NONE).join().nameOf(1, Cancellation.NONE).join());
        names.update(false, Cancellation.NONE).join();
    }

    @Test
    void refusedBytesRecheckOnceThenDoNotRefetchUntilForced() {
        Remote remote = new Remote();
        remote.sent = "1;X.blp\n9;B.m2\n".getBytes(StandardCharsets.UTF_8);
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        assertEquals("LISTFILE_INVALID", names.update(true, Cancellation.NONE).join().code());
        assertEquals(2, remote.parts);
        names.update(false, Cancellation.NONE).join();
        assertEquals(2, remote.parts);
        remote.sent = null;
        assertEquals(
                NamesTypes.FileNamesState.READY,
                names.update(true, Cancellation.NONE).join().state());
    }

    @Test
    void invalidVerifiedCsvPublishesNoIndex() {
        Remote remote = new Remote();
        remote.csv = "2;a\n1;b".getBytes(StandardCharsets.UTF_8);
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        assertEquals(
                "LISTFILE_INVALID",
                names(source, store).update(true, Cancellation.NONE).join().code());
        assertEquals(1, remote.parts);
        assertEquals(
                0,
                source.readHistory(new History.Query(null, null, 7, null, 1000), Cancellation.NONE)
                        .join()
                        .size());
    }

    @Test
    void canceledCallerIsNotRecordedAsFailedAttempt() {
        Cancellation token = new Cancellation();
        token.cancel();
        CommunityFileNames names = names(new MemoryObjectSource(), new MemoryGeneratedStore());
        assertThrows(CancellationException.class, () -> names.update(true, token).join());
    }

    @Test
    void damagedIndexRepairsFromHeldHistory() {
        Remote remote = new Remote();
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        CommunityFileNames names = names(source, store);
        names.update(true, Cancellation.NONE).join();
        ListfileStore index = (ListfileStore) names.current(Cancellation.NONE).join();
        GeneratedKey segment =
                index.keys().stream()
                        .filter(
                                k ->
                                        !ListfileStore.manifestKeys(
                                                        index.release().tag(),
                                                        index.release().sha256())
                                                .contains(k))
                        .findFirst()
                        .orElseThrow();
        store.damage(segment);
        assertNull(index.nameOf(1, Cancellation.NONE).join());
        LoadedNames fixed = names.current(Cancellation.NONE).join();
        assertNotSame(index, fixed);
        assertEquals("A.blp", fixed.nameOf(1, Cancellation.NONE).join());
        assertEquals(1, remote.parts);
    }

    @Test
    void releaseIdentityBoundsAndJsonDepth() {
        for (String json : List.of("{}", "{\"tag_name\":\"BAD\"}", "[]", "null")) {
            assertThrows(
                    ListfileFetchException.class,
                    () -> ListfileRelease.parse(json.getBytes(StandardCharsets.UTF_8), NOW));
        }
    }
}
