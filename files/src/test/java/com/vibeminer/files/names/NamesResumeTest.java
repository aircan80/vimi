package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.MemoryObjectSource;
import com.vibeminer.access.ObservationSource;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.History;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
final class NamesResumeTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String TAG = "resume-v1";
    private static final String PATH = "dir/" + "a".repeat(1020);
    private static final int ROWS = 8300;

    @Test
    void restartedEngineReusesPersistedFirstPartAfterSecondFetchIsCanceled() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int id = 1; id <= ROWS; id++) {
            out.writeBytes((id + ";" + PATH + "\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] csv = out.toByteArray();
        assertTrue(csv.length > SourceUrls.PART_BYTES);
        assertTrue(csv.length < 2L * SourceUrls.PART_BYTES);
        String sha = HexFormat.of().formatHex(ListfileStore.digest().digest(csv));
        ListfileRelease release = new ListfileRelease(TAG, NOW, sha, csv.length);
        MemoryGeneratedStore generated = new MemoryGeneratedStore();
        List<com.vibeminer.generated.GeneratedKey> manifests = ListfileStore.manifestKeys(TAG, sha);
        int[] partCalls = new int[2];
        int[] releaseCalls = new int[1];
        boolean[] cancelSecondPart = {true};
        int[] unpublishedChecks = new int[1];
        MemoryObjectSource[] sourceHolder = new MemoryObjectSource[1];
        ObservationSource remote =
                new ObservationSource() {
                    @Override
                    public Set<ObservationKind> kinds() {
                        return Set.of(
                                ObservationKind.LISTFILE_RELEASE, ObservationKind.LISTFILE_PART);
                    }

                    @Override
                    public CompletableFuture<Observation> fetch(
                            ObservationRequest request, Cancellation token) {
                        token.check();
                        if (request.kind() == ObservationKind.LISTFILE_RELEASE) {
                            releaseCalls[0]++;
                            String json =
                                    "{\"tag_name\":\""
                                            + TAG
                                            + "\",\"published_at\":\"2026-11-01T00:00:00Z\",\"assets\":[{\"name\":\""
                                            + SourceUrls.ASSET_NAME
                                            + "\",\"size\":"
                                            + csv.length
                                            + ",\"digest\":\"sha256:"
                                            + sha
                                            + "\",\"browser_download_url\":\""
                                            + SourceUrls.asset(TAG)
                                            + "\"}]}";
                            return CompletableFuture.completedFuture(
                                    new Observation(
                                            request.kind(),
                                            "wowlistfile",
                                            "latest",
                                            SourceUrls.LATEST_RELEASE,
                                            NOW.plusSeconds(releaseCalls[0]),
                                            json.getBytes(StandardCharsets.UTF_8)));
                        }
                        int part = ObservationRequest.partIndex(request.region());
                        partCalls[part]++;
                        if (part == 1) {
                            assertTrue(
                                    generated.has(CommunityFileNames.stagedKey(release, 0)).join());
                            for (var manifest : manifests) {
                                assertFalse(generated.has(manifest).join());
                            }
                            assertTrue(
                                    sourceHolder[0]
                                            .readHistory(
                                                    new History.Query(
                                                            TAG,
                                                            null,
                                                            ObservationKind.LISTFILE_PART
                                                                    .historyKind(),
                                                            null,
                                                            1000),
                                                    Cancellation.NONE)
                                            .join()
                                            .isEmpty());
                            unpublishedChecks[0]++;
                            if (cancelSecondPart[0]) {
                                token.cancel();
                                return CompletableFuture.failedFuture(new CancellationException());
                            }
                        }
                        int first = part * SourceUrls.PART_BYTES;
                        byte[] bytes =
                                Arrays.copyOfRange(
                                        csv,
                                        first,
                                        Math.min(csv.length, first + SourceUrls.PART_BYTES));
                        return CompletableFuture.completedFuture(
                                new Observation(
                                        request.kind(),
                                        TAG,
                                        request.region(),
                                        SourceUrls.asset(TAG),
                                        NOW.plusSeconds(10 + part),
                                        bytes));
                    }
                };
        MemoryObjectSource source = new MemoryObjectSource(List.of(remote));
        sourceHolder[0] = source;
        Clock clock = Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC);
        CommunityFileNames first =
                new CommunityFileNames(
                        source, generated, token -> CompletableFuture.completedFuture(true), clock);
        Cancellation canceled = new Cancellation();
        assertThrows(CancellationException.class, () -> first.update(true, canceled).join());
        assertEquals(1, partCalls[0]);
        assertEquals(1, partCalls[1]);
        assertTrue(generated.has(CommunityFileNames.stagedKey(release, 0)).join());
        assertFalse(generated.has(CommunityFileNames.stagedKey(release, 1)).join());
        assertEquals(
                NamesTypes.FileNamesState.NOT_READY,
                first.status(Cancellation.NONE).join().state());

        cancelSecondPart[0] = false;
        CommunityFileNames restarted =
                new CommunityFileNames(
                        source, generated, token -> CompletableFuture.completedFuture(true), clock);
        assertEquals(
                NamesTypes.FileNamesState.NOT_READY,
                restarted.status(Cancellation.NONE).join().state());
        NamesTypes.FileNamesStatus completed = restarted.update(true, Cancellation.NONE).join();
        assertEquals(NamesTypes.FileNamesState.READY, completed.state());
        assertEquals(ROWS, completed.count());
        assertEquals(1, partCalls[0]);
        assertEquals(2, partCalls[1]);
        assertEquals(2, unpublishedChecks[0]);
        assertEquals(
                2,
                source.readHistory(
                                new History.Query(
                                        TAG,
                                        null,
                                        ObservationKind.LISTFILE_PART.historyKind(),
                                        null,
                                        1000),
                                Cancellation.NONE)
                        .join()
                        .size());
        for (var manifest : manifests) {
            assertTrue(generated.has(manifest).join());
        }
        assertFalse(generated.has(CommunityFileNames.stagedKey(release, 0)).join());
        assertFalse(generated.has(CommunityFileNames.stagedKey(release, 1)).join());
        assertEquals(
                PATH,
                restarted.current(Cancellation.NONE).join().nameOf(ROWS, Cancellation.NONE).join());
    }
}
