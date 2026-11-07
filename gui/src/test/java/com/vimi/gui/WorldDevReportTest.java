package com.vimi.gui;

import java.util.List;

/** Developer overlay pages as text: splitting, labelling, dedup, wrapping, report; no JavaFX. */
public final class WorldDevReportTest {
    private WorldDevReportTest() {}

    static final String DETAIL = "9 tiles: 9 textured (0 partial), 0 height fallback"
            + "; static diagnostic water on 6"
            + "; 23 BASIC exterior buildings and 4243 doodads on 9 (not drawn: 2 buildings,"
            + " 10418 doodads (10365 capped))"
            + "; 3 static creature models on 2 tiles (Stand pose, facing north)"
            + "; spawns not drawn: 4 unmodelled"
            + "; 1 unavailable (28_31: missing)";
    static final String POSITION =
            "Tile 28,31 · x 1611.9 / y 405.0 / altitude 307.1 · 60 yards/s · 9 / 9 tiles";

    static WorldDevReport.Texts texts(String gpu) {
        return new WorldDevReport.Texts(DETAIL, gpu,
                "Terrain explorer · persistent GPU terrain", POSITION, "", "Free flight · no collision",
                "W, Shift", "W A S D move · E/Space up", "Recorded silence before the next music track");
    }

    public static void main(String[] args) {
        pagesCycleThenTurnOff();
        detailSplitsIntoLabelledLinesOutsideParentheses();
        streamPageShowsPositionAndDetailOnce();
        renderPageNeverRepeatsTheStreamDetail();
        inputAndAudioPages();
        longValuesWrapUnderThemselves();
        headerMarksTheShownPageAndTheKey();
        reportHoldsEveryPage();
        System.out.println("World developer report: page cycle, labelled split, once-only detail,"
                + " wrapping, header and report passed");
    }

    private static void pagesCycleThenTurnOff() {
        var page = WorldDevReport.Page.STREAM;
        require(page.next() == WorldDevReport.Page.RENDER
                        && page.next().next() == WorldDevReport.Page.INPUT
                        && page.next().next().next() == WorldDevReport.Page.AUDIO
                        && WorldDevReport.Page.AUDIO.next() == null,
                "Stream, Render, Input, Audio, then off");
    }

    private static void detailSplitsIntoLabelledLinesOutsideParentheses() {
        var parts = WorldDevReport.parts(DETAIL, "; ");
        require(parts.size() == 6, "Six detail parts: " + parts);
        require(parts.get(2).endsWith("(10365 capped))"), "Nested parentheses stay whole: " + parts.get(2));
        var labels = parts.stream().map(part -> WorldDevReport.detailLine(part).label()).toList();
        require(labels.equals(List.of("terrain", "water", "objects", "creatures", "creatures", "missing")),
                "Detail labels: " + labels);
        require(WorldDevReport.detailLine("loading buildings").label().equals("objects")
                        && WorldDevReport.detailLine("Renderer not responding; Retry").label().equals("status"),
                "Other parts get a subject or a plain status label");
        require(WorldDevReport.parts("  ;  ; a", "; ").equals(List.of("a"))
                        && WorldDevReport.parts(null, "; ").isEmpty()
                        && WorldDevReport.parts("", "; ").isEmpty(),
                "Blank parts are dropped: " + WorldDevReport.parts("  ;  ; a", "; "));
        require(WorldDevReport.parts("a (b; c) ; d", "; ").equals(List.of("a (b; c)", "d")),
                "Separators inside parentheses do not split");
    }

    private static void streamPageShowsPositionAndDetailOnce() {
        var lines = WorldDevReport.lines(WorldDevReport.Page.STREAM, texts(DETAIL));
        var labels = lines.stream().map(WorldDevReport.Line::label).toList();
        require(labels.subList(0, 4).equals(List.of("tile", "camera", "speed", "prepared")),
                "Position parts first: " + labels);
        require(lines.getFirst().value().equals("28,31"), "Tile value: " + lines.getFirst());
        var text = WorldDevReport.format(lines);
        require(occurrences(text, "static diagnostic water on 6") == 1
                        && occurrences(text, "9 textured") == 1,
                "Each detail part is shown once:\n" + text);
        var legacy = WorldDevReport.lines(WorldDevReport.Page.STREAM, new WorldDevReport.Texts(
                "Terrain textures not loaded", "", "", "Tile 1,2 · x 1 / y 2 / altitude 3 · 20 yards/s"
                        + " · 3 tiles loaded", "", "", "", "", ""));
        require(legacy.getLast().equals(new WorldDevReport.Line("status", "Terrain textures not loaded"))
                        && legacy.get(3).equals(new WorldDevReport.Line("prepared", "3 tiles loaded")),
                "Software-path texts are labelled too: " + legacy);
    }

    private static void renderPageNeverRepeatsTheStreamDetail() {
        var same = WorldDevReport.format(WorldDevReport.lines(WorldDevReport.Page.RENDER, texts(DETAIL)));
        require(!same.contains("9 textured") && same.contains("gpu       same detail as the Stream page")
                        && same.startsWith("view      Terrain explorer"),
                "While GPU and texture texts are the same detail, Render points at Stream:\n" + same);
        var own = WorldDevReport.format(WorldDevReport.lines(WorldDevReport.Page.RENDER,
                texts("GPU World frame upload failed; retry the viewer.")));
        require(own.contains("gpu       GPU World frame upload failed\ngpu       retry the viewer."),
                "A different GPU status is shown in its own lines:\n" + own);
        var none = WorldDevReport.lines(WorldDevReport.Page.RENDER,
                new WorldDevReport.Texts("", null, "", "", "", "", "", "", ""));
        require(none.equals(List.of(new WorldDevReport.Line("", "nothing to report"))),
                "An empty page says so: " + none);
    }

    private static void inputAndAudioPages() {
        var input = WorldDevReport.format(WorldDevReport.lines(WorldDevReport.Page.INPUT, texts(DETAIL)));
        require(input.startsWith("look      cursor capture OK\nmotion    Free flight · no collision\n"
                        + "held      W, Shift\nkeys      W A S D move\nkeys      E/Space up"),
                "Input page:\n" + input);
        var audio = WorldDevReport.lines(WorldDevReport.Page.AUDIO, texts(DETAIL));
        require(audio.equals(List.of(new WorldDevReport.Line("audio",
                        "Recorded silence before the next music track"))),
                "Audio page: " + audio);
    }

    private static void longValuesWrapUnderThemselves() {
        var long_ = "word ".repeat(40).strip();
        var text = WorldDevReport.format(List.of(new WorldDevReport.Line("objects", long_)));
        var rows = text.split("\n");
        require(rows.length > 1, "A long value wraps: " + text);
        for (var row : rows) {
            require(row.length() <= WorldDevReport.WIDTH, "Rows fit the width: " + row.length());
        }
        require(rows[0].startsWith("objects   word") && rows[1].startsWith(" ".repeat(10) + "word"),
                "Continuations hang under the value:\n" + text);
        require(text.replace("\n" + " ".repeat(10), " ").equals("objects   " + long_),
                "Wrapping loses no words:\n" + text);
    }

    private static void headerMarksTheShownPageAndTheKey() {
        var header = WorldDevReport.header(WorldDevReport.Page.RENDER, "F3");
        require(header.contains("[2 Render]") && header.contains(" 1 Stream ") && header.endsWith("F3: next"),
                "Header: " + header);
    }

    private static void reportHoldsEveryPage() {
        var report = WorldDevReport.report(texts(DETAIL));
        for (var page : WorldDevReport.Page.values()) {
            require(report.contains("== " + page.label + " =="), "Report has " + page + ":\n" + report);
        }
        var mapped = texts(DETAIL).map(text -> text.replace("9", "#"));
        require(mapped.stream().startsWith("# tiles") && mapped.audio().equals(texts(DETAIL).audio()),
                "map rewrites every text: " + mapped);
        require(new WorldDevReport.Texts(null, null, null, null, null, null, null, null, null)
                        .map(text -> { throw new AssertionError("null texts are not mapped"); })
                        .stream() == null,
                "Null texts stay null");
        require(occurrences(report, "9 textured") == 1 && report.contains("Recorded silence")
                        && report.contains("cursor capture OK"),
                "The report repeats no detail and covers every page:\n" + report);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
