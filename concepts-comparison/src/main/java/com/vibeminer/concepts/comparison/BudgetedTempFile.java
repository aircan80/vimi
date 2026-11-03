package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** One owned scratch file. Charged bytes survive failed writes until successful deletion. */
public final class BudgetedTempFile implements AutoCloseable {
    @FunctionalInterface
    interface FrameWriter {
        void write(FileChannel channel, ByteBuffer header, ByteBuffer body, Cancellation token)
                throws IOException;
    }

    private final Path path;
    private final TempBudget.Reservation reservation;
    private final TempBudget budget;
    private final FrameWriter writer;
    private FileChannel channel;
    private boolean sealed, failed, deleted;

    private BudgetedTempFile(
            Path path, TempBudget.Reservation reservation, FrameWriter writer, TempBudget budget) {
        this.path = path;
        this.reservation = reservation;
        this.writer = writer;
        this.budget = budget;
    }

    public static BudgetedTempFile create(
            Path directory, String prefix, TempBudget budget, String owner, Cancellation token)
            throws IOException {
        return create(directory, prefix, budget, owner, token, BudgetedTempFile::writeFrame);
    }

    static BudgetedTempFile create(
            Path directory,
            String prefix,
            TempBudget budget,
            String owner,
            Cancellation token,
            FrameWriter writer)
            throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(writer);
        if (prefix == null || !prefix.matches("[A-Za-z0-9_.-]{3,32}")) {
            throw new IllegalArgumentException("Use a bounded scratch-file prefix");
        }
        var reservation = Objects.requireNonNull(budget).reserveDisk(owner, 0, token);
        try {
            return new BudgetedTempFile(
                    Files.createTempFile(directory, prefix, ".tmp"), reservation, writer, budget);
        } catch (IOException | RuntimeException failure) {
            reservation.close();
            throw failure;
        }
    }

    public Path path() {
        return path;
    }

    /** Validate scratch ownership before composing stages under a shared whole-job quota. */
    public synchronized void requireBudget(TempBudget expected) {
        if (deleted || failed || budget != Objects.requireNonNull(expected)) {
            throw new IllegalArgumentException("A live same-budget scratch file is required");
        }
    }

    /** Includes planned bytes of a failed/partial write until the file is deleted. */
    public synchronized long size() {
        return reservation.size();
    }

    /** Caller reserves its codec/frame memory before creating the supplied byte array. */
    public synchronized void appendFrame(byte[] frame, Cancellation token) throws IOException {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(token).check();
        if (sealed || failed || deleted) {
            throw new IOException("Scratch file is sealed, failed or deleted");
        }
        if (frame.length > ComparisonService.RECORD_BYTES) {
            throw new IllegalArgumentException("Scratch frame exceeds record limit");
        }
        reservation.grow(4L + frame.length, token);
        try {
            if (channel == null) {
                channel = FileChannel.open(path, StandardOpenOption.WRITE);
            }
            writer.write(
                    channel,
                    ByteBuffer.allocate(4).putInt(frame.length).flip(),
                    ByteBuffer.wrap(frame),
                    token);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    public synchronized void seal() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        sealed = true;
    }

    public synchronized BudgetedTempFile transferTo(String owner) {
        if (deleted) {
            throw new IllegalStateException("Scratch file deleted");
        }
        reservation.transferTo(owner);
        return this;
    }

    private static void writeFrame(
            FileChannel channel, ByteBuffer header, ByteBuffer body, Cancellation token)
            throws IOException {
        for (var buffer : new ByteBuffer[] {header, body}) {
            while (buffer.hasRemaining()) {
                token.check();
                if (channel.write(buffer) <= 0) {
                    throw new IOException("Scratch write did not advance");
                }
            }
        }
    }

    /** Close may be retried: a deletion failure retains the reservation and file ownership. */
    @Override
    public synchronized void close() throws IOException {
        if (deleted) {
            return;
        }
        seal();
        Files.deleteIfExists(path);
        reservation.close();
        deleted = true;
    }
}
