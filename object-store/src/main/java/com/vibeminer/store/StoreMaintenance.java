package com.vibeminer.store;

import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.StoreTypes.*;

public interface StoreMaintenance {
    CompletableFuture<ScanReport> scan(Cancellation cancellation);
    CompletableFuture<VerifyReport> verify(boolean full, Cancellation cancellation);
    CompletableFuture<CompactReport> compact(double minDeadFraction, int maxPacks, Cancellation cancellation);
    CompletableFuture<ScrubReport> scrub(boolean full, Cancellation cancellation);
}
