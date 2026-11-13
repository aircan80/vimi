package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The developer overlay's pages as text, without JavaFX: the explorer's long status texts split
 * into labelled monospaced lines, each source text on exactly one page, and the all-pages report.
 */
final class WorldDevReport {
    private WorldDevReport() {}

    /** Label column width in characters; values wrap with a hanging indent past {@link #WIDTH}. */
    static final int LABEL = 9, WIDTH = 72;

    enum Page {
        STREAM("Stream"),
        RENDER("Render"),
        INPUT("Input"),
        AUDIO("Audio");

        final String label;

        Page(String label) {
            this.label = label;
        }

        /** The page after this one; null (off) after the last. */
        Page next() {
            var all = values();
            return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
        }
    }

    /** One labelled line of a page. */
    record Line(String label, String value) {}

    /**
     * The status texts the overlay shows, read when a page is drawn. Null or blank texts are
     * left out.
     *
     * @param stream the stream/texture detail ("9 tiles: 9 textured …; …")
     * @param gpu the GPU status, often the same detail text while streaming
     * @param renderer the explorer status line (renderer capabilities, or the latest view message)
     * @param position the position line ("Tile 28,31 · x … · 60 yards/s · 9 / 9 tiles")
     * @param look the mouse-look capture status; empty when capture works
     * @param motion the motion status ("Free camera · no collision")
     * @param held the keys held right now
     * @param keys the key help line
     * @param audio the recorded area audio status
     */
    record Texts(String stream, String gpu, String renderer, String position, String look,
            String motion, String held, String keys, String audio) {
        /** Every non-null text through {@code each}, e.g. redaction before a report leaves the app. */
        Texts map(java.util.function.UnaryOperator<String> each) {
            java.util.function.UnaryOperator<String> f = text -> text == null ? null : each.apply(text);
            return new Texts(f.apply(stream), f.apply(gpu), f.apply(renderer), f.apply(position),
                    f.apply(look), f.apply(motion), f.apply(held), f.apply(keys), f.apply(audio));
        }
    }

    static String header(Page page, String key) {
        var text = new StringBuilder("DEV ");
        for (var each : Page.values()) {
            String name = (each.ordinal() + 1) + " " + each.label;
            text.append(' ').append(each == page ? "[" + name + "]" : " " + name + " ");
        }
        return text.append("   ").append(key).append(": next").toString();
    }

    /** Every page, for a bug report. */
    static String report(Texts texts) {
        var text = new StringBuilder("vimi World developer report");
        for (var page : Page.values()) {
            text.append("\n\n== ").append(page.label).append(" ==\n").append(format(lines(page, texts)));
        }
        return text.append('\n').toString();
    }

    /** A page's labelled lines; each source text appears on exactly one page. */
    static List<Line> lines(Page page, Texts texts) {
        var lines = new ArrayList<Line>();
        switch (page) {
            case STREAM -> {
                for (var part : parts(texts.position(), " · ")) lines.add(positionLine(part));
                for (var part : parts(texts.stream(), "; ")) lines.add(detailLine(part));
            }
            case RENDER -> {
                add(lines, "view", texts.renderer());
                if (blank(texts.gpu())) break;
                if (texts.gpu().equals(texts.stream())) lines.add(new Line("gpu", "same detail as the Stream page"));
                else for (var part : parts(texts.gpu(), "; ")) lines.add(new Line("gpu", part));
            }
            case INPUT -> {
                lines.add(new Line("look", blank(texts.look()) ? "cursor capture OK" : texts.look()));
                add(lines, "motion", texts.motion());
                lines.add(new Line("held", blank(texts.held()) ? "none" : texts.held()));
                for (var part : parts(texts.keys(), " · ")) lines.add(new Line("keys", part));
            }
            case AUDIO -> add(lines, "audio", texts.audio());
        }
        if (lines.isEmpty()) lines.add(new Line("", "nothing to report"));
        return lines;
    }

    private static void add(List<Line> lines, String label, String value) {
        if (!blank(value)) lines.add(new Line(label, value.strip()));
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** A position-line part, labelled by what it measures. */
    static Line positionLine(String part) {
        if (part.startsWith("Tile ")) return new Line("tile", part.substring(5));
        if (part.contains("altitude")) return new Line("camera", part);
        if (part.endsWith("yards/s")) return new Line("speed", part);
        if (part.contains(" tiles")) return new Line("prepared", part);
        return new Line("position", part);
    }

    private static final java.util.regex.Pattern TERRAIN = java.util.regex.Pattern.compile("\\d+ tiles?: .*");

    /** A stream-detail part, labelled by its subject. */
    static Line detailLine(String part) {
        String lower = part.toLowerCase(Locale.ROOT);
        if (TERRAIN.matcher(lower).matches()) return new Line("terrain", part);
        if (lower.contains("water")) return new Line("water", part);
        if (lower.contains("building") || lower.contains("doodad")) return new Line("objects", part);
        if (lower.contains("creature") || lower.contains("spawn")) return new Line("creatures", part);
        if (lower.contains("unavailable")) return new Line("missing", part);
        return new Line("status", part);
    }

    /**
     * {@code text} split at {@code separator} outside parentheses, parts stripped, blanks dropped;
     * so "(not drawn: 2 buildings, 3 doodads)" stays with its count.
     */
    static List<String> parts(String text, String separator) {
        var parts = new ArrayList<String>();
        if (blank(text)) return parts;
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && text.startsWith(separator, i)) {
                keep(parts, text.substring(start, i));
                start = i + separator.length();
                i = start - 1;
            }
        }
        keep(parts, text.substring(start));
        return parts;
    }

    private static void keep(List<String> parts, String part) {
        if (!part.isBlank()) parts.add(part.strip());
    }

    /** Monospaced lines: the label padded to {@link #LABEL}, values wrapped under themselves. */
    static String format(List<Line> lines) {
        var text = new StringBuilder();
        String indent = " ".repeat(LABEL + 1);
        for (var line : lines) {
            if (!text.isEmpty()) text.append('\n');
            text.append(String.format(Locale.ROOT, "%-" + LABEL + "s ", line.label()));
            var words = line.value().split(" ");
            int column = LABEL + 1;
            boolean first = true;
            for (var word : words) {
                if (!first && column + 1 + word.length() > WIDTH) {
                    text.append('\n').append(indent);
                    column = LABEL + 1;
                    first = true;
                }
                if (!first) {
                    text.append(' ');
                    column++;
                }
                text.append(word);
                column += word.length();
                first = false;
            }
        }
        return text.toString();
    }

}
