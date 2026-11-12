package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Persistent race idle evaluations for humanoid NPCs, in two kinds of entries, so a later session
 * fits each NPC's Stand without resolving it or assessing its race model again:
 *
 * <ul>
 *   <li>A <b>resolution</b> per extended display ID: the race and ChrModel the NPC resolves to.
 *   <li>A <b>Stand</b> per ChrModel and race model content key: the assessed Stand on the race
 *       model's rest surface (or why it has none), and the model's attachment points with their
 *       bones, which the fit to an NPC's appearance needs.
 * </ul>
 *
 * <p>Fitting a Stand to an NPC appearance takes milliseconds and needs only these two, so no fitted
 * animation (a copy of the whole NPC surface) is stored.
 *
 * <p>Checked the way {@link RaceNpcIndex} checks an appearance entry; anything else is a miss and
 * the idle is evaluated as before:
 *
 * <ul>
 *   <li>The key names the build, the locale and the NPC or the ChrModel and model. The scope,
 *       stored with the entry and compared on read, adds this index's revision, the reader
 *       revisions, a digest of every class file of the idle path ({@link #CODE}), build and locale.
 *   <li>The entry names every table the races handle had read when it was written, with the digest
 *       of their fingerprints.
 *   <li>A Stand names the race model, skeleton and SKIN files it was built from; each is stated
 *       again and must keep its content key.
 * </ul>
 *
 * <p>Only Stands whose sources were all read are written: one admitted, or one refused for a reason
 * of the model. A result with something unread never is.
 */
final class RaceNpcIdleIndex {
    /** Bumped when an entry's format or meaning changes. */
    static final String REVISION = "npc-idle-index-v1";

    private static final int RESOLUTION_MAGIC = 0x4e495231; // "NIR1"
    private static final int STAND_MAGIC = 0x4e495331; // "NIS1"

    /** A Stand holds the race model's rest surface, 65536 vertices at most. */
    private static final int MAX_ENTRY_BYTES = 16 << 20;

    /**
     * The idle path's code: the races package (resolve, assessment, fit), the Stand admission, the
     * idle readers and the animation itself.
     */
    static final List<String> CODE_PREFIXES =
            List.of(
                    "com/vibeminer/concepts/races/",
                    "com/vibeminer/concepts/core/RelationIndexes",
                    "com/vibeminer/concepts/models/DisplayIdleAnimations",
                    "com/vibeminer/formats/models/M2IdleAnimation",
                    "com/vibeminer/formats/models/BuildModels",
                    "com/vibeminer/rendering/models/PreviewAnimation");

    static final String CODE =
            RaceNpcIndex.code(RaceNpcIdleIndex.class.getClassLoader(), CODE_PREFIXES);

    private final RaceNpcIndex index;
    private final String code;

    RaceNpcIdleIndex(RaceNpcIndex index) {
        this(index, CODE);
    }

    /** As above, under another code digest (tests). */
    RaceNpcIdleIndex(RaceNpcIndex index, String code) {
        this.index = index;
        this.code = code;
    }

    boolean available() {
        return index.available() && code != null;
    }

    /** The race and ChrModel an NPC resolves to. */
    record Resolution(long raceId, long chrModelId) {}

    /**
     * An assessed Stand, as written: the result without its variant, the model's attachment points
     * and their bones, and the files it was built from.
     */
    record Stand(
            RaceTypes.State state,
            List<String> reasons,
            List<String> facts,
            String message,
            PreviewAnimation animation,
            Map<Integer, Integer> attachments,
            Map<Long, String> sources) {
        Stand {
            if (state != RaceTypes.State.READY && state != RaceTypes.State.UNSUPPORTED
                    || (state == RaceTypes.State.READY) != (animation != null)) {
                throw new IllegalArgumentException("Only complete Stands are indexed");
            }
            reasons = List.copyOf(reasons);
            facts = List.copyOf(facts);
            attachments = Map.copyOf(new TreeMap<>(attachments));
            sources = Map.copyOf(new TreeMap<>(sources));
        }

        RaceMediaTypes.Idle result(RaceMediaTypes.Variant variant) {
            return new RaceMediaTypes.Idle(state, variant, animation, reasons, facts, message);
        }
    }

    // --- Keys and scope ---

    GeneratedKey resolutionKey(long extendedDisplayInfoId) {
        return key("npc-idle-resolution\n" + extendedDisplayInfoId);
    }

    GeneratedKey standKey(long chrModelId, long modelFileDataId, String modelKey) {
        return key("race-stand\n" + chrModelId + "\n" + modelFileDataId + "\n" + modelKey);
    }

    private GeneratedKey key(String what) {
        var files = index.files();
        return GeneratedKey.hash(
                (REVISION + "\n" + files.build() + "\n" + files.locale() + "\n" + what)
                        .getBytes(StandardCharsets.UTF_8));
    }

    String scope(String what) {
        var files = index.files();
        return REVISION
                + "\n"
                + code
                + "\n"
                + M2ModelReader.REVISION
                + "\n"
                + M2RestDrawSkinReader.REVISION
                + "\n"
                + files.build()
                + "\n"
                + files.locale()
                + "\n"
                + what;
    }

    // --- Resolutions ---

    /** The NPC's resolution while every table it names is unchanged; null otherwise. */
    Resolution resolution(long extendedDisplayInfoId, Cancellation token) {
        byte[] bytes = valid(resolutionKey(extendedDisplayInfoId), token);
        if (bytes == null) {
            return null;
        }
        try {
            var in = body(bytes, RESOLUTION_MAGIC, "npc " + extendedDisplayInfoId);
            if (in == null) {
                return null;
            }
            long race = in.readLong(), chrModel = in.readLong();
            return race < 1 || chrModel < 1 || in.read() != -1
                    ? null
                    : new Resolution(race, chrModel);
        } catch (IOException damaged) {
            return null;
        }
    }

    boolean writeResolution(long extendedDisplayInfoId, Resolution value, Cancellation token) {
        return write(
                resolutionKey(extendedDisplayInfoId),
                RESOLUTION_MAGIC,
                "npc " + extendedDisplayInfoId,
                Map.of(),
                out -> {
                    out.writeLong(value.raceId());
                    out.writeLong(value.chrModelId());
                },
                token);
    }

    // --- Stands ---

    /** The Stand of this ChrModel and model while its tables and sources are unchanged. */
    Stand stand(long chrModelId, long modelFileDataId, String modelKey, Cancellation token) {
        String what = "stand " + chrModelId + " " + modelFileDataId + " " + modelKey;
        byte[] bytes = valid(standKey(chrModelId, modelFileDataId, modelKey), token);
        if (bytes == null) {
            return null;
        }
        try {
            var in = body(bytes, STAND_MAGIC, what);
            if (in == null) {
                return null;
            }
            var sources = sources(in);
            boolean ready = in.readBoolean();
            var reasons = strings(in);
            var facts = strings(in);
            String message = RaceNpcIndex.text(in);
            var attachments = new TreeMap<Integer, Integer>();
            for (int i = RaceNpcIndex.count(in); i > 0; i--) {
                attachments.put(in.readInt(), in.readInt());
            }
            PreviewAnimation animation = ready ? PreviewAnimation.read(in) : null;
            if (in.read() != -1
                    || !modelKey.equals(sources.get(modelFileDataId))
                    || !index.sourcesUnchanged(sources, token)) {
                return null;
            }
            return new Stand(
                    ready ? RaceTypes.State.READY : RaceTypes.State.UNSUPPORTED,
                    reasons,
                    facts,
                    message,
                    animation,
                    attachments,
                    sources);
        } catch (IOException | RuntimeException damaged) {
            token.check();
            return null;
        }
    }

    boolean writeStand(
            long chrModelId,
            long modelFileDataId,
            String modelKey,
            Stand stand,
            Cancellation token) {
        if (!modelKey.equals(stand.sources().get(modelFileDataId))) {
            return false;
        }
        return write(
                standKey(chrModelId, modelFileDataId, modelKey),
                STAND_MAGIC,
                "stand " + chrModelId + " " + modelFileDataId + " " + modelKey,
                stand.sources(),
                out -> {
                    out.writeBoolean(stand.state() == RaceTypes.State.READY);
                    strings(out, stand.reasons());
                    strings(out, stand.facts());
                    RaceNpcIndex.text(out, stand.message());
                    out.writeInt(stand.attachments().size());
                    for (var point : new TreeMap<>(stand.attachments()).entrySet()) {
                        out.writeInt(point.getKey());
                        out.writeInt(point.getValue());
                    }
                    if (stand.animation() != null) {
                        stand.animation().write(out);
                    }
                },
                token);
    }

    // --- Shared entry frame: magic, scope, tables and their digest, then the body ---

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * The stored bytes under {@code key} when every table they name still has the recorded digest;
     * null when absent, damaged or changed. The frame is checked again by {@link #body}.
     */
    @SuppressWarnings("try")
    private byte[] valid(GeneratedKey key, Cancellation token) {
        if (!available()) {
            return null;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var read = index.generated().read(key, lower).join();
            token.check();
            if (read.state() != GeneratedTypes.ReadState.PRESENT || read.bytes() == null) {
                return null;
            }
            var frame = frame(read.bytes());
            if (frame == null) {
                return null;
            }
            var digest = index.tablesDigest(frame.tables(), token);
            return digest != null && MessageDigest.isEqual(digest, frame.digest())
                    ? read.bytes()
                    : null;
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            return null;
        }
    }

    private record Frame(int magic, String scope, List<TableTypes.Ref> tables, byte[] digest) {}

    /** The frame of an entry, or null when it is damaged. */
    private static Frame frame(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            return frame(in);
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }

    private static Frame frame(DataInputStream in) throws IOException {
        int magic = in.readInt();
        String scope = RaceNpcIndex.text(in);
        var refs = new ArrayList<TableTypes.Ref>();
        for (int i = RaceNpcIndex.count(in); i > 0; i--) {
            refs.add(RaceNpcIndex.ref(RaceNpcIndex.text(in)));
        }
        byte[] digest = in.readNBytes(32);
        if (digest.length != 32
                || refs.isEmpty()
                || new TreeSet<>(refs.stream().map(RaceNpcIndex::name).toList()).size()
                        != refs.size()) {
            throw new IOException("Damaged entry frame");
        }
        return new Frame(magic, scope, refs, digest);
    }

    /** The body of {@code bytes} after its frame, when written with this magic for this scope. */
    private DataInputStream body(byte[] bytes, int magic, String what) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        var frame = frame(in);
        return frame.magic() == magic && frame.scope().equals(scope(what)) ? in : null;
    }

    /** Writes an entry naming every table this handle read so far; false when it cannot. */
    @SuppressWarnings("try")
    private boolean write(
            GeneratedKey key,
            int magic,
            String what,
            Map<Long, String> sources,
            Body body,
            Cancellation token) {
        if (!available()) {
            return false;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var refs = index.touchedTables();
            if (refs == null) {
                return false;
            }
            var digest = index.tablesDigest(refs, token);
            if (digest == null || sources.size() > RaceNpcIndex.MAX_LIST) {
                return false;
            }
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(magic);
            RaceNpcIndex.text(out, scope(what));
            out.writeInt(refs.size());
            for (var ref : refs) {
                RaceNpcIndex.text(out, RaceNpcIndex.name(ref));
            }
            out.write(digest);
            if (magic == STAND_MAGIC) {
                out.writeInt(sources.size());
                for (var source : new TreeMap<>(sources).entrySet()) {
                    out.writeLong(source.getKey());
                    RaceNpcIndex.text(out, source.getValue());
                }
            }
            body.write(out);
            out.flush();
            if (bytes.size() > MAX_ENTRY_BYTES) {
                return false;
            }
            token.check();
            // A put keeps a record already present, so an older entry is removed first.
            index.generated().delete(List.of(key), lower).join();
            var stored =
                    index.generated()
                            .put(List.of(new GeneratedTypes.Put(key, bytes.toByteArray())), lower)
                            .join();
            return stored.size() == 1 && stored.getFirst() == GeneratedTypes.PutOutcome.STORED;
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (IOException | RuntimeException failure) {
            // Not written; the idle is evaluated again next time.
            token.check();
            return false;
        }
    }

    private static Map<Long, String> sources(DataInputStream in) throws IOException {
        var sources = new TreeMap<Long, String>();
        for (int i = RaceNpcIndex.count(in); i > 0; i--) {
            sources.put(in.readLong(), RaceNpcIndex.text(in));
        }
        return sources;
    }

    private static void strings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (var value : values) {
            RaceNpcIndex.text(out, value);
        }
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        var out = new ArrayList<String>();
        for (int i = RaceNpcIndex.count(in); i > 0; i--) {
            out.add(RaceNpcIndex.text(in));
        }
        return out;
    }
}
