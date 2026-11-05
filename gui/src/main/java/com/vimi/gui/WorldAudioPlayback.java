package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.formats.audio.AudioStreamDecoder;
import com.vibeminer.formats.audio.PcmStream;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Two owned playback channels (music and ambience). Each channel has one worker thread that owns
 * its decoder process and audio line; callers only publish desired state and never wait on audio.
 * Decoded PCM is streamed to the line and never stored.
 */
final class WorldAudioPlayback implements AutoCloseable {
    static final double FADE_SECONDS = 1.5;
    static final int MAX_SILENCE_MILLIS = 600_000, MAX_LOOP_BYTES = 16 << 20;
    static final String OUTPUT_UNAVAILABLE = "Audio output is unavailable on this machine.",
            OUTPUT_STOPPED = "Audio output stopped accepting audio.";
    /**
     * Common line formats, used only to check that an output device exists before decoding: 16-bit
     * stereo, or 16-bit mono for a mono-only device. Channels and frame size must be concrete:
     * {@code AudioFormat.matches} treats unspecified values as wildcards only on the mixer's side,
     * and Windows DirectSound advertises concrete channel counts, so an unspecified probe matched
     * no Windows device. Only the rate is left open, which every mixer leaves unspecified too.
     */
    static final List<AudioFormat> PROBES = List.of(
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioSystem.NOT_SPECIFIED, 16, 2, 4,
                    AudioSystem.NOT_SPECIFIED, true),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioSystem.NOT_SPECIFIED, 16, 1, 2,
                    AudioSystem.NOT_SPECIFIED, true));

    /** True when the device accepts any probe format. */
    static boolean probe(Predicate<AudioFormat> supported) {
        return PROBES.stream().anyMatch(supported);
    }

    static boolean outputSupported() {
        return probe(format -> AudioSystem.isLineSupported(
                new DataLine.Info(SourceDataLine.class, format)));
    }

    @FunctionalInterface
    interface Lines {
        SourceDataLine open(AudioFormat format) throws Exception;
    }

    /** One decoded stream of signed 16-bit big-endian frames. */
    interface Pcm extends AutoCloseable {
        int sampleRate();

        int channels();

        int read(byte[] buffer, int offset, int length, BooleanSupplier canceled) throws IOException;

        @Override
        void close();
    }

    @FunctionalInterface
    interface Decoder {
        Pcm open(byte[] encoded, BooleanSupplier canceled) throws IOException;
    }

    @FunctionalInterface
    interface Source {
        CompletableFuture<WorldAreaAudio.AudioFile> file(long fileDataId, Cancellation token);
    }

    record Status(WorldAreaAudio.Kind kind, boolean playing, String message) {}

    /**
     * Decoder messages are shown only when authored by the audio decoder; process start and pipe
     * failures can name the local Java installation, so they map to {@code fallback}.
     */
    static String detail(IOException failure, String fallback) {
        String message = failure.getMessage();
        if (message == null) return fallback;
        if (message.startsWith("World audio ")) return message;
        // OggVoiceInput's shared preflight is worded for voice previews.
        if (message.startsWith("Voice preview requires")) return "not a valid Vorbis stream";
        return fallback;
    }

    static Pcm decode(byte[] encoded, BooleanSupplier canceled) throws IOException {
        PcmStream stream = AudioStreamDecoder.open(encoded, canceled);
        return new Pcm() {
            @Override public int sampleRate() { return stream.sampleRate(); }
            @Override public int channels() { return stream.channels(); }
            @Override public int read(byte[] buffer, int offset, int length, BooleanSupplier c)
                    throws IOException {
                return stream.read(buffer, offset, length, c);
            }
            @Override public void close() { stream.close(); }
        };
    }

    final Channel music, ambience;

    WorldAudioPlayback(Consumer<Status> status) {
        this(WorldAudioPlayback::outputSupported, AudioSystem::getSourceDataLine,
                WorldAudioPlayback::decode, status);
    }

    WorldAudioPlayback(Lines lines, Decoder decoder, Consumer<Status> status) {
        this(() -> true, lines, decoder, status);
    }

    /** {@code output} is checked before each track's file read and decoder start. */
    WorldAudioPlayback(BooleanSupplier output, Lines lines, Decoder decoder,
            Consumer<Status> status) {
        music = new Channel(WorldAreaAudio.Kind.MUSIC, output, lines, decoder, status);
        ambience = new Channel(WorldAreaAudio.Kind.AMBIENCE, output, lines, decoder, status);
    }

    Channel channel(WorldAreaAudio.Kind kind) {
        return kind == WorldAreaAudio.Kind.MUSIC ? music : ambience;
    }

    /** Never blocks; completes after both channel workers released their line and decoder. */
    CompletableFuture<Void> closeAsync() {
        return CompletableFuture.allOf(music.closeAsync(), ambience.closeAsync());
    }

    @Override
    public void close() {
        closeAsync();
    }

    static final class Channel {
        private final WorldAreaAudio.Kind kind;
        private final BooleanSupplier output;
        private final Lines lines;
        private final Decoder decoder;
        private final Consumer<Status> status;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private WorldAreaAudio.Track track;
        private Source source;
        private boolean enabled, closed;
        private long generation;
        private Thread worker;
        private Cancellation request;
        private Pcm active;
        private volatile double volume = 0.5;

        Channel(WorldAreaAudio.Kind kind, BooleanSupplier output, Lines lines, Decoder decoder,
                Consumer<Status> status) {
            this.kind = kind;
            this.output = output;
            this.lines = lines;
            this.decoder = decoder;
            this.status = status;
        }

        /** Same recorded sound kit keeps playing across area changes; null fades out. */
        synchronized void track(WorldAreaAudio.Track next, Source nextSource) {
            if (closed) return;
            if (next != null && track != null && source == nextSource
                    && next.soundKitId() == track.soundKitId()
                    && next.fileDataIds().equals(track.fileDataIds())) return;
            track = next;
            source = next == null ? null : Objects.requireNonNull(nextSource);
            changed();
        }

        synchronized void enabled(boolean value) {
            if (closed || enabled == value) return;
            enabled = value;
            changed();
        }

        synchronized boolean enabled() {
            return enabled;
        }

        /** Linear 0..1; applied with a short ramp so changes never click. */
        void volume(double value) {
            volume = Math.max(0, Math.min(1, value));
        }

        private void changed() {
            generation++;
            if (request != null) request.cancel();
            notifyAll();
            if (enabled && track != null && worker == null) {
                worker = Thread.ofPlatform().daemon().name("world-audio-" + kind.name()
                        .toLowerCase(java.util.Locale.ROOT)).unstarted(this::run);
                worker.start();
            }
        }

        synchronized CompletableFuture<Void> closeAsync() {
            if (!closed) {
                closed = true;
                changed();
                if (active != null) active.close();
                if (worker == null) stopped.complete(null);
            }
            return stopped.copy();
        }

        private synchronized boolean current(long expected) {
            return !closed && enabled && generation == expected;
        }

        private void report(boolean playing, String message) {
            try {
                status.accept(new Status(kind, playing, message));
            } catch (RuntimeException ignored) {
                /* Presentation cannot interrupt owned audio cleanup. */
            }
        }

        private void run() {
            try {
                while (true) {
                    long expected;
                    WorldAreaAudio.Track playing;
                    Source from;
                    Cancellation token = new Cancellation();
                    synchronized (this) {
                        if (closed) return;
                        if (!enabled || track == null) {
                            worker = null;
                            report(false, enabled ? "No recorded " + label() + " here"
                                    : label() + " off");
                            return;
                        }
                        expected = generation;
                        playing = track;
                        from = source;
                        request = token;
                    }
                    String problem = play(expected, playing, from, token);
                    if (problem != null) {
                        report(false, problem);
                        waitForChange(expected, -1);
                    } else if (current(expected)) {
                        int min = Math.min(playing.silenceMinMillis(), MAX_SILENCE_MILLIS);
                        int max = Math.min(playing.silenceMaxMillis(), MAX_SILENCE_MILLIS);
                        long pause = max == 0 ? 0
                                : ThreadLocalRandom.current().nextLong(min, max + 1L);
                        if (pause > 0) {
                            report(false, "Recorded silence before the next " + label()
                                    + " track");
                            waitForChange(expected, pause);
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (this) {
                    if (worker == Thread.currentThread()) worker = null;
                    if (closed && worker == null) stopped.complete(null);
                }
            }
        }

        private String label() {
            return kind == WorldAreaAudio.Kind.MUSIC ? "music" : "ambience";
        }

        /** Negative waits until the desired state changes. */
        private synchronized void waitForChange(long expected, long millis)
                throws InterruptedException {
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
            while (generation == expected && !closed) {
                if (millis < 0) {
                    wait();
                    continue;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }

        private Pcm open(long expected, Source from, long fileId, Cancellation token,
                String[] problem) {
            report(false, "Loading recorded " + label() + " file #" + fileId + "…");
            WorldAreaAudio.AudioFile file;
            try {
                file = from.file(fileId, token).join();
            } catch (RuntimeException failure) {
                problem[0] = "Recorded " + label() + " file #" + fileId + " is unavailable.";
                return null;
            }
            if (!current(expected)) return null;
            if (file.state() != WorldAreaAudio.FileState.READ) {
                problem[0] = file.issue();
                return null;
            }
            try {
                return decoder.open(file.bytes(), () -> !current(expected));
            } catch (IOException failure) {
                problem[0] = "Recorded " + label() + " file #" + fileId + " cannot be decoded: "
                        + detail(failure, "the decoder could not start");
            } catch (RuntimeException canceledOrFailed) {
                problem[0] = "Recorded " + label() + " decoding failed.";
            }
            return null;
        }

        /**
         * Plays one music track, or loops ambience on one line until the desired state changes.
         * Returns a visible problem, or null after a natural end or a completed fade-out.
         */
        private String play(long expected, WorldAreaAudio.Track playing, Source from,
                Cancellation token) {
            if (!outputAvailable()) return current(expected) ? OUTPUT_UNAVAILABLE : null;
            var ids = playing.fileDataIds();
            long fileId = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
            String[] problem = new String[1];
            Pcm pcm = open(expected, from, fileId, token, problem);
            if (pcm == null) return current(expected) ? problem[0] : null;
            if (!adopt(pcm)) return null;
            SourceDataLine line = null;
            try {
                var format = new AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, true);
                int frame = pcm.channels() * 2;
                try {
                    line = lines.open(format);
                    line.open(format, Math.max(frame, pcm.sampleRate() / 4 * frame));
                    line.start();
                } catch (Exception unavailable) {
                    return current(expected) ? OUTPUT_UNAVAILABLE : null;
                }
                report(true, "Playing recorded " + label() + " (sound kit #"
                        + playing.soundKitId() + ", file #" + fileId + ") from "
                        + playing.areaName());
                byte[] block = new byte[4096 - 4096 % frame];
                double gain = 0, step = 1 / (FADE_SECONDS * pcm.sampleRate());
                boolean fadingOut = false;
                // Ambience loops replay a bounded in-memory copy on the same line, without a gap.
                var loop = kind == WorldAreaAudio.Kind.AMBIENCE && ids.size() == 1
                        ? new java.io.ByteArrayOutputStream() : null;
                byte[] cached = null;
                long frames = 0;
                while (true) {
                    if (closedNow()) return null;
                    boolean live = current(expected);
                    if (!live && gain <= 0) return null;
                    int count;
                    try {
                        count = pcm.read(block, 0, block.length, this::closedNow);
                    } catch (IOException failure) {
                        return live ? "Recorded " + label() + " stream failed: "
                                + detail(failure, "decoder output was lost") : null;
                    } catch (java.util.concurrent.CancellationException canceled) {
                        return null;
                    } catch (RuntimeException failed) {
                        // Not a natural end: retrying would re-decode the same file in a loop.
                        return live ? "Recorded " + label() + " stream failed." : null;
                    }
                    if (count < 0) {
                        if (frames == 0) {
                            return live ? "Recorded " + label() + " file #" + fileId
                                    + " decoded no audio." : null;
                        }
                        frames = 0;
                        if (live && kind == WorldAreaAudio.Kind.AMBIENCE) {
                            if (loop != null && loop.size() > 0) cached = loop.toByteArray();
                            loop = null;
                            Pcm next = cached != null
                                    ? new MemoryPcm(pcm.sampleRate(), pcm.channels(), cached)
                                    : open(expected, from,
                                            ids.get(ThreadLocalRandom.current()
                                                    .nextInt(ids.size())), token, problem);
                            if (next == null) return current(expected) ? problem[0] : null;
                            if (next.sampleRate() != pcm.sampleRate()
                                    || next.channels() != pcm.channels()) {
                                next.close();
                                return null;
                            }
                            pcm.close();
                            pcm = next;
                            if (!adopt(pcm)) return null;
                            continue;
                        }
                        if (live) {
                            try {
                                line.drain();
                            } catch (RuntimeException stopped) {
                                return current(expected) ? OUTPUT_STOPPED : null;
                            }
                        }
                        return null;
                    }
                    frames += count / frame;
                    if (loop != null) {
                        if (loop.size() <= MAX_LOOP_BYTES - count) loop.write(block, 0, count);
                        else loop = null;
                    }
                    fadingOut |= !live;
                    double target = fadingOut ? 0 : volume;
                    gain = apply(block, count, pcm.channels(), gain, target, step);
                    for (int offset = 0; offset < count; ) {
                        int written;
                        try {
                            written = line.write(block, offset, count - offset);
                        } catch (RuntimeException stopped) {
                            written = 0;
                        }
                        // A removed device or a line stopped by the OS; stop instead of re-looping.
                        if (written <= 0) return current(expected) ? OUTPUT_STOPPED : null;
                        offset += written;
                    }
                }
            } finally {
                pcm.close();
                synchronized (this) {
                    active = null;
                }
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (RuntimeException ignored) {
                        /* A failed close only affects this finished track. */
                    }
                }
            }
        }

        /** Cheap device check before any file read or decoder process; no line is created. */
        private boolean outputAvailable() {
            try {
                return output.getAsBoolean();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private synchronized boolean adopt(Pcm pcm) {
            if (closed) {
                pcm.close();
                return false;
            }
            active = pcm;
            return true;
        }

        private synchronized boolean closedNow() {
            return closed;
        }
    }

    /** A bounded decoded ambience loop held only in memory for the current area. */
    private static final class MemoryPcm implements Pcm {
        private final int sampleRate, channels;
        private final byte[] bytes;
        private int position;

        MemoryPcm(int sampleRate, int channels, byte[] bytes) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bytes = bytes;
        }

        @Override public int sampleRate() { return sampleRate; }
        @Override public int channels() { return channels; }

        @Override
        public int read(byte[] buffer, int offset, int length, BooleanSupplier canceled) {
            if (position == bytes.length) {
                position = 0;
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override public void close() {}
    }

    /**
     * Scales big-endian frames in place, ramping linearly by {@code step} per frame toward the
     * target. Returns the gain after the last frame.
     */
    static double apply(byte[] block, int count, int channels, double gain, double target,
            double step) {
        int frame = channels * 2;
        for (int i = 0; i + frame <= count; i += frame) {
            if (gain < target) gain = Math.min(target, gain + step);
            else if (gain > target) gain = Math.max(target, gain - step);
            double perceptual = gain * gain;
            for (int c = 0; c < frame; c += 2) {
                int sample = (short) (block[i + c] << 8 | block[i + c + 1] & 0xff);
                int scaled = (int) Math.round(sample * perceptual);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                block[i + c] = (byte) (scaled >> 8);
                block[i + c + 1] = (byte) scaled;
            }
        }
        return gain;
    }
}
