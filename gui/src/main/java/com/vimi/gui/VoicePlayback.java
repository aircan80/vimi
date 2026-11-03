package com.vimi.gui;

import com.vibeminer.formats.audio.PcmClip;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** One short PCM voice at a time; stopping/closing releases the platform audio line. */
final class VoicePlayback implements AutoCloseable {
    private final AtomicReference<SourceDataLine> active = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    CompletableFuture<String> play(PcmClip clip) {
        stop();
        long request = generation.get();
        var result = new CompletableFuture<String>();
        Thread.startVirtualThread(
                () -> {
                    SourceDataLine line = null;
                    try {
                        if (stale(request)) {
                            result.complete(null);
                            return;
                        }
                        var format =
                                new AudioFormat(clip.sampleRate(), 16, clip.channels(), true, true);
                        line = AudioSystem.getSourceDataLine(format);
                        line.open(format, 8192);
                        synchronized (this) {
                            if (stale(request)) {
                                result.complete(null);
                                return;
                            }
                            active.set(line);
                        }
                        byte[] bytes = clip.bytes();
                        line.start();
                        for (int offset = 0; offset < bytes.length && !stale(request); ) {
                            int n =
                                    line.write(
                                            bytes, offset, Math.min(4096, bytes.length - offset));
                            if (n <= 0) {
                                break;
                            }
                            offset += n;
                        }
                        if (!stale(request)) {
                            line.drain();
                        }
                        result.complete(null);
                    } catch (Exception failure) {
                        result.complete(
                                stale(request)
                                        ? null
                                        : "Audio output is unavailable on this machine.");
                    } finally {
                        if (line != null) {
                            active.compareAndSet(line, null);
                            line.close();
                        }
                    }
                });
        return result;
    }

    private boolean stale(long request) {
        return closed || generation.get() != request;
    }

    void stop() {
        SourceDataLine line;
        synchronized (this) {
            generation.incrementAndGet();
            line = active.getAndSet(null);
        }
        if (line != null) {
            line.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
