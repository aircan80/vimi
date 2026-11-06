package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.formats.audio.AudioStreamDecoder;
import com.vibeminer.formats.audio.PcmStream;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Two owned playback channels (music and ambience). Each channel has one worker thread that owns
 * the current track's decoder process and audio line; callers only publish desired state and never
 * wait on audio. A replaced track becomes a tail voice on its own thread: it keeps playing until
 * the next track has audio, then both cross-fade over {@link #FADE_SECONDS}. Decoded PCM is
 * streamed to the lines and never stored.
 */
final class WorldAudioPlayback implements AutoCloseable {
    static final double FADE_SECONDS = 1.5;
    static final int MAX_SILENCE_MILLIS = 600_000, MAX_LOOP_BYTES = 16 << 20;
    /** Backoff for a full source queue: grows per attempt up to the maximum, never gives up. */
    static final long BUSY_RETRY_MILLIS = 100, BUSY_RETRY_MAX_MILLIS = 1_000;
    /** Busy attempts after which the status says the source is busy instead of "Loading". */
    static final int BUSY_STATUS_ATTEMPTS = 3;
    /** A new line after a mixed-format switch ramps up this fast, so it starts without a click. */
    static final double SWITCH_RAMP_SECONDS = 0.005;
    /**
     * A replaced track keeps playing at full level until the next one has audio, for at most this
     * long; then it fades out regardless.
     */
    static final long HOLD_MAX_MILLIS = 5_000;
    /** A replaced track that must give up its line for the next one fades out this fast. */
    static final double YIELD_FADE_SECONDS = 0.05;
    static final String OUTPUT_UNAVAILABLE = "Audio output is unavailable on this machine.",
            OUTPUT_STOPPED = "Audio output stopped accepting audio.";
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
        this(AudioOutput::channels, AudioSystem::getSourceDataLine,
                WorldAudioPlayback::decode, status);
    }

    WorldAudioPlayback(Lines lines, Decoder decoder, Consumer<Status> status) {
        this(() -> 2, lines, decoder, status);
    }

    /**
     * {@code output} gives the device's channels (see {@link AudioOutput#channels()}); it is
     * checked before each track's file read and decoder start.
     */
    WorldAudioPlayback(IntSupplier output, Lines lines, Decoder decoder,
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
        private final IntSupplier output;
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
        /** Replaced tracks still sounding; {@code held} is the one waiting for the next track. */
        private final java.util.List<Tail> tails = new java.util.ArrayList<>();
        private Tail held;
        private volatile double volume = 0.5;

        Channel(WorldAreaAudio.Kind kind, IntSupplier output, Lines lines, Decoder decoder,
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

        /** The desired recorded track, or null. */
        synchronized WorldAreaAudio.Track track() {
            return track;
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
                held = null;
                for (var tail : tails) tail.stop();
                settle();
            }
            return stopped.copy();
        }

        /** Completes the close once the worker and every tail released their line and decoder. */
        private synchronized void settle() {
            if (closed && worker == null && tails.isEmpty()) stopped.complete(null);
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
                            // Nothing replaces the held track: it fades out now.
                            if (held != null) held.release();
                            held = null;
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
                    settle();
                }
            }
        }

        private String label() {
            return kind == WorldAreaAudio.Kind.MUSIC ? "music" : "ambience";
        }

        /** Waits up to {@code millis}; true when the desired state is still {@code expected}. */
        private boolean pause(long expected, long millis) {
            try {
                waitForChange(expected, millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            return current(expected);
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
            WorldAreaAudio.AudioFile file = null;
            for (int attempt = 1; file == null; attempt++) {
                try {
                    file = from.file(fileId, token).join();
                } catch (RuntimeException failure) {
                    // A full source queue drains shortly; it says nothing about the file.
                    if (!WorldAreaAudio.busy(failure)) {
                        problem[0] = "Recorded " + label() + " file #" + fileId
                                + " is unavailable.";
                        return null;
                    }
                    if (attempt == BUSY_STATUS_ATTEMPTS) {
                        report(false, "Waiting for the audio source to load recorded " + label()
                                + " file #" + fileId + "…");
                    }
                    if (!pause(expected, Math.min(BUSY_RETRY_MAX_MILLIS,
                            BUSY_RETRY_MILLIS * attempt))) return null;
                }
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
         * Plays one track. The replaced track, if one is held, keeps playing until this one writes
         * audio and is released then, so the two cross-fade. If this track is itself replaced
         * before it has audio, the held track is passed on to the next one instead.
         */
        private String play(long expected, WorldAreaAudio.Track playing, Source from,
                Cancellation token) {
            Tail previous = takeHeld();
            try {
                return playTrack(expected, playing, from, token, previous);
            } finally {
                if (previous != null && !previous.released()) passOn(expected, previous);
            }
        }

        private synchronized Tail takeHeld() {
            var tail = held;
            held = null;
            return tail;
        }

        /** A superseded load passes the held track on; any other end releases it to fade out. */
        private synchronized void passOn(long expected, Tail previous) {
            if (!closed && enabled && track != null && generation != expected && held == null) {
                held = previous;
            } else {
                previous.release();
            }
        }

        /** The replaced track keeps sounding on its own thread; see {@link Tail}. */
        private synchronized void handOff(Tail tail) {
            if (closed) {
                tail.stop();
            } else {
                if (held != null) held.release();
                held = tail;
            }
            tails.add(tail);
            tail.start();
        }

        /**
         * Plays one music track, or loops ambience until the desired state changes. Ambience stays
         * on one line while the formats match; a file with another format drains the old line and
         * continues on a new one, ramped up from silence in a few milliseconds. When the desired
         * state changes, the playing track is handed off as a tail instead of fading out here.
         * Returns a visible problem, or null after a natural end or a hand-off.
         */
        private String playTrack(long expected, WorldAreaAudio.Track playing, Source from,
                Cancellation token, Tail previous) {
            // The device's channels are known before any file read or decoder start.
            int device = outputChannels();
            if (device == 0) return current(expected) ? OUTPUT_UNAVAILABLE : null;
            var ids = playing.fileDataIds();
            long fileId = pick(playing);
            String[] problem = new String[1];
            Pcm pcm = open(expected, from, fileId, token, problem);
            if (pcm == null) return current(expected) ? problem[0] : null;
            if (!adopt(pcm)) return null;
            Output out = null;
            try {
                out = Output.open(lines, pcm, device);
                if (out == null && previous != null) {
                    // A device that cannot play two lines at once: the old track yields its line.
                    previous.yieldLine();
                    out = Output.open(lines, pcm, device);
                }
                if (out == null) return current(expected) ? OUTPUT_UNAVAILABLE : null;
                reportPlaying(playing, fileId);
                double gain = 0;
                // The recorded kit and entry volumes are linear amplitude; gain is squared when
                // applied.
                double kit = Math.sqrt(playing.volume(fileId));
                // True while a switched line ramps up from silence at the quick step.
                boolean ramping = false;
                // Ambience loops replay a bounded in-memory copy on the same line, without a gap.
                var loop = kind == WorldAreaAudio.Kind.AMBIENCE && ids.size() == 1
                        ? new java.io.ByteArrayOutputStream() : null;
                byte[] cached = null;
                long frames = 0;
                while (true) {
                    if (closedNow()) return null;
                    if (!current(expected)) {
                        // Replaced, muted or gone: the track keeps sounding as a tail, which the
                        // next track releases once it has audio (or the run loop, if none comes).
                        if (gain > 0) {
                            handOff(new Tail(pcm, out, gain, kit, cached, loop));
                            pcm = null;
                            out = null;
                        }
                        return null;
                    }
                    byte[] block = out.block;
                    int count;
                    try {
                        count = pcm.read(block, 0, block.length, this::closedNow);
                    } catch (IOException failure) {
                        return current(expected) ? "Recorded " + label() + " stream failed: "
                                + detail(failure, "decoder output was lost") : null;
                    } catch (java.util.concurrent.CancellationException canceled) {
                        return null;
                    } catch (RuntimeException failed) {
                        // Not a natural end: retrying would re-decode the same file in a loop.
                        return current(expected) ? "Recorded " + label() + " stream failed."
                                : null;
                    }
                    if (count < 0) {
                        boolean live = current(expected);
                        if (frames == 0) {
                            return live ? "Recorded " + label() + " file #" + fileId
                                    + " decoded no audio." : null;
                        }
                        frames = 0;
                        if (live && kind == WorldAreaAudio.Kind.AMBIENCE) {
                            if (loop != null && loop.size() > 0) cached = loop.toByteArray();
                            loop = null;
                            long nextId = cached != null ? fileId : pick(playing);
                            Pcm next = cached != null
                                    ? new MemoryPcm(pcm.sampleRate(), pcm.channels(), cached)
                                    : open(expected, from, nextId, token, problem);
                            if (next == null) return current(expected) ? problem[0] : null;
                            if (next.sampleRate() != pcm.sampleRate()
                                    || next.channels() != pcm.channels()) {
                                // Mixed-format kit: finish the old line, then switch lines.
                                try {
                                    out.line.drain();
                                } catch (RuntimeException stopped) {
                                    next.close();
                                    return current(expected) ? OUTPUT_STOPPED : null;
                                }
                                out.close();
                                out = Output.open(lines, next, device);
                                if (out == null) {
                                    next.close();
                                    return current(expected) ? OUTPUT_UNAVAILABLE : null;
                                }
                                // A new line starting at full gain would click.
                                gain = 0;
                                ramping = true;
                            }
                            pcm.close();
                            pcm = next;
                            if (!adopt(pcm)) return null;
                            fileId = nextId;
                            kit = Math.sqrt(playing.volume(fileId));
                            // A reopened file reported "Loading"; report it playing again.
                            if (cached == null) reportPlaying(playing, fileId);
                            continue;
                        }
                        if (live) {
                            try {
                                out.line.drain();
                            } catch (RuntimeException stopped) {
                                return current(expected) ? OUTPUT_STOPPED : null;
                            }
                        }
                        return null;
                    }
                    frames += count / out.frame;
                    if (loop != null) {
                        if (loop.size() <= MAX_LOOP_BYTES - count) loop.write(block, 0, count);
                        else loop = null;
                    }
                    double target = volume * kit;
                    int bytes = out.downmix ? AudioOutput.downmix(block, count) : count;
                    gain = apply(block, bytes, out.channels, gain, target,
                            ramping ? out.quickStep : out.step);
                    if (gain >= target) ramping = false;
                    for (int offset = 0; offset < bytes; ) {
                        int written;
                        try {
                            written = out.line.write(block, offset, bytes - offset);
                        } catch (RuntimeException stopped) {
                            written = 0;
                        }
                        // A removed device or a line stopped by the OS; stop instead of re-looping.
                        if (written <= 0) return current(expected) ? OUTPUT_STOPPED : null;
                        offset += written;
                    }
                    // This track has audio: the replaced one starts its fade-out now.
                    if (previous != null) previous.release();
                }
            } finally {
                if (pcm != null) pcm.close();
                synchronized (this) {
                    active = null;
                }
                if (out != null) out.close();
            }
        }

        /**
         * A replaced track still sounding on its own line and thread. It plays at full level while
         * held, for at most {@link #HOLD_MAX_MILLIS}, then fades out over {@link #FADE_SECONDS}
         * and closes its decoder and line. A single-file ambience keeps looping while it fades.
         */
        private final class Tail {
            private final Pcm pcm;
            private final Output out;
            private final double kit;
            private final long holdUntil = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(HOLD_MAX_MILLIS);
            private final Thread thread;
            private double gain;
            private byte[] cached;
            private java.io.ByteArrayOutputStream loop;
            private volatile boolean released, stopped, yielding;

            Tail(Pcm pcm, Output out, double gain, double kit, byte[] cached,
                    java.io.ByteArrayOutputStream loop) {
                this.pcm = pcm;
                this.out = out;
                this.gain = gain;
                this.kit = kit;
                this.cached = cached;
                this.loop = loop;
                thread = Thread.ofPlatform().daemon().name("world-audio-" + label() + "-tail")
                        .unstarted(this::run);
            }

            void start() {
                thread.start();
            }

            /** Starts the fade-out; idempotent. */
            void release() {
                released = true;
            }

            boolean released() {
                return released;
            }

            /** Closes at once, from any thread. */
            void stop() {
                stopped = true;
                pcm.close();
            }

            /** Fades out within {@link #YIELD_FADE_SECONDS} and waits for the line to close. */
            void yieldLine() {
                yielding = true;
                released = true;
                try {
                    thread.join(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            private void run() {
                Pcm reading = pcm;
                double quick = 1 / (YIELD_FADE_SECONDS * pcm.sampleRate());
                try {
                    while (!stopped) {
                        boolean hold = !released && System.nanoTime() < holdUntil;
                        double target = hold ? volume * kit : 0;
                        if (!hold && gain <= 0) return;
                        byte[] block = out.block;
                        int count;
                        try {
                            count = reading.read(block, 0, block.length, () -> stopped);
                        } catch (IOException | RuntimeException ended) {
                            return;
                        }
                        if (count < 0) {
                            if (cached == null && loop != null && loop.size() > 0) {
                                cached = loop.toByteArray();
                            }
                            loop = null;
                            // A file of a multi-file kit ends the tail with it.
                            if (cached == null) return;
                            reading = new MemoryPcm(pcm.sampleRate(), pcm.channels(), cached);
                            continue;
                        }
                        if (loop != null) {
                            if (loop.size() <= MAX_LOOP_BYTES - count) loop.write(block, 0, count);
                            else loop = null;
                        }
                        int bytes = out.downmix ? AudioOutput.downmix(block, count) : count;
                        gain = apply(block, bytes, out.channels, gain, target,
                                yielding ? quick : out.step);
                        for (int offset = 0; offset < bytes; ) {
                            int written;
                            try {
                                written = out.line.write(block, offset, bytes - offset);
                            } catch (RuntimeException removed) {
                                written = 0;
                            }
                            if (written <= 0) return;
                            offset += written;
                        }
                    }
                } finally {
                    pcm.close();
                    out.close();
                    synchronized (Channel.this) {
                        tails.remove(this);
                        if (held == this) held = null;
                        settle();
                    }
                }
            }
        }

        private void reportPlaying(WorldAreaAudio.Track playing, long fileId) {
            report(true, "Playing recorded " + label() + " (sound kit #"
                    + playing.soundKitId() + ", file #" + fileId + ") from "
                    + playing.areaName() + (playing.volume(fileId) == 0
                            ? "; its recorded volume is 0, so it is silent" : ""));
        }

        private static long pick(WorldAreaAudio.Track playing) {
            return playing.pick(ThreadLocalRandom.current().nextDouble());
        }

        /** Cheap device check before any file read or decoder process; no line is created. */
        private int outputChannels() {
            try {
                return output.getAsInt();
            } catch (RuntimeException unavailable) {
                return 0;
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

    /** One started line for one decoded format, with its block size and fade step. */
    private static final class Output {
        final SourceDataLine line;
        final int channels, frame;
        final boolean downmix;
        final double step, quickStep;
        final byte[] block;

        private Output(SourceDataLine line, Pcm pcm, int channels) {
            this.line = line;
            this.channels = channels;
            downmix = channels < pcm.channels();
            frame = pcm.channels() * 2;
            step = 1 / (FADE_SECONDS * pcm.sampleRate());
            quickStep = 1 / (SWITCH_RAMP_SECONDS * pcm.sampleRate());
            block = new byte[4096 - 4096 % frame];
        }

        /**
         * A stereo stream on a mono-only device plays through a mono line, downmixed. Null when
         * the device refuses the format; a partly opened line is closed.
         */
        static Output open(Lines lines, Pcm pcm, int device) {
            int channels = AudioOutput.lineChannels(pcm.channels(), device);
            var format = new AudioFormat(pcm.sampleRate(), 16, channels, true, true);
            int lineFrame = channels * 2;
            SourceDataLine line = null;
            try {
                line = lines.open(format);
                line.open(format, Math.max(lineFrame, pcm.sampleRate() / 4 * lineFrame));
                line.start();
                return new Output(line, pcm, channels);
            } catch (Exception unavailable) {
                if (line != null) close(line);
                return null;
            }
        }

        void close() {
            close(line);
        }

        private static void close(SourceDataLine line) {
            try {
                line.stop();
                line.close();
            } catch (RuntimeException ignored) {
                /* A failed close only affects this finished track. */
            }
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
