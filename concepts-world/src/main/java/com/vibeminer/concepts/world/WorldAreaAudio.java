package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Recorded AreaTable music and ambience relationships for a resolved terrain-area chain, plus a
 * bounded original-file read. It evaluates no game time, WMO interior, underwater, intro, player
 * condition or SoundKit mixing rule; those limits are reported in every plan.
 */
public final class WorldAreaAudio {
    public static final int MAX_ENTRIES = 64, MAX_FILE_BYTES = 16 << 20;

    public enum Kind {
        MUSIC,
        AMBIENCE
    }

    /** ZoneMusic and SoundAmbience record day in slot 0 and night in slot 1. */
    public enum Variant {
        DAY,
        NIGHT
    }

    public enum State {
        RECORDED,
        NONE_RECORDED,
        UNAVAILABLE
    }

    /**
     * One recorded sound relationship. {@code areaId} is the chain member that supplied it, which
     * is the parent zone when the subzone records none.
     */
    public record Track(
            Kind kind,
            long areaId,
            String areaName,
            long relationId,
            long soundKitId,
            List<Long> fileDataIds,
            int silenceMinMillis,
            int silenceMaxMillis) {
        public Track {
            Objects.requireNonNull(kind);
            fileDataIds = List.copyOf(fileDataIds);
            if (areaId <= 0
                    || relationId <= 0
                    || soundKitId <= 0
                    || fileDataIds.isEmpty()
                    || fileDataIds.size() > MAX_ENTRIES
                    || silenceMinMillis < 0
                    || silenceMaxMillis < silenceMinMillis) {
                throw new IllegalArgumentException("Invalid recorded audio track");
            }
        }
    }

    public record Selection(Kind kind, State state, Track track, String message) {
        public Selection {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(state);
            Objects.requireNonNull(message);
            if ((state == State.RECORDED) != (track != null)
                    || track != null && track.kind() != kind) {
                throw new IllegalArgumentException("Inconsistent audio selection");
            }
        }
    }

    public record Plan(
            long mapId,
            long areaId,
            Variant variant,
            Selection music,
            Selection ambience,
            List<String> issues) {
        public Plan {
            Objects.requireNonNull(variant);
            Objects.requireNonNull(music);
            Objects.requireNonNull(ambience);
            issues = List.copyOf(issues);
        }
    }

    public enum FileState {
        READ,
        UNAVAILABLE
    }

    /** Original encoded bytes held only in memory by the caller; never a stored derivative. */
    public record AudioFile(long fileDataId, FileState state, byte[] bytes, String issue) {
        public AudioFile {
            if ((state == FileState.READ) != (bytes != null)
                    || (bytes == null) == (issue == null)) {
                throw new IllegalArgumentException("Inconsistent audio file result");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes;
        }
    }

    static final String LIMITS =
            "Recorded terrain-area audio only: game time, WMO interiors, underwater, intro"
                    + " music, player conditions and SoundKit mixing are not evaluated.";

    private final BuildTables tables;
    private final BuildFiles files;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon().name("world-audio-source").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Cancellation lifetime = new Cancellation();
    private CompletableFuture<Void> closing;

    WorldAreaAudio(BuildTables tables, BuildFiles files) {
        this.tables = Objects.requireNonNull(tables);
        this.files = files;
    }

    CompletableFuture<Plan> plan(
            long mapId, WorldCurrentArea.Result area, Variant variant, Cancellation token) {
        Objects.requireNonNull(area);
        Objects.requireNonNull(variant);
        return submit(token, cancel -> resolve(tables, mapId, area, variant, cancel));
    }

    CompletableFuture<AudioFile> file(long fileDataId, Cancellation token) {
        return submit(token, cancel -> read(files, fileDataId, cancel));
    }

    @SuppressWarnings("try")
    private synchronized <T> CompletableFuture<T> submit(
            Cancellation token, java.util.function.Function<Cancellation, T> action) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World audio source closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        var cancel = new Cancellation();
                        try (var caller = token.onCancel(cancel::cancel);
                                var owner = lifetime.onCancel(cancel::cancel)) {
                            cancel.check();
                            result.complete(action.apply(cancel));
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("World audio request queue is full", full));
        }
        return result.copy();
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        var done = closing;
        // Queued requests still run, observe the canceled lifetime and fail promptly.
        lifetime.cancel();
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                            /* Await the one owned source request. */
                        }
                        done.complete(null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        done.completeExceptionally(interrupted);
                    }
                });
        return done.copy();
    }

    /** Pure resolution over borrowed tables; visible for authored-table tests. */
    static Plan resolve(
            BuildTables tables,
            long mapId,
            WorldCurrentArea.Result area,
            Variant variant,
            Cancellation token) {
        var issues = new ArrayList<String>();
        issues.add(LIMITS);
        if (area.state() != WorldCurrentArea.State.KNOWN || area.chain().isEmpty()) {
            String message = "Terrain area unavailable; no recorded area audio is selected.";
            return new Plan(
                    mapId,
                    area.areaId(),
                    variant,
                    new Selection(Kind.MUSIC, State.UNAVAILABLE, null, message),
                    new Selection(Kind.AMBIENCE, State.UNAVAILABLE, null, message),
                    issues);
        }
        return new Plan(
                mapId,
                area.areaId(),
                variant,
                select(tables, Kind.MUSIC, area.chain(), variant, token, issues),
                select(tables, Kind.AMBIENCE, area.chain(), variant, token, issues),
                issues);
    }

    private static Selection select(
            BuildTables tables,
            Kind kind,
            List<WorldService.Area> chain,
            Variant variant,
            Cancellation token,
            List<String> issues) {
        String label = kind == Kind.MUSIC ? "zone music" : "ambience";
        try {
            var areas = Table.open(tables, "AreaTable", token);
            String field = kind == Kind.MUSIC ? "ZoneMusic" : "AmbienceID";
            areas.reference(field, -1, Set.of(kind == Kind.MUSIC ? "ZoneMusic" : "SoundAmbience"));
            WorldService.Area source = null;
            long relation = 0;
            // The most specific subzone that records a relationship wins over its parents.
            for (int i = chain.size() - 1; i >= 0 && relation == 0; i--) {
                var row = areas.get(tables, chain.get(i).id(), token);
                relation = areas.number(row, field, -1);
                source = chain.get(i);
            }
            if (relation == 0) {
                return new Selection(
                        kind, State.NONE_RECORDED, null, "No " + label + " is recorded here.");
            }
            int slot = variant.ordinal();
            long kit;
            int silenceMin = 0, silenceMax = 0;
            if (kind == Kind.MUSIC) {
                var music = Table.open(tables, "ZoneMusic", token);
                // Recorded definitions retain the historical SoundEntries label for SoundKit IDs.
                music.reference("Sounds", slot, Set.of("SoundEntries", "SoundKit"));
                var row = music.get(tables, relation, token);
                kit = music.number(row, "Sounds", slot);
                long min = music.number(row, "SilenceIntervalMin", slot),
                        max = music.number(row, "SilenceIntervalMax", slot);
                if (min < 0 || max < min || max > 3_600_000) {
                    throw new Unavailable("ZoneMusic #" + relation + " silence is out of bounds");
                }
                silenceMin = (int) min;
                silenceMax = (int) max;
            } else {
                var ambience = Table.open(tables, "SoundAmbience", token);
                ambience.reference("AmbienceID", slot, Set.of("SoundKit"));
                kit = ambience.number(ambience.get(tables, relation, token), "AmbienceID", slot);
            }
            if (kit == 0) {
                return new Selection(
                        kind,
                        State.NONE_RECORDED,
                        null,
                        "No "
                                + variant.name().toLowerCase(java.util.Locale.ROOT)
                                + " "
                                + label
                                + " sound is recorded for "
                                + source
                                + ".");
            }
            var entries = Table.open(tables, "SoundKitEntry", token);
            entries.reference("SoundKitID", -1, Set.of("SoundKit"));
            entries.integer("FileDataID", -1);
            var files = new ArrayList<Long>();
            for (var row : entries.rows(tables, "SoundKitID", kit, MAX_ENTRIES, token, issues)) {
                long id = entries.number(row, "FileDataID", -1);
                if (id > 0 && id <= 0xffffffffL && !files.contains(id)) {
                    files.add(id);
                }
            }
            if (files.isEmpty()) {
                throw new Unavailable("SoundKit #" + kit + " has no readable file entries");
            }
            return new Selection(
                    kind,
                    State.RECORDED,
                    new Track(
                            kind,
                            source.id(),
                            source.toString(),
                            relation,
                            kit,
                            files,
                            silenceMin,
                            silenceMax),
                    "Recorded " + label + " from " + source + ".");
        } catch (Unavailable unavailable) {
            return new Selection(
                    kind,
                    State.UNAVAILABLE,
                    null,
                    "Recorded " + label + " unavailable: " + unavailable.getMessage());
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            return new Selection(
                    kind,
                    State.UNAVAILABLE,
                    null,
                    "Recorded " + label + " unavailable: " + reason(failure));
        }
    }

    static AudioFile read(BuildFiles files, long fileDataId, Cancellation token) {
        if (fileDataId <= 0 || fileDataId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid audio file ID");
        }
        if (files == null) {
            return new AudioFile(
                    fileDataId, FileState.UNAVAILABLE, null, "Build files are unavailable.");
        }
        try {
            var ref = FileTypes.FileRef.id(fileDataId);
            var stat = files.stat(ref, token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || stat.file().size() == null) {
                return new AudioFile(
                        fileDataId,
                        FileState.UNAVAILABLE,
                        null,
                        "Audio file #" + fileDataId + " is " + stat.state() + ".");
            }
            long size = stat.file().size();
            if (size < 32 || size > MAX_FILE_BYTES) {
                return new AudioFile(
                        fileDataId,
                        FileState.UNAVAILABLE,
                        null,
                        "Audio file #" + fileDataId + " is outside playback bounds.");
            }
            var bytes = files.readRange(ref, 0, (int) size, token).join();
            token.check();
            if (bytes.state() != FileTypes.FileReadState.COMPLETE
                    || bytes.bytes() == null
                    || bytes.bytes().length != size) {
                return new AudioFile(
                        fileDataId,
                        FileState.UNAVAILABLE,
                        null,
                        "Audio file #" + fileDataId + " is " + bytes.state() + ".");
            }
            return new AudioFile(fileDataId, FileState.READ, bytes.bytes(), null);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            return new AudioFile(
                    fileDataId,
                    FileState.UNAVAILABLE,
                    null,
                    "Audio file #" + fileDataId + ": " + reason(failure));
        }
    }

    private static String reason(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? "source unavailable" : error.getMessage();
    }

    private static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /** Verified-name integer fields only; array slots use the recorded {@code Name[i]} columns. */
    private static final class Table {
        final String name;
        final TableSchema schema;
        final List<Db2Types.Column> columns;

        private Table(String name, TableTypes.SchemaResult result) {
            this.name = name;
            schema = result.schema();
            columns = result.columns();
        }

        static Table open(BuildTables tables, String name, Cancellation token) {
            token.check();
            var result =
                    tables.schema(
                                    TableTypes.Ref.forName(name),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (result.table() == null
                    || result.table().state() != TableTypes.State.READY
                            && result.table().state() != TableTypes.State.PARTIAL
                    || result.schema() == null
                    || result.columns() == null) {
                throw new Unavailable(
                        name
                                + " is "
                                + (result.table() == null ? "absent" : result.table().state()));
            }
            return new Table(name, result);
        }

        private TableSchema.SchemaColumn field(String field, int slot) {
            var matches = schema.columns().stream().filter(c -> c.name().equals(field)).toList();
            if (matches.size() != 1) {
                throw new Unavailable(name + "." + field + " is not in the recorded schema");
            }
            var column = matches.getFirst();
            if (!column.verifiedName()
                    || column.kind() != TableSchema.ColumnKind.INT
                    || (slot < 0 ? column.arrayLength() != 1 : slot >= column.arrayLength())) {
                throw new Unavailable(name + "." + field + " has an unreviewed shape");
            }
            return column;
        }

        void integer(String field, int slot) {
            field(field, slot);
            index(field, slot);
        }

        void reference(String field, int slot, Set<String> targets) {
            var foreign = field(field, slot).foreignKey();
            if (foreign == null
                    || !targets.contains(foreign.table())
                    || !"ID".equals(foreign.column())) {
                throw new Unavailable(name + "." + field + " is not a recorded reference");
            }
            index(field, slot);
        }

        private int index(String field, int slot) {
            String wanted = slot < 0 ? field : field + "[" + slot + "]";
            int found = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(wanted)) {
                    if (found >= 0 || columns.get(i).interpretation() != null) {
                        throw new Unavailable(name + "." + wanted + " is ambiguous");
                    }
                    found = i;
                }
            }
            if (found < 0) {
                throw new Unavailable(name + "." + wanted + " is not readable");
            }
            return found;
        }

        long number(Db2Types.Row row, String field, int slot) {
            field(field, slot);
            int i = index(field, slot);
            if (i >= row.values().size()) {
                throw new Unavailable(name + " #" + row.id() + " is truncated");
            }
            try {
                return Long.parseLong(row.values().get(i));
            } catch (NumberFormatException invalid) {
                throw new Unavailable(name + " #" + row.id() + " has a non-integer " + field);
            }
        }

        Db2Types.Row get(BuildTables tables, long id, Cancellation token) {
            token.check();
            var found =
                    tables.get(
                                    TableTypes.Ref.forName(name),
                                    id,
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (found.outcome() != TableTypes.RowOutcome.FOUND || found.row() == null) {
                throw new Unavailable(name + " #" + id + " is " + found.outcome());
            }
            return found.row().row();
        }

        List<Db2Types.Row> rows(
                BuildTables tables,
                String filter,
                long value,
                int bound,
                Cancellation token,
                List<String> issues) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.forName(name),
                                            -1,
                                            bound,
                                            List.of(
                                                    new TableTypes.Filter(
                                                            filter,
                                                            TableTypes.FilterOp.EQUALS,
                                                            Long.toString(value),
                                                            null)),
                                            null),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (page.state() != TableTypes.PageState.OK) {
                throw new Unavailable(name + " rows are " + page.state());
            }
            if (page.nextAfter() != null) {
                String note = "Sound kits are bounded to " + bound + " file entries.";
                if (!issues.contains(note)) {
                    issues.add(note);
                }
            }
            return page.rows().stream().map(TableTypes.Row::row).toList();
        }
    }
}
