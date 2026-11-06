package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;

/** #381 spike: native-present gating, placement and child-window bookkeeping; no display. */
public final class WorldNativePresenterTest {
    private WorldNativePresenterTest() {}

    public static void main(String[] args) {
        gating();
        placement();
        bookkeeping();
        realUser32IsHarmlessWithoutAChild();
        System.out.println("World native presenter: opt-in gating, physical placement, change-only"
                + " placement, discovery retry, refusal fallback and user32 binding passed");
    }

    private static void gating() {
        require(!WorldNativePresenter.requested(null, "Windows 11"), "Off by default");
        require(!WorldNativePresenter.requested("false", "Windows 11"), "Off when false");
        require(WorldNativePresenter.requested("true", "Windows 11"), "On when asked on Windows");
        require(!WorldNativePresenter.requested("true", "Mac OS X") && !WorldNativePresenter.requested("true", "Linux"),
                "Windows only");
    }

    private static void placement() {
        var p = WorldNativePresenter.Placement.of(220, 30.4, 880, 640.2, 1.25, 1.25);
        require(p.equals(new WorldNativePresenter.Placement(275, 38, 1100, 801)),
                "125%: scaled, rounded outward: " + p);
        var unit = WorldNativePresenter.Placement.of(0, 0, 800, 500, 1, 1);
        require(unit.equals(new WorldNativePresenter.Placement(0, 0, 800, 500)), "100%: exact");
        try {
            WorldNativePresenter.Placement.of(0, 0, 0, 10, 1, 1);
            throw new AssertionError("Empty bounds refused");
        } catch (IllegalArgumentException expected) {
            // Nothing to place.
        }
    }

    static final class Fake implements WorldNativePresenter.Windows {
        long child;
        boolean refusePlace;
        final List<String> calls = new ArrayList<>();

        @Override public long findChild(long parent, String className) {
            calls.add("find " + parent + " " + className);
            return child;
        }

        @Override public boolean place(long window, WorldNativePresenter.Placement p) {
            calls.add("place " + window + " " + p.x() + "," + p.y() + " " + p.width() + "x" + p.height());
            return !refusePlace;
        }

        @Override public boolean show(long window, boolean visible) {
            calls.add((visible ? "show " : "hide ") + window);
            return true;
        }
    }

    private static void bookkeeping() {
        var windows = new Fake();
        var presenter = new WorldNativePresenter(windows);
        var at = new WorldNativePresenter.Placement(10, 20, 300, 200);
        require(!presenter.show(at) && windows.calls.isEmpty(), "No parent: nothing happens");
        presenter.parent(77);
        require(!presenter.show(at), "No child yet: readback stays");
        windows.child = 99;
        require(presenter.show(at) && presenter.shown(), "The child appears: placed and shown");
        require(windows.calls.subList(1, windows.calls.size()).equals(List.of(
                "find 77 " + WorldNativePresenter.CHILD_CLASS, "place 99 10,20 300x200", "show 99")),
                "Find, place, show: " + windows.calls);
        windows.calls.clear();
        require(presenter.show(at) && windows.calls.isEmpty(), "An unchanged placement sends nothing");
        presenter.show(new WorldNativePresenter.Placement(10, 20, 301, 200));
        require(windows.calls.equals(List.of("place 99 10,20 301x200")), "Only changes are sent: " + windows.calls);
        windows.calls.clear();
        presenter.hide();
        presenter.hide();
        require(windows.calls.equals(List.of("hide 99")) && !presenter.shown(), "Hide once");
        windows.calls.clear();
        windows.refusePlace = true;
        require(!presenter.show(new WorldNativePresenter.Placement(0, 0, 5, 5)), "A refused placement falls back");
        windows.refusePlace = false;
        windows.calls.clear();
        presenter.show(new WorldNativePresenter.Placement(0, 0, 5, 5));
        require(windows.calls.getFirst().startsWith("find"), "After a refusal the child is looked up again");
        presenter.parent(78);
        require(!presenter.shown(), "A new parent forgets the old child");
    }

    private static void realUser32IsHarmlessWithoutAChild() {
        var windows = WorldNativePresenter.user32();
        require(windows.findChild(0, "NoSuchClassForVimiTests") == 0, "No such child: 0");
        require(!windows.place(0, new WorldNativePresenter.Placement(0, 0, 1, 1)), "No window: refused");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
