package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.PutResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface HistoryLog extends HistoryReader {
    CompletableFuture<List<PutResult>> appendHistory(
            History.Observation observation, Cancellation cancellation);
}
