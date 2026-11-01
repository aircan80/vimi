package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.CompactReport;
import com.vibeminer.store.StoreTypes.ScanReport;
import com.vibeminer.store.StoreTypes.ScrubReport;
import com.vibeminer.store.StoreTypes.VerifyReport;
import java.util.concurrent.CompletableFuture;

public interface StoreMaintenance {
    CompletableFuture<ScanReport> scan(Cancellation cancellation);

    CompletableFuture<VerifyReport> verify(boolean full, Cancellation cancellation);

    CompletableFuture<CompactReport> compact(
            double minDeadFraction, int maxPacks, Cancellation cancellation);

    CompletableFuture<ScrubReport> scrub(boolean full, Cancellation cancellation);
}
