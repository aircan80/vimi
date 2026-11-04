package com.vibeminer.concepts.locations;

import com.vibeminer.concepts.locations.MapNavigationTypes.ReadState;
import com.vibeminer.concepts.locations.MapNavigationTypes.Table;
import com.vibeminer.concepts.locations.MapNavigationTypes.TableRead;
import java.util.List;
import java.util.Objects;

/**
 * Selected-page resource evidence only; a map's type/name never establishes image/floor absence.
 */
public record MapResourceEvidence(
        Resource resource, State state, int matched, List<TableRead> tables, List<FileRead> files) {
    public enum Resource {
        ARTWORK_MANIFEST,
        ARTWORK_IMAGES,
        FLOORS
    }

    public enum State {
        NOT_CHECKED,
        READY,
        PARTIAL,
        NONE_RECORDED,
        UNAVAILABLE,
        UNSUPPORTED,
        AMBIGUOUS
    }

    public enum FileState {
        READY,
        PARTIAL,
        NOT_LOCAL,
        PROTECTED,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public record FileRead(long fileId, String contentKey, FileState state) {
        public FileRead {
            MapNavigationTypes.id(fileId, false);
            Objects.requireNonNull(state);
            if (contentKey != null && !MapNavigationTypes.hex(contentKey, 32)
                    || (state == FileState.READY || state == FileState.PARTIAL)
                            && contentKey == null) {
                throw new IllegalArgumentException("Invalid map image evidence");
            }
        }
    }

    public MapResourceEvidence {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(state);
        Objects.requireNonNull(tables);
        Objects.requireNonNull(files);
        if (tables.size() > 8 || files.size() > 256 || matched < -1 || matched > 256) {
            throw new IllegalArgumentException("Map resource evidence limit");
        }
        tables = List.copyOf(tables);
        files = List.copyOf(files);
        var kinds = java.util.EnumSet.noneOf(Table.class);
        for (var table : tables) {
            if (!kinds.add(table.table())) {
                throw new IllegalArgumentException("Conflicting table evidence");
            }
        }
        if (state == State.NOT_CHECKED && (matched != -1 || !tables.isEmpty() || !files.isEmpty())
                || state == State.NONE_RECORDED && matched != 0
                || state == State.READY && matched < 1
                || state == State.AMBIGUOUS && matched < 2
                || state == State.PARTIAL && matched < 0
                || (state == State.UNAVAILABLE || state == State.UNSUPPORTED) && matched != -1) {
            throw new IllegalArgumentException("Contradictory map resource evidence");
        }
        if (state == State.NONE_RECORDED) {
            if (resource == Resource.ARTWORK_IMAGES
                    || !files.isEmpty()
                    || resource == Resource.ARTWORK_MANIFEST
                            && !complete(tables, Table.ART_RELATION)
                    || resource == Resource.FLOORS
                            && (!complete(tables, Table.GROUP)
                                    || !complete(tables, Table.GROUP_MEMBER))) {
                throw new IllegalArgumentException(
                        "No complete evidence of recorded resource absence");
            }
        }
        if (state == State.READY) {
            boolean valid =
                    switch (resource) {
                        case ARTWORK_MANIFEST ->
                                matched == 1
                                        && complete(tables, Table.ART_RELATION)
                                        && complete(tables, Table.ART)
                                        && complete(tables, Table.ART_STYLE)
                                        && complete(tables, Table.ART_TILE);
                        case ARTWORK_IMAGES ->
                                files.size() == matched
                                        && files.stream()
                                                .allMatch(file -> file.state() == FileState.READY);
                        case FLOORS ->
                                complete(tables, Table.GROUP)
                                        && complete(tables, Table.GROUP_MEMBER);
                    };
            if (!valid) {
                throw new IllegalArgumentException(
                        "Ready resource lacks qualified source evidence");
            }
        }
    }

    public static MapResourceEvidence unchecked(Resource resource) {
        return new MapResourceEvidence(resource, State.NOT_CHECKED, -1, List.of(), List.of());
    }

    private static boolean complete(List<TableRead> tables, Table table) {
        return tables.stream()
                .anyMatch(read -> read.table() == table && read.state() == ReadState.READY);
    }
}
