package com.vibeminer.formats.tables;

import com.vibeminer.formats.db2.Db2Types;
import java.util.Objects;

/** Explicit preparation observations; not a source authorization or strict row-memory contract. */
public final class NativePreparationTypes {
    private NativePreparationTypes() {}

    /** Uses the owning BuildFiles policy, which can include network acquisition. */
    public enum SourceAccess {
        CONFIGURED_SOURCE
    }

    public enum State {
        READY,
        UNSUPPORTED,
        UNAVAILABLE,
        SOURCE_CHANGED
    }

    public enum Phase {
        PREPARING,
        VALIDATING
    }

    public record Progress(Phase phase) {
        public Progress {
            Objects.requireNonNull(phase);
        }
    }

    /** Detached current native graph observation, with no borrowed generated/capture owner. */
    public record Stamp(
            String graphIdentity,
            String contentKey,
            String layoutHash,
            CapturedRowsTypes.IssueChannelEvidence evidence,
            long rowCount,
            Db2Types.Completion completion) {
        public Stamp {
            if (graphIdentity == null
                    || !graphIdentity.matches("[0-9a-f]{32}")
                    || contentKey == null
                    || !contentKey.matches("[0-9a-f]{32}")
                    || layoutHash == null
                    || !layoutHash.matches("[0-9a-fA-F]{8}")
                    || rowCount < 0) {
                throw new IllegalArgumentException("Invalid native preparation stamp");
            }
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(completion);
            if (evidence.state() != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                    || !graphIdentity.equals(evidence.graphIdentity())) {
                throw new IllegalArgumentException("Unbound native preparation evidence");
            }
        }
    }

    public record Result(State state, SourceAccess access, TableTypes.Entry table, Stamp stamp) {
        public Result {
            Objects.requireNonNull(state);
            Objects.requireNonNull(access);
            if ((state == State.READY) != (stamp != null)) {
                throw new IllegalArgumentException("Native preparation result state");
            }
        }
    }
}
