package com.vibeminer.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.StoreTypes.PutResult;

public interface HistoryLog extends HistoryReader {
    CompletableFuture<List<PutResult>> appendHistory(History.Observation observation, Cancellation cancellation);
}
