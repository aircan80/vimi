package com.vibeminer.concepts.comparison;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable session-only comparison contracts; null values never imply known absence. */
public final class ComparisonTypes {
    private ComparisonTypes() {}

    public enum Completeness {
        COMPLETE,
        PARTIAL,
        UNKNOWN
    }

    public enum ValueState {
        VALUE,
        KNOWN_EMPTY,
        UNKNOWN,
        PROTECTED,
        UNSUPPORTED,
        INVALID,
        LIMITED,
        NOT_APPLICABLE
    }

    public enum ValueKind {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        REFERENCE,
        BYTES_IDENTITY
    }

    public enum Aspect {
        CONTENT,
        EXTERNAL_EVIDENCE,
        AVAILABILITY,
        INTERPRETATION
    }

    public enum Membership {
        BOTH,
        ADDED,
        REMOVED,
        OBSERVED_ONLY_A,
        OBSERVED_ONLY_B
    }

    public enum Difference {
        EQUAL,
        CHANGED,
        UNKNOWN,
        INCOMPARABLE
    }

    public enum RunState {
        RUNNING,
        COMPLETED,
        LIMITED,
        CANCELED,
        FAILED,
        CLOSED
    }

    public record Descriptor(
            String identity,
            String product,
            String locale,
            String namespace,
            String scope,
            Map<String, String> dependencies) {
        public Descriptor {
            require(identity);
            require(product);
            require(locale);
            require(namespace);
            require(scope);
            dependencies = Map.copyOf(dependencies);
        }
    }

    public record Coverage(Completeness completeness, String scope, List<String> issues) {
        public Coverage {
            Objects.requireNonNull(completeness);
            require(scope);
            issues = List.copyOf(issues);
        }
    }

    public record Evidence(String source, String identity, String field, String raw) {
        public Evidence {
            require(source);
            require(identity);
        }
    }

    public record Field(
            String semantics,
            ValueKind kind,
            ValueState state,
            String value,
            Aspect aspect,
            List<Evidence> evidence) {
        public Field {
            require(semantics);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(state);
            Objects.requireNonNull(aspect);
            evidence = List.copyOf(evidence);
            if (state == ValueState.VALUE && value == null) {
                throw new IllegalArgumentException("Known value required");
            }
            if (state != ValueState.VALUE && value != null) {
                throw new IllegalArgumentException("Unknown/empty state carries no value");
            }
        }
    }

    /** key uses adapter-declared canonical lexical ordering, e.g. fixed-width unsigned IDs. */
    public record Record(String key, Map<String, Field> fields, List<Evidence> evidence) {
        public Record {
            require(key);
            fields = Map.copyOf(fields);
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * nextCursor null is exhaustion, not proof of completeness. COMPLETE is admissible only then.
     */
    public record InputPage(
            String identity, List<Record> rows, String nextCursor, Coverage coverage) {
        public InputPage {
            require(identity);
            rows = List.copyOf(rows);
            Objects.requireNonNull(coverage);
        }
    }

    public record FieldDelta(String key, Field a, Field b, Difference difference, Aspect aspect) {}

    public record Result(
            String key, Membership membership, Record a, Record b, List<FieldDelta> fields) {
        public Result {
            fields = List.copyOf(fields);
        }
    }

    public record Progress(
            RunState state,
            long observed,
            long stagedBytes,
            Coverage a,
            Coverage b,
            String message) {}

    public record ResultPage(long offset, List<Result> rows, Long nextOffset, Progress progress) {
        public ResultPage {
            rows = List.copyOf(rows);
        }
    }

    static void require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Nonblank identity required");
        }
    }
}
