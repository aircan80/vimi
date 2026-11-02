package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

public interface FileNames {
    CompletableFuture<NamesTypes.FileNamesStatus> status(Cancellation cancellation);

    CompletableFuture<NamesTypes.FileNamesStatus> update(boolean force, Cancellation cancellation);

    CompletableFuture<LoadedNames> current(Cancellation cancellation);

    FileNames OFF =
            new FileNames() {
                public CompletableFuture<NamesTypes.FileNamesStatus> status(Cancellation c) {
                    c.check();
                    return CompletableFuture.completedFuture(
                            new NamesTypes.FileNamesStatus(NamesTypes.FileNamesState.OFF));
                }

                public CompletableFuture<NamesTypes.FileNamesStatus> update(
                        boolean force, Cancellation c) {
                    return status(c);
                }

                public CompletableFuture<LoadedNames> current(Cancellation c) {
                    c.check();
                    return CompletableFuture.completedFuture(null);
                }
            };
}
