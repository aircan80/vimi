package com.vimi.gui;

import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.ItemFacts;
import com.vibeminer.concepts.items.ItemSummary;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.TableTypes;

import java.util.List;
import java.util.Locale;

/** Bounded, immutable browser rows. No gameplay calculations or media acquisition. */
public final class ResearchPresentation {
    private ResearchPresentation() {}

    public record Row(long id, String name, List<String> cells, Object source) {
        public Row {
            cells = List.copyOf(cells);
        }

        public String cell(int index) {
            return index < cells.size() ? cells.get(index) : "—";
        }
    }

    public record Page(
            List<Row> rows,
            String next,
            String status,
            long total,
            com.vibeminer.concepts.items.ItemGridPage.Options itemOptions) {
        public Page(List<Row> rows, String next, String status) {
            this(rows, next, status, -1, null);
        }

        public Page {
            rows = List.copyOf(rows);
        }
    }

    public static List<String> columns(String area) {
        return switch (area) {
            case "Files" -> List.of("FileDataID", "Name", "Type", "Decoded size", "Downloaded");
            case "Tables" ->
                    List.of("FileDataID", "Table", "Readiness", "Readable rows", "Declared rows");
            case "Items" ->
                    List.of("Item ID", "Name", "Class", "Subclass", "Quality", "Slot", "Base iLvl");
            default -> List.of("Creature ID", "Name", "Display slots", "Name status");
        };
    }

    public static String bytes(Long value) {
        return value == null ? "Unknown" : String.format(Locale.ROOT, "%,d B", value);
    }

    public static String state(Enum<?> value) {
        if (value == null) return "Unknown";
        String text = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static Row file(FileTypes.BuildFile file) {
        String name =
                file.path() == null
                        ? "Unnamed"
                        : file.path()
                                .substring(
                                        Math.max(
                                                        file.path().lastIndexOf('/'),
                                                        file.path().lastIndexOf('\\'))
                                                + 1);
        String storage =
                switch (file.storage()) {
                    case STORED -> "Stored locally";
                    case NOT_STORED -> "Not stored";
                    case NO_ENCODED_COPY -> "No encoded copy";
                };
        return new Row(
                file.fileDataId(),
                name,
                List.of(
                        Long.toString(file.fileDataId()),
                        name,
                        file.format() == FileTypes.FileFormat.UNKNOWN
                                ? "Unknown"
                                : file.format().name(),
                        bytes(file.size()),
                        storage),
                file);
    }

    public static Row table(TableTypes.Entry table) {
        String name = table.name() == null ? "Unnamed table" : table.name();
        return new Row(
                table.fileDataId(),
                name,
                List.of(
                        Long.toString(table.fileDataId()),
                        name,
                        state(table.state()),
                        table.counts() == null
                                ? "Unknown"
                                : Long.toString(table.counts().readableRows()),
                        table.counts() == null
                                ? "Unknown"
                                : Long.toString(table.counts().declaredRows())),
                table);
    }

    public static String itemValue(ItemSummary item, String column) {
        var values =
                item.values().stream()
                        .filter(v -> v.column().equals(column))
                        .map(v -> v.value())
                        .distinct()
                        .toList();
        return values.size() == 1
                ? values.getFirst()
                : values.isEmpty() ? "Unknown" : "Conflicting";
    }

    public static Row item(ItemSummary item) {
        String name =
                item.name() == null || item.name().value() == null || item.name().value().isBlank()
                        ? "Item " + item.id()
                        : item.name().value();
        String quality = itemValue(item, "OverallQualityID");
        String slot = itemValue(item, "InventoryType");
        return new Row(
                item.id(),
                name,
                List.of(
                        Long.toString(item.id()),
                        name,
                        itemValue(item, "ClassID"),
                        itemValue(item, "SubclassID"),
                        ItemFacts.QUALITY.getOrDefault(quality, quality),
                        ItemFacts.INVENTORY_TYPE.getOrDefault(slot, slot),
                        itemValue(item, "ItemLevel")),
                item);
    }

    public static Row item(com.vibeminer.concepts.items.ItemListLabels value) {
        var row = item(value.item());
        var cells = new java.util.ArrayList<>(row.cells());
        cells.set(2, classText(value.labels() == null ? null : value.labels().itemClass()));
        cells.set(3, classText(value.labels() == null ? null : value.labels().subclass()));
        return new Row(row.id(), row.name(), cells, row.source());
    }

    private static String classText(com.vibeminer.concepts.items.ItemClassLabel label) {
        if (label == null || label.text() == null || label.text().isBlank()) return "Unavailable";
        return switch (label.state()) {
            case READY -> label.text();
            case INCOMPLETE_TARGET -> label.text() + " (partial)";
            default -> "Unavailable";
        };
    }

    public static Row item(com.vibeminer.concepts.items.ItemGridRow row) {
        return new Row(
                row.id(),
                row.name(),
                List.of(
                        Long.toString(row.id()),
                        row.name(),
                        row.itemClass() == null ? "Unavailable" : row.itemClass(),
                        row.subclass() == null ? "Unavailable" : row.subclass(),
                        row.quality() == null ? "Unknown" : row.quality(),
                        row.slot() == null ? "Unknown" : row.slot(),
                        row.itemLevel() == null ? "Unknown" : row.itemLevel().toString()),
                row);
    }

    public static Row creature(CreatureTypes.CreatureSummary creature) {
        String name =
                creature.name() == null
                                || creature.name().value() == null
                                || creature.name().value().isBlank()
                        ? "Creature " + creature.id()
                        : creature.name().value();
        return new Row(
                creature.id(),
                name,
                List.of(
                        Long.toString(creature.id()),
                        name,
                        creature.slots() == null
                                ? "Unknown"
                                : Integer.toString(creature.slots().size()),
                        creature.name() == null ? "Unknown" : state(creature.name().state())),
                creature);
    }

    public static boolean matches(Row row, String query) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        return (row.id()
                        + " "
                        + row.name()
                        + " "
                        + (row.source() instanceof FileTypes.BuildFile file ? file.path() : ""))
                .toLowerCase(Locale.ROOT)
                .contains(term);
    }

    public static String qualityInk(String quality) {
        return switch (quality) {
            case "Poor" -> "#777777";
            case "Uncommon" -> "#16822c";
            case "Rare" -> "#1267b0";
            case "Epic" -> "#8d37b6";
            case "Legendary" -> "#af5700";
            case "Artifact" -> "#886522";
            default -> "#243e3c";
        };
    }
}
