package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.models.M2ModelReader;
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
 *   <li>The key names the build, the locale and the extended display ID. The entry's scope, stored
 *       with it and compared on read, adds the index revision, the codec and reader revisions, and
 *       a digest of every class file of the NPC path ({@link #CODE}): a new app version that
 *       changes how an NPC is resolved or planned never reads an older entry, and its next write
 *       replaces it under the same key.
 *   <li>The entry names every table this races handle read through its tables before it was written
 *       ({@link #recording}), with a digest of each one's state, content key, counts, schema, page
 *       identity and client hotfix view ({@link #fingerprint}). A table that changed, or a hotfix
 *       view that is refused or changed, is a miss.
 *   <li>Every file the appearance was composed from (model, bake and layers, skeleton, and the
 *       attached item files) is stated again and must keep its content key, and an encrypted one
 *       the names of the keys it lacks; the composite itself checks its skin and skeleton when
 *       restored. The attached item files are recorded beside the composite when it is composed;
 *       after a restore, an NPC with a helmet or shoulders is written only when that record exists.
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

    /** The NPC path's code digest this index reads and writes under ({@link #CODE}). */
    private final String code;

    RaceNpcIndex(BuildTables tables, BuildFiles files, GeneratedStore generated) {
        this(tables, files, generated, CODE);
    }

    /** As above, under another code digest (tests). */
    RaceNpcIndex(BuildTables tables, BuildFiles files, GeneratedStore generated, String code) {
        this.tables = tables;
        this.files = files;
        this.generated = generated;
        this.code = code;
    }

    boolean available() {
        return files != null && generated != null && code != null;
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

    /**
     * The tables this handle read so far, by name, for an entry written now; null when the handle
     * read none, too many, or made a call that names no single table.
     */
    List<TableTypes.Ref> touchedTables() {
        if (untracked) {
            return null;
        }
        var refs = new ArrayList<>(touched);
        refs.sort(java.util.Comparator.comparing(RaceNpcIndex::name));
        return refs.isEmpty() || refs.size() > MAX_LIST ? null : refs;
    }

    /** The digest of {@code refs}' fingerprints as this handle takes them, or null. */
    byte[] tablesDigest(List<TableTypes.Ref> refs, Cancellation token) {
        var taken = new java.util.HashMap<TableTypes.Ref, String>();
        for (var ref : refs) {
            String fingerprint = known(ref, token);
            if (fingerprint == null) {
                return null;
            }
            taken.put(ref, fingerprint);
        }
        return digest(refs, taken, token);
    }

    /** Whether every source still states as recorded ({@link #source}). */
    boolean sourcesUnchanged(Map<Long, String> sources, Cancellation token) {
        for (var source : sources.entrySet()) {
            if (!source.getValue().equals(source(source.getKey(), token))) {
                return false;
            }
        }
        return true;
    }

    /** {@code known} content keys as {@link #source} states them, for an entry written now. */
    Map<Long, String> sourcesNow(Map<Long, String> known, Cancellation token) {
        return sources(known, token);
    }

    GeneratedStore generated() {
        return generated;
    }

    BuildFiles files() {
        return files;
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

    /**
     * The generated-store key of an NPC's entry: the build, locale and extended display ID only.
     * Everything else an entry depends on is in its {@link #scope}, stored with it and compared on
     * read, so an entry of an older generation (another app version, codec or key set) is a miss
     * and is replaced by the next write instead of lingering under a key nothing reads again.
     */
    GeneratedKey key(long extendedDisplayInfoId) {
        return GeneratedKey.hash(
                (KEY + "\n" + files.build() + "\n" + files.locale() + "\n" + extendedDisplayInfoId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static final String KEY = "npc-appearance-index";

    String scope(long extendedDisplayInfoId) {
        return REVISION
                + "\n"
                + code
                + "\n"
                + M2ModelReader.REVISION
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

    /**
     * What a source must keep: its content key, or {@code !STATE} when it is not found. An
     * encrypted file keeps its content key when a key for it is added or removed, so the names of
     * the keys its bytes still lack are part of it too.
     */
    String source(long fileDataId, Cancellation token) {
        token.check();
        var stat = files.stat(FileTypes.FileRef.id(fileDataId), token).join();
        if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
            return "!" + stat.state();
        }
        return stat.file().encrypted()
                ? stat.file().contentKey() + missingKeys(fileDataId, token)
                : stat.file().contentKey();
    }

    /** The key names an encrypted file's bytes lack now, or why it cannot be opened. */
    private String missingKeys(long fileDataId, Cancellation token) {
        var open = files.openRead(FileTypes.FileRef.id(fileDataId), token).join();
        if (open.stream() == null) {
            return " open " + open.state();
        }
        try (var stream = open.stream()) {
            // The holes name the keys still missing; chunkKeys of a decoded stream lists every key
            // the file is encrypted with, present or not.
            return " lacks "
                    + stream.holes().stream()
                            .map(FileTypes.FileHole::keyName)
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .sorted()
                            .toList();
        }
    }

    /**
     * {@code known} content keys as {@link #source} states them: an encrypted file's missing key
     * names are added now.
     */
    private Map<Long, String> sources(Map<Long, String> known, Cancellation token) {
        var out = new TreeMap<Long, String>();
        for (var source : known.entrySet()) {
            String now = source(source.getKey(), token);
            // Only the key names are added: the content key stays the one composed from.
            int space = now.indexOf(' ');
            out.put(
                    source.getKey(),
                    space < 0 || now.startsWith("!")
                            ? source.getValue()
                            : source.getValue() + now.substring(space));
        }
        return out;
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
            var sources = new TreeMap<Long, String>(sources(known, token));
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

    // --- A composite's attachment files ---

    private static GeneratedKey attachmentKey(GeneratedKey composite) {
        return GeneratedKey.hash(
                ("npc-composite-attachment-files-v1\n" + composite.hex())
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Records the item files a composite's attachments were read from, beside the composite, so an
     * entry written after the composite is restored can still name them.
     */
    @SuppressWarnings("try")
    void attachments(GeneratedKey composite, Set<Long> files, Cancellation token) {
        if (!available() || files.size() > MAX_LIST) {
            return;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var bytes = java.nio.ByteBuffer.allocate(4 + 16 + 4 + 8 * files.size());
            bytes.putInt(MAGIC).put(composite.bytes()).putInt(files.size());
            for (long file : new TreeSet<>(files)) {
                bytes.putLong(file);
            }
            var key = attachmentKey(composite);
            generated.delete(List.of(key), lower).join();
            generated.put(List.of(new GeneratedTypes.Put(key, bytes.array())), lower).join();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check(); // not recorded: an entry after a restore is then not written
        }
    }

    /** The item files recorded for a composite's attachments, or null when none are recorded. */
    @SuppressWarnings("try")
    Set<Long> attachments(GeneratedKey composite, Cancellation token) {
        if (!available()) {
            return null;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var read = generated.read(attachmentKey(composite), lower).join();
            if (read.state() != GeneratedTypes.ReadState.PRESENT || read.bytes() == null) {
                return null;
            }
            var bytes = java.nio.ByteBuffer.wrap(read.bytes());
            byte[] named = new byte[16];
            if (bytes.remaining() < 24 || bytes.getInt() != MAGIC) {
                return null;
            }
            bytes.get(named);
            int count = bytes.getInt();
            if (!composite.equals(new GeneratedKey(named))
                    || count < 0
                    || count > MAX_LIST
                    || bytes.remaining() != 8L * count) {
                return null;
            }
            var files = new TreeSet<Long>();
            for (int i = 0; i < count; i++) {
                files.add(bytes.getLong());
            }
            return files;
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException damaged) {
            token.check();
            return null;
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

    static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST) {
            throw new IOException("Entry list out of bounds");
        }
        return count;
    }

    static void text(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    static String text(DataInputStream in) throws IOException {
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
     * The compiled code that decides an NPC's resolve, omissions, plan, attachments and result:
     * every class file of the races package (nested, local and anonymous classes included), and the
     * item and relation readers it calls.
     */
    static final List<String> CODE_PREFIXES =
            List.of(
                    "com/vibeminer/concepts/races/",
                    "com/vibeminer/concepts/items/ItemAppearanceReader",
                    "com/vibeminer/concepts/items/ItemModelFiles",
                    "com/vibeminer/concepts/core/RelationIndexes");

    /**
     * A digest of the class files under {@link #CODE_PREFIXES}, so an app version that resolves or
     * plans NPCs differently never reads an entry an earlier one wrote, without a list of classes
     * or a revision to keep by hand. Null when they cannot be listed: the index is then off.
     */
    static final String CODE = code(RaceNpcIndex.class.getClassLoader(), CODE_PREFIXES);

    /**
     * The digest of the class files under {@code prefixes}. Each prefix names a class file (or a
     * package folder whose classes all count), and its folder or jar is found from a class file it
     * names, so a jar without folder entries is listed too.
     */
    static String code(ClassLoader loader, List<String> prefixes) {
        try {
            var classes = new TreeMap<String, byte[]>();
            for (String prefix : prefixes) {
                String directory = prefix.substring(0, prefix.lastIndexOf('/') + 1);
                String anchor = anchor(prefix);
                var location = loader.getResource(anchor);
                if (location == null) {
                    return null;
                }
                collect(location, anchor, directory, prefix, classes);
            }
            if (classes.isEmpty()) {
                return null;
            }
            var digest = sha256();
            for (var each : classes.entrySet()) {
                digest.update(each.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(each.getValue());
            }
            return HexFormat.of().formatHex(Arrays.copyOf(digest.digest(), 16));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** A class file that locates a prefix: the class it names, or this class for its package. */
    private static String anchor(String prefix) {
        return prefix.endsWith("/")
                ? prefix + RaceNpcIndex.class.getSimpleName() + ".class"
                : prefix + ".class";
    }

    /** The class files beside {@code anchor} (in its folder or its jar) starting with prefix. */
    private static void collect(
            java.net.URL anchor,
            String anchorName,
            String directory,
            String prefix,
            Map<String, byte[]> out)
            throws IOException {
        switch (anchor.getProtocol()) {
            case "file" -> {
                java.nio.file.Path folder;
                try {
                    folder = java.nio.file.Path.of(anchor.toURI()).getParent();
                } catch (java.net.URISyntaxException invalid) {
                    throw new IOException(invalid);
                }
                try (var listing = java.nio.file.Files.list(folder)) {
                    for (var file : listing.sorted().toList()) {
                        String name = directory + file.getFileName();
                        if (name.startsWith(prefix) && name.endsWith(".class")) {
                            out.put(name, java.nio.file.Files.readAllBytes(file));
                        }
                    }
                }
            }
            case "jar" -> {
                var connection = (java.net.JarURLConnection) anchor.openConnection();
                connection.setUseCaches(false);
                try (var jar = connection.getJarFile()) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(prefix)
                                && name.endsWith(".class")
                                && name.indexOf('/', directory.length()) < 0) {
                            try (InputStream in = jar.getInputStream(entry)) {
                                out.put(name, in.readAllBytes());
                            }
                        }
                    }
                }
            }
            default -> throw new IOException("Unlisted class location for " + anchorName);
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
