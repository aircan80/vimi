package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;

/** Build/locale client table service; opening reads no table bytes. */
public interface Tables {
    CompletableFuture<TableTypes.OpenResult> forBuild(
            BuildRef build, String locale, Cancellation token);
}
