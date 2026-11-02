package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AsyncIconRequestsTest {
    private AsyncIconRequestsTest() {}

    public static void main(String[] args) throws Exception {
        Map<Long, CompletableFuture<BlpImageTypes.Result>> calls = new ConcurrentHashMap<>();
        Map<Long, Cancellation> tokens = new ConcurrentHashMap<>();
        var first = new CountDownLatch(4);
        var fifth = new CountDownLatch(1);
        var loader =
                new AsyncIconRequests(
                        4,
                        8,
                        (id, token) -> {
                            var future = new CompletableFuture<BlpImageTypes.Result>();
                            calls.put(id, future);
                            tokens.put(id, token);
                            first.countDown();
                            if (id == 5) fifth.countDown();
                            return future;
                        });
        var one = loader.request(1);
        var duplicate = loader.request(1);
        for (long id = 2; id <= 6; id++) loader.request(id);
        require(first.await(5, TimeUnit.SECONDS), "Four requests start");
        require(calls.size() == 4 && !calls.containsKey(5L), "Parallel request bound");
        var failed =
                new BlpImageTypes.Result(
                        BlpImageTypes.State.FILE_FAILED,
                        null,
                        BlpImageTypes.Cache.NONE,
                        java.util.List.of(),
                        null,
                        "FIXTURE",
                        null,
                        null);
        calls.get(1L).complete(failed);
        one.get(5, TimeUnit.SECONDS);
        duplicate.get(5, TimeUnit.SECONDS);
        require(fifth.await(5, TimeUnit.SECONDS), "Queue progresses on completion");
        require(calls.size() == 5, "Shared FileDataID request is coalesced");
        loader.retain(Set.of(6L));
        require(tokens.get(2L).isCanceled(), "Obsolete page cancels active work");
        require(
                !calls.containsKey(6L),
                "Canceled active work keeps its admission slot until retired");
        loader.close();
        require(loader.request(7).isCompletedExceptionally(), "Closed loader rejects work");
        for (var future : calls.values()) future.complete(failed);
        require(!calls.containsKey(6L), "Closed loader never starts queued work");
        System.out.println(
                "Passed bounded page icon acquisition, coalescing, queueing, cancellation and close");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
