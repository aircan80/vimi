package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainCamera;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selection highlight of one picked object: its drawn local bounds through its recorded transform,
 * projected with the explorer's own camera over the presented frame as a wireframe box. Drawn on
 * the FX overlay only (no GPU pass); it redraws only while a selection exists and the view changes.
 * When the selected tile's objects are no longer resident the selection retires. FX thread.
 */
final class WorldSelectionOverlay extends Pane implements AutoCloseable {
    /** Bits of the 12 box edges: pairs of corner indices that differ in one axis bit. */
    private static final int[][] EDGES = edges();

    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private Supplier<WorldSpawnOverlay.View> views = () -> null;
    private Supplier<List<WorldObjectPicks.Tile>> live = List::of;
    private Runnable retired = () -> {};
    private WorldObjectPicks.Selected selected;
    private double[] corners;
    private WorldSpawnOverlay.View drawn;
    private double drawnWidth, drawnHeight;
    private boolean closed;

    WorldSelectionOverlay() {
        setId("world-selection-overlay");
        setMouseTransparent(true);
        setPickOnBounds(false);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                refresh();
            }
        };
    }

    /** The presented view, the resident pick tiles, and what to do when a selection retires. */
    void bind(Supplier<WorldSpawnOverlay.View> views, Supplier<List<WorldObjectPicks.Tile>> live, Runnable retired) {
        this.views = Objects.requireNonNull(views);
        this.live = Objects.requireNonNull(live);
        this.retired = Objects.requireNonNull(retired);
    }

    /** Highlights a selection, or clears it with null. */
    void select(WorldObjectPicks.Selected value) {
        if (closed) return;
        selected = value;
        corners = value == null ? null : value.tile().geometry().corners(value.hit().placement()).orElse(null);
        drawn = null;
        clear();
        if (value == null) timer.stop();
        else {
            timer.start();
            refresh();
        }
    }

    WorldObjectPicks.Selected selected() {
        return selected;
    }

    void refresh() {
        if (closed || selected == null) return;
        if (live.get().stream().noneMatch(tile -> tile == selected.tile())) {
            select(null);
            retired.run();
            return;
        }
        var view = views.get();
        if (view == null) {
            if (drawn != null) clear();
            drawn = null;
            return;
        }
        if (view.equals(drawn) && getWidth() == drawnWidth && getHeight() == drawnHeight) return;
        drawn = view;
        drawnWidth = getWidth();
        drawnHeight = getHeight();
        clear();
        if (corners == null) return;
        var g = canvas.getGraphicsContext2D();
        g.setStroke(Color.rgb(255, 214, 64, .95));
        g.setLineWidth(2);
        for (var segment : segments(corners, view, getWidth(), getHeight())) {
            g.strokeLine(segment[0], segment[1], segment[2], segment[3]);
        }
    }

    /**
     * Viewport-pixel segments of the box edges, clipped at the near plane, with the same frame fit
     * and focal length as the spawn markers and terrain picking.
     */
    static List<double[]> segments(double[] corners, WorldSpawnOverlay.View view, double viewWidth,
            double viewHeight) {
        double frameWidth = view.frameWidth(), frameHeight = view.frameHeight();
        var result = new ArrayList<double[]>();
        if (!(frameWidth > 0) || !(frameHeight > 0) || !(viewWidth > 0) || !(viewHeight > 0)) return result;
        var camera = TerrainCamera.from(view.camera());
        double scale = Math.min(viewWidth / frameWidth, viewHeight / frameHeight);
        double left = (viewWidth - frameWidth * scale) / 2, top = (viewHeight - frameHeight * scale) / 2;
        double focal = TerrainCamera.focalPixels(frameWidth);
        var eyes = new TerrainCamera.Eye[8];
        for (int i = 0; i < 8; i++) eyes[i] = camera.eye(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2]);
        for (var edge : EDGES) {
            var a = eyes[edge[0]];
            var b = eyes[edge[1]];
            double near = TerrainCamera.NEAR;
            if (a.depth() <= near && b.depth() <= near) continue;
            if (a.depth() <= near) a = clip(b, a, near);
            else if (b.depth() <= near) b = clip(a, b, near);
            result.add(new double[] {
                left + (frameWidth * .5 + a.right() * focal / a.depth()) * scale,
                top + (frameHeight * .5 - a.up() * focal / a.depth()) * scale,
                left + (frameWidth * .5 + b.right() * focal / b.depth()) * scale,
                top + (frameHeight * .5 - b.up() * focal / b.depth()) * scale});
        }
        return result;
    }

    private static TerrainCamera.Eye clip(TerrainCamera.Eye in, TerrainCamera.Eye out, double near) {
        double t = (in.depth() - near) / (in.depth() - out.depth());
        return new TerrainCamera.Eye(in.right() + (out.right() - in.right()) * t,
                in.up() + (out.up() - in.up()) * t, near);
    }

    private static int[][] edges() {
        var edges = new ArrayList<int[]>();
        for (int i = 0; i < 8; i++)
            for (int bit = 1; bit < 8; bit <<= 1)
                if ((i & bit) == 0) edges.add(new int[] {i, i | bit});
        return edges.toArray(int[][]::new);
    }

    private void clear() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    @Override
    public void close() {
        closed = true;
        timer.stop();
        selected = null;
        corners = null;
    }
}
