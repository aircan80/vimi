package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A persistent index from a humanoid NPC's extended display ID to its finished appearance: the
 * saved composite's key and model, and what the resolve, omission count and plan decided (visible
 * parts, items, page facts, omissions). A hit restores the composite and skips all three.
 *
 * <p>An entry is used only while everything it was built from is unchanged; anything else is a miss
 * and the NPC is prepared as before:
 *
 * <ul>
 *   <li>The key names the build, the locale, the extended display ID, the codec revisions the
 *       composite key names, and a digest of the NPC path's own code ({@link #code}): a new app
 *       version that changes how an NPC is resolved or planned never reads an older entry.
 *   <li>The entry names every table this races handle read through its tables before it was written
 *       ({@link #recording}), with a digest of each one's state, content key, counts, schema, page
 *       identity and client hotfix view ({@link #fingerprint}). A table that changed, or a hotfix
 *       view that is refused or changed, is a miss.
 *   <li>Every file the appearance was composed from (model, bake and layers, skeleton, and the
 *       attached item files when they were composed in this handle) is stated again and must keep
 *       its content key; the composite itself checks its skin and skeleton when restored.
 * </ul>
 *
 * <p>Only complete appearances are written, once the composite is in the generated store; an
 * appearance shown with something unread is never written.
 */
final class RaceNpcIndex {
    /** Bumped when the entry format or what an entry means changes. */
    static final String REVISION = "npc-appearance-index-v1";

    private static final int MAGIC = 0x4e414931; // "NAI1"

    /** Bounds on one entry's lists, so a damaged entry cannot ask for unbounded memory. */
    static final int MAX_LIST = 65536;

    private static final int MAX_ENTRY_BYTES = 4 << 20;

    private final BuildTables tables;
    private final BuildFiles files;
    private final GeneratedStore generated;

    /** Every table this handle read through {@link #recording}, by reference. */
    private final Set<TableTypes.Ref> touched = ConcurrentHashMap.newKeySet();

    /** Set when the handle used a table call that names no single table: nothing is written. */
    private volatile boolean untracked;

    /**
     * Each touched table's fingerprint, taken once per handle: a handle's tables are fixed for its
     * lifetime, as the races component's remembered results already assume. A changed table is seen
     * by the next handle.
     */
    private final Map<TableTypes.Ref, String> fingerprints = new ConcurrentHashMap<>();

    RaceNpcIndex(BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this.tables = tables;
        this.files = files;
        this.generated = generated;
    }

    boolean available() {
        return files != null && generated != null && CODE != null;
    }

    /** One finished NPC appearance, as written. Sources map a file to {@link #source}. */
    record Entry(
            GeneratedKey composite,
            long modelFileDataId,
            Set<Integer> visible,
            List<RaceAppearanceTypes.Item> items,
            List<String> issues,
            int omittedItems,
            int omittedAttachments,
            Map<Long, String> sources,
            List<TableTypes.Ref> tables,
            byte[] digest) {
        Entry {
            visible = Set.copyOf(new TreeSet<>(visible));
            items = List.copyOf(items);
            issues = List.copyOf(issues);
            sources = Map.copyOf(new TreeMap<>(sources));
            tables = List.copyOf(tables);
            digest = digest.clone();
        }
    }

    // --- Table reads ---

    /**
     * {@code raw} with every table it is asked about recorded: the tables an entry depends on are
     * the ones this handle actually read, including those only a shared relation index reads. Every
     * call is passed through unchanged.
     */
    BuildTables recording(BuildTables raw) {
        return (BuildTables)
                java.lang.reflect.Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            record(method, args);
                            try {
                                return method.invoke(raw, args);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    private void record(java.lang.reflect.Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return;
        }
        switch (method.getName()) {
            case "build", "locale", "close", "closeAsync" -> {
                return;
            }
            default -> {}
        }
        TableTypes.Ref ref = null;
        if (args != null && args.length > 0) {
            ref =
                    switch (args[0]) {
                        case TableTypes.Ref direct -> direct;
                        case TableTypes.PageRequest page -> page.table();
                        case TableTypes.FollowRequest follow -> follow.table();
                        default -> null;
                    };
        }
        // A follow also reads the referenced table, and a listing or a reverse reference reads
        // tables it does not name: entries could not name what they depend on.
        if (ref == null || method.getName().equals("follow")) {
            untracked = true;
        }
        if (ref != null) {
            touched.add(ref);
        }
    }

    /** The touched tables, for tests. */
    Set<TableTypes.Ref> touched() {
        return Set.copyOf(touched);
    }

    boolean untracked() {
        return untracked;
    }

    /**
     * What a table's rows depend on, read without decoding rows: its state, content key, counts,
     * reason, schema, page identity (the base table's source, keys and definition, or the hotfix
     * view's) and the hotfix view's metadata. Null when the table cannot be described now.
     */
    String fingerprint(TableTypes.Ref ref, Cancellation token) {
        token.check();
        try {
            var schema = tables.schema(ref, TableTypes.Priority.INTERACTIVE, token).join();
            var entry = schema.table();
            var text = new StringBuilder(name(ref)).append('\n');
            if (entry == null) {
                return null;
            }
            text.append(entry.fileDataId())
                    .append('\n')
                    .append(entry.name())
                    .append('\n')
                    .append(entry.state())
                    .append('\n')
                    .append(entry.contentKey())
                    .append('\n')
                    .append(entry.counts());
            if (entry.reason() != null) {
                text.append('\n')
                        .append(entry.reason().code())
                        .append(entry.reason().definitionState())
                        .append(entry.reason().db2OpenState())
                        .append(entry.reason().keyNames());
            }
            text.append('\n')
                    .append(schema.columns())
                    .append('\n')
                    .append(schema.schema() == null ? null : schema.schema().layoutHashes());
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(ref, -1, 1, List.of(), null),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            text.append('\n').append(page.state()).append('\n').append(page.identity());
            token.check();
            var hotfix =
                    tables.hotfixInfo(ref, null, TableTypes.Priority.INTERACTIVE, token).join();
            if (hotfix.isPresent()) {
                var info = hotfix.get();
                text.append("\nhotfix\n")
                        .append(info.inputFingerprint())
                        .append('\n')
                        .append(info.identity())
                        .append('\n')
                        .append(info.policy())
                        .append('\n')
                        .append(info.unavailable())
                        .append('\n')
                        .append(
                                List.of(
                                        info.baseReadableRows(),
                                        info.effectiveRows(),
                                        (long) info.added(),
                                        (long) info.replaced(),
                                        (long) info.deleted(),
                                        (long) info.protectedRows(),
                                        (long) info.unknownRows(),
                                        (long) info.ignored()));
            }
            return text.toString();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException unreadable) {
            token.check();
            return null;
        }
    }

    /** This handle's fingerprint of {@code ref}, taken on first use. */
    private String known(TableTypes.Ref ref, Cancellation token) {
        var known = fingerprints.get(ref);
        if (known != null) {
            return known;
        }
        var taken = fingerprint(ref, token);
        if (taken != null) {
            fingerprints.putIfAbsent(ref, taken);
        }
        return taken;
    }

    /** The digest of {@code refs}' fingerprints, or null when one cannot be taken. */
    private byte[] digest(
            List<TableTypes.Ref> refs, Map<TableTypes.Ref, String> taken, Cancellation token) {
        var digest = sha256();
        for (var ref : refs) {
            String fingerprint = taken.get(ref);
            if (fingerprint == null) {
                return null;
            }
            digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        token.check();
        return digest.digest();
    }

    // --- Lookup and write ---

    /** The generated-store key of an NPC's entry. */
    GeneratedKey key(long extendedDisplayInfoId) {
        return GeneratedKey.hash(scope(extendedDisplayInfoId).getBytes(StandardCharsets.UTF_8));
    }

    String scope(long extendedDisplayInfoId) {
        return REVISION
                + "\n"
                + CODE
                + "\n"
                + MeshAppearanceCodec.REVISION
                + "\n"
                + M2RestDrawSkinReader.REVISION
                + "\n"
                + Blp2Decoder.REVISION
                + "\n"
                + files.build()
                + "\n"
                + files.locale()
                + "\n"
                + tables.build()
                + "\n"
                + tables.locale()
                + "\n"
                + extendedDisplayInfoId;
    }

    /**
     * The NPC's entry when every table it names and every source file are unchanged; null on any
     * mismatch, damage or read failure.
     */
    @SuppressWarnings("try")
    Entry find(long extendedDisplayInfoId, Cancellation token) {
        if (!available()) {
            return null;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var read = generated.read(key(extendedDisplayInfoId), lower).join();
            token.check();
            if (read.state() != GeneratedTypes.ReadState.PRESENT || read.bytes() == null) {
                return null;
            }
            var entry = decode(read.bytes(), scope(extendedDisplayInfoId));
            if (entry == null) {
                return null;
            }
            var taken = new java.util.HashMap<TableTypes.Ref, String>();
            for (var ref : entry.tables()) {
                String fingerprint = known(ref, token);
                if (fingerprint == null) {
                    return null;
                }
                taken.put(ref, fingerprint);
            }
            var digest = digest(entry.tables(), taken, token);
            if (digest == null || !MessageDigest.isEqual(digest, entry.digest())) {
                return null;
            }
            for (var source : entry.sources().entrySet()) {
                if (!source.getValue().equals(source(source.getKey(), token))) {
                    return null;
                }
            }
            return entry;
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            return null;
        }
    }

    /** A file's content key, or {@code !STATE} when it is not found: what a source must keep. */
    String source(long fileDataId, Cancellation token) {
        token.check();
        var stat = files.stat(FileTypes.FileRef.id(fileDataId), token).join();
        return stat.state() == FileTypes.FileLookupState.FOUND && stat.file() != null
                ? stat.file().contentKey()
                : "!" + stat.state();
    }

    /**
     * Writes an NPC's entry for a complete appearance whose composite is saved under {@code
     * composite}. {@code known} are sources with the content keys the appearance was composed from;
     * {@code others} are stated now. The entry names every table touched so far with its
     * fingerprint; a table that cannot be described writes nothing. Returns whether it was written.
     */
    @SuppressWarnings("try")
    boolean write(
            long extendedDisplayInfoId,
            GeneratedKey composite,
            long modelFileDataId,
            RaceAppearanceTypes.Result result,
            int omittedItems,
            int omittedAttachments,
            Map<Long, String> known,
            Set<Long> others,
            Cancellation token) {
        if (!available() || untracked || result.appearance() == null) {
            return false;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            if (!generated.has(composite, lower).join()) {
                return false;
            }
            var refs = new ArrayList<>(touched);
            refs.sort(java.util.Comparator.comparing(RaceNpcIndex::name));
            if (refs.isEmpty() || refs.size() > MAX_LIST) {
                return false;
            }
            var taken = new java.util.HashMap<TableTypes.Ref, String>();
            for (var ref : refs) {
                String fingerprint = known(ref, token);
                if (fingerprint == null) {
                    return false;
                }
                taken.put(ref, fingerprint);
            }
            var digest = digest(refs, taken, token);
            var sources = new TreeMap<Long, String>(known);
            for (long fileDataId : others) {
                if (!sources.containsKey(fileDataId)) {
                    sources.put(fileDataId, source(fileDataId, token));
                }
            }
            if (digest == null || sources.size() > MAX_LIST) {
                return false;
            }
            var entry =
                    new Entry(
                            composite,
                            modelFileDataId,
                            result.visibleParts(),
                            result.items(),
                            result.issues(),
                            omittedItems,
                            omittedAttachments,
                            sources,
                            refs,
                            digest);
            byte[] bytes = encode(entry, scope(extendedDisplayInfoId));
            if (bytes.length > MAX_ENTRY_BYTES) {
                return false;
            }
            token.check();
            // A put keeps a record already present, so an older entry is removed first.
            var key = key(extendedDisplayInfoId);
            generated.delete(List.of(key), lower).join();
            var stored = generated.put(List.of(new GeneratedTypes.Put(key, bytes)), lower).join();
            return stored.size() == 1 && stored.getFirst() == GeneratedTypes.PutOutcome.STORED;
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            // Not written; the NPC is prepared again next time.
            token.check();
            return false;
        }
    }

    // --- Entry bytes ---

    static String name(TableTypes.Ref ref) {
        return ref.name() != null ? "n:" + ref.name() : "i:" + ref.fileDataId();
    }

    static TableTypes.Ref ref(String name) {
        if (name.startsWith("n:")) {
            return TableTypes.Ref.forName(name.substring(2));
        }
        if (name.startsWith("i:")) {
            return TableTypes.Ref.id(Long.parseLong(name.substring(2)));
        }
        throw new IllegalArgumentException("Unknown table reference");
    }

    static byte[] encode(Entry entry, String scope) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            text(out, scope);
            out.write(entry.composite().bytes());
            out.writeLong(entry.modelFileDataId());
            out.writeInt(entry.omittedItems());
            out.writeInt(entry.omittedAttachments());
            out.writeInt(entry.visible().size());
            for (int part : new TreeSet<>(entry.visible())) {
                out.writeInt(part);
            }
            out.writeInt(entry.items().size());
            for (var item : entry.items()) {
                out.writeLong(item.itemId());
                out.writeInt(item.inventoryType());
                out.writeLong(item.displayId());
            }
            out.writeInt(entry.issues().size());
            for (var issue : entry.issues()) {
                text(out, issue);
            }
            out.writeInt(entry.sources().size());
            for (var source : new TreeMap<>(entry.sources()).entrySet()) {
                out.writeLong(source.getKey());
                text(out, source.getValue());
            }
            out.writeInt(entry.tables().size());
            for (var ref : entry.tables()) {
                text(out, name(ref));
            }
            out.write(entry.digest());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
    }

    /** The entry in {@code bytes} when it was written for {@code scope}; null otherwise. */
    static Entry decode(byte[] bytes, String scope) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || !scope.equals(text(in))) {
                return null;
            }
            byte[] composite = in.readNBytes(16);
            if (composite.length != 16) {
                return null;
            }
            long model = in.readLong();
            int omittedItems = in.readInt(), omittedAttachments = in.readInt();
            var visible = new TreeSet<Integer>();
            for (int i = count(in); i > 0; i--) {
                visible.add(in.readInt());
            }
            var items = new ArrayList<RaceAppearanceTypes.Item>();
            for (int i = count(in); i > 0; i--) {
                items.add(new RaceAppearanceTypes.Item(in.readLong(), in.readInt(), in.readLong()));
            }
            var issues = new ArrayList<String>();
            for (int i = count(in); i > 0; i--) {
                issues.add(text(in));
            }
            var sources = new TreeMap<Long, String>();
            for (int i = count(in); i > 0; i--) {
                sources.put(in.readLong(), text(in));
            }
            var refs = new ArrayList<TableTypes.Ref>();
            for (int i = count(in); i > 0; i--) {
                refs.add(ref(text(in)));
            }
            byte[] digest = in.readNBytes(32);
            if (digest.length != 32
                    || in.read() != -1
                    || model < 1
                    || model > 0xffffffffL
                    || omittedItems < 0
                    || omittedAttachments < 0
                    || refs.isEmpty()
                    || new TreeSet<>(refs.stream().map(RaceNpcIndex::name).toList()).size()
                            != refs.size()) {
                return null;
            }
            return new Entry(
                    new GeneratedKey(composite),
                    model,
                    visible,
                    items,
                    issues,
                    omittedItems,
                    omittedAttachments,
                    sources,
                    refs,
                    digest);
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST) {
            throw new IOException("Entry list out of bounds");
        }
        return count;
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 65536) {
            throw new IOException("Entry text out of bounds");
        }
        byte[] utf8 = in.readNBytes(length);
        if (utf8.length != length) {
            throw new IOException("Entry text truncated");
        }
        return new String(utf8, StandardCharsets.UTF_8);
    }

    // --- The NPC path's code ---

    /**
     * The classes whose code decides an NPC's resolve, omissions, plan, attachments and result.
     * Their nested classes are included.
     */
    static final List<Class<?>> CODE_CLASSES =
            List.of(
                    RaceNpcIndex.class,
                    RaceNpcs.class,
                    RacesService.class,
                    RaceAppearance.class,
                    RaceAppearancePlan.class,
                    RaceAppearanceProfiles.class,
                    RaceAppearanceTypes.class,
                    RaceClothingGeometry.class,
                    RaceCustomization.class,
                    RaceCustomizationTypes.class,
                    RaceMedia.class,
                    RaceMediaTypes.class,
                    RaceModelDefinitions.class,
                    RaceNpcAttachments.class,
                    RaceTypes.class,
                    com.vibeminer.concepts.items.ItemModelFiles.class,
                    com.vibeminer.concepts.core.RelationIndexes.class);

    /**
     * A digest of {@link #CODE_CLASSES}' compiled code, so an app version that resolves or plans
     * NPCs differently never reads an entry an earlier one wrote, without a revision to remember to
     * bump. Null when a class file cannot be read: the index is then off.
     */
    static final String CODE = code(CODE_CLASSES);

    static String code(List<Class<?>> classes) {
        try {
            var digest = sha256();
            var all = new ArrayList<Class<?>>();
            for (var type : classes) {
                nested(type, all);
            }
            for (var type : all) {
                String resource = type.getName().replace('.', '/') + ".class";
                try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) {
                        return null;
                    }
                    digest.update(resource.getBytes(StandardCharsets.UTF_8));
                    digest.update(in.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(Arrays.copyOf(digest.digest(), 16));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static void nested(Class<?> type, List<Class<?>> out) {
        if (out.contains(type)) {
            return;
        }
        out.add(type);
        var inner = new ArrayList<>(List.of(type.getDeclaredClasses()));
        inner.sort(java.util.Comparator.comparing(Class::getName));
        for (var each : inner) {
            nested(each, out);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
