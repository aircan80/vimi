package com.vibeminer.concepts.comparison.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonPolicyLimit;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.effects.PreparedItemEffectProjection;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Binding;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PairPlan;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceProfile;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceStamp;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Session-bound compact effects dictionaries; the owner keeps the lease through result paging. */
public final class EffectsSnapshot implements AutoCloseable {
    public static final String NAMESPACE = "item-effects.parent-own-link.v1";
    public static final long METADATA_BYTES = 1L << 20;
    private static final long SOURCE_METADATA_BYTES = 128L << 10;

    public enum Side {
        A,
        B
    }

    public record Endpoint(
            BuildRef build, String locale, String sessionId, Map<String, String> dependencies) {
        public Endpoint {
            Objects.requireNonNull(build);
            text(locale, 128);
            text(sessionId, 128);
            dependencies = boundedMap(dependencies, 64L << 10);
        }
    }

    public record SourceRef(String session, int index, Side side, SourceKind kind) {
        public SourceRef {
            text(session, 128);
            Objects.requireNonNull(side);
            Objects.requireNonNull(kind);
            if (index != slot(side, kind)) {
                throw new IllegalArgumentException("Effects source slot differs");
            }
        }
    }

    public record SourceInfo(
            SourceRef ref,
            SourceStamp stamp,
            SourceState state,
            Compilation compilation,
            Map<String, String> metadata) {
        public SourceInfo {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(stamp);
            Objects.requireNonNull(state);
            Objects.requireNonNull(compilation);
            metadata = boundedMap(metadata, SOURCE_METADATA_BYTES);
        }

        public SourceProfile profile() {
            return compilation.profile();
        }
    }

    public record FieldSpec(String key, SourceKind kind, Binding a, Binding b) {}

    public record Scope(
            IdentityAdmission identity,
            RelationMode mode,
            List<FieldSpec> assessed,
            boolean targetAssessed,
            List<String> reasons) {
        public Scope {
            if (assessed.size() > 22 || reasons.size() > 32) {
                throw new IllegalArgumentException("Effects scope limit");
            }
            assessed = List.copyOf(assessed);
            reasons = List.copyOf(reasons);
        }
    }

    public static final class Limit extends ComparisonPolicyLimit {
        private static final long serialVersionUID = 1L;

        public Limit(String message) {
            super(message);
        }
    }

    private final TempBudget budget;
    private final TempBudget.Reservation metadata;
    private final String session = UUID.randomUUID().toString();
    private final Map<Side, Endpoint> endpoints = new EnumMap<>(Side.class);
    private final Map<Side, RelationMode> modes = new EnumMap<>(Side.class);
    private final SourceInfo[] sources = new SourceInfo[4];
    private final Map<String, Long> omissions = new LinkedHashMap<>();
    // Fixed envelope for paired masks/reasons and both final descriptor dictionaries.
    private long retained = 128L << 10;
    private Scope scope;
    private String fieldScope;
    private boolean closed;

    public EffectsSnapshot(TempBudget budget, Endpoint a, Endpoint b, Cancellation token)
            throws TempBudget.Limit {
        this.budget = Objects.requireNonNull(budget);
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        if (!a.build().product().equals(b.build().product()) || !a.locale().equals(b.locale())) {
            throw new IllegalArgumentException("Effects endpoints differ in product/locale");
        }
        long bytes = endpointBytes(a) + endpointBytes(b);
        if (retained + bytes > METADATA_BYTES) {
            throw new Limit("Effects endpoint metadata limit");
        }
        metadata = budget.reserveMemory("effects-source-snapshot", METADATA_BYTES, token);
        endpoints.put(Side.A, a);
        endpoints.put(Side.B, b);
        retained += bytes;
    }

    public TempBudget budget() {
        return budget;
    }

    public synchronized TempBudget.Reservation metadataReservation() {
        open();
        return metadata;
    }

    public synchronized Endpoint endpoint(Side side) {
        open();
        return endpoints.get(Objects.requireNonNull(side));
    }

    /** Compact registration only; the caller retains capture/endpoint ownership on every path. */
    public synchronized SourceRef register(
            Side side,
            SourceStamp stamp,
            SourceState state,
            Compilation compilation,
            Map<String, String> sourceMetadata) {
        open();
        if (scope != null) {
            throw new IllegalStateException("Effects scope sealed");
        }
        var endpoint = endpoint(side);
        if (!endpoint.build().equals(stamp.build())
                || !endpoint.locale().equals(stamp.locale())
                || stamp.fileDataId() != stamp.kind().profile().fileDataId()
                || !stamp.tableName().equals(stamp.kind().profile().table())) {
            throw new IllegalArgumentException("Effects source differs from sealed endpoint");
        }
        int index = slot(side, stamp.kind());
        if (sources[index] != null) {
            throw new IllegalArgumentException("Effects source slot already registered");
        }
        if (compilation.profile() != null
                && (!compilation.profile().stamp().equals(stamp)
                        || compilation.profile().state() != state)) {
            throw new IllegalArgumentException("Effects compact profile/source mismatch");
        }
        long bytes =
                2048L
                        + 8L * stamp.toString().length()
                        + 2L * mapBytes(sourceMetadata, SOURCE_METADATA_BYTES);
        if (compilation.profile() != null) {
            // Conservative compact estimate checked before copying any metadata collections.
            bytes += 4L * compilation.profile().toString().length();
        }
        bytes += 4L * compilation.omissions().toString().length();
        if (retained + bytes > METADATA_BYTES) {
            throw new Limit("Effects snapshot reservation exceeded");
        }
        var ref = new SourceRef(session, index, side, stamp.kind());
        var info = new SourceInfo(ref, stamp, state, compilation, sourceMetadata);
        sources[index] = info;
        retained += bytes;
        return ref;
    }

    /** Must follow positive relation-source qualification; absent schemas cannot select a mode. */
    public synchronized void selectMode(Side side, RelationMode mode) {
        open();
        Objects.requireNonNull(mode);
        if (scope != null || modes.containsKey(side)) {
            throw new IllegalStateException("Effects mode already sealed");
        }
        SourceKind kind =
                mode == RelationMode.DIRECT ? SourceKind.DIRECT_EFFECT : SourceKind.BRIDGE;
        var info = sources[slot(side, kind)];
        if (info == null
                || info.ref().kind() != kind
                || info.compilation().identity() != IdentityAdmission.ADMITTED) {
            throw new IllegalArgumentException("Effects mode requires qualified relation profile");
        }
        modes.put(side, mode);
    }

    public synchronized SourceInfo source(SourceRef ref) {
        open();
        if (!session.equals(ref.session())) {
            throw new IllegalArgumentException("Effects source belongs to another session");
        }
        var source = sources[ref.index()];
        if (source == null || !source.ref().equals(ref)) {
            throw new IllegalArgumentException("Unknown effects source");
        }
        return source;
    }

    public synchronized SourceInfo sourceAt(int index) {
        open();
        if (index < 0 || index >= sources.length || sources[index] == null) {
            throw new IllegalArgumentException("Unknown effects source slot");
        }
        return sources[index];
    }

    public synchronized SourceRef ref(Side side, SourceKind kind) {
        open();
        var info = sources[slot(side, kind)];
        if (info == null || info.ref().kind() != kind) {
            throw new IllegalArgumentException("Effects source not registered");
        }
        return info.ref();
    }

    public synchronized SourceProfile profile(SourceRef ref) {
        return source(ref).profile();
    }

    public synchronized SourceRef relationRef(Side side) {
        open();
        var mode = modes.get(side);
        if (mode == null) {
            throw new IllegalStateException("Effects relation mode not selected");
        }
        return ref(
                side, mode == RelationMode.DIRECT ? SourceKind.DIRECT_EFFECT : SourceKind.BRIDGE);
    }

    public synchronized RelationMode mode(Side side) {
        open();
        return modes.get(side);
    }

    public synchronized Scope sealScope() {
        open();
        if (scope != null) {
            return scope;
        }
        var reasons = new ArrayList<String>();
        var fields = new ArrayList<FieldSpec>();
        var mode = modes.get(Side.A);
        if (mode == null || mode != modes.get(Side.B)) {
            reasons.add(
                    "Relation profiles unavailable or incompatible; no cross-profile identity mapping.");
            for (var side : Side.values()) {
                var notes = new ArrayList<String>();
                for (var info : sources) {
                    if (info == null || info.ref().side() != side) {
                        continue;
                    }
                    String code =
                            info.compilation().omissions().isEmpty()
                                    ? ""
                                    : "/" + info.compilation().omissions().getFirst().code();
                    notes.add(info.stamp().tableName() + "=" + info.state() + code);
                }
                reasons.add(side + ": mode=" + modes.get(side) + "; " + String.join(", ", notes));
            }
            return seal(new Scope(IdentityAdmission.UNKNOWN, null, fields, false, reasons));
        }
        var relation = pair(relationRef(Side.A), relationRef(Side.B), fields, reasons);
        if (relation.identity() != IdentityAdmission.ADMITTED) {
            return seal(new Scope(relation.identity(), mode, List.of(), false, reasons));
        }
        boolean target = mode == RelationMode.DIRECT;
        if (mode == RelationMode.BRIDGE) {
            var a = sources[slot(Side.A, SourceKind.RETAIL_EFFECT)];
            var b = sources[slot(Side.B, SourceKind.RETAIL_EFFECT)];
            if (a != null && b != null && a.profile() != null && b.profile() != null) {
                var p = pair(a.ref(), b.ref(), fields, reasons);
                target = p.identity() == IdentityAdmission.ADMITTED && !p.assessed().isEmpty();
            } else {
                reasons.add(
                        "Effect inputs globally unassessed; qualified bridge references retained.");
            }
        }
        reasons.add("Observed links only; relation-set membership is not certified.");
        return seal(new Scope(IdentityAdmission.ADMITTED, mode, fields, target, reasons));
    }

    private PairPlan pair(SourceRef a, SourceRef b, List<FieldSpec> fields, List<String> reasons) {
        var p = PreparedItemEffectProjection.pair(profile(a), profile(b));
        for (var binding : p.assessed()) {
            fields.add(new FieldSpec(binding.key(), a.kind(), binding.left(), binding.right()));
        }
        if (!p.omissions().isEmpty()) {
            reasons.add(
                    a.kind()
                            + ": "
                            + p.omissions().stream()
                                    .map(
                                            o ->
                                                    Objects.toString(o.field(), "source")
                                                            + " "
                                                            + o.code())
                                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        return p;
    }

    private Scope seal(Scope value) {
        scope = value;
        var parts = new TreeMap<String, String>();
        parts.put("policy", PreparedItemEffectProjection.POLICY_REVISION);
        parts.put("identity", value.identity().name());
        parts.put("mode", String.valueOf(value.mode()));
        parts.put("target", Boolean.toString(value.targetAssessed()));
        for (var field : value.assessed()) {
            parts.put(field.key(), field.a().fingerprint());
        }
        fieldScope = hash(canonical(parts));
        return value;
    }

    public synchronized Scope scope() {
        open();
        if (scope == null) {
            throw new IllegalStateException("Effects scope not sealed");
        }
        return scope;
    }

    /** Bounded counted omissions, never an accumulating per-row list. */
    public synchronized void noteOmission(String code) {
        open();
        text(code, 128);
        if (!omissions.containsKey(code) && omissions.size() >= 31) {
            code = "OTHER_OMISSIONS";
        }
        omissions.merge(code, 1L, Math::addExact);
    }

    public synchronized Map<String, Long> omissionCounts() {
        open();
        return Map.copyOf(omissions);
    }

    public synchronized Map<String, String> dependencies(Side side) {
        open();
        var result = new TreeMap<>(endpoint(side).dependencies());
        result.put("effects.policy", PreparedItemEffectProjection.POLICY_REVISION);
        result.put("effects.endpointBuild", endpoint(side).build().toString());
        result.put("effects.locale", endpoint(side).locale());
        result.put("effects.omissionCounts", omissions.toString());
        result.put("effects.endpointSession", endpoint(side).sessionId());
        result.put("effects.fieldScope", fieldScope == null ? "pending" : fieldScope);
        if (scope != null) {
            result.put("effects.scope.identity", scope.identity().name());
            result.put("effects.scope.mode", String.valueOf(scope.mode()));
            result.put("effects.scope.targetAssessed", Boolean.toString(scope.targetAssessed()));
            result.put(
                    "effects.scope.assessed",
                    scope.assessed().stream()
                            .map(FieldSpec::key)
                            .collect(java.util.stream.Collectors.joining(", ")));
            result.put("effects.scope.reasons", String.join("; ", scope.reasons()));
        }
        for (var info : sources) {
            if (info == null || info.ref().side() != side) {
                continue;
            }
            String prefix = "effects." + info.ref().kind().name() + ".";
            var stamp = info.stamp();
            result.put(prefix + "state", info.state().name());
            result.put(prefix + "identity", info.compilation().identity().name());
            result.put(prefix + "content", Objects.toString(stamp.contentKey(), "unknown"));
            result.put(prefix + "layout", Objects.toString(stamp.layoutHash(), "unknown"));
            result.put(
                    prefix + "definition", Objects.toString(stamp.definitionSha256(), "unknown"));
            result.put(prefix + "block", Objects.toString(stamp.blockIdentity(), "unknown"));
            result.put(prefix + "graph", Objects.toString(stamp.graphIdentity(), "unknown"));
            result.put(prefix + "reader", Objects.toString(stamp.readerRevision(), "unknown"));
            result.put(prefix + "options", Objects.toString(stamp.readerOptions(), "unknown"));
            result.put(prefix + "encoding", Objects.toString(stamp.rowEncoding(), "unknown"));
            result.put(
                    prefix + "keys", Objects.toString(stamp.effectiveKeysFingerprint(), "unknown"));
            result.put(prefix + "issue.state", stamp.issueEvidence().state());
            result.put(
                    prefix + "issue.policy",
                    Objects.toString(stamp.issueEvidence().policy(), "unknown"));
            result.put(
                    prefix + "issue.producer",
                    Objects.toString(stamp.issueEvidence().producer(), "unknown"));
            result.put(
                    prefix + "issue.digest",
                    Objects.toString(stamp.issueEvidence().bindingDigest(), "unknown"));
            result.put(prefix + "omissions", info.compilation().omissions().toString());
            info.metadata().forEach((key, value) -> result.put(prefix + "capture." + key, value));
        }
        return Map.copyOf(result);
    }

    public synchronized ComparisonTypes.Descriptor descriptor(Side side) {
        var scope = scope();
        var values = dependencies(side);
        return new ComparisonTypes.Descriptor(
                hash(session + side + canonical(values)),
                endpoint(side).build().product(),
                endpoint(side).locale(),
                NAMESPACE,
                fieldScope,
                values);
    }

    public synchronized long estimatedRetainedBytes() {
        open();
        return retained;
    }

    private static int slot(Side side, SourceKind kind) {
        return (Objects.requireNonNull(side) == Side.A ? 0 : 2)
                + (kind == SourceKind.RETAIL_EFFECT ? 1 : 0);
    }

    private static long endpointBytes(Endpoint value) {
        return 2048L + 4L * value.sessionId().length() + mapBytes(value.dependencies(), 64L << 10);
    }

    private static Map<String, String> boundedMap(Map<String, String> values, long maxBytes) {
        mapBytes(values, maxBytes);
        return Map.copyOf(values);
    }

    private static long mapBytes(Map<String, String> values, long maxBytes) {
        Objects.requireNonNull(values);
        if (values.size() > 192) {
            throw new Limit("Effects metadata entry limit");
        }
        long size = 0;
        for (var entry : values.entrySet()) {
            text(entry.getKey(), 512);
            Objects.requireNonNull(entry.getValue());
            size += 128L + 4L * entry.getKey().length() + 4L * entry.getValue().length();
            if (size > maxBytes) {
                throw new Limit("Effects metadata byte limit");
            }
        }
        return size;
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Invalid effects metadata text");
        }
    }

    private static String canonical(Map<String, String> values) {
        var result = new StringBuilder();
        new TreeMap<>(values)
                .forEach(
                        (key, value) ->
                                result.append(key.length())
                                        .append(':')
                                        .append(key)
                                        .append(value.length())
                                        .append(':')
                                        .append(value));
        return result.toString();
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("Effects snapshot closed");
        }
        metadata.requireMemory(budget, METADATA_BYTES);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        java.util.Arrays.fill(sources, null);
        endpoints.clear();
        modes.clear();
        omissions.clear();
        scope = null;
        metadata.close();
    }
}
