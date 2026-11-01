package com.vibeminer.files.names;
import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;
/** Consent switch: false, null or failure disables community names. */
@FunctionalInterface
public interface FileNameSwitch { CompletableFuture<Boolean> enabled(Cancellation cancellation); }
