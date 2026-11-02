package com.vimi.gui;

import java.util.List;
import java.util.Locale;

/** Authored items and tooltip lines, never an interpretation of real build data. */
public final class MockItemCatalog {
    private MockItemCatalog() {}

    public enum Quality {
        COMMON("Common", "#555b57", "#ffffff"),
        UNCOMMON("Uncommon", "#26733c", "#1eff00"),
        RARE("Rare", "#1769a6", "#0070dd"),
        EPIC("Epic", "#8140ac", "#a335ee"),
        LEGENDARY("Legendary", "#995000", "#ff8000");

        private final String label;
        private final String ink;
        private final String tooltipInk;
        Quality(String label, String ink, String tooltipInk) {
            this.label = label;
            this.ink = ink;
            this.tooltipInk = tooltipInk;
        }
        public String label() { return label; }
        public String ink() { return ink; }
        public String tooltipInk() { return tooltipInk; }
        @Override public String toString() { return label; }
    }

    public record TooltipLine(String text, String kind) {}

    public record Item(long id, String name, String itemClass, String subclass, Quality quality,
            String slot, Integer itemLevel, String icon, List<TooltipLine> lines) {
        public Item { lines = List.copyOf(lines); }
    }

    private static TooltipLine line(String value) { return new TooltipLine(value, "plain"); }
    private static TooltipLine stat(String value) { return new TooltipLine(value, "stat"); }
    private static TooltipLine flavor(String value) { return new TooltipLine(value, "flavor"); }

    public static List<Item> entries() {
        return List.of(
                new Item(900001, "Wayfarer’s Longsword", "Weapon", "Sword", Quality.UNCOMMON, "Main hand", 100, "inv_sword_04", List.of(
                        line("Binds when equipped"), line("74 – 112 Damage"), line("Speed 2.60"), line("(35.8 damage per second)"),
                        stat("+12 Strength"), stat("+18 Stamina"), line("Durability 90 / 90"), line("Requires Level 70"), flavor("A trusty blade for an unwritten journey."))),
                new Item(900002, "Robe of Quiet Stars", "Armor", "Cloth", Quality.RARE, "Chest", 120, "inv_chest_cloth_17", List.of(
                        line("Binds when picked up"), line("80 Armor"), stat("+24 Intellect"), stat("+36 Stamina"), stat("+16 Critical Strike"),
                        stat("+12 Haste"), line("Durability 100 / 100"), line("Requires Level 70"), flavor("Silver threads catch the light of distant stars."))),
                new Item(900003, "Bastion of the Last Watch", "Armor", "Shield", Quality.EPIC, "Off hand", 145, "inv_shield_04", List.of(
                        line("Binds when picked up"), line("420 Armor"), line("180 Block"), stat("+30 Strength"), stat("+52 Stamina"),
                        stat("Equip: Your resolve is strengthened while guarding an ally."), line("Requires Level 70"), flavor("Its watch has never ended."))),
                new Item(900004, "Crimson Restorative", "Consumable", "Potion", Quality.COMMON, "None", null, "inv_potion_51", List.of(
                        line("Unique (20)"), stat("Use: Restores 1,200 health. (5 Min Cooldown)"), line("Requires Level 20"), flavor("A bright draught with a bitter finish."))),
                new Item(900005, "Explorer’s Satchel", "Container", "Bag", Quality.UNCOMMON, "Bag", 35, "inv_misc_bag_08", List.of(
                        line("Binds when equipped"), line("16 Slot Bag"), flavor("Room for a few more discoveries."))),
                new Item(900006, "Gleaming Ruby", "Gem", "Red", Quality.RARE, "None", 80, "inv_misc_gem_ruby_02", List.of(
                        stat("+10 Strength"), line("Matches a Red Socket."), line("Requires Level 50"), flavor("A spark of fire, caught in stone."))),
                new Item(900007, "Pattern: Starwoven Robe", "Recipe", "Tailoring", Quality.RARE, "None", null, "inv_scroll_03", List.of(
                        line("Requires Tailoring (75)"), stat("Use: Teaches you how to create a Starwoven Robe."))),
                new Item(900008, "Helm of the Emberward", "Armor", "Plate", Quality.EPIC, "Head", 145, "inv_helmet_03", List.of(
                        line("Binds when picked up"), line("245 Armor"), stat("+28 Strength"), stat("+44 Stamina"), stat("+18 Mastery"),
                        line("Durability 100 / 100"), line("Requires Level 70"), flavor("Warm to the touch, even in winter."))),
                new Item(900009, "The First Dawn", "Weapon", "Sword", Quality.LEGENDARY, "Main hand", 160, "inv_sword_04", List.of(
                        line("Binds when picked up"), line("Unique-Equipped"), line("120 – 180 Damage"), line("Speed 2.60"), stat("+38 Strength"),
                        stat("Equip: Your attacks may call forth a glimmer of the first dawn."), line("Requires Level 70"), flavor("Every legend begins with a single light."))),
                new Item(900010, "Faded Apprentice Robe", "Armor", "Cloth", Quality.COMMON, "Chest", 5, "inv_chest_cloth_17", List.of(
                        line("8 Armor"), line("Durability 35 / 35"), line("Requires Level 1"), flavor("Well worn, but carefully mended."))));
    }

    public static boolean matches(Item item, String query, String itemClass, String subclass, String quality, String slot) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        return (item.name().toLowerCase(Locale.ROOT).contains(term) || Long.toString(item.id()).equals(term))
                && (itemClass.equals("All classes") || itemClass.equals(item.itemClass()))
                && (subclass.equals("All subclasses") || subclass.equals(item.subclass()))
                && (quality.equals("All qualities") || quality.equals(item.quality().label()))
                && (slot.equals("All slots") || slot.equals(item.slot()));
    }
}
