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
        hideWorksBeforeTheFirstShow();
        resetAndRefusedShowForgetTheChild();
        frameSize();
        realUser32IsHarmlessWithoutAChild();
        System.out.println("World native presenter: opt-in gating, physical placement, change-only"
                + " placement, discovery retry, refusal fallback, hide before the first show, reset per stream, frame size and"
                + " user32 binding passed");
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
        boolean refusePlace, refuseShow;
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
            return !(visible && refuseShow);
        }
    }

    private static void bookkeeping() {
        var windows = new Fake();
        var presenter = new WorldNativePresenter(windows);
        var at = new WorldNativePresenter.Placement(10, 20, 300, 200);
        var shown = WorldNativePresenter.Result.SHOWN;
        require(presenter.show(at) == WorldNativePresenter.Result.NO_CHILD && windows.calls.isEmpty(),
                "No parent: nothing happens");
        presenter.parent(77);
        require(presenter.show(at) == WorldNativePresenter.Result.NO_CHILD, "No child yet: readback stays");
        windows.child = 99;
        require(presenter.show(at) == shown && presenter.shown(), "The child appears: placed and shown");
        require(windows.calls.subList(1, windows.calls.size()).equals(List.of(
                "find 77 " + WorldNativePresenter.CHILD_CLASS, "place 99 10,20 300x200", "show 99")),
                "Find, place, show: " + windows.calls);
        windows.calls.clear();
        require(presenter.show(at) == shown && windows.calls.isEmpty(), "An unchanged placement sends nothing");
        presenter.show(new WorldNativePresenter.Placement(10, 20, 301, 200));
        require(windows.calls.equals(List.of("place 99 10,20 301x200")), "Only changes are sent: " + windows.calls);
        windows.calls.clear();
        presenter.hide();
        presenter.hide();
        require(windows.calls.equals(List.of("hide 99")) && !presenter.shown(), "Hide once");
        windows.calls.clear();
        windows.refusePlace = true;
        require(presenter.show(new WorldNativePresenter.Placement(0, 0, 5, 5)) == WorldNativePresenter.Result.REFUSED,
                "A refused placement is reported");
        windows.refusePlace = false;
        windows.calls.clear();
        presenter.show(new WorldNativePresenter.Placement(0, 0, 5, 5));
        require(windows.calls.getFirst().startsWith("find"), "After a refusal the child is looked up again");
        presenter.parent(78);
        require(!presenter.shown(), "A new parent forgets the old child");
    }

    /** The child starts hidden, but a hide before any show still reaches it, once. */
    private static void hideWorksBeforeTheFirstShow() {
        var windows = new Fake();
        long[] now = {0};
        var presenter = new WorldNativePresenter(windows, () -> now[0]);
        presenter.parent(77);
        presenter.hide();
        presenter.hide();
        require(windows.calls.equals(List.of("find 77 " + WorldNativePresenter.CHILD_CLASS)),
                "No child yet: nothing to hide, and the lookup is throttled: " + windows.calls);
        windows.child = 99;
        windows.calls.clear();
        presenter.hide();
        require(windows.calls.isEmpty(), "Still throttled: " + windows.calls);
        now[0] += WorldNativePresenter.HIDE_LOOKUP_NANOS;
        presenter.hide();
        presenter.hide();
        require(windows.calls.equals(List.of("find 77 " + WorldNativePresenter.CHILD_CLASS, "hide 99")),
                "Hidden before the first show, once: " + windows.calls);
        windows.calls.clear();
        presenter.show(new WorldNativePresenter.Placement(1, 2, 3, 4));
        require(windows.calls.equals(List.of("place 99 1,2 3x4", "show 99")), "Placed before shown: " + windows.calls);
        windows.calls.clear();
        presenter.hide();
        require(windows.calls.equals(List.of("hide 99")), "Hidden again after a show: " + windows.calls);
        // A refused placement forgets the child, its placement and its visibility.
        windows.refusePlace = true;
        require(presenter.show(new WorldNativePresenter.Placement(5, 5, 5, 5))
                == WorldNativePresenter.Result.REFUSED, "Refused");
        windows.refusePlace = false;
        windows.calls.clear();
        presenter.show(new WorldNativePresenter.Placement(1, 2, 3, 4));
        require(windows.calls.equals(List.of("find 77 " + WorldNativePresenter.CHILD_CLASS,
                "place 99 1,2 3x4", "show 99")),
                "After a refusal the old placement is not assumed: placed and shown again: " + windows.calls);
    }

    /** A new stream's child is looked up, placed and shown afresh, never the old handle. */
    private static void resetAndRefusedShowForgetTheChild() {
        var windows = new Fake();
        var presenter = new WorldNativePresenter(windows);
        var at = new WorldNativePresenter.Placement(1, 2, 3, 4);
        presenter.parent(77);
        windows.child = 99;
        require(presenter.show(at) == WorldNativePresenter.Result.SHOWN, "First stream shown");
        presenter.hide();
        presenter.reset();
        windows.child = 100;
        windows.calls.clear();
        require(presenter.show(at) == WorldNativePresenter.Result.SHOWN, "Second stream shown");
        require(windows.calls.equals(List.of("find 77 " + WorldNativePresenter.CHILD_CLASS,
                "place 100 1,2 3x4", "show 100")),
                "Same parent and placement: the new child is found, placed and shown: " + windows.calls);
        presenter.hide();
        windows.refuseShow = true;
        windows.child = 101;
        windows.calls.clear();
        require(presenter.show(at) == WorldNativePresenter.Result.REFUSED, "A stale handle is refused");
        windows.refuseShow = false;
        windows.calls.clear();
        presenter.show(at);
        require(windows.calls.equals(List.of("find 77 " + WorldNativePresenter.CHILD_CLASS,
                "place 101 1,2 3x4", "show 101")),
                "A refused show forgets the handle: looked up and placed again: " + windows.calls);
    }

    private static void frameSize() {
        require(java.util.Arrays.equals(WorldNativePresenter.frameSize(false, 900, 600, 800, 533),
                new double[] {800, 533}), "Readback: the image's size");
        require(WorldNativePresenter.frameSize(false, 900, 600, 0, 0) == null, "Readback without an image: none");
        require(java.util.Arrays.equals(WorldNativePresenter.frameSize(true, 900, 600, 0, 0),
                new double[] {900, 600}), "Native: the viewport the child fills, no image needed");
        require(java.util.Arrays.equals(WorldNativePresenter.frameSize(true, 900, 600, 640, 400),
                new double[] {900, 600}), "Native: a stale readback image is ignored");
        require(WorldNativePresenter.frameSize(true, 0, 600, 0, 0) == null, "Native without area: none");
    }

    private static void realUser32IsHarmlessWithoutAChild() {
        var windows = WorldNativePresenter.user32();
        require(windows.findChild(0, "NoSuchClassForVimiTests") == 0, "No such child: 0");
        require(!windows.place(0, new WorldNativePresenter.Placement(0, 0, 1, 1)), "No window: refused");
        require(!windows.show(0, true) && !windows.show(0, false), "No window: ShowWindowAsync refused");
        var off = WorldNativePresenter.unavailable();
        require(off.findChild(1, "x") == 0 && !off.place(1, new WorldNativePresenter.Placement(0, 0, 1, 1))
                && !off.show(1, true), "Unavailable: every call fails");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
