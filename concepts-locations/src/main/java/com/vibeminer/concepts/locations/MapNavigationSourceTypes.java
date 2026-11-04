package com.vibeminer.concepts.locations;

import java.util.List;
import java.util.Objects;

/** Detached source evidence; no prepared reader, schema or row ownership escapes. */
public final class MapNavigationSourceTypes {
    private MapNavigationSourceTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED,
        STALE,
        LIMIT_EXCEEDED
    }

    public record TableStamp(
            String table,
            String contentKey,
            String layout,
            String graphIdentity,
            String sourceDigest,
            String schemaDigest,
            long rows) {
        public TableStamp {
            if (!List.of("UiMap", "UiMapAssignment").contains(table)
                    || contentKey == null
                    || !contentKey.matches("[0-9a-fA-F]{32}")
                    || layout == null
                    || !layout.matches("[0-9a-fA-F]{8}")
                    || graphIdentity == null
                    || !graphIdentity.matches("[0-9a-f]{32}")
                    || sourceDigest == null
                    || !sourceDigest.matches("[0-9a-f]{64}")
                    || schemaDigest == null
                    || !schemaDigest.matches("[0-9a-f]{64}")
                    || rows < 0) {
                throw new IllegalArgumentException("Invalid navigation table stamp");
            }
        }
    }

    public record Result(
            State state,
            MapNavigationGraph graph,
            List<TableStamp> sources,
            List<String> diagnostics) {
        public Result {
            Objects.requireNonNull(state);
            if (sources.size() > 2
                    || diagnostics.size() > 64
                    || diagnostics.stream().anyMatch(s -> s == null || s.length() > 128)) {
                throw new IllegalArgumentException("Navigation result exceeds metadata limits");
            }
            sources = List.copyOf(sources);
            diagnostics = List.copyOf(diagnostics);
            if ((state == State.READY || state == State.PARTIAL) != (graph != null)) {
                throw new IllegalArgumentException("Navigation result state disagrees with graph");
            }
        }

        public static Result unsupported() {
            return new Result(
                    State.UNSUPPORTED, null, List.of(), List.of("STRICT_CAPTURE_UNSUPPORTED"));
        }
    }
}
