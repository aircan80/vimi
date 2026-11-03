package com.vibeminer.concepts.comparison.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.items.PreparedItemTypes;
import com.vibeminer.concepts.items.PreparedItemTypes.Binding;
import com.vibeminer.concepts.items.PreparedItemTypes.Compilation;
import com.vibeminer.concepts.items.PreparedItemTypes.FieldAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.Selection;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceStamp;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Four detached source dictionaries sharing one held metadata reservation through result paging.
 */
public final class ItemsSnapshot implements AutoCloseable {
    public static final long METADATA_BYTES = 1L << 20;

    public enum Side {
        A,
        B
    }

    public enum IdentityScope {
        ADMITTED,
        PARTIAL,
        INCOMPATIBLE,
        UNAVAILABLE
    }

    public enum FieldScope {
        ADMITTED,
        PARTIAL,
        INCOMPATIBLE,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public record SourceRef(int index, Side side, SourceKind kind) {
        public SourceRef {
            Objects.requireNonNull(side);
            Objects.requireNonNull(kind);
            if (index != slot(side, kind)) {
                throw new IllegalArgumentException("Source reference differs from slot");
            }
        }
    }

    public record SourceInfo(
            SourceRef ref,
            SourceStamp stamp,
            SourceState state,
            IdentityAdmission identity,
            FieldAdmission fields,
            SourceProfile profile,
            List<PreparedItemTypes.Omission> omissions) {
        public SourceInfo {
            omissions = List.copyOf(omissions);
        }
    }

    public record BindingRef(int index, SourceRef source, Binding binding) {}

    public record FieldSpec(String key, BindingRef a, BindingRef b) {}

    public record SourcePair(
            SourceKind kind,
            IdentityScope identity,
            FieldScope fields,
            List<FieldSpec> assessed,
            List<String> reasons) {
        public SourcePair {
            assessed = List.copyOf(assessed);
            reasons = List.copyOf(reasons);
        }
    }

    private final TempBudget budget;
    private final TempBudget.Reservation metadata;
    private final Selection selection;
    private final Map<Side, Map<String, String>> endpoints = new java.util.EnumMap<>(Side.class);
    private final Map<SourceRef, Map<String, String>> sourceMetadata = new LinkedHashMap<>();
    private final SourceInfo[] sources = new SourceInfo[4];
    private final List<BindingRef> bindings = new ArrayList<>();
    private final Map<SourceRef, Map<PreparedItemTypes.SourceQualifiedKey, Integer>> bindingIds =
            new LinkedHashMap<>();
    private List<SourcePair> pairs;
    private String scope;
    private long retained = 32768;
    private boolean closed;

    public ItemsSnapshot(TempBudget budget, Selection selection, Cancellation token)
            throws TempBudget.Limit {
        this.budget = Objects.requireNonNull(budget);
        this.selection = Objects.requireNonNull(selection);
        metadata = budget.reserveMemory("items-source-snapshot", METADATA_BYTES, token);
    }

    public Selection selection() {
        return selection;
    }

    public TempBudget budget() {
        return budget;
    }

    /** Borrowed only; the coordinator closes this snapshot after core/input/paging drain. */
    public synchronized TempBudget.Reservation metadataReservation() {
        open();
        return metadata;
    }

    public synchronized SourceRef register(
            Side side, SourceStamp stamp, SourceState state, Compilation compilation)
            throws IOException {
        open();
        Objects.requireNonNull(side);
        Objects.requireNonNull(stamp);
        Objects.requireNonNull(state);
        Objects.requireNonNull(compilation);
        if (pairs != null) {
            throw new IllegalStateException("Items scope already sealed");
        }
        int index = slot(side, stamp.kind());
        if (sources[index] != null) {
            throw new IllegalArgumentException("Source slot already registered");
        }
        var profile = compilation.profile();
        if (profile != null
                && (!profile.stamp().equals(stamp)
                        || profile.state() != state
                        || !profile.selection().equals(selection))) {
            throw new IllegalArgumentException("Profile/source context differs");
        }
        if (stamp.fileDataId() != stamp.kind().fileDataId()
                || !stamp.tableName().equalsIgnoreCase(stamp.kind().tableName())) {
            throw new IllegalArgumentException("Source namespace differs");
        }
        for (var source : sources) {
            if (source != null) {
                if (!source.stamp().build().product().equals(stamp.build().product())
                        || !source.stamp().locale().equals(stamp.locale())) {
                    throw new IllegalArgumentException("Items endpoints differ in product/locale");
                }
                if (source.ref().side() == side && !source.stamp().build().equals(stamp.build())) {
                    throw new IllegalArgumentException("Endpoint sources differ");
                }
            }
        }
        long bytes = profile == null ? stampBytes(stamp) : profile.estimatedRetainedBytes();
        bytes += 4096L + compilation.omissions().size() * 8192L;
        int count = profile == null ? 0 : profile.bindings().size();
        bytes += count * 512L;
        if (retained + bytes > METADATA_BYTES || bindings.size() + count > 256) {
            throw new ItemsStageCodec.Limit("Items snapshot metadata exceeds reservation");
        }
        var ref = new SourceRef(index, side, stamp.kind());
        var ids = new LinkedHashMap<PreparedItemTypes.SourceQualifiedKey, Integer>();
        if (profile != null) {
            for (var binding : profile.bindings()) {
                if (ids.put(binding.key(), bindings.size() + ids.size()) != null) {
                    throw new IllegalArgumentException("Duplicate source binding");
                }
            }
        }
        if (profile != null) {
            for (var binding : profile.bindings()) {
                bindings.add(new BindingRef(bindings.size(), ref, binding));
            }
        }
        sources[index] =
                new SourceInfo(
                        ref,
                        stamp,
                        state,
                        compilation.identity(),
                        compilation.fields(),
                        profile,
                        compilation.omissions());
        bindingIds.put(ref, Map.copyOf(ids));
        retained += bytes;
        return ref;
    }

    /** Registers bounded endpoint provenance; retained descriptor copies are charged here. */
    public synchronized void registerEndpoint(Side side, Map<String, String> values) {
        open();
        Objects.requireNonNull(side);
        Objects.requireNonNull(values);
        if (pairs != null || endpoints.containsKey(side)) {
            throw new IllegalStateException("Endpoint already registered or scope sealed");
        }
        if (values.size() > 64) {
            throw new ItemsStageCodec.Limit("Endpoint metadata count");
        }
        long bytes = 4096;
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getKey().length() > 128
                    || entry.getValue().length() > 1024) {
                throw new IllegalArgumentException("Endpoint metadata bounds");
            }
            bytes += 1024L + 12L * (entry.getKey().length() + entry.getValue().length());
        }
        if (retained + bytes > METADATA_BYTES) {
            throw new ItemsStageCodec.Limit("Endpoint metadata reservation");
        }
        endpoints.put(side, Map.copyOf(values));
        retained += bytes;
    }

    public synchronized void registerSourceMetadata(SourceRef ref, Map<String, String> values) {
        source(ref);
        if (pairs != null || sourceMetadata.containsKey(ref)) {
            throw new IllegalStateException("Source metadata sealed or already registered");
        }
        if (values.size() > 16) {
            throw new ItemsStageCodec.Limit("Source metadata count");
        }
        long bytes = 4096;
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getKey().length() > 128
                    || entry.getValue().length() > 1024) {
                throw new IllegalArgumentException("Source metadata bounds");
            }
            bytes += 1024L + 12L * (entry.getKey().length() + entry.getValue().length());
        }
        if (retained + bytes > METADATA_BYTES) {
            throw new ItemsStageCodec.Limit("Source metadata reservation");
        }
        sourceMetadata.put(ref, Map.copyOf(values));
        retained += bytes;
    }

    public synchronized SourceRef registerUnavailable(
            Side side, SourceStamp stamp, SourceState state, String reason) throws IOException {
        if (state == SourceState.READABLE || state == SourceState.PARTIAL) {
            throw new IllegalArgumentException("Unavailable source state required");
        }
        if (reason == null || reason.length() > 128) {
            throw new IllegalArgumentException("Reason bounds");
        }
        return register(
                side,
                stamp,
                state,
                new Compilation(
                        IdentityAdmission.UNKNOWN,
                        FieldAdmission.UNSUPPORTED,
                        null,
                        List.of(
                                new PreparedItemTypes.Omission(
                                        null, null, PreparedItemTypes.CellState.UNKNOWN, reason))));
    }

    public synchronized SourceInfo source(SourceRef ref) {
        open();
        var value = sources[ref.index()];
        if (value == null || !value.ref().equals(ref)) {
            throw new IllegalArgumentException("Unregistered source");
        }
        return value;
    }

    public synchronized SourceRef ref(Side side, SourceKind kind) {
        open();
        var ref = new SourceRef(slot(side, kind), side, kind);
        source(ref);
        return ref;
    }

    public synchronized SourceProfile profile(SourceRef ref) {
        return source(ref).profile();
    }

    public synchronized List<SourceInfo> sources() {
        open();
        return java.util.Arrays.stream(sources).filter(Objects::nonNull).toList();
    }

    public synchronized BindingRef binding(int index) {
        open();
        if (index < 0 || index >= bindings.size()) {
            throw new IllegalArgumentException("Unknown binding index");
        }
        return bindings.get(index);
    }

    public synchronized int bindingIndex(
            SourceRef source, PreparedItemTypes.SourceQualifiedKey key) {
        open();
        source(source);
        var id = bindingIds.get(source).get(key);
        if (id == null) {
            throw new IllegalArgumentException("Cell not in source profile");
        }
        return id;
    }

    public synchronized List<SourcePair> sealPairScope() throws IOException {
        open();
        if (pairs != null) {
            return pairs;
        }
        if (java.util.Arrays.stream(sources).anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Register all source outcomes before sealing");
        }
        var result = new ArrayList<SourcePair>();
        for (var kind : SourceKind.values()) {
            result.add(admit(kind));
        }
        long extra = 8192;
        for (var pair : result) {
            for (var spec : pair.assessed()) {
                extra += 2048L + 16L * spec.key().length();
            }
        }
        if (retained + extra > METADATA_BYTES) {
            throw new ItemsStageCodec.Limit("Items field scope exceeds snapshot reservation");
        }
        pairs = List.copyOf(result);
        retained += extra;
        var scopeFields = new TreeMap<String, String>();
        for (var pair : pairs) {
            scopeFields.put(pair.kind().name() + ".identity", pair.identity().name());
            scopeFields.put(pair.kind().name() + ".fields", pair.fields().name());
            for (var spec : pair.assessed()) {
                scopeFields.put(spec.key(), spec.a().binding().key().semantics());
            }
        }
        scope = hash(canonical(scopeFields));
        return pairs;
    }

    public synchronized List<SourcePair> pairScope() {
        open();
        if (pairs == null) {
            throw new IllegalStateException("Items pair scope not sealed");
        }
        return pairs;
    }

    public synchronized String fieldScope() {
        pairScope();
        return scope;
    }

    public synchronized int admittedFieldCount() {
        return pairScope().stream().mapToInt(p -> p.assessed().size()).sum();
    }

    public synchronized List<String> reasons() {
        return pairScope().stream().flatMap(p -> p.reasons().stream()).limit(64).toList();
    }

    public synchronized boolean canPublishIds(SourceRef ref) {
        var source = source(ref);
        var pair =
                pairScope().stream().filter(p -> p.kind() == ref.kind()).findFirst().orElseThrow();
        return source.identity() == IdentityAdmission.ADMITTED
                && pair.identity() != IdentityScope.INCOMPATIBLE;
    }

    public synchronized Map<String, String> dependencies(Side side) {
        open();
        var values = new TreeMap<String, String>();
        if (side != null) {
            values.putAll(endpoints.getOrDefault(side, Map.of()));
        }
        values.put("items.policy", "items-snapshot-v1");
        values.put(
                "items.selection",
                selection.families().stream().map(Enum::name).sorted().toList().toString());
        values.put("items.projection", selection.policyRevision());
        values.put("items.fieldScope", scope == null ? "pending" : scope);
        for (var source : sources) {
            if (source != null && (side == null || source.ref().side() == side)) {
                String prefix =
                        "items."
                                + source.ref().side().name()
                                + "."
                                + source.ref().kind().name()
                                + ".";
                sourceMetadata
                        .getOrDefault(source.ref(), Map.of())
                        .forEach((key, value) -> values.put(prefix + "capture." + key, value));
                values.put(
                        prefix + "omissions",
                        boundedManifest(
                                source.omissions().stream()
                                        .map(
                                                o ->
                                                        (o.family() == null
                                                                        ? "source"
                                                                        : o.family().name())
                                                                + ":"
                                                                + o.fact()
                                                                + ":"
                                                                + o.state()
                                                                + ":"
                                                                + o.code())
                                        .toList()));
                if (pairs != null) {
                    var pair =
                            pairs.stream()
                                    .filter(p -> p.kind() == source.ref().kind())
                                    .findFirst()
                                    .orElseThrow();
                    values.put(prefix + "pair.identity", pair.identity().name());
                    values.put(prefix + "pair.fields", pair.fields().name());
                    values.put(
                            prefix + "assessed",
                            boundedManifest(pair.assessed().stream().map(FieldSpec::key).toList()));
                    values.put(prefix + "unassessed.reasons", boundedManifest(pair.reasons()));
                }
                var s = source.stamp();
                values.put(prefix + "build", s.build().toString());
                values.put(prefix + "locale", s.locale());
                values.put(prefix + "state", source.state().name());
                values.put(prefix + "identity", source.identity().name());
                values.put(prefix + "fields", source.fields().name());
                if (source.profile() != null) {
                    values.put(
                            prefix + "identity.fingerprint",
                            source.profile().identityFingerprint());
                    values.put(prefix + "fields.fingerprint", source.profile().fieldFingerprint());
                }
                put(values, prefix + "content", s.contentKey());
                put(values, prefix + "layout", s.layoutHash());
                put(values, prefix + "definition", s.definitionSha256());
                put(values, prefix + "block", s.blockIdentity());
                put(values, prefix + "reader", s.readerRevision());
                put(values, prefix + "options", s.readerOptions());
                put(values, prefix + "rows", s.rowEncoding());
                put(values, prefix + "graph", s.graphIdentity());
                put(values, prefix + "keys", s.effectiveKeysFingerprint());
            }
        }
        return Map.copyOf(values);
    }

    public synchronized ComparisonTypes.Descriptor descriptor(Side side) {
        pairScope();
        var source = source(ref(side, SourceKind.ITEM));
        return new ComparisonTypes.Descriptor(
                hash(dependenciesText(side)),
                source.stamp().build().product(),
                source.stamp().locale(),
                "items.ItemID.v1",
                fieldScope(),
                dependencies(side));
    }

    public synchronized long estimatedRetainedBytes() {
        open();
        return retained;
    }

    private SourcePair admit(SourceKind kind) {
        var a = source(ref(Side.A, kind));
        var b = source(ref(Side.B, kind));
        boolean left = a.identity() == IdentityAdmission.ADMITTED,
                right = b.identity() == IdentityAdmission.ADMITTED;
        IdentityScope identity =
                left && right
                        ? Objects.equals(
                                        a.profile().identityFingerprint(),
                                        b.profile().identityFingerprint())
                                ? IdentityScope.ADMITTED
                                : IdentityScope.INCOMPATIBLE
                        : left || right ? IdentityScope.PARTIAL : IdentityScope.UNAVAILABLE;
        if (identity != IdentityScope.ADMITTED) {
            return new SourcePair(
                    kind,
                    identity,
                    FieldScope.UNAVAILABLE,
                    List.of(),
                    List.of(kind.name() + ": identity scope " + identity.name()));
        }
        if (!readable(a.state()) || !readable(b.state())) {
            return new SourcePair(
                    kind,
                    identity,
                    FieldScope.UNAVAILABLE,
                    List.of(),
                    List.of(kind.name() + ": source unavailable; fields unassessed"));
        }
        if (!a.profile().fieldFingerprint().equals(b.profile().fieldFingerprint())) {
            return new SourcePair(
                    kind,
                    identity,
                    FieldScope.INCOMPATIBLE,
                    List.of(),
                    List.of(kind.name() + ": incompatible fields; qualified IDs retained"));
        }
        var assessed = new ArrayList<FieldSpec>();
        for (var entry : bindingIds.get(a.ref()).entrySet()) {
            var other = bindingIds.get(b.ref()).get(entry.getKey());
            if (other == null) {
                continue;
            }
            var x = binding(entry.getValue());
            var y = binding(other);
            if (x.binding().kind() != y.binding().kind()) {
                continue;
            }
            assessed.add(new FieldSpec(canonicalKey(entry.getKey()), x, y));
        }
        assessed.sort(java.util.Comparator.comparing(FieldSpec::key));
        return new SourcePair(
                kind,
                identity,
                assessed.isEmpty()
                        ? FieldScope.UNSUPPORTED
                        : a.omissions().isEmpty() && b.omissions().isEmpty()
                                ? FieldScope.ADMITTED
                                : FieldScope.PARTIAL,
                assessed,
                List.of(
                        kind.name()
                                + ": "
                                + assessed.size()
                                + " source-qualified fields assessed; membership unknown"));
    }

    private String dependenciesText(Side side) {
        return canonical(new TreeMap<>(dependencies(side)));
    }

    private static String boundedManifest(List<String> entries) {
        var text = new StringBuilder();
        for (var entry : entries) {
            if (text.length() + entry.length() + 1 > 8192) {
                text.append("[manifest limited]");
                break;
            }
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(entry);
        }
        return text.isEmpty() ? "none" : text.toString();
    }

    private static String canonical(Map<String, String> values) {
        var text = new StringBuilder();
        values.forEach(
                (key, value) ->
                        text.append(key.length())
                                .append(':')
                                .append(key)
                                .append(value.length())
                                .append(':')
                                .append(value));
        return text.toString();
    }

    static String canonicalKey(PreparedItemTypes.SourceQualifiedKey key) {
        return "item."
                + key.source().name()
                + "."
                + key.fact().name()
                + "."
                + HexFormat.of().formatHex(key.field().getBytes(StandardCharsets.UTF_8))
                + "."
                + (key.array() ? key.arraySlot() : "scalar");
    }

    private static boolean readable(SourceState state) {
        return state == SourceState.READABLE || state == SourceState.PARTIAL;
    }

    private static void put(Map<String, String> values, String name, String value) {
        values.put(name, value == null ? "unknown" : value);
    }

    private static long stampBytes(SourceStamp s) {
        return 2048L + 4L * s.toString().length();
    }

    private static int slot(Side side, SourceKind kind) {
        return (side == Side.A ? 0 : 2) + (kind == SourceKind.ITEM ? 0 : 1);
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("Items snapshot closed");
        }
        metadata.requireMemory(budget, METADATA_BYTES);
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

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        java.util.Arrays.fill(sources, null);
        bindings.clear();
        bindingIds.clear();
        endpoints.clear();
        sourceMetadata.clear();
        pairs = null;
        scope = null;
        metadata.close();
    }
}
