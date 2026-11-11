package com.vibeminer.activity;

import java.util.Objects;

/**
 * Optional application-visible HTTP response-body telemetry. Never receives request identifiers.
 */
public interface TransferObserver {
    enum Kind {
        CDN,
        BUILD_METADATA,
        LISTFILE,
        TACT_KEYS,
        EG_LINK,
        POLESTAR,
        QUESTIE
    }

    interface Attempt extends AutoCloseable {
        void headersReceived();

        void readStarted();

        /** Positive bytes are recorded even when close raced the read; -1 is EOF. */
        void readFinished(int count);

        @Override
        void close();
    }

    Attempt NOOP_ATTEMPT =
            new Attempt() {
                public void headersReceived() {}

                public void readStarted() {}

                public void readFinished(int count) {}

                public void close() {}
            };
    TransferObserver NONE = kind -> NOOP_ATTEMPT;

    Attempt begin(Kind kind);

    /** Optional telemetry exceptions cannot change transport behavior. */
    static TransferObserver safe(TransferObserver observer) {
        Objects.requireNonNull(observer);
        if (observer == NONE) {
            return NONE;
        }
        return kind -> {
            final Attempt delegate;
            try {
                delegate = Objects.requireNonNull(observer.begin(kind));
            } catch (RuntimeException ignored) {
                return NOOP_ATTEMPT;
            }
            return new Attempt() {
                public void headersReceived() {
                    try {
                        delegate.headersReceived();
                    } catch (RuntimeException ignored) {
                    }
                }

                public void readStarted() {
                    try {
                        delegate.readStarted();
                    } catch (RuntimeException ignored) {
                    }
                }

                public void readFinished(int count) {
                    try {
                        delegate.readFinished(count);
                    } catch (RuntimeException ignored) {
                    }
                }

                public void close() {
                    try {
                        delegate.close();
                    } catch (RuntimeException ignored) {
                    }
                }
            };
        };
    }
}
