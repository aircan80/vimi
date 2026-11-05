package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Headless ordering checks for the inline instance switch: the open world drains before the
 * interior opens, the instance audio never overlaps the open world's, and the open world never
 * resumes before the interior has drained. No display or GPU.
 */
public final class WorldInstanceSwitchTest {
    private WorldInstanceSwitchTest() {}

    public static void main(String[] args) {
        switchInWaitsForTheOpenWorldToDrain();
        switchOutClearsAudioThenWaitsForTheInteriorToDrain();
        leavingDuringEntryCancelsItAndALateRetirementOpensNothing();
        abandonInsideDropsTheInteriorWithoutResuming();
        failuresRefuseWithAReasonAndResume();
        aSecondEntryIsRefusedWhileOneIsUnderWay();
        System.out.println(
                "World instance switch: switch-in, switch-out, cancel, abandon, refusal and audio"
                        + " handover passed");
    }

    /** Records every hook call in order; futures are completed by the test. */
    private static final class Recorder implements WorldInstanceSwitch.Hooks<String> {
        final List<String> events = new ArrayList<>();
        CompletableFuture<Void> retirement = new CompletableFuture<>();
        CompletableFuture<Void> interiorDrain = new CompletableFuture<>();
        RuntimeException openFailure;
        int opened;

        @Override
        public CompletableFuture<Void> retireWorld() {
            events.add("retire");
            return retirement;
        }

        @Override
        public WorldInstanceSwitch.Interior openInterior(String target) {
            if (openFailure != null) throw openFailure;
            opened++;
            events.add("open " + target);
            return new WorldInstanceSwitch.Interior() {
                @Override
                public javafx.scene.Node node() {
                    return null;
                }

                @Override
                public CompletableFuture<Void> closeAsync() {
                    events.add("close " + target);
                    return interiorDrain;
                }
            };
        }

        @Override
        public void showInterior(String target, WorldInstanceSwitch.Interior interior) {
            events.add("show " + target);
        }

        @Override
        public void audio(String target) {
            events.add("audio " + target);
        }

        @Override
        public void resumeWorld(boolean worldOpen) {
            events.add("resume " + worldOpen);
        }

        @Override
        public void status(String message) {
            events.add("status " + message);
        }

        List<String> actions() {
            return events.stream().filter(e -> !e.startsWith("status")).toList();
        }
    }

    private static void switchInWaitsForTheOpenWorldToDrain() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        check(instances.enter("Lair", true), "Entry starts");
        check(instances.phase() == WorldInstanceSwitch.Phase.ENTERING, "Entering");
        check(hooks.actions().equals(List.of("retire")),
                "Nothing opens and no audio changes before the open world drains: " + hooks.events);
        hooks.retirement.complete(null);
        check(instances.phase() == WorldInstanceSwitch.Phase.INSIDE, "Inside");
        check(hooks.actions().equals(List.of("retire", "open Lair", "show Lair", "audio Lair")),
                "Interior opens after the drain; its audio starts after it is shown: "
                        + hooks.events);
        check("Lair".equals(instances.target()), "Target kept");
    }

    private static void switchOutClearsAudioThenWaitsForTheInteriorToDrain() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        instances.enter("Lair", true);
        hooks.retirement.complete(null);
        hooks.events.clear();
        instances.leave();
        check(instances.phase() == WorldInstanceSwitch.Phase.LEAVING, "Leaving");
        check(hooks.actions().equals(List.of("audio null", "close Lair")),
                "Instance audio stops before the interior closes, and nothing resumes yet: "
                        + hooks.events);
        check(!instances.drained().isDone(), "The interior has not drained yet");
        instances.leave(); // a second click while leaving does nothing
        check(hooks.actions().size() == 2, "A repeated leave is ignored");
        hooks.interiorDrain.complete(null);
        check(instances.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Back in the world");
        check(hooks.actions().equals(List.of("audio null", "close Lair", "resume true")),
                "The open world resumes only after the drain: " + hooks.events);
        check(instances.drained().isDone(), "Drained");
        // Re-entering works after a full round trip.
        hooks.retirement = new CompletableFuture<>();
        check(instances.enter("Lair", false), "Re-entry starts");
    }

    private static void leavingDuringEntryCancelsItAndALateRetirementOpensNothing() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        instances.enter("Lair", true);
        instances.leave();
        check(instances.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Entry canceled");
        check(hooks.actions().equals(List.of("retire", "resume true")),
                "Cancel resumes the world: " + hooks.events);
        hooks.retirement.complete(null);
        check(hooks.opened == 0, "A late retirement opens no interior");
        check(hooks.actions().equals(List.of("retire", "resume true")),
                "And plays no instance audio: " + hooks.events);

        // An abandon during entry (map change) neither opens nor resumes.
        var other = new Recorder();
        var second = new WorldInstanceSwitch<>(other, Runnable::run);
        second.enter("Lair", true);
        check(second.abandon().isDone(), "Nothing to drain while entering");
        other.retirement.complete(null);
        check(other.opened == 0 && other.actions().equals(List.of("retire")),
                "Abandoned entry stays closed: " + other.events);
    }

    private static void abandonInsideDropsTheInteriorWithoutResuming() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        instances.enter("Lair", true);
        hooks.retirement.complete(null);
        hooks.events.clear();
        var drained = instances.abandon();
        check(instances.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Abandoned");
        check(hooks.actions().equals(List.of("audio null", "close Lair")),
                "Abandon stops audio and closes the interior: " + hooks.events);
        check(!drained.isDone(), "The caller can wait for the drain");
        hooks.interiorDrain.complete(null);
        check(drained.isDone(), "Drain observed");
        check(hooks.actions().equals(List.of("audio null", "close Lair")),
                "No resume after an abandon: " + hooks.events);
        check(instances.abandon().isDone(), "A second abandon is a no-op");
    }

    private static void failuresRefuseWithAReasonAndResume() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        instances.enter("Lair", true);
        hooks.retirement.completeExceptionally(new IllegalStateException("stuck"));
        check(instances.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Refused");
        check(hooks.opened == 0, "No interior after an unconfirmed retirement");
        check(hooks.events.stream().anyMatch(e -> e.startsWith("status ") && e.contains("not entered")),
                "The reason is stated: " + hooks.events);
        check(hooks.events.getLast().equals("resume true"), "The world resumes");

        var failing = new Recorder();
        failing.openFailure = new IllegalArgumentException("no GPU");
        var second = new WorldInstanceSwitch<>(failing, Runnable::run);
        second.enter("Lair", false);
        failing.retirement.complete(null);
        check(second.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Open failure refused");
        check(failing.events.stream().anyMatch(e -> e.contains("could not be opened")),
                "Open failure stated: " + failing.events);
        check(failing.events.getLast().equals("resume false"), "Map page restored");
        check(failing.actions().stream().noneMatch(e -> e.startsWith("audio")),
                "No audio change for a refused instance: " + failing.events);

        var leaving = new Recorder();
        var third = new WorldInstanceSwitch<>(leaving, Runnable::run);
        third.enter("Lair", true);
        leaving.retirement.complete(null);
        third.leave();
        leaving.interiorDrain.completeExceptionally(new IllegalStateException("child stuck"));
        check(third.phase() == WorldInstanceSwitch.Phase.OPEN_WORLD, "Left despite a stuck drain");
        check(leaving.events.stream().anyMatch(e -> e.contains("could not be confirmed stopped")),
                "Stuck drain stated: " + leaving.events);
    }

    private static void aSecondEntryIsRefusedWhileOneIsUnderWay() {
        var hooks = new Recorder();
        var instances = new WorldInstanceSwitch<>(hooks, Runnable::run);
        instances.enter("Lair", true);
        check(!instances.enter("Chasm", true), "Second entry refused");
        hooks.retirement.complete(null);
        check(!instances.enter("Chasm", true), "Refused while inside");
        check(hooks.opened == 1 && "Lair".equals(instances.target()), "Only the first opened");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
