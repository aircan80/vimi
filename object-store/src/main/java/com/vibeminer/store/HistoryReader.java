package com.vibeminer.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.History.*;

public interface HistoryReader {
    CompletableFuture<List<Entry>> readHistory(Query query, Cancellation cancellation);
}
