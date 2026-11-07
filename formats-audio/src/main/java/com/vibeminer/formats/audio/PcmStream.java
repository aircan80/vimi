package com.vibeminer.formats.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Owned signed 16-bit big-endian PCM from one decoder process. Reads return whole frames; a decoder
 * that stops producing output for the stall bound fails instead of blocking forever. Closing
 * destroys the process from any thread.
 */
public final class PcmStream implements AutoCloseable {
    private static final byte[] END = new byte[0];

    /** PCM read from the decoder per block, and the blocks queued ahead of the reader. */
    static final int CHUNK_BYTES = 16_384, QUEUED_CHUNKS = 8;

    private final Process process;
    private final ArrayBlockingQueue<Object> chunks = new ArrayBlockingQueue<>(QUEUED_CHUNKS);
    private final long stallNanos, maximum;
    private final int sampleRate, channels;
    private byte[] current = END;
    private int position;
    private long delivered;
    private boolean ended;
    private volatile boolean closed, decoded;

    private PcmStream(
            Process process, int sampleRate, int channels, long stallNanos, byte[] first) {
        this.process = process;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.stallNanos = stallNanos;
        this.maximum = (long) sampleRate * channels * 2 * AudioStreamDecoder.MAX_SECONDS;
        this.current = first;
    }

    static PcmStream start(
            Process process, byte[] encoded, BooleanSupplier canceled, long stallNanos)
            throws IOException {
        try {
            Thread.ofVirtual()
                    .name("audio-stream-input")
                    .start(
                            () -> {
                                try (var input = process.getOutputStream()) {
                                    input.write(encoded);
                                } catch (IOException ignored) {
                                    /* The output side reports the decoder failure. */
                                }
                            });
            var header = new ArrayBlockingQueue<Object>(1);
            var output = process.getInputStream();
            Thread.ofVirtual()
                    .name("audio-stream-header")
                    .start(
                            () -> {
                                try {
                                    header.add(output.readNBytes(12));
                                } catch (IOException failure) {
                                    header.add(failure);
                                }
                            });
            Object received = poll(header, canceled, stallNanos);
            if (!(received instanceof byte[] bytes) || bytes.length != 12) {
                throw new IOException("World audio could not be decoded within its bounds");
            }
            var values = ByteBuffer.wrap(bytes);
            int magic = values.getInt(), rate = values.getInt(), channels = values.getInt();
            if (magic != AudioStreamDecoder.MAGIC
                    || rate < 8_000
                    || rate > 48_000
                    || channels < 1
                    || channels > 2) {
                throw new IOException("World audio decoder returned an unsupported format");
            }
            var stream = new PcmStream(process, rate, channels, stallNanos, END);
            Thread.ofVirtual().name("audio-stream-output").start(() -> stream.pump(output));
            return stream;
        } catch (IOException | RuntimeException | Error failure) {
            process.destroyForcibly();
            throw failure;
        }
    }

    private void pump(java.io.InputStream output) {
        try (output) {
            while (!closed) {
                byte[] block = output.readNBytes(CHUNK_BYTES);
                if (block.length == 0) {
                    decoded = true;
                    break;
                }
                chunks.put(block);
            }
            if (!closed) {
                chunks.put(
                        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
                                ? END
                                : new IOException("World audio decoding failed"));
            }
        } catch (IOException failure) {
            chunks.offer(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * True once the decoder has written its last PCM. What remains is the bounded buffered tail
     * (this stream's queue), so a caller can prepare what follows the file.
     */
    public boolean decoded() {
        return decoded;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    /** Blocking read of whole frames; returns -1 after a successful decoder exit. */
    public int read(byte[] buffer, int offset, int length, BooleanSupplier canceled)
            throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        int frame = channels * 2;
        length -= length % frame;
        if (length == 0) {
            throw new IllegalArgumentException("Read requires one whole frame");
        }
        int count = 0;
        while (count < length) {
            if (closed) {
                throw new CancellationException();
            }
            if (position == current.length) {
                if (ended || count > 0 && chunks.isEmpty()) {
                    break;
                }
                Object next = poll(chunks, () -> closed || canceled.getAsBoolean(), stallNanos);
                if (next instanceof IOException failure) {
                    throw failure;
                }
                current = (byte[]) next;
                position = 0;
                if (current.length == 0) {
                    ended = true;
                    break;
                }
                if ((delivered += current.length) > maximum) {
                    throw new IOException("World audio exceeds its duration bound");
                }
            }
            int copy = Math.min(length - count, current.length - position);
            System.arraycopy(current, position, buffer, offset + count, copy);
            position += copy;
            count += copy;
        }
        if (count % frame != 0) {
            if (!ended) {
                // Pipe blocks need not be frame aligned; finish the split frame.
                return count + finishFrame(buffer, offset + count, frame - count % frame, canceled);
            }
            throw new IOException("World audio ended inside a frame");
        }
        return count == 0 ? -1 : count;
    }

    private int finishFrame(byte[] buffer, int offset, int missing, BooleanSupplier canceled)
            throws IOException {
        int count = 0;
        while (count < missing) {
            if (position == current.length) {
                Object next = poll(chunks, () -> closed || canceled.getAsBoolean(), stallNanos);
                if (next instanceof IOException failure) {
                    throw failure;
                }
                current = (byte[]) next;
                position = 0;
                if (current.length == 0) {
                    ended = true;
                    throw new IOException("World audio ended inside a frame");
                }
                if ((delivered += current.length) > maximum) {
                    throw new IOException("World audio exceeds its duration bound");
                }
            }
            int copy = Math.min(missing - count, current.length - position);
            System.arraycopy(current, position, buffer, offset + count, copy);
            position += copy;
            count += copy;
        }
        return count;
    }

    private static Object poll(
            ArrayBlockingQueue<Object> queue, BooleanSupplier canceled, long stallNanos)
            throws IOException {
        long deadline = System.nanoTime() + stallNanos;
        try {
            while (true) {
                AudioStreamDecoder.check(canceled);
                Object value = queue.poll(50, TimeUnit.MILLISECONDS);
                if (value != null) {
                    return value;
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("World audio decoder stalled");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    @Override
    public void close() {
        closed = true;
        process.destroyForcibly();
        chunks.clear();
    }
}
