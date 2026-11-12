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

        /** True once only the buffered end of this stream remains to be read. */
        default boolean nearEnd() {
            return false;
        }

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
            @Override public boolean nearEnd() { return stream.decoded(); }
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
            return open(expected, from, fileId, token, problem, false);
        }

        /** A quiet open reports no status: a look-ahead must not interrupt "Playing". */
        private Pcm open(long expected, Source from, long fileId, Cancellation token,
                String[] problem, boolean quiet) {
            if (!quiet) report(false, "Loading recorded " + label() + " file #" + fileId + "…");
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
                    if (attempt == BUSY_STATUS_ATTEMPTS && !quiet) {
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
            // At most one look-ahead per voice: the next file of a multi-file ambience, opened
            // once the current one is near its end.
            CompletableFuture<Pcm> ahead = null;
            long aheadId = 0;
            if (!adopt(pcm)) return null;
            Output out = null;
            try {
                out = openOutput(pcm, device);
                if (out == null) return current(expected) ? OUTPUT_UNAVAILABLE : null;
                reportPlaying(playing, fileId);
                var gain = new Envelope(0, level(playing, fileId));
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
                        if (gain.position > 0) {
                            handOff(new Tail(pcm, out, gain, playing.volume(fileId), cached,
                                    loop));
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
                        if (!live) {
                            // Replaced just as this pass ended: hand off as a tail all the same,
                            // which loops a single-file ambience while it fades, or plays out what
                            // the line holds, instead of closing the line on its buffered audio.
                            if (gain.position > 0) {
                                handOff(new Tail(pcm, out, gain, playing.volume(fileId), cached,
                                        loop));
                                pcm = null;
                                out = null;
                            }
                            return null;
                        }
                        frames = 0;
                        if (live && kind == WorldAreaAudio.Kind.AMBIENCE) {
                            if (loop != null && loop.size() > 0) cached = loop.toByteArray();
                            loop = null;
                            long nextId;
                            Pcm next;
                            if (cached != null) {
                                // One in-memory copy serves every loop pass.
                                nextId = fileId;
                                next = pcm instanceof MemoryPcm memory ? memory
                                        : new MemoryPcm(pcm.sampleRate(), pcm.channels(), cached);
                            } else {
                                next = null;
                                nextId = 0;
                                if (ahead != null) {
                                    // The look-ahead is the next file; a failed one falls back to
                                    // opening it now, which reports its own status.
                                    next = ahead.join();
                                    nextId = aheadId;
                                    ahead = null;
                                }
                                if (next == null) {
                                    nextId = pick(playing);
                                    next = open(expected, from, nextId, token, problem);
                                }
                            }
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
                                out = openOutput(next, device);
                                if (out == null) {
                                    next.close();
                                    return current(expected) ? OUTPUT_UNAVAILABLE : null;
                                }
                                // A new line starting at full gain would click.
                                gain.position = 0;
                                ramping = true;
                            }
                            if (next != pcm) pcm.close();
                            pcm = next;
                            if (!adopt(pcm)) return null;
                            fileId = nextId;
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
                    int bytes = out.downmix ? AudioOutput.downmix(block, count) : count;
                    apply(block, bytes, out.channels, gain, 1, level(playing, fileId),
                            ramping ? out.quickStep : out.step, out.step);
                    if (gain.position >= 1) ramping = false;
                    // This track has audio: the replaced one starts its fade-out with this block.
                    if (previous != null) previous.release();
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
                    if (ahead == null && kind == WorldAreaAudio.Kind.AMBIENCE && cached == null
                            && loop == null && pcm.nearEnd()) {
                        long id = pick(playing);
                        aheadId = id;
                        ahead = CompletableFuture.supplyAsync(
                                () -> open(expected, from, id, token, new String[1], true),
                                task -> Thread.ofVirtual().name("world-audio-look-ahead")
                                        .start(task));
                    }
                }
            } finally {
                if (ahead != null) discard(ahead);
                if (pcm != null) pcm.close();
                synchronized (this) {
                    active = null;
                }
                if (out != null) out.close();
            }
        }

        /** Linear amplitude the slider and the recorded volumes ask for. */
        private double level(WorldAreaAudio.Track playing, long fileId) {
            return level(playing.volume(fileId));
        }

        private double level(double recorded) {
            double slider = volume;
            return slider * slider * recorded;
        }

        /**
         * Opens a line for {@code pcm}. A device that refuses a line only because its lines are
         * in use (it cannot play two at once) gets every sounding tail's line back first; a
         * refused format leaves the tails alone.
         */
        private Output openOutput(Pcm pcm, int device) {
            boolean[] busy = new boolean[1];
            var out = Output.open(lines, pcm, device, busy);
            if (out != null || !busy[0]) return out;
            java.util.List<Tail> sounding;
            synchronized (this) {
                sounding = java.util.List.copyOf(tails);
            }
            if (sounding.isEmpty()) return null;
            for (var tail : sounding) tail.yieldLine();
            return Output.open(lines, pcm, device, busy);
        }

        /**
         * A replaced track still sounding on its own line and thread. It plays at full level while
         * held, for at most {@link #HOLD_MAX_MILLIS}, then fades out over {@link #FADE_SECONDS}
         * and closes its decoder and line once the line has played what it holds. A single-file
         * ambience keeps looping while it fades.
         */
        private final class Tail {
            private final Pcm pcm;
            private final Output out;
            /** The recorded linear volume of the playing file. */
            private final double recorded;
            private final Envelope gain;
            private final long holdUntil = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(HOLD_MAX_MILLIS);
            private final Thread thread;
            private byte[] cached;
            private MemoryPcm memory;
            private java.io.ByteArrayOutputStream loop;
            private volatile boolean released, stopped, yielding;

            Tail(Pcm pcm, Output out, Envelope gain, double recorded, byte[] cached,
                    java.io.ByteArrayOutputStream loop) {
                this.pcm = pcm;
                this.out = out;
                this.gain = gain;
                this.recorded = recorded;
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

            /**
             * Fades out within {@link #YIELD_FADE_SECONDS}, plays what the line holds and waits
             * for the line to close. A tail stuck in a stalled read is stopped instead.
             */
            void yieldLine() {
                yielding = true;
                released = true;
                try {
                    thread.join(2_000);
                    if (thread.isAlive()) {
                        stop();
                        thread.join(500);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            private void run() {
                Pcm reading = pcm;
                double quick = 1 / (YIELD_FADE_SECONDS * pcm.sampleRate());
                // A natural end or a finished fade plays out the line's buffer before closing;
                // a failure or a stop does not.
                boolean drain = false;
                try {
                    while (!stopped) {
                        boolean hold = !released && System.nanoTime() < holdUntil;
                        if (!hold && gain.position <= 0) {
                            drain = true;
                            return;
                        }
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
                            if (cached == null) {
                                drain = true;
                                return;
                            }
                            if (memory == null) {
                                memory = new MemoryPcm(pcm.sampleRate(), pcm.channels(), cached);
                            }
                            reading = memory;
                            continue;
                        }
                        if (loop != null) {
                            if (loop.size() <= MAX_LOOP_BYTES - count) loop.write(block, 0, count);
                            else loop = null;
                        }
                        int bytes = out.downmix ? AudioOutput.downmix(block, count) : count;
                        apply(block, bytes, out.channels, gain, hold ? 1 : 0, level(recorded),
                                yielding ? quick : out.step, out.step);
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
                    if (drain && !stopped) {
                        try {
                            out.line.drain();
                        } catch (RuntimeException removed) {
                            /* The line is closed below either way. */
                        }
                    }
                    out.close();
                    synchronized (Channel.this) {
                        tails.remove(this);
                        if (held == this) held = null;
                        settle();
                    }
                }
            }
        }

        /**
         * Closes an unused look-ahead once its open completes. Its open observes the changed
         * state and stops promptly. Only a closing channel waits for it, briefly, so close does
         * not complete while it still owns a decoder; on a track change the next track starts
         * at once, even if a source read ignores cancellation.
         */
        private void discard(CompletableFuture<Pcm> ahead) {
            ahead.thenAccept(pcm -> {
                if (pcm != null) pcm.close();
            });
            synchronized (this) {
                if (!closed) return;
            }
            try {
                ahead.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception notReady) {
                /* Closed by the callback above once it completes. */
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
         * the device refuses the line; a partly opened line is closed. {@code busy} is set when
         * the refusal is a {@link javax.sound.sampled.LineUnavailableException}, which the
         * platform raises for lines in use, not for unsupported formats.
         */
        static Output open(Lines lines, Pcm pcm, int device, boolean[] busy) {
            busy[0] = false;
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
                busy[0] = unavailable instanceof javax.sound.sampled.LineUnavailableException;
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

    /** One voice's fade position (0 silent to 1 full) and its current linear level. */
    static final class Envelope {
        double position, level;

        Envelope(double position, double level) {
            this.position = position;
            this.level = level;
        }
    }

    /**
     * Scales big-endian frames in place. The fade position moves by {@code step} per frame toward
     * {@code toward}, so a whole fade lasts the same time at any volume. The level moves toward
     * {@code level} by {@code levelStep} per frame, so volume changes never click. The amplitude is
     * {@code sin(position * pi / 2) * level}: an equal-power curve, so two voices crossing at the
     * same rate keep their summed power instead of dipping at mid-fade.
     */
    static void apply(byte[] block, int count, int channels, Envelope gain, double toward,
            double level, double step, double levelStep) {
        int frame = channels * 2;
        for (int i = 0; i + frame <= count; i += frame) {
            if (gain.position < toward) gain.position = Math.min(toward, gain.position + step);
            else if (gain.position > toward) gain.position = Math.max(toward, gain.position - step);
            if (gain.level < level) gain.level = Math.min(level, gain.level + levelStep);
            else if (gain.level > level) gain.level = Math.max(level, gain.level - levelStep);
            double amplitude = Math.sin(gain.position * Math.PI / 2) * gain.level;
            for (int c = 0; c < frame; c += 2) {
                int sample = (short) (block[i + c] << 8 | block[i + c + 1] & 0xff);
                int scaled = (int) Math.round(sample * amplitude);
                scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                block[i + c] = (byte) (scaled >> 8);
                block[i + c + 1] = (byte) scaled;
            }
        }
    }
}
