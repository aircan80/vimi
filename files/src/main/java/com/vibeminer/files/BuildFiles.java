package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureProgress;
import com.vibeminer.files.FileTypes.EnsureResult;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** One build and locale. Methods may run concurrently; close rejects new operations. */
public interface BuildFiles extends AutoCloseable {
    BuildRef build();

    String locale();

    AsyncCursor<BuildFile> list(FileFilter filter, Cancellation cancellation);

    CompletableFuture<FileStat> stat(FileRef file, Cancellation cancellation);

    CompletableFuture<Boolean> exists(FileRef file, Cancellation cancellation);

    CompletableFuture<FileRead> get(FileRef file, Cancellation cancellation);

    CompletableFuture<FileRead> readRange(
            FileRef file, long offset, int length, Cancellation cancellation);

    CompletableFuture<FileOpen> openRead(FileRef file, Cancellation cancellation);

    CompletableFuture<EnsureResult> ensure(
            EnsureSelection selection,
            EnsurePriority priority,
            Consumer<EnsureProgress> progress,
            Cancellation cancellation);

    @Override
    void close();
}
