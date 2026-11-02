package com.vimi.gui;

import java.util.List;

/** Authored presentation samples only; no game-data decoding or backend access. */
public final class MockResearchData {
    private MockResearchData() {}

    public record Entry(String id, String name, String kind, String details, String sourceTable) {
        @Override
        public String toString() {
            return id + "   " + name;
        }
    }

    public static List<Entry> entries(String area) {
        return switch (area) {
            case "Tables" -> List.of(
                    new Entry("Item", "Item", "Table definition + sample records", "ID 900001 · ClassID 2 · SubclassID 7\nID 900002 · ClassID 4 · SubclassID 1\n\nThese are authored records, not decoded build data.", ""),
                    new Entry("ItemSparse", "ItemSparse", "Localized item fields", "ID 900001 · Name: Example longsword\nID 900002 · Name: Example robe\nLocale: enUS · sample names", ""),
                    new Entry("Creature", "Creature", "Client creature declarations", "ID 700001 · Name: Example forest wolf\nID 700002 · Name: Example stone guardian\n\nClient declarations do not establish spawn locations.", ""),
                    new Entry("ItemClass", "ItemClass", "Classification fields", "ID 2 · Weapons\nID 4 · Armor\n\nIllustrative classification labels.", ""));
            case "Creatures" -> List.of(
                    new Entry("700001", "Example forest wolf", "Creature declaration · sample", "Display ID: 400001 · mock\nCreature type: Beast · mock\n\nModel preview unavailable. This is an authored client-data example, not evidence of a live spawn.", "Creature"),
                    new Entry("700002", "Example stone guardian", "Creature declaration · sample", "Display ID: 400002 · mock\nCreature type: Unknown\n\nModel preview unavailable. No server behavior is inferred.", "Creature"));
            default -> throw new IllegalArgumentException("Unknown research area: " + area);
        };
    }
}
