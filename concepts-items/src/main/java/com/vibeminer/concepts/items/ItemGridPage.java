package com.vibeminer.concepts.items;

import java.util.List;

public record ItemGridPage(
        ItemsPageState state,
        List<ItemGridRow> rows,
        long matches,
        long available,
        Options options,
        String message) {
    public record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record Options(
            List<Choice> classes,
            List<Choice> subclasses,
            List<Choice> qualities,
            List<Choice> slots) {
        public Options {
            classes = List.copyOf(classes);
            subclasses = List.copyOf(subclasses);
            qualities = List.copyOf(qualities);
            slots = List.copyOf(slots);
        }

        public static Options empty() {
            return new Options(List.of(), List.of(), List.of(), List.of());
        }
    }

    public ItemGridPage {
        rows = List.copyOf(rows);
    }
}
