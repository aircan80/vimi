package com.vimi.gui;

import java.util.List;

/** Search/status filters cannot leave Open build targeting a hidden or stale row. */
public final class BuildSelectionTest {
    private BuildSelectionTest() {}

    public static void main(String[] args) {
        var previous = build("a", false);
        var other = build("b", false);
        var refreshed = build("a", true);
        require(
                BuildInfo.visibleSelection(List.of(other), previous) == other,
                "Filtering out the selected row selects a visible result");
        require(
                BuildInfo.visibleSelection(List.of(), previous) == null,
                "No results clears selection so actions cannot open a hidden build");
        require(
                BuildInfo.visibleSelection(List.of(other, refreshed), previous) == refreshed,
                "Retained identity uses the newest row, including its latest context/status");
        require(
                BuildInfo.visibleSelection(List.of(other, previous), previous) == previous,
                "A still-visible choice survives filtering without jumping to the first row");
        require(
                BuildInfo.visibleSelection(List.of(other), null) == other,
                "Initial selection uses a visible result");
        System.out.println(
                "Build selection: hidden/empty filters, refreshed facts and stable choices passed");
    }

    private static BuildInfo build(String key, boolean prepared) {
        return new BuildInfo(
                "wowf",
                "Forever",
                "1.60.1." + key,
                "us",
                key,
                BuildInfo.BuildSource.CURRENT,
                prepared,
                null,
                0);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
