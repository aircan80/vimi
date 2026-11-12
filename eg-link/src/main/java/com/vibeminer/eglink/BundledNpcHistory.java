package com.vibeminer.eglink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The EG Link NPC display history that ships with the application: per NPC and client build that
 * recorded it across the upstream repository's recent commits, the displays of the newest commit
 * holding that build, with the build, commit and collection date they came from. Built by hand
 * before a release with {@code scripts/release/eg_link_history.py}; nothing is downloaded. The file
 * is checked against the SHA-256 its NOTICE.md records when it loads.
 */
public final class BundledNpcHistory {
    static final String FORMAT = "vimi-eg-link-npc-history-v2";
    private static final int MAX_BYTES = 8 << 20, MAX_NPCS = 1 << 20, MAX_SOURCES = 4096;
    private static final Pattern BUILD = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+"),
            COMMIT = Pattern.compile("[0-9a-f]{40}"),
            DIGEST = Pattern.compile("SHA-256 ([0-9a-f]{64})");

    /** Where one NPC's displays were collected: client build, commit and dates. */
    public record Source(String build, String commit, String committedAt, String collectedOn) {}

    /** One NPC's bundled displays and their source. */
    public record Entry(long npcId, List<EgLinkTypes.CreatureDisplay> displays, Source source) {
        public Entry {
            displays = List.copyOf(displays);
        }

        /**
         * EG Link, its license, the build, commit and collection date, and that it ships bundled.
         */
        public String provenance() {
            return "EG Link creature_details ("
                    + EgLinkTypes.LICENSE
                    + "), build "
                    + source.build()
                    + ", commit "
                    + source.commit().substring(0, 12)
                    + ", collected "
                    + (source.collectedOn().isEmpty() ? "unknown" : source.collectedOn())
                    + " (bundled)";
        }
    }

    /** What the Settings page shows for the bundled history. */
    public record Attribution(
            String product,
            String locale,
            String upstream,
            String firstCommit,
            String lastCommit,
            int npcs,
            List<String> builds,
            String notice) {
        public Attribution {
            builds = List.copyOf(builds);
        }
    }

    /** Each NPC's entries, newest build first. */
    private final Map<Long, List<Entry>> entries;

    private final int lines;
    private final Attribution attribution;

    private BundledNpcHistory(Map<Long, List<Entry>> entries, Attribution attribution) {
        var sorted = new HashMap<Long, List<Entry>>();
        int count = 0;
        for (var npc : entries.entrySet()) {
            var list = new ArrayList<>(npc.getValue());
            list.sort((a, b) -> compareBuilds(b.source().build(), a.source().build()));
            sorted.put(npc.getKey(), List.copyOf(list));
            count += list.size();
        }
        this.entries = Map.copyOf(sorted);
        this.lines = count;
        this.attribution = attribution;
    }

    /** The history, or why it could not be read: a bad file is a value, never a class failure. */
    private record Loaded(BundledNpcHistory history, String problem) {}

    private static final class Shared {
        static final Loaded VALUE = loadShipped();
    }

    /**
     * The bundled history, read and verified once, or empty when the shipped file is unreadable.
     */
    public static Optional<BundledNpcHistory> available() {
        return Optional.ofNullable(Shared.VALUE.history());
    }

    /** Why the shipped history could not be read, or null when it was. */
    public static String problem() {
        return Shared.VALUE.problem();
    }

    /** The bundled history; throws {@link IllegalStateException} when it is unreadable. */
    public static BundledNpcHistory bundled() {
        return available().orElseThrow(() -> new IllegalStateException(problem()));
    }

    /**
     * The bundled NPC display history's attribution, read from its NOTICE.md; throws {@link
     * IllegalStateException} when the history is unreadable.
     */
    public static Attribution attribution() {
        return bundled().attribution;
    }

    /** The NPC's entries, one per build that recorded it, newest build first. */
    public List<Entry> entries(long npcId) {
        return entries.getOrDefault(npcId, List.of());
    }

    /** The NPC's entry from the newest build that recorded it. */
    public Optional<Entry> displays(long npcId) {
        var list = entries(npcId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /** This history's attribution. */
    public Attribution describe() {
        return attribution;
    }

    /** The number of NPCs. */
    public int size() {
        return entries.size();
    }

    /** The number of entries (one per NPC and build). */
    public int lines() {
        return lines;
    }

    /** The product the history was collected for (EG Link's {@code wowf}). */
    public String product() {
        return attribution.product();
    }

    private static Loaded loadShipped() {
        try {
            return new Loaded(
                    load(resource("history/NOTICE.md"), resource("history/npc-displays.txt")),
                    null);
        } catch (IOException | RuntimeException failure) {
            return new Loaded(
                    null, "Bundled EG Link NPC history is unreadable: " + failure.getMessage());
        }
    }

    /** Verifies {@code data} against the SHA-256 {@code notice} records, then parses it. */
    static BundledNpcHistory load(byte[] notice, byte[] data) throws IOException {
        var digest = DIGEST.matcher(new String(notice, StandardCharsets.UTF_8));
        if (!digest.find() || !digest.group(1).equals(sha256(data))) {
            throw new IOException("Bundled NPC history failed its recorded hash");
        }
        return parse(data, new String(notice, StandardCharsets.UTF_8));
    }

    /** Parses the bundled text format; package-private for tests. */
    static BundledNpcHistory parse(byte[] data, String notice) throws IOException {
        try {
            return parseChecked(data, notice);
        } catch (NumberFormatException malformed) {
            throw new IOException("Malformed number in bundled NPC history", malformed);
        }
    }

    private static BundledNpcHistory parseChecked(byte[] data, String notice) throws IOException {
        var header = new HashMap<String, String[]>();
        var sources = new HashMap<Integer, Source>();
        var entries = new HashMap<Long, List<Entry>>();
        int count = 0;
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.endsWith("\n")) {
            throw new IOException("Truncated bundled NPC history");
        }
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            String[] fields = line.split("\t", -1);
            if (line.startsWith("#source\t")) {
                if (fields.length != 6
                        || !BUILD.matcher(fields[2]).matches()
                        || !COMMIT.matcher(fields[3]).matches()
                        || sources.size() >= MAX_SOURCES) {
                    throw new IOException("Malformed bundled NPC history source");
                }
                int index = Integer.parseInt(fields[1]);
                if (sources.putIfAbsent(
                                index, new Source(fields[2], fields[3], fields[4], fields[5]))
                        != null) {
                    throw new IOException("Duplicate bundled NPC history source");
                }
            } else if (line.startsWith("#")) {
                if (header.putIfAbsent(fields[0].substring(1), fields) != null) {
                    throw new IOException("Duplicate bundled NPC history header");
                }
            } else {
                if (fields.length != 3 || count >= MAX_NPCS) {
                    throw new IOException("Malformed bundled NPC history line");
                }
                long npc = Long.parseLong(fields[0]);
                var source = sources.get(Integer.parseInt(fields[1]));
                if (npc < 1 || npc > 0xffffffffL || source == null) {
                    throw new IOException("Bundled NPC history line names no source");
                }
                var displays = new ArrayList<EgLinkTypes.CreatureDisplay>();
                for (String display : fields[2].split(",", -1)) {
                    String[] parts = display.split(":", -1);
                    if (parts.length != 3
                            || displays.size() >= EgLinkRepository.MAX_CREATURE_DISPLAYS) {
                        throw new IOException("Malformed bundled NPC display");
                    }
                    try {
                        displays.add(
                                new EgLinkTypes.CreatureDisplay(
                                        Long.parseLong(parts[0]),
                                        Double.parseDouble(parts[1]),
                                        Double.parseDouble(parts[2])));
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid bundled NPC display", invalid);
                    }
                }
                var list = entries.computeIfAbsent(npc, key -> new ArrayList<>());
                for (var held : list) {
                    if (held.source().build().equals(source.build())) {
                        throw new IOException("Duplicate bundled NPC build");
                    }
                }
                list.add(new Entry(npc, displays, source));
                count++;
            }
        }
        if (!FORMAT.equals(value(header, "format", 1))
                || Integer.parseInt(value(header, "npcs", 1)) != entries.size()
                || Integer.parseInt(value(header, "entries", 1)) != count) {
            throw new IOException("Unsupported or inconsistent bundled NPC history");
        }
        var builds =
                sources.values().stream()
                        .map(Source::build)
                        .distinct()
                        .sorted(BundledNpcHistory::compareBuilds)
                        .toList();
        return new BundledNpcHistory(
                entries,
                new Attribution(
                        value(header, "product", 1),
                        value(header, "locale", 1),
                        value(header, "upstream", 1),
                        value(header, "range", 1),
                        value(header, "range", 2),
                        entries.size(),
                        builds,
                        notice));
    }

    static int compareBuilds(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static String value(Map<String, String[]> header, String key, int index)
            throws IOException {
        String[] fields = header.get(key);
        if (fields == null || fields.length <= index) {
            throw new IOException("Missing bundled NPC history header " + key);
        }
        return fields[index];
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = BundledNpcHistory.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + name);
            }
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Bundled resource exceeds its bound");
            }
            return bytes;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
