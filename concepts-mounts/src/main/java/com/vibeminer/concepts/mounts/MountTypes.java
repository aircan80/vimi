package com.vibeminer.concepts.mounts;

import java.util.List;

/**
 * Selected-build mount facts; collection ownership and current character eligibility are unknown.
 */
public final class MountTypes {
    private MountTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public record Mount(
            long id,
            String name,
            String description,
            String sourceText,
            Long sourceSpellId,
            Long sourceType,
            Long mountTypeId,
            Long conditionId,
            Long flags) {}

    public record Variant(long recordId, long displayId, Long conditionId, String sourceTable) {}

    public record Catalog(State state, List<Mount> mounts, List<String> issues, String message) {
        public Catalog {
            mounts = List.copyOf(mounts);
            issues = List.copyOf(issues);
        }
    }

    public record Overview(
            State state, Mount mount, List<Variant> variants, List<String> issues, String message) {
        public Overview {
            variants = List.copyOf(variants);
            issues = List.copyOf(issues);
        }
    }
}
