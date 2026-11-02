package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenResult;
import com.vibeminer.files.names.FileNames;
import java.util.concurrent.CompletableFuture;

/** Layer four boundary: one file-system handle per build and locale. */
public interface Files {
    CompletableFuture<FilesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation cancellation);

    FileNames names();
}
