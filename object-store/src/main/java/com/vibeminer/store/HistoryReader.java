package com.vibeminer.store;

import com.vibeminer.store.History.Entry;
import com.vibeminer.store.History.Query;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface HistoryReader {
    CompletableFuture<List<Entry>> readHistory(Query query, Cancellation cancellation);
}
