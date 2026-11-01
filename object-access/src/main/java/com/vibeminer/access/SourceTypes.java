package com.vibeminer.access;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.RangePlanner.Location;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Origin;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable layer 2 messages matching the seven-layer C# object-access boundary. */
public final class SourceTypes {
    private SourceTypes() {}

    public enum FetchPriority {
        INTERACTIVE,
        BACKGROUND
    }

    public enum ObjectState {
        PRESENT,
        NOT_FOUND,
        UNAVAILABLE,
        REFUSED,
        NEEDS_NETWORK,
        STORE_FAILED,
        UNSUPPORTED
    }

    public enum ServedFrom {
        STORE,
        SHARED_STORE,
        CDN
    }

    public enum ObservationKind {
        VERSIONS,
        CDNS,
        BGDL,
        SUMMARY,
        DEFINITION,
        LISTFILE_RELEASE,
        LISTFILE_PART,
        LISTFILE_DELTA;

        public int historyKind() {
            return ordinal() + 1;
        }
    }

    public record CdnContext(CdnRoute route, List<CdnKey> archives) {
        public CdnContext {
            Objects.requireNonNull(route);
            archives = archives == null ? List.of() : List.copyOf(archives);
        }

        public CdnContext(CdnRoute route) {
            this(route, List.of());
        }
    }

    public record ObjectRequest(
            ObjectKey key, Long declaredLength, List<Location> locations, int order) {
        public ObjectRequest {
            Objects.requireNonNull(key);
            locations = locations == null ? List.of() : List.copyOf(locations);
        }

        public ObjectRequest(ObjectKey key) {
            this(key, null, List.of(), 0);
        }

        public ObjectRequest(ObjectKey key, Long declaredLength) {
            this(key, declaredLength, List.of(), 0);
        }
    }

    public record ObjectResult(
            ObjectKey key,
            ObjectState state,
            ServedFrom servedFrom,
            Origin origin,
            Long length,
            byte[] bytes,
            String code,
            String message,
            Instant observedAt) {
        public ObjectResult {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }

        public boolean isPresent() {
            return state == ObjectState.PRESENT;
        }

        public boolean retryable() {
            return state == ObjectState.NOT_FOUND
                    || state == ObjectState.UNAVAILABLE
                    || state == ObjectState.NEEDS_NETWORK;
        }

        public ObjectResult withoutBytes() {
            return new ObjectResult(
                    key, state, servedFrom, origin, length, null, code, message, observedAt);
        }
    }

    public record Observation(
            ObservationKind kind,
            String product,
            String region,
            URI url,
            Instant observedAt,
            byte[] bytes) {
        public Observation {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(product);
            Objects.requireNonNull(region);
            Objects.requireNonNull(url);
            Objects.requireNonNull(observedAt);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ObservationRequest(
            ObservationKind kind, String product, String region, String name, boolean record) {
        public ObservationRequest {
            Objects.requireNonNull(kind);
        }

        public ObservationRequest(ObservationKind kind, String product, String region) {
            this(kind, product, region, null, true);
        }

        public String problem() {
            if (kind == ObservationKind.LISTFILE_DELTA) {
                return "Listfile deltas are recorded, never fetched";
            }
            if (kind == ObservationKind.LISTFILE_RELEASE) {
                return !"wowlistfile".equals(product) || !"latest".equals(region) || name != null
                        ? "Invalid listfile release request"
                        : null;
            }
            if (kind == ObservationKind.LISTFILE_PART) {
                return !validTag(product) || partIndex(region) == null || name != null
                        ? "Invalid listfile part request"
                        : null;
            }
            if (kind == ObservationKind.DEFINITION) {
                return product == null
                                || !product.matches("[0-9a-f]{8}")
                                || !"wowdbdefs".equals(region)
                                || name == null
                                || !name.matches("[A-Za-z0-9_-]{1,128}")
                        ? "Invalid definition request"
                        : null;
            }
            return name == null ? null : "Only definition requests have a name";
        }

        public static String partRegion(int index) {
            if (index < 0 || index >= 64) {
                throw new IllegalArgumentException("Part is 0..63");
            }
            return "p" + index;
        }

        public static Integer partIndex(String region) {
            if (region == null || !region.matches("p(?:[0-9]|[1-5][0-9]|6[0-3])")) {
                return null;
            }
            return Integer.parseInt(region.substring(1));
        }

        public static boolean validTag(String tag) {
            return tag != null && tag.matches("[a-z0-9_-]{1,64}");
        }

        public static String recordProblem(Observation observation) {
            if (observation == null
                    || observation.kind() != ObservationKind.LISTFILE_PART
                            && observation.kind() != ObservationKind.LISTFILE_DELTA) {
                return "Only held listfile parts and deltas can be recorded";
            }
            if (!validTag(observation.product())
                    || !com.vibeminer.network.SourceUrls.asset(observation.product())
                            .equals(observation.url())) {
                return "Invalid held observation identity";
            }
            if (observation.kind() == ObservationKind.LISTFILE_PART
                    ? partIndex(observation.region()) == null
                    : !"delta".equals(observation.region())) {
                return "Invalid held observation region";
            }
            int limit = observation.kind() == ObservationKind.LISTFILE_PART ? 8 << 20 : 16 << 20;
            return observation.bytes.length < 1 || observation.bytes.length > limit
                    ? "Held observation exceeds its bounds"
                    : null;
        }
    }

    public record ObservationResult(
            ObjectState state,
            Observation observation,
            boolean recorded,
            String code,
            String message,
            Instant observedAt) {}

    public record ObjectProgress(long done, long total, long cdnBytes) {}

    public record Options(
            int minBackground,
            int maxBackground,
            int interactive,
            int queued,
            long inFlightBytes,
            long interactiveInFlightBytes,
            long inMemoryObjectBytes,
            long rangeFetchMinBytes,
            Duration hostCooldown,
            Clock clock) {
        public Options {
            Objects.requireNonNull(clock);
            Objects.requireNonNull(hostCooldown);
            if (minBackground < 1
                    || maxBackground < minBackground
                    || interactive < 1
                    || queued < 1
                    || inFlightBytes < 1
                    || interactiveInFlightBytes < 1
                    || inMemoryObjectBytes < 1
                    || inMemoryObjectBytes > (64L << 20)
                    || rangeFetchMinBytes < 1
                    || hostCooldown.isNegative()) {
                throw new IllegalArgumentException("Invalid source bounds");
            }
        }

        public Options() {
            this(
                    16,
                    32,
                    4,
                    256,
                    256L << 20,
                    64L << 20,
                    64L << 20,
                    64L << 20,
                    Duration.ofSeconds(30),
                    Clock.systemUTC());
        }
    }
}
