package com.vimi.gui;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Compact mock file browser. File identity is independent of attributed paths and inferred type. */
public final class FilesView {
    private FilesView() {}

    public static BorderPane create(BuildInfo build) {
        List<MockFileCatalog.FileEntry> samples = MockFileCatalog.entries();
        TableView<MockFileCatalog.FileEntry> table = new TableView<>();
        table.getStyleClass().add("files-grid");
        table.setFixedCellSize(28);
        table.setAccessibleText("Files · authored sample catalog");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<MockFileCatalog.FileEntry, Long> id = new TableColumn<>("FileDataID");
        id.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().id()));
        id.setPrefWidth(105);
        table.getColumns().add(id);
        column(table, "Name", MockFileCatalog.FileEntry::name, 245);
        column(table, "Type", MockFileCatalog.FileEntry::type, 140);
        column(table, "Size", MockFileCatalog.FileEntry::size, 145);
        column(table, "Downloaded", MockFileCatalog.FileEntry::downloaded, 150);
        // Summaries may contain ranges and unknown lengths; do not sort them as plain strings.
        table.getColumns().get(3).setSortable(false);
        table.setPlaceholder(text("No matching sample files. Clear filters to see all entries.", "muted"));

        VBox inspector = new VBox(11);
        inspector.setPadding(new Insets(20));
        inspector.getStyleClass().add("inspector");
        ScrollPane details = new ScrollPane(inspector);
        details.setFitToWidth(true);
        details.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        details.setPrefWidth(340);
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            inspector.getChildren().clear();
            if (selected == null) {
                inspector.getChildren().add(text("Select a file to inspect its path and variants.", "muted"));
                return;
            }
            inspector.getChildren().addAll(text("SELECTED FILE", "eyebrow"), text(selected.name(), "h2"),
                    fact("FileDataID", Long.toString(selected.id())),
                    fact("Full path", selected.path() == null ? "Not known" : selected.path()),
                    fact("Name source", selected.path() == null ? "No filename supplied" : "Community listfile · authored example"),
                    fact("Type", selected.type()),
                    text(selected.path() == null ? "Content format has not been inspected." : "Type inferred from the attributed filename extension; content has not been inspected.", "muted"),
                    fact("Encoded download size", selected.size()), fact("Downloaded", selected.downloaded()),
                    text("VARIANTS · " + selected.variants().size(), "eyebrow"));
            for (int index = 0; index < selected.variants().size(); index++) {
                var variant = selected.variants().get(index);
                String state = switch (variant.local()) {
                    case STORED -> "Stored locally";
                    case NOT_STORED -> "Not stored";
                    case UNKNOWN -> "Unknown";
                };
                VBox card = new VBox(6, text("Variant " + (index + 1) + " · " + variant.locale(), "value"),
                        fact("Encoded / decoded size", MockFileCatalog.bytes(variant.encodedBytes()) + " / " + MockFileCatalog.bytes(variant.decodedBytes())),
                        text(state, "muted"));
                card.getStyleClass().add("notice");
                inspector.getChildren().add(card);
            }
            inspector.getChildren().addAll(fact("Exact mock build", build.product() + " " + build.version()),
                    text("Authored samples only. Names, sizes and local status are illustrative; no files are read or downloaded.", "muted"));
        });

        TextField search = new TextField();
        search.setPromptText("Search FileDataID, name or path");
        search.setAccessibleText("Search sample files");
        ComboBox<String> type = new ComboBox<>();
        type.getItems().add("All types");
        samples.stream().map(MockFileCatalog.FileEntry::type).distinct().forEach(type.getItems()::add);
        type.setValue("All types");
        type.setAccessibleText("File type filter");
        ComboBox<String> local = new ComboBox<>(FXCollections.observableArrayList("All download states", "Stored locally", "Not stored", "Partly stored", "Unknown"));
        local.setValue("All download states");
        local.setAccessibleText("Downloaded filter");
        FilteredList<MockFileCatalog.FileEntry> filtered = new FilteredList<>(FXCollections.observableArrayList(samples));
        SortedList<MockFileCatalog.FileEntry> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        Label count = text(samples.size() + " sample files", "muted");
        Runnable filter = () -> {
            var selected = table.getSelectionModel().getSelectedItem();
            String term = search.getText().strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(file -> matches(file, term, type.getValue(), local.getValue()));
            if (selected != null && sorted.contains(selected)) {
                table.getSelectionModel().select(selected);
            } else {
                table.getSelectionModel().clearSelection();
                inspector.getChildren().setAll(text("Select a file to inspect its path and variants.", "muted"));
            }
            count.setText(filtered.size() + " of " + samples.size() + " sample files");
        };
        search.textProperty().addListener((observable, before, after) -> filter.run());
        type.setOnAction(event -> filter.run());
        local.setOnAction(event -> filter.run());
        Button clear = new Button("Clear filters");
        clear.setOnAction(event -> {
            search.clear();
            type.setValue("All types");
            local.setValue("All download states");
        });
        HBox filters = new HBox(10, search, type, local, clear);
        filters.getStyleClass().add("toolbar");
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox heading = new VBox(8, text("Files", "h1"),
                text("Authored sample subset · community names and extension-based types · sizes are encoded bytes", "muted"));
        heading.setPadding(new Insets(18, 16, 6, 16));
        VBox results = new VBox(heading, filters, table, count);
        count.setPadding(new Insets(8, 16, 8, 16));
        VBox.setVgrow(table, Priority.ALWAYS);
        BorderPane view = new BorderPane(results);
        view.setRight(details);
        table.getSelectionModel().selectFirst();
        return view;
    }

    static boolean matches(MockFileCatalog.FileEntry file, String term, String type, String local) {
        boolean text = (file.id() + " " + file.name() + " " + (file.path() == null ? "" : file.path()))
                .toLowerCase(Locale.ROOT).contains(term);
        boolean typeMatch = type.equals("All types") || type.equals(file.type());
        String state = file.downloaded();
        boolean localMatch = switch (local) {
            case "Stored locally", "Not stored" -> state.equals(local);
            case "Unknown" -> file.variants().stream().anyMatch(v -> v.local() == MockFileCatalog.LocalState.UNKNOWN);
            case "Partly stored" -> {
                long stored = file.variants().stream().filter(v -> v.local() == MockFileCatalog.LocalState.STORED).count();
                yield stored > 0 && stored < file.variants().size();
            }
            default -> true;
        };
        return text && typeMatch && localMatch;
    }

    private static void column(TableView<MockFileCatalog.FileEntry> table, String title,
            Function<MockFileCatalog.FileEntry, String> value, int width) {
        TableColumn<MockFileCatalog.FileEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private static VBox fact(String label, String value) {
        return new VBox(4, text(label, "muted"), text(value, "value"));
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
