package com.vimi.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Sample item browser with offline icon assets and authored WoW-style tooltips. */
public final class ItemsView extends BorderPane {
    private final List<MockItemCatalog.Item> samples = MockItemCatalog.entries();
    private final Map<String, Image> icons = new HashMap<>();
    private final TableView<MockItemCatalog.Item> table = new TableView<>();
    private final VBox detail = new VBox(16);
    private final Label hidden = text("Selected item is outside these results. Clear filters to show it.", "muted");
    private final TextField search = new TextField();
    private final ComboBox<String> itemClass = combo("All classes");
    private final ComboBox<String> subclass = combo("All subclasses");
    private final ComboBox<String> quality = combo("All qualities");
    private final ComboBox<String> slot = combo("All slots");
    private MockItemCatalog.Item selected;
    private boolean filtering;

    public ItemsView(BuildInfo build, Consumer<String> sourceNavigation) {
        table.setAccessibleText("Items · authored samples with bundled icons");
        table.setFixedCellSize(36);
        table.getStyleClass().add("items-grid");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<MockItemCatalog.Item, MockItemCatalog.Item> picture = new TableColumn<>("");
        picture.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        picture.setMinWidth(40);
        picture.setMaxWidth(40);
        picture.setSortable(false);
        picture.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(MockItemCatalog.Item item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : icon(item, 28));
            }
        });
        table.getColumns().add(picture);
        TableColumn<MockItemCatalog.Item, String> name = column("Name", MockItemCatalog.Item::name, 225);
        name.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                MockItemCatalog.Item item = getTableRow() == null ? null : getTableRow().getItem();
                setStyle(item == null || empty ? "" : "-fx-text-fill: " + item.quality().ink() + ";");
            }
        });
        column("Class", MockItemCatalog.Item::itemClass, 100);
        column("Subclass", MockItemCatalog.Item::subclass, 100);
        column("Quality", item -> item.quality().label(), 95);
        column("Slot", MockItemCatalog.Item::slot, 90);
        TableColumn<MockItemCatalog.Item, Integer> level = new TableColumn<>("iLvl");
        level.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().itemLevel()));
        level.setPrefWidth(55);
        level.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Integer value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value == null ? "—" : value.toString());
            }
        });
        table.getColumns().add(level);
        table.setPlaceholder(text("No matching sample items. Clear filters to see all items.", "muted"));
        hidden.setManaged(false);
        hidden.setVisible(false);
        detail.setPadding(new Insets(20));
        detail.getStyleClass().add("inspector");
        table.getSelectionModel().selectedItemProperty().addListener((observable, before, item) -> {
            if (!filtering && item != null) {
                selected = item;
                showTooltip(item, build, sourceNavigation);
                hidden.setVisible(false);
                hidden.setManaged(false);
            }
        });
        ScrollPane inspector = new ScrollPane(detail);
        inspector.setFitToWidth(true);
        inspector.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        inspector.setPrefWidth(365);
        setRight(inspector);

        search.setPromptText("Name or exact item ID");
        search.setAccessibleText("Search item name or exact ID");
        itemClass.setAccessibleText("Item class filter");
        subclass.setAccessibleText("Item subclass filter");
        quality.setAccessibleText("Item quality filter");
        slot.setAccessibleText("Item equipment slot filter");
        samples.stream().map(MockItemCatalog.Item::itemClass).distinct().sorted().forEach(itemClass.getItems()::add);
        samples.stream().map(item -> item.quality().label()).distinct().forEach(quality.getItems()::add);
        samples.stream().map(MockItemCatalog.Item::slot).distinct().sorted().forEach(slot.getItems()::add);
        updateSubclasses();
        FilteredList<MockItemCatalog.Item> filtered = new FilteredList<>(FXCollections.observableArrayList(samples));
        SortedList<MockItemCatalog.Item> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        Label count = text(samples.size() + " sample items · icons bundled locally", "muted");
        count.setPadding(new Insets(8, 16, 8, 16));
        Runnable filter = () -> {
            filtering = true;
            filtered.setPredicate(item -> MockItemCatalog.matches(item, search.getText(), itemClass.getValue(), subclass.getValue(), quality.getValue(), slot.getValue()));
            if (selected != null && sorted.contains(selected)) {
                table.getSelectionModel().select(selected);
            } else {
                table.getSelectionModel().clearSelection();
            }
            hidden.setVisible(selected != null && !sorted.contains(selected));
            hidden.setManaged(hidden.isVisible());
            filtering = false;
            count.setText(filtered.size() + " of " + samples.size() + " sample items · icons bundled locally");
        };
        search.textProperty().addListener((observable, before, after) -> filter.run());
        itemClass.setOnAction(event -> {
            updateSubclasses();
            filter.run();
        });
        subclass.setOnAction(event -> { if (!filtering) { filter.run(); } });
        quality.setOnAction(event -> filter.run());
        slot.setOnAction(event -> filter.run());
        Button clear = new Button("Clear filters");
        clear.setOnAction(event -> {
            search.clear();
            itemClass.setValue("All classes");
            subclass.setValue("All subclasses");
            quality.setValue("All qualities");
            slot.setValue("All slots");
        });
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox heading = new HBox(12, text("Items", "h1"), space, clear);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox title = new VBox(6, heading, text("Authored items and tooltip values · enUS · artwork is bundled sample icon art", "muted"));
        title.setPadding(new Insets(18, 16, 8, 16));
        GridPane filters = new GridPane();
        filters.getStyleClass().add("item-filters");
        filters.setHgap(8);
        double[] widths = {32, 17, 19, 16, 16};
        List<javafx.scene.Node> controls = List.of(search, itemClass, subclass, quality, slot);
        String[] labels = {"Name / Item ID", "Class", "Subclass", "Quality", "Slot"};
        for (int index = 0; index < widths.length; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(widths[index]);
            filters.getColumnConstraints().add(column);
            Region control = (Region) controls.get(index);
            control.setMinWidth(0);
            control.setMaxWidth(Double.MAX_VALUE);
            VBox field = new VBox(4, text(labels[index], "eyebrow"), control);
            field.setMinWidth(0);
            filters.add(field, index, 0);
        }
        VBox list = new VBox(title, filters, table, count);
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(list);
        table.getSelectionModel().selectFirst();
    }

    private void updateSubclasses() {
        boolean previous = filtering;
        filtering = true;
        subclass.getItems().setAll("All subclasses");
        samples.stream().filter(item -> itemClass.getValue().equals("All classes") || item.itemClass().equals(itemClass.getValue()))
                .map(MockItemCatalog.Item::subclass).distinct().sorted().forEach(subclass.getItems()::add);
        subclass.setValue("All subclasses");
        filtering = previous;
    }

    private void showTooltip(MockItemCatalog.Item item, BuildInfo build, Consumer<String> navigate) {
        detail.getChildren().setAll(text("ITEM DETAILS", "eyebrow"), hidden);
        Label name = text(item.name(), "wow-item-name");
        name.setStyle("-fx-text-fill: " + item.quality().tooltipInk() + ";");
        HBox top = new HBox(12, icon(item, 44), name);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        VBox tooltip = new VBox(7, top);
        tooltip.getStyleClass().add("wow-item-tooltip");
        if (item.itemLevel() != null) {
            tooltip.getChildren().add(text("Item Level " + item.itemLevel(), "wow-level"));
        }
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        tooltip.getChildren().add(new HBox(10, text(item.slot().equals("None") ? item.itemClass() : item.slot(), "wow-line"), space,
                text(item.subclass(), "wow-line")));
        for (var line : item.lines()) {
            tooltip.getChildren().add(text(line.text(), "wow-" + line.kind()));
        }
        detail.getChildren().addAll(tooltip,
                text("Mock tooltip · names, stats and effects are authored design examples. No gameplay calculations or live item data.", "muted"),
                fact("Item ID / quality", item.id() + " / " + item.quality().label()),
                fact("Class / subclass", item.itemClass() + " / " + item.subclass()),
                fact("Exact mock build", build.product() + " " + build.version()),
                text("SOURCE TABLES", "eyebrow"));
        Button source = new Button("Open Item table");
        source.setOnAction(event -> navigate.accept("Item"));
        Button sparse = new Button("Open ItemSparse table");
        sparse.setOnAction(event -> navigate.accept("ItemSparse"));
        detail.getChildren().addAll(source, sparse);
    }

    private HBox icon(MockItemCatalog.Item item, int size) {
        Image image = icons.computeIfAbsent(item.icon(), key -> new Image(ItemsView.class.getResource("/items/" + key + ".jpg").toExternalForm()));
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setSmooth(true);
        HBox frame = new HBox(view);
        frame.setStyle("-fx-border-color: " + item.quality().ink() + "; -fx-border-width: 1; -fx-background-color: #182128;");
        frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        frame.setAccessibleText(item.quality().label() + " item icon");
        return frame;
    }

    private TableColumn<MockItemCatalog.Item, String> column(String title, Function<MockItemCatalog.Item, String> value, int width) {
        TableColumn<MockItemCatalog.Item, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
        return column;
    }

    private static ComboBox<String> combo(String all) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(all);
        box.setValue(all);
        return box;
    }

    private static VBox fact(String label, String value) { return new VBox(4, text(label, "muted"), text(value, "value")); }
    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
