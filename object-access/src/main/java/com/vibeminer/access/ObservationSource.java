package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** External replies normalized without interpreting their contents. */
public interface ObservationSource {
    Set<ObservationKind> kinds();

    CompletableFuture<Observation> fetch(ObservationRequest request, Cancellation cancellation);
}
