package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import java.io.Serial;
import java.util.List;

/** Per-host dated evidence; a network failure never proves persistent object absence. */
public final class CdnException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final java.util.ArrayList<Attempt> attempts;

    public CdnException(List<Attempt> attempts) {
        super(attempts.isEmpty() ? "No host was tried" : attempts.getLast().message());
        if (attempts.isEmpty()) {
            throw new IllegalArgumentException("A failure must name an attempt");
        }
        this.attempts = new java.util.ArrayList<>(attempts);
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public Failure failure() {
        return attempts.getLast().failure();
    }

    public String code() {
        return attempts.getLast().code();
    }

    public Failure overall() {
        if (attempts.stream().allMatch(a -> a.failure() == Failure.OFFLINE)) {
            return Failure.OFFLINE;
        }
        boolean bad =
                attempts.stream()
                        .anyMatch(
                                a ->
                                        a.failure() == Failure.VERIFICATION_FAILED
                                                || a.failure() == Failure.SIZE_CONFLICT);
        for (int i = attempts.size() - 1; i >= 0; i--) {
            Failure f = attempts.get(i).failure();
            if (passing(f) && (f != Failure.OFFLINE || bad)) {
                return f;
            }
        }
        if (attempts.stream().anyMatch(a -> a.failure() == Failure.VERIFICATION_FAILED)) {
            return Failure.VERIFICATION_FAILED;
        }
        if (attempts.stream().anyMatch(a -> a.failure() == Failure.SIZE_CONFLICT)) {
            return Failure.SIZE_CONFLICT;
        }
        if (attempts.stream()
                .allMatch(
                        a -> a.failure() == Failure.NOT_FOUND || a.failure() == Failure.OFFLINE)) {
            return Failure.NOT_FOUND;
        }
        return failure();
    }

    public boolean retryable() {
        return switch (overall()) {
            case SIZE_CONFLICT, VERIFICATION_FAILED, PROTOCOL, TOO_LARGE -> false;
            default -> true;
        };
    }

    private static boolean passing(Failure f) {
        return switch (f) {
            case OFFLINE, TIMEOUT, NETWORK, HTTP_STATUS, RANGE_IGNORED, RANGE_MISMATCH -> true;
            default -> false;
        };
    }
}
