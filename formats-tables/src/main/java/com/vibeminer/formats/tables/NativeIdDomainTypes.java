package com.vibeminer.formats.tables;

/** Selected-ID evidence is separate from the existing uncertified whole-capture membership. */
public final class NativeIdDomainTypes {
    public static final String REVISION = "native-selected-id-domain-v1";

    private NativeIdDomainTypes() {}

    public enum SourceAccess {
        CONFIGURED_SOURCE
    }

    public enum State {
        READABLE_UNIQUE,
        PROTECTED_ID,
        NOT_OBSERVED,
        UNSUPPORTED_DOMAIN,
        SOURCE_CHANGED,
        BUSY,
        UNAVAILABLE,
        LIMIT_EXCEEDED,
        CANCELED
    }

    /** Only the actual client-native inspector can construct this detached positive witness. */
    public sealed interface Witness permits ClientBuildTables.NativeIdWitness {
        long selectedId();

        long readableOrdinal();

        long sourceFileDataId();

        String sourceContentKey();

        long consumedLength();

        String layoutHash();

        String receiptFingerprint();

        int excludedCount();

        String excludedDigest();

        String evidenceDigest();

        String profileRevision();
    }

    public record Result(State state, Witness witness) {
        public Result {
            java.util.Objects.requireNonNull(state);
            if ((state == State.READABLE_UNIQUE) != (witness != null)) {
                throw new IllegalArgumentException("Selected-ID result/witness mismatch");
            }
        }

        public static Result unsupported() {
            return new Result(State.UNSUPPORTED_DOMAIN, null);
        }
    }
}
