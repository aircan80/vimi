package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.FileHole;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Async-only seekable decoded stream. One read at a time. May outlive its build handle. */
public abstract class FileReadStream implements AutoCloseable {
    public abstract long length();

    public abstract long position();

    public abstract void seek(long position);

    public abstract CompletableFuture<Integer> read(
            ByteBuffer destination, Cancellation cancellation);

    public abstract List<FileHole> holes();

    public List<String> chunkKeys() {
        return holes().stream().map(FileHole::keyName).distinct().sorted().toList();
    }

    @Override
    public abstract void close();
}
