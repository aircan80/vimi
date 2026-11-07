package com.vimi.gui;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The World explorer's Escape menu, drawn over a dimmed viewport like a game client's menu: a
 * column of large entries (Resume, the settings pages, Leave instance, Back to map list), and one
 * page at a time in the same panel under a "Back" header.
 *
 * <p>The pages hold the view's existing controls, which stay their models; the view fills each
 * page whenever the explorer layout is built. Input on the menu stops here and never reaches
 * flight, look or inspection. FX thread only.
 */
final class WorldGameMenu extends StackPane {
    enum Page {
        CONTROLS("Controls"),
        AUDIO("Audio"),
        DISPLAY("Display"),
        BOOKMARKS("Bookmarks"),
        INSTANCES("Instances");

        final String title;

        Page(String title) {
            this.title = title;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Region dim = new Region();
    private final VBox panel = new VBox(10);
    private final Label title = new Label("WORLD MENU");
    private final Button back = new Button("‹ Back"), resume = new Button("Resume"),
            leave = new Button("Leave instance");
    private final VBox main = new VBox(6);
    private final StackPane body = new StackPane();
    private final Map<Page, VBox> pages = new EnumMap<>(Page.class);
    // Plain boxes, not scroll panes: a page's controls are in the scene graph from the start.
    private final Runnable opened, closed;
    private Page page;

    /**
     * {@code opened} runs when the menu opens (the view releases held keys); {@code closed} when
     * it closes (the view gives focus back to the world); {@code leaveInstance} is the menu's
     * Leave instance entry.
     */
    WorldGameMenu(Runnable opened, Runnable closed, Runnable leaveInstance) {
        this.opened = Objects.requireNonNull(opened);
        this.closed = Objects.requireNonNull(closed);
        Objects.requireNonNull(leaveInstance);
        setId("world-menu");
        setFocusTraversable(false);
        dim.setId("world-menu-dim");
        // The world shows through at 60%.
        dim.setStyle("-fx-background-color: rgba(0, 0, 0, 0.4);");
        dim.setOnMouseClicked(e -> close());
        panel.setId("world-menu-panel");
        panel.setStyle("-fx-background-color: rgba(243, 246, 243, 0.98); -fx-background-radius: 10;"
                + " -fx-padding: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,.5), 18, .2, 0, 3);");
        panel.setPrefWidth(460);
        panel.setMaxWidth(520);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        title.setId("world-menu-title");
        title.setStyle("-fx-font-size: 1.3em; -fx-font-weight: bold;");
        back.setId("world-menu-back");
        back.setOnAction(e -> showMain());
        var header = new HBox(10, back, title);
        header.setAlignment(Pos.CENTER_LEFT);
        resume.setId("world-menu-resume");
        resume.setOnAction(e -> close());
        entry(resume, "Esc");
        main.setId("world-menu-main");
        main.getChildren().add(resume);
        for (var each : Page.values()) {
            var open = new Button(each.title);
            open.setId("world-menu-" + each.id());
            open.setOnAction(e -> open(each));
            entry(open, null);
            main.getChildren().add(open);
            var content = new VBox(10);
            content.setId("world-menu-page-" + each.id());
            content.setPadding(new Insets(0, 4, 0, 0));
            content.setVisible(false);
            pages.put(each, content);
            body.getChildren().add(content);
        }
        leave.setId("world-menu-leave");
        leave.setOnAction(e -> {
            close();
            leaveInstance.run();
        });
        entry(leave, null);
        leave.managedProperty().bind(leave.visibleProperty());
        main.getChildren().add(leave);
        body.getChildren().addFirst(main);
        VBox.setVgrow(body, Priority.ALWAYS);
        panel.getChildren().addAll(header, body);
        getChildren().addAll(dim, panel);
        StackPane.setAlignment(panel, Pos.CENTER);
        WorldHud.stopInput(this);
        setVisible(false);
        showMain();
    }

    /** Adds an entry under the pages (the view's own "Back to map list" button). */
    void lastEntry(Button button) {
        entry(button, null);
        if (button.getParent() != main) main.getChildren().add(button);
    }

    /** The Leave instance entry; the view shows it only inside an instance. */
    Button leaveEntry() {
        return leave;
    }

    /** Empties every page and drops {@code last} from the entries, e.g. while an instance switch owns the controls. */
    void clearPages(Button last) {
        pages.values().forEach(box -> box.getChildren().clear());
        main.getChildren().remove(last);
    }

    /** A page's content box; the view fills it with its controls. */
    VBox page(Page which) {
        return pages.get(which);
    }

    boolean shown() {
        return isVisible();
    }

    /** The page on show, or null on the main column. */
    Page current() {
        return page;
    }

    /** Opens the menu on its main column. */
    void open() {
        showMain();
        reveal();
    }

    /** Opens the menu straight on {@code which}. */
    void open(Page which) {
        page = Objects.requireNonNull(which);
        title.setText(which.title.toUpperCase(Locale.ROOT));
        back.setVisible(true);
        main.setVisible(false);
        pages.forEach((each, box) -> box.setVisible(each == which));
        reveal();
    }

    void close() {
        if (!isVisible()) return;
        setVisible(false);
        showMain();
        closed.run();
    }

    /** "‹ Back" on a page: the main column again. */
    void showMain() {
        page = null;
        title.setText("WORLD MENU");
        back.setVisible(false);
        main.setVisible(true);
        pages.values().forEach(box -> box.setVisible(false));
    }

    private void reveal() {
        if (isVisible()) return;
        setVisible(true);
        opened.run();
    }

    /** A large menu entry, never focus-traversable, with an optional key hint on the right. */
    private static void entry(Button button, String key) {
        button.setFocusTraversable(false);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle("-fx-font-size: 1.1em; -fx-padding: 8 14;");
        if (key != null) {
            var hint = new Label(key);
            hint.setStyle("-fx-opacity: .6;");
            button.setGraphic(hint);
            button.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            button.setGraphicTextGap(240);
        }
    }

    /** A titled section of a page. */
    static VBox section(String heading, Node... rows) {
        var label = new Label(heading);
        label.setStyle("-fx-font-weight: bold;");
        var box = new VBox(6, label);
        box.getChildren().addAll(rows);
        return box;
    }
}
