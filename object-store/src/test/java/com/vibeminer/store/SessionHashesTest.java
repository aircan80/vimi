package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class SessionHashesTest {
    private final Object incarnation = new Object();

    private SessionHashes.Identity identity(int pack, long offset) {
        return SessionHashes.Identity.of(
                incarnation,
                new StoreIndex.Row(
                        StoreAssertions.key(new byte[] {1}), pack, offset, 1, new byte[32]));
    }

    @Test
    void successfulProofIsReusedAndDefaultLimitIs1048576() {
        SessionHashes hashes = new SessionHashes();
        var key = identity(1, 64);
        var check = hashes.begin(key);
        assertNotNull(check);
        hashes.verified(check);
        assertNull(hashes.begin(key));
        assertEquals(1_048_576, SessionHashes.CAPACITY);
    }

    @Test
    void newIdentityAtCapacityClearsTheSetWithoutEvictingForDuplicateProofs() {
        SessionHashes hashes = new SessionHashes(2);
        var first = identity(1, 64);
        var firstCheck = hashes.begin(first);
        hashes.verified(firstCheck);
        hashes.verified(hashes.begin(identity(1, 165)));
        hashes.verified(firstCheck);
        assertEquals(2, hashes.size());
        assertNull(hashes.begin(first));
        var last = identity(2, 64);
        hashes.verified(hashes.begin(last));
        assertEquals(1, hashes.size());
        assertNull(hashes.begin(last));
        assertNotNull(hashes.begin(first));
    }

    @Test
    void clearRejectsAnOldInFlightProof() {
        SessionHashes hashes = new SessionHashes();
        var key = identity(1, 64);
        var old = hashes.begin(key);
        hashes.clear();
        hashes.verified(old);
        assertEquals(0, hashes.size());
        assertNotNull(hashes.begin(key));
    }

    @Test
    void channelLocationLengthKeyAndExpectedShaAllParticipateInIdentity() {
        SessionHashes hashes = new SessionHashes();
        var key = StoreAssertions.key(new byte[] {1});
        byte[] sha = new byte[32];
        var original =
                SessionHashes.Identity.of(incarnation, new StoreIndex.Row(key, 1, 64, 1, sha));
        hashes.verified(hashes.begin(original));
        assertNotNull(
                hashes.begin(
                        SessionHashes.Identity.of(
                                new Object(), new StoreIndex.Row(key, 1, 64, 1, sha))));
        assertNotNull(hashes.begin(identity(2, 64)));
        assertNotNull(hashes.begin(identity(1, 65)));
        assertNotNull(
                hashes.begin(
                        SessionHashes.Identity.of(
                                incarnation, new StoreIndex.Row(key, 1, 64, 2, sha))));
        assertNotNull(
                hashes.begin(
                        SessionHashes.Identity.of(
                                incarnation,
                                new StoreIndex.Row(
                                        StoreAssertions.key(new byte[] {2}), 1, 64, 1, sha))));
        sha[31] = 1;
        assertNotNull(
                hashes.begin(
                        SessionHashes.Identity.of(
                                incarnation, new StoreIndex.Row(key, 1, 64, 1, sha))));
        assertNull(hashes.begin(original));
    }

    @Test
    void concurrentProofsAndClearsRemainBounded() {
        SessionHashes hashes = new SessionHashes(8);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            int pack = worker + 1;
            CompletableFuture<Void> done = new CompletableFuture<>();
            tasks.add(done);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    for (int i = 0; i < 100; i++) {
                                        var check = hashes.begin(identity(pack, 64 + i));
                                        if (check != null) {
                                            hashes.verified(check);
                                        }
                                        if (i % 17 == 0) {
                                            hashes.clear();
                                        }
                                    }
                                    done.complete(null);
                                } catch (Throwable error) {
                                    done.completeExceptionally(error);
                                }
                            });
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        assertTrue(hashes.size() <= 8);
    }
}
