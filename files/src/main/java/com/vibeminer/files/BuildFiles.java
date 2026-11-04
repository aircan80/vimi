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

    /**
     * Index/presence/name facts only: never opens encoded payloads or sniffs content headers.
     * Format filtering uses available name extensions only; without a name the format is UNKNOWN.
     * The default refuses rather than silently falling back to content-inspecting listing.
     */
    default AsyncCursor<BuildFile> listMetadata(FileFilter filter, Cancellation cancellation) {
        throw new UnsupportedOperationException("Metadata-only file listing is not implemented");
    }

    /**
     * Ordered selected-locale root content inventory. Reads only immutable root/encoding indexes;
     * never queries local presence, names, formats or payload bytes. Size remains unknown when no
     * encoded variant supplies it. Exhaustion alone does not certify locale membership coverage.
     * Older implementations explicitly refuse, allowing callers to choose a truthful slower path.
     */
    default AsyncCursor<FileTypes.ContentFile> listContentInventory(
            FileTypes.FileIdRange range, Cancellation cancellation) {
        throw new UnsupportedOperationException("Content-only file inventory is not implemented");
    }

    CompletableFuture<FileStat> stat(FileRef file, Cancellation cancellation);

    /**
     * Lookup state plus index facts only: file id, size, content key, local storage and the
     * encrypted flag agree with {@link #stat}, but the path is not looked up in the listfile and
     * payload headers are never sniffed, so the path is null, the path source is NONE and the
     * format is UNKNOWN. The default delegates to {@code stat}, which is always a valid (slower)
     * answer for those facts.
     */
    default CompletableFuture<FileStat> statContent(FileRef file, Cancellation cancellation) {
        return stat(file, cancellation);
    }

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
