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
 * The EG Link NPC display history that ships with the application: per NPC, the displays of the
 * newest client build that recorded it across the upstream repository's recent commits, with the
 * build, commit and collection date they came from. Built by hand before a release with {@code
 * scripts/release/eg_link_history.py}; nothing is downloaded. The file is checked against the
 * SHA-256 its NOTICE.md records when it loads.
 */
public final class BundledNpcHistory {
    static final String FORMAT = "vimi-eg-link-npc-history-v1";
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

        /** EG Link, its license, the build and collection date, and that it ships bundled. */
        public String provenance() {
            return "EG Link creature_details ("
                    + EgLinkTypes.LICENSE
                    + "), build "
                    + source.build()
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

    private final Map<Long, Entry> entries;
    private final Attribution attribution;

    private BundledNpcHistory(Map<Long, Entry> entries, Attribution attribution) {
        this.entries = Map.copyOf(entries);
        this.attribution = attribution;
    }

    private static final class Shared {
        static final BundledNpcHistory VALUE = load();
    }

    /** The bundled history, read and verified once. */
    public static BundledNpcHistory bundled() {
        return Shared.VALUE;
    }

    /** The bundled NPC display history's attribution, read from its NOTICE.md. */
    public static Attribution attribution() {
        return bundled().attribution;
    }

    public Optional<Entry> displays(long npcId) {
        return Optional.ofNullable(entries.get(npcId));
    }

    public int size() {
        return entries.size();
    }

    /** The product the history was collected for (EG Link's {@code wowf}). */
    public String product() {
        return attribution.product();
    }

    private static BundledNpcHistory load() {
        try {
            byte[] notice = resource("history/NOTICE.md");
            byte[] data = resource("history/npc-displays.txt");
            var digest = DIGEST.matcher(new String(notice, StandardCharsets.UTF_8));
            if (!digest.find() || !digest.group(1).equals(sha256(data))) {
                throw new IOException("Bundled NPC history failed its recorded hash");
            }
            return parse(data, new String(notice, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Bundled EG Link NPC history is unreadable: " + failure.getMessage(), failure);
        }
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
        var entries = new HashMap<Long, Entry>();
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
                if (fields.length != 3 || entries.size() >= MAX_NPCS) {
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
                if (entries.putIfAbsent(npc, new Entry(npc, displays, source)) != null) {
                    throw new IOException("Duplicate bundled NPC");
                }
            }
        }
        if (!FORMAT.equals(value(header, "format", 1))
                || Integer.parseInt(value(header, "npcs", 1)) != entries.size()) {
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
