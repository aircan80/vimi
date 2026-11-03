package com.vibeminer.network;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Immutable values at the layer 1 network boundary; no HTTP-library or storage types. */
public final class NetworkTypes {
    private NetworkTypes() {}

    public enum ObjectKind {
        CONFIG,
        DATA,
        INDEX
    }

    public enum Failure {
        OFFLINE,
        NOT_FOUND,
        HTTP_STATUS,
        TIMEOUT,
        NETWORK,
        RANGE_IGNORED,
        RANGE_MISMATCH,
        SIZE_CONFLICT,
        VERIFICATION_FAILED,
        PROTOCOL,
        TOO_LARGE
    }

    public enum DocumentKind {
        VERSIONS,
        CDNS,
        BGDL,
        SUMMARY
    }

    @FunctionalInterface
    public interface Check {
        String refusal(byte[] bytes);
    }

    /**
     * A fresh chunk is owned by the sink; its future provides backpressure. Exceptions belong to
     * the caller.
     */
    @FunctionalInterface
    public interface Sink {
        CompletableFuture<Void> accept(byte[] bytes, Cancellation cancellation);
    }

    public record Request(
            ObjectKind kind,
            CdnKey key,
            Long offset,
            Long length,
            Long expectedTotal,
            Long maxLength,
            Check check) {
        public Request {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(key);
            if (length != null && length < 1
                    || maxLength != null && maxLength < 1
                    || offset != null
                            && (offset < 0 || length == null || offset > Long.MAX_VALUE - length)
                    || expectedTotal != null
                            && (offset == null
                                    || expectedTotal < 1
                                    || offset > expectedTotal - length)
                    || maxLength != null
                            && (offset != null || length != null && length > maxLength)) {
                throw new IllegalArgumentException("Invalid object or range bounds");
            }
        }

        public static Request whole(ObjectKind kind, CdnKey key) {
            return new Request(kind, key, null, null, null, null, null);
        }

        public static Request whole(
                ObjectKind kind, CdnKey key, Long expectedLength, Long maximum, Check check) {
            return new Request(kind, key, null, expectedLength, null, maximum, check);
        }

        public static Request range(
                ObjectKind kind, CdnKey key, long offset, long length, Long total, Check check) {
            return new Request(kind, key, offset, length, total, null, check);
        }

        public boolean isRange() {
            return offset != null;
        }
    }

    public record Attempt(
            String host,
            URI url,
            Failure failure,
            Integer status,
            Instant observedAt,
            String message,
            String code)
            implements java.io.Serializable {}

    public record Reply(
            String host,
            URI url,
            String httpVersion,
            long length,
            Long total,
            byte[] bytes,
            Instant observedAt,
            Duration headersAfter,
            Duration elapsed,
            List<Attempt> failed) {
        public Reply {
            bytes = bytes == null ? null : bytes.clone();
            failed = List.copyOf(failed);
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    public record VersionReply(
            String product,
            String region,
            DocumentKind kind,
            URI url,
            Instant observedAt,
            byte[] bytes,
            Duration elapsed) {
        public VersionReply {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record DefinitionReply(
            String tableName, String revision, URI url, Instant observedAt, byte[] bytes) {
        public DefinitionReply {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record TactKeyReply(URI url, Instant observedAt, byte[] bytes) {
        public TactKeyReply {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ListfileReply(URI url, Instant observedAt, byte[] bytes) {
        public ListfileReply {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ListfilePart(
            URI url, Instant observedAt, byte[] bytes, long offset, long totalLength) {
        public ListfilePart {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
