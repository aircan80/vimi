package com.vimi.gui;

import com.vibeminer.formats.audio.PcmClip;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** One admitted playback and one asynchronous line close; never waits for audio on FX. */
final class VoicePlayback implements AutoCloseable {
    @FunctionalInterface
    interface Lines {
        SourceDataLine open(AudioFormat format) throws Exception;
    }

    private static final class Work {
        final AtomicReference<SourceDataLine> line = new AtomicReference<>();
        final AtomicBoolean closing = new AtomicBoolean();
        final CompletableFuture<Void> released = new CompletableFuture<>();
        volatile boolean cancelled;

        void release(SourceDataLine value) {
            if (!closing.compareAndSet(false, true)) return;
            Thread.ofVirtual()
                    .name("voice-line-close")
                    .start(
                            () -> {
                                try {
                                    value.close();
                                    released.complete(null);
                                } catch (RuntimeException failure) {
                                    released.completeExceptionally(failure);
                                }
                            });
        }
    }

    private final Lines lines;
    private final IntSupplier device;
    private final AtomicReference<Work> active = new AtomicReference<>();
    private final AtomicBoolean admitted = new AtomicBoolean();
    private volatile boolean closed;

    VoicePlayback() {
        this(AudioSystem::getSourceDataLine, AudioOutput::channels);
    }

    VoicePlayback(Lines lines) {
        this(lines, () -> 2);
    }

    /** {@code device} gives the output's channels, as {@link AudioOutput#channels()} does. */
    VoicePlayback(Lines lines, IntSupplier device) {
        this.lines = lines;
        this.device = device;
    }

    CompletableFuture<String> play(PcmClip clip) {
        return play(clip, () -> {});
    }

    CompletableFuture<String> play(PcmClip clip, Runnable started) {
        stop();
        if (closed) return CompletableFuture.completedFuture("Audio output is closed.");
        if (!admitted.compareAndSet(false, true))
            return CompletableFuture.completedFuture("Audio output is still stopping. Try again.");
        var work = new Work();
        active.set(work);
        var result = new CompletableFuture<String>();
        Thread.ofVirtual()
                .name("voice-playback")
                .start(
                        () -> {
                            SourceDataLine line = null;
                            String problem = null;
                            try {
                                if (closed || work.cancelled) return;
                                // clip.bytes() is a copy, so a downmix may rewrite it in place.
                                byte[] bytes = clip.bytes();
                                int length = bytes.length;
                                // A stereo clip on a mono-only device plays downmixed to mono.
                                int channels =
                                        clip.channels() == 2
                                                ? AudioOutput.lineChannels(2, device.getAsInt())
                                                : clip.channels();
                                if (channels < clip.channels())
                                    length = AudioOutput.downmix(bytes, length);
                                var format =
                                        new AudioFormat(
                                                clip.sampleRate(), 16, channels, true, true);
                                line = lines.open(format);
                                line.open(format, 8192);
                                work.line.set(line);
                                if (closed || work.cancelled) return;
                                line.start();
                                if (!closed && !work.cancelled) {
                                    try {
                                        started.run();
                                    } catch (RuntimeException ignored) {
                                        /* Feedback cannot interrupt line cleanup. */
                                    }
                                }
                                for (int offset = 0;
                                        offset < length && !closed && !work.cancelled; ) {
                                    int n =
                                            line.write(
                                                    bytes,
                                                    offset,
                                                    Math.min(4096, length - offset));
                                    if (n <= 0) break;
                                    offset += n;
                                }
                                if (!closed && !work.cancelled) line.drain();
                            } catch (Exception failure) {
                                problem =
                                        work.cancelled || closed
                                                ? null
                                                : "Audio output is unavailable on this machine.";
                            } finally {
                                if (line != null) work.release(line);
                                else work.released.complete(null);
                                String message = problem;
                                work.released.whenComplete(
                                        (unused, failure) -> {
                                            active.compareAndSet(work, null);
                                            if (failure != null) closed = true;
                                            admitted.set(false);
                                            result.complete(
                                                    failure == null
                                                            ? message
                                                            : "Audio output is unavailable on this"
                                                                  + " machine.");
                                        });
                            }
                        });
        return result;
    }

    void stop() {
        var work = active.get();
        if (work == null) return;
        work.cancelled = true;
        var line = work.line.get();
        if (line != null) work.release(line);
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
