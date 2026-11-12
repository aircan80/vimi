package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.AuthoredTerrainPoolPressure;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Genuine native source/packet/transport with authored child pixels; no GPU/FPS assertion. */
public final class WorldStreamSourceControllerTest {
    private WorldStreamSourceControllerTest() {}

    public static void main(String[] args) throws Exception {
        aHelperHungMidCommitFailsTheSessionWithADiagnostic();
        retryCommitKeepsExactTargetUntilPriorFileActuallyRetires();
        retirementBetweenRefusalAndRetryStillWakesTheCommit();
        failedFxWakeStillCancelsHeldInitialSourceQualification();
        returningNearestCanProgressWhenAllNeverPresentedNeighborsRemainWanted();
        newNearestCanProgressWhenAllNeverPresentedNeighborsRemainWanted();
        skyOnlyFeedbackPreservesPixelsWithoutSuppressingForegroundOrUserSky();
        skyOnlyFullUnionAdmitsMissingLookaheadBeforeReacquiringVictim();
        permanentSkyDisjointDemandRetiresPinsWithoutFakePresentation();
        explicitUnsupportedSourceHasCleanNoOwnerDrain();
        stallSummaryWithoutSessionIsBoundedAndNeverWaits();
        fullPresentationUnionRecoversBackDemandWithoutAcceptingUnwantedPixels(false);
        fullPresentationUnionRecoversBackDemandWithoutAcceptingUnwantedPixels(true);
        backDemandDoesNotTreatPresentedOnlySourceAsLiveGpuResident();
        pickedAreaUsesRetainedResidentMetadataWithoutBorrowing();
        areaLookupCoversEveryResident();
        uncoveredCameraFrameKeepsOldPixelsWhileNearestTileLoads();
        cameraPublicationSerializesInitialAndFxForwarding();
        initialSeedHeldReplacementCameraAndPresentationDrain();
        supersededHeldSourceCancelsBeforeStalePredicateAndKeepsSession();
        missingRootIsFinitePerDemandAndDoesNotInvalidateCurrentScope();
        missingNearestIsFiniteUnavailableWithoutFillingOutputUnion();
        helperRejectedTileRetriesWithBackoffThenBecomesUnavailable();
        helperRejectionCrossingACancelIsTerminalNotAFailure();
        helperRejectionOfATileOutOfDemandIsNotCounted();
        rejectedNearestTileDoesNotStallItsNeighbours();
        heldPresentationBackpressuresTwelveUniqueOwnersAndKeepsFinalFrame();
        nativePresentationAcceptsCurrentContentAndRelievesTwelveOwners();
        nativePresentationWithAStillCameraAcceptsEachCommit();
        everySessionSendsTheBodyOnceAfterItsFirstCommit();
        nativeReportBeforeTheReceiptIsStillAccepted();
        poolReleaseRetriesWithoutDemandOrParentCapacityChange();
        capacityChangeBetweenTileRefusalAndRetryStillWakesPreparation();
        bumpBetweenIssueAndRefusalStillWakesRetry("geometry", false);
        bumpBetweenIssueAndRefusalStillWakesRetry("output", false);
        bumpBetweenIssueAndRefusalStillWakesRetry("output", true);
        bumpBetweenIssueAndRefusalStillWakesRetry("pool", false);
        bumpBetweenIssueAndRefusalStillWakesRetry("geometry", false, true);
        bumpBetweenIssueAndRefusalStillWakesRetry("output", true, true);
        busyGeometryLaneIsNotPolledAndItsReleaseWakesTheTile();
        heldSourceWorkerIsNotRetriedByItsOwnCredit();
        capacityChangeFromElsewhereWakesASourceWorkerRefusal();
        sameResidentCameraFrameDoesNotRetryMaterialPool();
        transportFrameSignalQueuesPresentationWithoutAnyPulse();
        steadyCameraFramesReuseAFewPixelArrays();
        everyControllerDropPathReleasesItsFrame(false);
        commitReleasesAQueuedFrameOfTheOldReceipt();
        refusedPendingSkyKeepsTheOldRasterBelowTwelveOwners();
        acceptedSkyRelabelsTheRetainedViewAndTerrainClearsTheLabel();
        everyControllerDropPathReleasesItsFrame(true);
        queuedGpuOwnerLateCloseIsPartOfControllerDrain();
        closeDuringActualInitialWdtQualificationIsCleanAndWaitsSourceDrain();
        terrainNeverWaitsForBlockedObjectPreparation();
        lateObjectsAttachAfterTerrainAndReportBuildings();
        budgetRefusedObjectsRetryAfterAReleaseOnly();
        creaturesShareTheirBytesAndYieldToBuildings();
        buildingsComeFirstAndCreaturesReplaceThemLater();
        refusedCreatureReplacementKeepsTheBuildings();
        lateObjectFailuresStayIsolatedFromTerrain();
        lateObjectsCompletingAfterRetirementAreIgnored();
        refusedInitialQualificationRetriesOnItsDeadline();
        refusedCommitRetriesWhenItsRetirementKickWasAlreadyConsumed();
        materialPoolRefusalRetriesOnItsDeadline();
        anIdleViewRunsNoStepOnTicksAndScansAreasOnlyWhenTheCameraMoves();
        aHelperRejectedTileRetriesOnItsBackoffDeadline();
        aSkyFrameThatChangesTheStepsInputsStartsAStep();
        borrowedTilesAreNeverDroppedToMakeRoom();
        aTileOneRingBeyondKeepsItsPreparation();
        aKeptTileUnavailableAfterDemandLeftIsNotPublished();
        aKeptTileNeverDisplacesAWantedResident();
        aKeptTileYieldsTheLaneWhenTheCameraTileIsMissing();
        tilesKeepStreamingWhileAFrameIsBorrowed();
        commitTimingStopsOnlyOnAFrameOfTheNewReceipt();
        aBorrowedFrameKeepsTheSourcesOfTheTableItShows();
        fiveByFiveNeighborhoodHoldsTwentyFiveResidentsWithinTheSourceUnion();
    }

    /**
     * #366: a frame the FX side still borrows never holds up streaming. Commits complete and the
     * next tiles are reserved and committed while it is borrowed; the borrowed frame, drawn from
     * an older table, is still accepted against that table (its sources were kept), and a later
     * frame of the newest table brings the view to READY.
     */
    private static void tilesKeepStreamingWhileAFrameIsBorrowed() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 4);
        fixture.residents = 4;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            int before = fixture.commitLines().size();
            fixture.camera(2); // a new camera, so the child draws a new frame to borrow
            var borrowed = fixture.nextFrame();
            fixture.controller.neighborhood(fixture.tiles.subList(0, 4));
            // Never present here: the frame stays borrowed throughout.
            fixture.until(() -> fixture.commitLines().size() >= before + 3);
            check(fixture.controller.mayPresent(borrowed) && fixture.controller.presented(borrowed),
                    "The borrowed frame is still accepted against the table it shows, its sources kept");
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 4);
        } finally {
            fixture.close();
        }
    }

    /**
     * Opt-in timings: parent.commit.toAcceptedFrame ends at the first accepted frame drawn from
     * the committed receipt. A borrowed frame of an older table, accepted after the commit, is
     * not that frame and must not end the measurement early.
     */
    private static void commitTimingStopsOnlyOnAFrameOfTheNewReceipt() throws Exception {
        boolean wasEnabled = WorldStageTimings.enabled();
        WorldStageTimings.enable(true);
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 4);
        fixture.residents = 4;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            int before = fixture.commitLines().size();
            fixture.camera(2);
            var borrowed = fixture.nextFrame(); // drawn from the table accepted before
            WorldStageTimings.reset();
            Object committedBefore = fixture.currentReceipt();
            fixture.controller.neighborhood(fixture.tiles.subList(0, 4));
            fixture.until(() -> fixture.commitLines().size() >= before + 1);
            // The helper logs the commit before it sends COMMITTED, so the log line alone does not
            // prove the controller applied the receipt. Wait for the receipt itself, read under the
            // controller's lock, so the old-table frame below is judged after the commit landed.
            fixture.until(() -> fixture.currentReceipt() != committedBefore);
            check(fixture.controller.mayPresent(borrowed) && fixture.controller.presented(borrowed),
                    "The borrowed old-table frame is accepted");
            check(WorldStageTimings.snapshot().get("parent.commit.toAcceptedFrame") == null,
                    "An old-receipt frame does not end the commit-to-frame timing");
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 4);
            var series = WorldStageTimings.snapshot().get("parent.commit.toAcceptedFrame");
            check(series != null && series.count() >= 1,
                    "A frame of the committed receipt ends the commit-to-frame timing");
        } finally {
            fixture.close();
            WorldStageTimings.reset();
            WorldStageTimings.enable(wasEnabled);
        }
    }

    /**
     * #366: a borrowed frame's own table keeps its sources. Tile 2 is committed, a frame showing
     * it is borrowed, and a further commit replaces tile 2 before that frame is accepted: tile 2
     * is then in neither the current nor the last accepted table, yet the borrowed frame must
     * still pass its handshake, so its source may not be retired underneath it.
     */
    private static void aBorrowedFrameKeepsTheSourcesOfTheTableItShows() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 4);
        fixture.residents = 2;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            var t = fixture.tiles;
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
            int accepted = fixture.commitLines().size();
            fixture.controller.neighborhood(List.of(t.get(0), t.get(2)));
            fixture.until(() -> fixture.commitLines().size() > accepted);
            fixture.camera(2);
            var borrowed = fixture.nextFrame(); // drawn from the table {0, 2}
            fixture.controller.neighborhood(List.of(t.get(0), t.get(3)));
            fixture.until(() -> fixture.commitLines().size() > accepted + 1);
            for (int i = 0; i < 20; i++) {
                fixture.pump(); // let any retirement run while the frame is still borrowed
                Thread.sleep(2);
            }
            check(fixture.controller.mayPresent(borrowed) && fixture.controller.presented(borrowed),
                    "The borrowed frame's tile 2 source was kept until its handshake");
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
        } finally {
            fixture.close();
        }
    }

    /**
     * #366: steps are event-driven. Once READY with a still camera, FX ticks run no step and no
     * ground or area scan; a camera change runs steps and exactly one new scan per camera.
     */
    private static void anIdleViewRunsNoStepOnTicksAndScansAreasOnlyWhenTheCameraMoves()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            // Let trailing completions settle, then count across many FX ticks.
            for (int i = 0; i < 50; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            long steps = fixture.controller.steps.get();
            long scans = fixture.controller.areaScans.get();
            for (int i = 0; i < 200; i++) {
                fixture.controller.pulse();
                Thread.sleep(1);
            }
            check(fixture.controller.steps.get() == steps,
                    "Idle ticks run no step: " + (fixture.controller.steps.get() - steps));
            check(fixture.controller.areaScans.get() == scans, "Idle ticks scan no area");
            fixture.camera(2);
            fixture.until(() -> fixture.controller.areaScans.get() > scans);
            for (int i = 0; i < 50; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(fixture.controller.steps.get() > steps, "A camera change runs a step");
            // Settled again: further ticks rescan nothing (the new camera may also have changed
            // the table, so the count it caused is not pinned exactly).
            long settledSteps = fixture.controller.steps.get();
            long settledScans = fixture.controller.areaScans.get();
            check(settledScans > scans && settledScans <= scans + 2,
                    "The new camera rescans the area: " + (settledScans - scans));
            for (int i = 0; i < 200; i++) {
                fixture.controller.pulse();
                Thread.sleep(1);
            }
            check(fixture.controller.steps.get() == settledSteps
                            && fixture.controller.areaScans.get() == settledScans,
                    "Ticks after the camera settles run no step and no scan");
        } finally {
            fixture.close();
        }
    }

    /**
     * #366 review: tiles a borrowed frame shows are never the make-room release. At the 5x5
     * source bound (25 residents, 28 sources) with three never-presented tiles that a borrowed
     * frame shows, a demand whose nearest tile is missing must not drop any of them while the
     * frame is borrowed: retirement keeps them, so each drop would free nothing and repeat.
     */
    private static void borrowedTilesAreNeverDroppedToMakeRoom() throws Exception {
        int residents = WorldStreamTransport.MAX_RESIDENTS;
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), residents + 4);
        fixture.residents = residents;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            var t = fixture.tiles;
            fixture.controller.neighborhood(t.subList(0, residents));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == residents);
            // Three new tiles commit while no newer pixels are accepted: 25 presented + 3 new.
            fixture.controller.neighborhood(t.subList(3, residents + 3));
            fixture.until(() -> fixture.pool.stats().leaseClaims() == residents + 3
                    && fixture.controller.snapshot().prepared() == residents);
            fixture.camera(2);
            var borrowed = fixture.nextFrame(); // shows the table with the three new tiles
            int commits = fixture.commitLines().size();
            // A new nearest tile is missing: make-room may only drop a tile nobody shows.
            var demand = new ArrayList<WorldFormats.Tile>();
            demand.add(t.get(residents + 3));
            demand.addAll(t.subList(4, residents + 3));
            fixture.controller.neighborhood(demand);
            for (int i = 0; i < 100; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(fixture.commitLines().size() == commits,
                    "No resident is dropped while the frame showing it is borrowed: "
                            + (fixture.commitLines().size() - commits) + " shrink commits");
            fixture.controller.discard(borrowed);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == residents);
        } catch (Throwable failure) {
            // Keep the assertion visible: a close that times out after it is only suppressed.
            try {
                fixture.close();
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
        fixture.close();
    }

    /** Consumes the controller's pending kick, as if a step had already taken it. */
    private static void consumeKick(WorldStreamSourceController controller) {
        try {
            var wake = WorldStreamSourceController.class.getDeclaredField("wake");
            wake.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) wake.get(controller)).set(false);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * #366 review: an initial qualification refused RETRY_LATER (the source lane busy at open)
     * arms its own retry. The lane's release sends no event and nothing moves the camera, so
     * only that deadline and an FX tick can retry it.
     */
    private static void refusedInitialQualificationRetriesOnItsDeadline() throws Exception {
        // No water tables: their completion is another kick that would mask a missing deadline.
        String water = System.getProperty("vimi.world.water");
        System.setProperty("vimi.world.water", "false");
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var held = com.vibeminer.concepts.world.AuthoredSourceWorkerPressure.hold(
                fixture.world.terrainStreaming().orElseThrow());
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().detail()
                    .startsWith("Waiting for source capacity"));
            held.close();
            fixture.clock.addAndGet(600_000_000L);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
        } finally {
            held.close();
            fixture.close();
            if (water == null) System.clearProperty("vimi.world.water");
            else System.setProperty("vimi.world.water", water);
        }
    }

    /**
     * #366 review: a refused commit whose awaited retirement arrived in a kick the same step
     * already consumed still retries: the step re-runs its guard. Clock frozen, no camera or
     * demand kick afterwards.
     */
    private static void refusedCommitRetriesWhenItsRetirementKickWasAlreadyConsumed()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(),13);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Path hold = fixture.directory.resolve("authored-hold-commit");
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            Files.writeString(hold,"12");
            fixture.controller.neighborhood(fixture.tiles.subList(4,13));
            fixture.until(() -> Files.exists(fixture.directory.resolve("authored-held-commit")));
            enqueueHeldFile(fixture.gpu.session, entered, release);
            check(entered.await(3,TimeUnit.SECONDS),"Parent file lane held");
            var session = fixture.gpu.session;
            var seamRan = new java.util.concurrent.atomic.AtomicBoolean();
            // Commits recorded before the retry: read on the controller worker, inside the seam,
            // so the retried commit cannot land first however late the test thread wakes up. A
            // refused commit is never sent, so it is not in the log.
            var committedBeforeRetry = new java.util.concurrent.atomic.AtomicInteger(-1);
            fixture.controller.refusalSeam = () -> {
                if (!seamRan.compareAndSet(false,true)) return;
                committedBeforeRetry.set(fixture.commitLines().size());
                long before = session.capacityRevision();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (session.capacityRevision() == before && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                check(session.capacityRevision() != before, "Retirement changed capacity");
                consumeKick(fixture.controller); // as if coalesced into this very step
            };
            Files.delete(hold);
            fixture.until(() -> fixture.commitLines().stream().anyMatch(line -> line.startsWith("12 ")));
            var returning = new ArrayList<>(fixture.tiles.subList(4,12));
            returning.addFirst(fixture.tiles.get(1));
            fixture.controller.neighborhood(returning);
            fixture.until(() -> committedBeforeRetry.get() >= 0);
            int before = committedBeforeRetry.get();
            // No presenting here: each accepted frame is a kick that would mask a missing re-run.
            fixture.until(() -> fixture.commitLines().size() > before);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    /**
     * #366 review: a tile refused at MATERIAL_POOL, whose own unused credit is held so no later
     * capacity event comes, arms its own retry: the pool's release sends no event, and only that
     * deadline and an FX tick can retry it.
     */
    private static void materialPoolRefusalRetriesOnItsDeadline() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var held = AuthoredTerrainPoolPressure.hold(fixture.pool);
        Path holdCredit = fixture.directory.resolve("authored-hold-unused-credit");
        try {
            Files.writeString(holdCredit, "hold");
            fixture.start();
            var pool = WorldStreamSourceController.class.getDeclaredField("blockedOnMaterialPool");
            pool.setAccessible(true);
            fixture.until(() -> {
                try {
                    return fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.WAITING_CAPACITY
                            && pool.getBoolean(fixture.controller);
                } catch (IllegalAccessException failure) {
                    throw new AssertionError(failure);
                }
            });
            held.close();
            fixture.clock.addAndGet(600_000_000L);
            // The retry gate opened: the step asks for a new reservation, which the still-held
            // unused credit refuses. Without its deadline it keeps waiting on the pool.
            fixture.until(() -> fixture.controller.snapshot().detail()
                    .equals("Waiting for GPU upload capacity"));
            Files.delete(holdCredit);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
        } finally {
            Files.deleteIfExists(holdCredit);
            held.close();
            fixture.close();
        }
    }

    /**
     * #366 review: a sky-only frame that changes what the step decides (sky newly pending, or the
     * old pins released) starts a full step, even when it arrives on the frame-only path that
     * runs none itself. Nothing is presented here, so no acceptance kick can stand in for it.
     */
    private static void aSkyFrameThatChangesTheStepsInputsStartsAStep() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 18);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9);
            // A disjoint demand loads up to the source bound, then waits for accepted pixels.
            fixture.controller.neighborhood(fixture.tiles.subList(9, 18));
            fixture.until(() -> fixture.pool.stats().leaseClaims() == 12);
            long[] last = {-1};
            long[] quietSince = {System.nanoTime()};
            fixture.until(() -> {
                long now = fixture.controller.steps.get();
                if (now != last[0]) {
                    last[0] = now;
                    quietSince[0] = System.nanoTime();
                }
                return System.nanoTime() - quietSince[0] > TimeUnit.MILLISECONDS.toNanos(300);
            });
            check(fixture.controller.skyWakeAtStep.get() < 0, "No sky frame yet");
            // Turn the sky on, then free the presentation slot without any kick, so the next
            // frame hint reaches the frame-only path with a sky frame.
            Files.write(fixture.directory.resolve("authored-sky"), new byte[0]);
            Thread.sleep(100);
            var lock = WorldStreamSourceController.class.getDeclaredField("lock");
            var queued = WorldStreamSourceController.class.getDeclaredField("queued");
            var frameField = WorldStreamSourceController.Presentation.class.getDeclaredField("frame");
            lock.setAccessible(true);
            queued.setAccessible(true);
            frameField.setAccessible(true);
            synchronized (lock.get(fixture.controller)) {
                var held = (WorldStreamSourceController.Presentation) queued.get(fixture.controller);
                if (held != null) {
                    ((WorldStreamTransport.Frame) frameField.get(held)).release();
                    queued.set(fixture.controller, null);
                }
            }
            // A new camera sent straight to the session (the controller is not told, so it gets
            // no kick): the child draws a fresh sky frame and its FRAME hint is the only event.
            var cameraField = WorldStreamSourceController.class.getDeclaredField("camera");
            cameraField.setAccessible(true);
            var known = (WorldStreamTransport.Camera) cameraField.get(fixture.controller);
            fixture.gpu.session.camera(new WorldStreamTransport.Camera(known.revision() + 1,
                    known.policy(), known.width(), known.height(), known.view()));
            fixture.until(() -> fixture.controller.skyWakeAtStep.get() >= 0);
            long woke = fixture.controller.skyWakeAtStep.get();
            fixture.until(() -> fixture.controller.steps.get() > woke);
        } finally {
            fixture.close();
        }
    }

    /**
     * #366 with #424: a tile the helper rejected is retried after its back-off from the FX tick
     * alone. The back-off arms the controller's own deadline at exactly the retry time, so one
     * tick past it retries the tile with no other event. The back-off also arms a wall-clock
     * fallback kick; the test waits until that has come and gone, on a controller clock still
     * before the retry time, so it cannot be what retries the tile. The retry itself (producing
     * the tile, offering it, the helper rejecting it again) is then awaited with no further tick,
     * under a bound only a missed retry reaches, not a loaded runner.
     */
    private static void aHelperRejectedTileRetriesOnItsBackoffDeadline() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        Path reject = fixture.directory.resolve("authored-reject-tile");
        Path log = fixture.directory.resolve("authored-rejections.log");
        try {
            Files.writeString(reject, Integer.toString(fixture.tiles.get(1).y()));
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            int key = fixture.tiles.get(1).x() * 64 + fixture.tiles.get(1).y();
            fixture.until(() -> fixture.controller.helperRejections(key) == 1);
            long rejected = System.nanoTime();
            var armed = WorldStreamSourceController.class.getDeclaredField("pulseArmed");
            var at = WorldStreamSourceController.class.getDeclaredField("pulseAt");
            var retries = WorldStreamSourceController.class.getDeclaredField("helperRetryAt");
            armed.setAccessible(true);
            at.setAccessible(true);
            retries.setAccessible(true);
            long retryAt = ((java.util.Map<?, ?>) retries.get(fixture.controller)).entrySet().stream()
                    .filter(entry -> entry.getKey().equals(key))
                    .map(entry -> (Long) entry.getValue())
                    .findFirst().orElseThrow();
            fixture.until(() -> {
                try {
                    return armed.getBoolean(fixture.controller)
                            && at.getLong(fixture.controller) == retryAt;
                } catch (IllegalAccessException failure) {
                    throw new AssertionError(failure);
                }
            });
            // The first back-off's wall-clock fallback fires RETRY_NANOS after the rejection; let
            // it pass while the controller clock is still before the retry time.
            var retry = WorldStreamSourceController.class.getDeclaredField("RETRY_NANOS");
            retry.setAccessible(true);
            long fallback = rejected + retry.getLong(null) + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < fallback) Thread.sleep(5);
            check(lines(log) == 1, "Not retried before its back-off");
            fixture.clock.set(retryAt);
            fixture.controller.pulse(); // one FX tick past the deadline, and nothing else
            // No further tick or FX task: bounded only so a missed retry fails, not hangs.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (lines(log) < 2 && System.nanoTime() < deadline) Thread.sleep(2);
            check(lines(log) == 2, "The tick past the back-off retried the rejected tile");
        } finally {
            Files.deleteIfExists(reject);
            fixture.close();
        }
    }

    /** Files whose root {@code held} preparation waits for {@code release}, noting cancellation. */
    private static AuthoredTerrainSource.Files holding(long held,
            CompletableFuture<Void> entered, CompletableFuture<Void> release,
            CompletableFuture<Void> canceled) {
        return new AuthoredTerrainSource.Files() {
            @Override @SuppressWarnings("try")
            FileTypes.FileStat stat(long id, Cancellation token) {
                if (id == held && !entered.isDone()) {
                    try (var registration = token.onCancel(() -> canceled.complete(null))) {
                        entered.complete(null);
                        release.join();
                        token.check();
                    }
                }
                return super.stat(id, token);
            }
        };
    }

    /** #366: a tile whose demand leaves for the next ring and comes back is never canceled. */
    private static void aTileOneRingBeyondKeepsItsPreparation() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var fixture = new Fixture(holding(1002, entered, release, canceled), 4);
        fixture.residents = 2;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            var t = fixture.tiles;
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            fixture.until(entered::isDone);
            // The camera crosses a border: tile 1 is one ring beyond the new demand.
            fixture.controller.neighborhood(List.of(t.get(0), t.get(2)), List.of(t.get(1)));
            for (int i = 0; i < 50; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(!canceled.isDone(), "A tile one ring beyond keeps its preparation");
            // And crosses back before tile 1 is ready.
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            release.complete(null);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
            check(!canceled.isDone(), "Crossing back and forth never restarted tile 1");
            check(fixture.files.opens(1002) == fixture.files.opens(2),
                    "Tile 1 was prepared once, as tile 0: " + fixture.files.opens(1002));
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    /**
     * #366 review: a kept tile whose source turns out missing (CAPABILITY_UNAVAILABLE) after the
     * demand left it publishes no UNAVAILABLE status, as for a helper rejection out of demand, and
     * the wanted tiles still load.
     */
    private static void aKeptTileUnavailableAfterDemandLeftIsNotPublished() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var fixture = new Fixture(holding(1002, entered, release, canceled), 4);
        fixture.residents = 2;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            var t = fixture.tiles;
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            fixture.until(entered::isDone);
            // Tile 1 goes one ring beyond, then its held source read finds the source missing.
            fixture.controller.neighborhood(List.of(t.get(0), t.get(2)), List.of(t.get(1)));
            fixture.files.sources.remove(1002L);
            release.complete(null);
            fixture.until(() -> fixture.controller.rejectionsOutOfDemand() == 1);
            check(!canceled.isDone(), "The kept tile was not canceled; its refusal arrived");
            check(fixture.controller.snapshot().phase() != WorldStreamSourceController.Phase.UNAVAILABLE
                            && !fixture.controller.snapshot().detail().contains("SOURCE_INPUT"),
                    "No unavailable status for a tile out of demand: "
                            + fixture.controller.snapshot().detail());
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    /**
     * #366: a kept tile that stages after the demand left it never displaces a wanted resident.
     * With the table full of wanted tiles it is canceled instead of committed.
     */
    private static void aKeptTileNeverDisplacesAWantedResident() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var fixture = new Fixture(holding(1004, entered, release, canceled), 4);
        fixture.residents = 2;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            var t = fixture.tiles;
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
            fixture.controller.neighborhood(List.of(t.get(0), t.get(2)), List.of(t.get(1)));
            fixture.until(entered::isDone); // tile 2 is being prepared
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            int commits = fixture.commitLines().size();
            release.complete(null);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2
                    && fixture.pool.stats().leaseClaims() == 2);
            check(fixture.commitLines().size() == commits,
                    "Staged tile 2 was canceled, not committed over wanted tile 1");
            check(fixture.files.opens(1002) == fixture.files.opens(2),
                    "Wanted tile 1 was never evicted and reloaded: " + fixture.files.opens(1002));
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    /**
     * #366 review: hysteresis never holds the single preparation lane while the camera tile is
     * missing. Tile 1 is being prepared and is in the keep ring, but the camera moved to tile 3,
     * which is not resident: tile 1 is canceled so tile 3 can load.
     */
    private static void aKeptTileYieldsTheLaneWhenTheCameraTileIsMissing() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var fixture = new Fixture(holding(1002, entered, release, canceled), 4);
        fixture.residents = 2;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            var t = fixture.tiles;
            fixture.controller.neighborhood(List.of(t.get(0), t.get(1)), List.of(t.get(2)));
            fixture.until(entered::isDone);
            fixture.controller.neighborhood(List.of(t.get(3), t.get(0)), List.of(t.get(1)));
            fixture.until(canceled::isDone);
            release.complete(null); // the held read now sees its cancellation and drains
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    /** Production bound (#173): 25 residents, and moving on replaces only the leaving tiles. */
    private static void fiveByFiveNeighborhoodHoldsTwentyFiveResidentsWithinTheSourceUnion()
            throws Exception {
        int residents = WorldStreamTransport.MAX_RESIDENTS;
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), residents + 5);
        fixture.residents = residents;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, residents));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == residents);
            check(fixture.commitLines().size() == residents, "One commit per resident up to the 5x5 bound");
            awaitLeaseClaims(fixture, residents, "One source atlas owner per resident");
            fixture.controller.neighborhood(fixture.tiles.subList(5, residents + 5));
            long[] peak = {0};
            // Right after the shift the snapshot still reads READY with 25 prepared from the old
            // demand, so wait for the five new tiles' commits too. Keep presenting throughout:
            // replaced tiles retire only after accepted pixels stop showing them, and at the
            // residents + 3 source bound the controller rightly waits for those pixels.
            // Owners are atlases held or reserved, not lease claims (#498): while a tile prepares,
            // its texture batch and the retained copy it hands over both hold a claim on one atlas.
            fixture.untilPresented(() -> {
                peak[0] = Math.max(peak[0], sourceOwners(fixture));
                return fixture.commitLines().size() >= residents + 5
                        && fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                        && fixture.controller.snapshot().prepared() == residents
                        && fixture.pool.stats().leaseClaims() == residents;
            });
            check(peak[0] <= residents + 3, "Source owners never exceed residents plus staged, pending and retiring");
            check(sourceOwners(fixture) <= com.vibeminer.concepts.world.TerrainAtlasPool.MAX_ENTRIES,
                    "The shared atlas pool keeps headroom for other borrowers");
        } finally { fixture.close(); }
    }

    /** One authored building: a quad drawn by one range of one 2x2 image, placed once. */
    private static com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Exterior building() {
        var builder = com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, ByteBuffer.wrap(new byte[20])); // 2x2 and its 1x1 mip
        builder.model();
        float[] vertices = new float[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertices[v * 8] = v % 2 * 10;
            vertices[v * 8 + 1] = v / 2 * 10;
            vertices[v * 8 + 5] = 1;
        }
        builder.mesh(java.nio.FloatBuffer.wrap(vertices),
                java.nio.IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}), new int[] {0, 6, 0});
        builder.placement(0, -100, -200, 12, 1, 1, 0);
        return builder.build();
    }

    /**
     * Object preparation starts only once terrain is idle, and terrain preempts it: an injected
     * preparer that blocks until canceled never holds up the first tile's commit nor the rest of
     * the neighborhood, and close drains it cleanly.
     */
    private static void terrainNeverWaitsForBlockedObjectPreparation() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 9);
        fixture.exteriors = true;
        var entered = new java.util.concurrent.atomic.AtomicInteger();
        var preempted = new java.util.concurrent.atomic.AtomicInteger();
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        var early = new java.util.concurrent.atomic.AtomicBoolean();
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) -> {
            // The child logs each commit before acknowledging it.
            if (fixture.commitLines().isEmpty()) early.set(true);
            entered.incrementAndGet();
            blocked.set(true);
            try {
                while (!cancellation.isCanceled()) Thread.sleep(1);
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            } finally {
                blocked.set(false);
            }
            preempted.incrementAndGet();
            cancellation.check();
            throw new AssertionError("Canceled preparation returned");
        };
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY && blocked.get());
            check(entered.get() == 1, "Idle terrain starts one object preparation");
            check(!early.get(), "Object preparation never starts before the first commit");
            check(fixture.controller.snapshot().detail().contains("loading buildings"),
                    "Ready status discloses buildings still loading: " + fixture.controller.snapshot());
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9 && blocked.get());
            check(preempted.get() >= 1, "Wanted terrain preempted the blocked preparation");
            check(fixture.commitLines().size() >= 9, "All nine tiles committed past the blocked preparer");
            check(fixture.objectLines().isEmpty(), "No objects were delivered");
        } finally {
            fixture.close();
        }
        check(!blocked.get(), "Close cancels and drains the blocked preparation");
    }

    /** Late objects attach to committed tiles after terrain; the ready line reports them. */
    private static void lateObjectsAttachAfterTerrainAndReportBuildings() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 3);
        fixture.exteriors = true;
        var prepared = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) -> {
            prepared.add(tile.y());
            return new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored")
                    .withPicks(tile, java.util.List.of(WorldObjectPicksTest.building(
                            new WorldObjectPicks.Key(0, -100, -200, 12, 1))));
        };
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 3));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().detail().contains("3 BASIC exterior buildings on 3")
                    && !fixture.controller.snapshot().detail().contains("loading buildings"));
            check(fixture.objectLines().size() == 3
                    && fixture.objectLines().stream().allMatch(line -> line.endsWith(" ATTACHED")),
                    "Each committed tile received its objects once: " + fixture.objectLines());
            check(prepared.size() == 3, "Each tile was prepared once: " + prepared);
            // Every drawn resident publishes its pick geometry with its recorded source.
            fixture.until(() -> fixture.controller.objects().size() == 3);
            var picked = fixture.controller.objects();
            check(picked.stream().allMatch(t -> t.geometry().placements() == 1
                            && t.sources().getFirst() instanceof WorldObjectPicks.Exterior)
                            && picked.stream().map(t -> t.tileY()).distinct().count() == 3,
                    "Pick tiles follow the drawn residents: " + picked.size());
            // Commits only ever carried terrain: none waited for an objects reply.
            check(fixture.commitLines().size() >= 3, "Terrain committed");
        } finally {
            fixture.close();
        }
    }

    /**
     * A BUDGET refusal is not a failure: the resident keeps its terrain and is offered again only
     * after a drawn objects set was released (its resident replaced in its slot) - never just
     * because the camera turned or the demand reordered.
     */
    private static void budgetRefusedObjectsRetryAfterAReleaseOnly() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 6);
        fixture.exteriors = true;
        fixture.residents = 4;
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) ->
                new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored");
        Path budget = fixture.directory.resolve("authored-budget-objects");
        int refusedY = fixture.tiles.get(3).y();
        java.util.function.Predicate<String> refusedTile = line -> line.startsWith(refusedY + " ");
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 3));
            fixture.untilPresented(() -> fixture.objectLines().stream()
                    .filter(line -> line.endsWith(" ATTACHED")).count() == 3);
            Files.writeString(budget, "full");
            fixture.controller.neighborhood(fixture.tiles.subList(0, 4));
            fixture.untilPresented(() -> fixture.objectLines().stream().anyMatch(
                    line -> refusedTile.test(line) && line.endsWith(" BUDGET")));
            check(!fixture.controller.snapshot().detail().contains("building objects failed"),
                    "A budget refusal is not reported as a failure: " + fixture.controller.snapshot().detail());
            Files.delete(budget);
            // Turning in place reorders the demand but releases nothing: no re-offer.
            var turned = new ArrayList<>(fixture.tiles.subList(0, 4));
            java.util.Collections.reverse(turned);
            fixture.controller.neighborhood(turned);
            fixture.camera(2);
            for (int i = 0; i < 100; i++) { fixture.pump(); Thread.sleep(2); }
            check(fixture.objectLines().stream().filter(refusedTile).count() == 1,
                    "No re-offer without a release: " + fixture.objectLines());
            // A new tile in a fresh slot releases nothing either.
            fixture.controller.neighborhood(fixture.tiles.subList(1, 5));
            fixture.untilPresented(() -> fixture.objectLines().stream()
                    .anyMatch(line -> line.startsWith(fixture.tiles.get(4).y() + " ")));
            for (int i = 0; i < 50; i++) { fixture.pump(); Thread.sleep(2); }
            check(fixture.objectLines().stream().filter(refusedTile).count() == 1,
                    "Still no re-offer without a release: " + fixture.objectLines());
            // A tile staged into a slot whose objects were drawn releases them: the refused set
            // is offered once more and attaches.
            fixture.controller.neighborhood(fixture.tiles.subList(2, 6));
            fixture.untilPresented(() -> fixture.objectLines().stream().anyMatch(
                    line -> refusedTile.test(line) && line.endsWith(" ATTACHED")));
            check(fixture.objectLines().stream().filter(refusedTile).count() == 2,
                    "Offered exactly once more after the release: " + fixture.objectLines());
        } finally {
            fixture.close();
        }
    }

    /**
     * Each tile's creatures get what the resident tiles' creatures leave of the area share, and a
     * set refused for budget while it carries creatures is offered again at once without them.
     */
    private static void creaturesShareTheirBytesAndYieldToBuildings() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 6);
        fixture.exteriors = true;
        fixture.residents = 4;
        long each = WorldTileCreatures.AREA_BYTES / 4;
        var asked = new java.util.concurrent.ConcurrentHashMap<Integer, List<Long>>();
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) -> {
            asked.computeIfAbsent(tile.y(), key -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(creatureBytes);
            var creatures = creatureBytes >= each ? WorldTileCreatures.Summary.drawn(3, 1, each)
                    : WorldTileCreatures.Summary.NONE;
            return new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored", creatures);
        };
        Path budget = fixture.directory.resolve("authored-budget-objects");
        int refusedY = fixture.tiles.get(3).y();
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 3));
            fixture.untilPresented(() -> fixture.objectLines().stream()
                    .filter(line -> line.endsWith(" ATTACHED")).count() == 3);
            var first = new ArrayList<Long>();
            for (var tile : fixture.tiles.subList(0, 3)) first.add(asked.get(tile.y()).getFirst());
            java.util.Collections.sort(first);
            check(first.equals(List.of(WorldTileCreatures.AREA_BYTES - 2 * each,
                            WorldTileCreatures.AREA_BYTES - each, WorldTileCreatures.AREA_BYTES)),
                    "Each tile gets what the attached creatures leave: " + first);
            Files.writeString(budget, "full");
            fixture.controller.neighborhood(fixture.tiles.subList(0, 4));
            fixture.untilPresented(() -> fixture.objectLines().stream()
                    .filter(line -> line.startsWith(refusedY + " ") && line.endsWith(" BUDGET")).count() == 2);
            check(asked.get(refusedY).equals(List.of(each, 0L)),
                    "Refused with creatures, offered again at once without them: " + asked.get(refusedY));
            for (int i = 0; i < 50; i++) { fixture.pump(); Thread.sleep(2); }
            check(asked.get(refusedY).size() == 2,
                    "Refused without creatures, it waits for a release as before: " + asked.get(refusedY));
        } finally {
            fixture.close();
        }
    }

    /**
     * With a preparer that appends creatures, every wanted tile's buildings go first, without
     * creatures, and each tile's creatures follow in a later pass that replaces its drawn set (or,
     * on a tile without buildings, attaches as its first). A tile whose creatures draw nothing is
     * not delivered again, and a refused replacement leaves the buildings drawn.
     */
    private static void buildingsComeFirstAndCreaturesReplaceThemLater() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 4);
        fixture.exteriors = true;
        long each = WorldTileCreatures.AREA_BYTES / 8;
        int empty = fixture.tiles.get(1).y(), still = fixture.tiles.get(2).y();
        var asked = new java.util.concurrent.ConcurrentHashMap<Integer, List<Long>>();
        var preparer = new WorldStreamSourceBridge.ExteriorPreparer() {
            @Override
            public WorldStreamSourceBridge.ExteriorCopy prepare(
                    com.vibeminer.concepts.world.WorldTerrainStreaming source,
                    com.vibeminer.concepts.world.WorldTerrainStreaming.Scope scope,
                    com.vibeminer.formats.world.WorldFormats.Tile tile,
                    com.vibeminer.access.Cancellation cancellation, int formats, long creatureBytes) {
                asked.computeIfAbsent(tile.y(), key -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(creatureBytes);
                boolean buildings = tile.y() != empty;
                if (creatureBytes == BUILDINGS_ONLY)
                    return buildings ? new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored")
                            : new WorldStreamSourceBridge.ExteriorCopy(null, 0, false, "no buildings");
                var creatures = tile.y() == still ? WorldTileCreatures.Summary.NONE
                        : WorldTileCreatures.Summary.drawn(3, 1, each);
                return new WorldStreamSourceBridge.ExteriorCopy(building(), buildings ? 1 : 0, false, "authored",
                        creatures);
            }

            @Override
            public boolean appendsCreatures() {
                return true;
            }
        };
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 3));
            fixture.untilPresented(() -> fixture.objectLines().size() == 4);
            var lines = fixture.objectLines();
            check(lines.subList(0, 2).stream().allMatch(line -> !line.contains("REPLACE") && line.endsWith(" ATTACHED"))
                    && !lines.subList(0, 2).contains(empty + " ATTACHED"),
                    "Both tiles with buildings got them first, without creatures: " + lines);
            check(lines.subList(2, 4).containsAll(List.of(fixture.tiles.get(0).y() + " REPLACE ATTACHED",
                            empty + " ATTACHED")),
                    "Creatures replaced the drawn set, or came first on a tile without buildings: " + lines);
            for (var tile : fixture.tiles.subList(0, 3))
                check(asked.get(tile.y()).size() == 2 && asked.get(tile.y()).getFirst()
                                == WorldStreamSourceBridge.ExteriorPreparer.BUILDINGS_ONLY
                                && asked.get(tile.y()).get(1) > 0,
                        "Each tile was prepared buildings only, then with creatures: " + asked);
            fixture.untilPresented(() -> fixture.controller.snapshot().detail().contains("6 static creature models"));
            check(fixture.objectLines().size() == 4, "Nothing delivered twice: " + fixture.objectLines());
        } finally {
            fixture.close();
        }
    }

    /** A replacement refused for budget leaves the drawn buildings and is not offered again at once. */
    private static void refusedCreatureReplacementKeepsTheBuildings() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        fixture.exteriors = true;
        Path budget = fixture.directory.resolve("authored-budget-objects");
        var creaturesAsked = new java.util.concurrent.atomic.AtomicInteger();
        var preparer = new WorldStreamSourceBridge.ExteriorPreparer() {
            @Override
            public WorldStreamSourceBridge.ExteriorCopy prepare(
                    com.vibeminer.concepts.world.WorldTerrainStreaming source,
                    com.vibeminer.concepts.world.WorldTerrainStreaming.Scope scope,
                    com.vibeminer.formats.world.WorldFormats.Tile tile,
                    com.vibeminer.access.Cancellation cancellation, int formats, long creatureBytes) {
                if (creatureBytes == BUILDINGS_ONLY)
                    return new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored");
                creaturesAsked.incrementAndGet();
                try {
                    // The buildings attached; the budget is full by the time creatures arrive.
                    Files.writeString(budget, "full");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                return new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored",
                        WorldTileCreatures.Summary.drawn(3, 1, 1 << 20));
            }

            @Override
            public boolean appendsCreatures() {
                return true;
            }
        };
        try {
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            int y = fixture.tiles.get(0).y();
            fixture.untilPresented(() -> fixture.objectLines().contains(y + " REPLACE BUDGET"));
            for (int i = 0; i < 50; i++) { fixture.pump(); Thread.sleep(2); }
            check(fixture.objectLines().equals(List.of(y + " ATTACHED", y + " REPLACE BUDGET"))
                    && creaturesAsked.get() == 1, "Refused once, not offered again: " + fixture.objectLines());
            var detail = fixture.controller.snapshot().detail();
            check(detail.contains("1 BASIC exterior building") && !detail.contains("building objects failed"),
                    "The buildings stay drawn: " + detail);
        } finally {
            fixture.close();
        }
    }

    /** Throwing preparers and failed child uploads cost only the buildings. */
    private static void lateObjectFailuresStayIsolatedFromTerrain() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        fixture.exteriors = true;
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) -> {
            if (tile.y() == 19) throw new IllegalStateException("authored preparation failure");
            return new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored");
        };
        try {
            Files.writeString(fixture.directory.resolve("authored-fail-objects"), "fail");
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 2));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2
                    && fixture.controller.snapshot().detail().contains("building objects failed on 2"));
            check(fixture.objectLines().equals(List.of("20 FAILED")),
                    "Only the second tile reached the child: " + fixture.objectLines());
            check(fixture.controller.snapshot().detail().startsWith("2 tiles"),
                    "Both terrain tiles stay current: " + fixture.controller.snapshot());
        } finally {
            fixture.close();
        }
    }

    /**
     * An attach still in flight when its tile retires completes late: the retired entry is not
     * recorded as tried and its closed source is left untouched.
     */
    private static void lateObjectsCompletingAfterRetirementAreIgnored() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 10);
        fixture.exteriors = true;
        // Only the first tile has buildings, so the only delivery is the one that goes stale.
        WorldStreamSourceBridge.ExteriorPreparer preparer = (source, scope, tile, cancellation, formats, creatureBytes) ->
                tile.y() == 19
                        ? new WorldStreamSourceBridge.ExteriorCopy(building(), 1, false, "authored")
                        : new WorldStreamSourceBridge.ExteriorCopy(null, 0, false, "none");
        Path hold = fixture.directory.resolve("authored-hold-objects");
        try {
            Files.writeString(hold, "hold");
            fixture.start(fixture.gpu::start, () -> {}, preparer);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            // The child has decided ATTACHED for the first tile and holds its reply.
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && Files.exists(fixture.directory.resolve("authored-held-objects")));
            check(fixture.objectLines().equals(List.of("19 ATTACHED")),
                    "First tile's objects decided: " + fixture.objectLines());
            Object retired = quiet(() -> {
                for (var entry : controllerEntries(fixture.controller).values()) {
                    var accessor = entry.getClass().getDeclaredMethod("resident");
                    accessor.setAccessible(true);
                    var resident = (WorldStreamTransport.Resident) accessor.invoke(entry);
                    if (resident.tileY() == 19) return entry;
                }
                throw new AssertionError("No resident for the first tile");
            });
            // Nine other wanted tiles fill every slot, so a commit retires the first tile while
            // its attach is still in flight.
            fixture.controller.neighborhood(fixture.tiles.subList(1, 10));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9
                    && quiet(() -> !controllerEntries(fixture.controller).containsValue(retired)));
            Files.delete(hold);
            // The late reply is consumed and every remaining tile is then tried (none has objects).
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && !fixture.controller.snapshot().detail().contains("loading buildings")
                    && quiet(() -> {
                        var field = WorldStreamSourceController.class.getDeclaredField("exteriorTried");
                        field.setAccessible(true);
                        return ((java.util.Set<?>) field.get(fixture.controller)).size() == 9;
                    }));
            check(fixture.objectLines().equals(List.of("19 ATTACHED")),
                    "Only the stale delivery reached the child: " + fixture.objectLines());
            check(!fixture.controller.snapshot().detail().contains("BASIC exterior buildings"),
                    "No current tile reports buildings: " + fixture.controller.snapshot());
            var tried = quiet(() -> {
                var field = WorldStreamSourceController.class.getDeclaredField("exteriorTried");
                field.setAccessible(true);
                return new ArrayList<>((java.util.Set<?>) field.get(fixture.controller));
            });
            check(tried.stream().noneMatch(entry -> entry == retired),
                    "Retired entry is not recorded as tried: " + tried);
            var sourceAccessor = retired.getClass().getDeclaredMethod("source");
            sourceAccessor.setAccessible(true);
            var source = sourceAccessor.invoke(retired);
            var summary = WorldStreamSourceBridge.Prepared.class
                    .getDeclaredField("exteriorSummary");
            summary.setAccessible(true);
            check(summary.get(source) == WorldStreamSourceBridge.ExteriorSummary.NONE,
                    "Closed source is left untouched");
        } finally {
            Files.deleteIfExists(hold);
            fixture.close();
        }
    }

    /** The controller's resident entries (worker-owned; read only while it is quiet). */
    private static java.util.Map<?, ?> controllerEntries(WorldStreamSourceController controller)
            throws ReflectiveOperationException {
        var field = WorldStreamSourceController.class.getDeclaredField("entries");
        field.setAccessible(true);
        return new java.util.IdentityHashMap<>((java.util.Map<?, ?>) field.get(controller));
    }

    @FunctionalInterface
    private interface Read<T> {
        T get() throws Exception;
    }

    /** Retries a reflective read of worker-owned state that raced a worker update. */
    private static <T> T quiet(Read<T> read) {
        for (int attempt = 0; ; attempt++) {
            try {
                return read.get();
            } catch (java.util.ConcurrentModificationException race) {
                if (attempt == 100) throw race;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    /**
     * #306: a helper that stops in the middle of a commit (the authored child holds it and sends
     * nothing more) fails the session once the reply quiet time passes, with a diagnostic naming
     * the commit, as a helper crash fails it; the view then offers its usual retry.
     */
    private static void aHelperHungMidCommitFailsTheSessionWithADiagnostic() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        Path hold = fixture.directory.resolve("authored-hold-commit");
        String previous = System.getProperty("vimi.world.replyQuietMillis");
        System.setProperty("vimi.world.replyQuietMillis", "1000");
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9);
            long last = fixture.commitLines().stream().mapToLong(line -> Long.parseLong(line.split(" ")[0]))
                    .max().orElseThrow();
            Files.writeString(hold, Long.toString(last + 1));
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (fixture.controller.snapshot().phase() != WorldStreamSourceController.Phase.FAILED
                    && System.nanoTime() < deadline) {
                fixture.controller.pulse();
                Runnable task;
                while ((task = fixture.fx.poll()) != null) task.run();
                Thread.sleep(5);
            }
            check(Files.exists(fixture.directory.resolve("authored-held-commit")), "The commit was held");
            var snapshot = fixture.controller.snapshot();
            check(snapshot.phase() == WorldStreamSourceController.Phase.FAILED,
                    "A hung commit fails the session: " + snapshot);
            check(String.valueOf(snapshot.detail()).contains("stopped answering")
                            && String.valueOf(snapshot.detail()).contains("a commit"),
                    "The failure names the hang and the commit: " + snapshot.detail());
        } finally {
            if (previous == null) System.clearProperty("vimi.world.replyQuietMillis");
            else System.setProperty("vimi.world.replyQuietMillis", previous);
            Files.deleteIfExists(hold);
            try {
                fixture.close();
            } catch (Exception | AssertionError closedFailed) {
                // The session already failed; its close reports that failure.
            }
        }
    }

    private static void retryCommitKeepsExactTargetUntilPriorFileActuallyRetires() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(),13);
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var firstRelease = new java.util.concurrent.CountDownLatch(1);
        Path hold = fixture.directory.resolve("authored-hold-commit");
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            Files.writeString(hold,"12");
            fixture.controller.neighborhood(fixture.tiles.subList(4,13));
            fixture.until(() -> Files.exists(fixture.directory.resolve("authored-held-commit")));
            enqueueHeldFile(fixture.gpu.session, firstEntered, firstRelease);
            check(firstEntered.await(3,TimeUnit.SECONDS),"Actual producer/file lane held before INPUT_CONSUMED12");
            Files.delete(hold);
            fixture.until(() -> fixture.commitLines().stream().anyMatch(line -> line.startsWith("12 ")));
            var returning = new ArrayList<>(fixture.tiles.subList(4,12));
            returning.addFirst(fixture.tiles.get(1));
            fixture.controller.neighborhood(returning);
            var targetField=WorldStreamSourceController.class.getDeclaredField("committing");targetField.setAccessible(true);
            var retryField=WorldStreamSourceController.class.getDeclaredField("retryCommit");retryField.setAccessible(true);
            // Wait on the published phase, not on retryCommit: the worker sets the flag before it
            // publishes WAITING_CAPACITY, so a read between the two still saw COMMITTING (#498).
            // until() fails on FAILED, so a fatal refusal still fails here.
            fixture.until(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.WAITING_CAPACITY
                    && fixture.controller.snapshot().detail().equals("Waiting for commit input retirement"));
            check(retryField.getBoolean(fixture.controller),
                    "Real pending commit input credit is pressure, not fatal or fabricated ACK");
            Object target=targetField.get(fixture.controller);
            var atField=WorldStreamSourceController.class.getDeclaredField("commitRetryAt");atField.setAccessible(true);
            fixture.controller.neighborhood(fixture.tiles.subList(1,10));
            fixture.clock.set(600_000_000L);
            fixture.controller.pulse();
            fixture.until(() -> {
                try {return atField.getLong(fixture.controller)==600_000_000L;}
                catch(IllegalAccessException error){throw new AssertionError(error);}
            });
            check(targetField.get(fixture.controller)==target,"500ms refused retry keeps exact immutable target aliases");
            firstRelease.countDown();

            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            // leaseClaims counts atlas leases held now (not a running total). READY can be
            // published before the worker's next step retires the previous presentation's
            // pins, so the count settles to the nine residents a step later (#498).
            awaitLeaseClaims(fixture,9,"Actual cleanup capacity eventually restores the requested neighborhood");
        } finally {
            Files.deleteIfExists(hold);
            firstRelease.countDown();
            fixture.close();
        }
    }

    /**
     * Lost wake-up regression: the previous commit's input retires (a capacity change) after the
     * transport refused the next commit but before the controller recorded the refusal. With the
     * frozen test clock the controller must still retry, because the change happened after the
     * refused commit was issued.
     */
    private static void retirementBetweenRefusalAndRetryStillWakesTheCommit() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(),13);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Path hold = fixture.directory.resolve("authored-hold-commit");
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            Files.writeString(hold,"12");
            fixture.controller.neighborhood(fixture.tiles.subList(4,13));
            fixture.until(() -> Files.exists(fixture.directory.resolve("authored-held-commit")));
            // Hold the parent's file lane so commit 12's input cannot retire yet.
            enqueueHeldFile(fixture.gpu.session, entered, release);
            check(entered.await(3,TimeUnit.SECONDS),"Parent file lane held");
            var session = fixture.gpu.session;
            var seamRan = new java.util.concurrent.atomic.AtomicBoolean();
            fixture.controller.refusalSeam = () -> {
                if (!seamRan.compareAndSet(false,true)) return;
                // Inside the window: let commit 12's input retire and wait for its capacity change.
                long before = session.capacityRevision();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (session.capacityRevision() == before && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                check(session.capacityRevision() != before, "Retirement changed capacity inside the window");
            };
            Files.delete(hold);
            fixture.until(() -> fixture.commitLines().stream().anyMatch(line -> line.startsWith("12 ")));
            var returning = new ArrayList<>(fixture.tiles.subList(4,12));
            returning.addFirst(fixture.tiles.get(1));
            fixture.controller.neighborhood(returning);
            fixture.until(seamRan::get);
            // The clock never advances: only the observed capacity change may trigger the retry.
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    /**
     * Lost wake-up regression (#283): a tile preparation refused with RETRY_LATER for the geometry
     * lane is retried although the lane's release lands after the refusal but before the
     * controller records it. The refused reservation's own unused-credit CREDIT also lands inside
     * that window; it is excluded from the refusal's capacity check (#366 review), so it is not
     * what wakes the retry. With the frozen test clock, the retry comes from the geometry release,
     * which a GEOMETRY_CREDIT refusal waits on (#304). A capacity change from elsewhere waking a
     * capacity-path refusal is {@link #capacityChangeFromElsewhereWakesASourceWorkerRefusal}.
     */
    private static void capacityChangeBetweenTileRefusalAndRetryStillWakesPreparation()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var geometry = com.vibeminer.concepts.world.AuthoredGeometryPressure.hold(fixture.world);
        Path holdCredit = fixture.directory.resolve("authored-hold-unused-credit");
        var seamRan = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Files.writeString(holdCredit, "hold");
            Runnable seam = () -> {
                if (!seamRan.compareAndSet(false, true)) return;
                try {
                    // Inside the window: free the geometry lane (what wakes the retry) and let
                    // the refused reservation's own credit arrive (which must not).
                    geometry.close();
                    var session = fixture.gpu.session;
                    long before = session.capacityRevision();
                    Files.delete(holdCredit);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (session.capacityRevision() == before && System.nanoTime() < deadline)
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    check(session.capacityRevision() != before,
                            "The refused reservation's credit changed capacity inside the window");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            };
            // Install the seam before any tile can be prepared: the GPU starts first.
            fixture.start(scope -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fixture.controller == null && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                fixture.controller.tileRefusalSeam = seam;
                return fixture.gpu.start(scope);
            });
            fixture.until(seamRan::get);
            var blocked = WorldStreamSourceController.class.getDeclaredField("blockedOnMaterialPool");
            blocked.setAccessible(true);
            check(!blocked.getBoolean(fixture.controller),
                    "A geometry-lane refusal waits on capacity, not on the material pool");
            // The clock never advances: only the geometry release may trigger the retry.
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
        } finally {
            Files.deleteIfExists(holdCredit);
            geometry.close();
            fixture.close();
        }
    }


    /**
     * #305: a presentation or demand bump that lands after a tile reservation was issued but
     * before its refusal is processed still wakes the retry. The clock never advances and the
     * refusal's own unused credit is held, so only the bump inside the window can wake it.
     * Sites: "geometry" (GEOMETRY_CREDIT, wakes on demand or its own release, #304), "pool"
     * (MATERIAL_POOL, wakes on demand or source retirement) and "output" (OUTPUT_CREDIT, the
     * generic path that also wakes on presentation).
     */
    private static void bumpBetweenIssueAndRefusalStillWakesRetry(
            String site, boolean presentation) throws Exception {
        bumpBetweenIssueAndRefusalStillWakesRetry(site, presentation, false);
    }

    /**
     * With {@code beforeIssue}, the bump lands earlier: after the step read its demand and pixels
     * but before it issued the reservation, so the tile was still chosen from the old ones.
     */
    private static void bumpBetweenIssueAndRefusalStillWakesRetry(
            String site, boolean presentation, boolean beforeIssue) throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        var held = new java.util.concurrent.atomic.AtomicReference<AutoCloseable>(
                switch (site) {
                    case "pool" -> AuthoredTerrainPoolPressure.hold(fixture.pool);
                    case "geometry" ->
                            com.vibeminer.concepts.world.AuthoredGeometryPressure.hold(fixture.world);
                    default -> null; // "output": held once the controller owns its source
                });
        boolean materialPool = site.equals("pool");
        Path holdCredit = fixture.directory.resolve("authored-hold-unused-credit");
        var refused = new java.util.concurrent.atomic.AtomicReference<Object>();
        String label = site + (presentation ? ", presentation bump" : ", demand bump")
                + (beforeIssue ? " before issue" : "");
        try {
            Files.writeString(holdCredit, "hold");
            var revision = WorldStreamSourceController.class.getDeclaredField(
                    presentation ? "presentationRevision" : "demandRevision");
            revision.setAccessible(true);
            var lockField = WorldStreamSourceController.class.getDeclaredField("lock");
            lockField.setAccessible(true);
            var bumped = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable bump = () -> {
                if (!bumped.compareAndSet(false, true)) return;
                try {
                    long before;
                    synchronized (lockField.get(fixture.controller)) {
                        before = revision.getLong(fixture.controller);
                    }
                    if (presentation) {
                        // Stands in for an FX presented() acceptance: same field, same lock.
                        synchronized (lockField.get(fixture.controller)) {
                            revision.setLong(fixture.controller, before + 1);
                        }
                    } else {
                        fixture.controller.neighborhood(fixture.tiles.subList(0, 2));
                    }
                    synchronized (lockField.get(fixture.controller)) {
                        check(revision.getLong(fixture.controller) == before + 1,
                                "The bump landed inside the refusal window: " + label);
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            Runnable seam = () -> {
                if (refused.get() != null) return;
                refused.set(Boolean.TRUE);
                if (!beforeIssue) bump.run();
            };
            fixture.start(scope -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fixture.controller == null && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                fixture.controller.tileRefusalSeam = seam;
                if (site.equals("output")) {
                    try {
                        var source = WorldStreamSourceController.class.getDeclaredField("source");
                        source.setAccessible(true);
                        held.set(com.vibeminer.concepts.world.AuthoredOutputPressure.hold(
                                (com.vibeminer.concepts.world.WorldTerrainStreaming)
                                        source.get(fixture.controller)));
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                }
                if (beforeIssue) fixture.controller.tileIssueSeam = bump;
                return fixture.gpu.start(scope);
            });
            fixture.until(() -> refused.get() != null);
            check(bumped.get(), "The bump ran: " + label);
            var pool = WorldStreamSourceController.class.getDeclaredField("blockedOnMaterialPool");
            pool.setAccessible(true);
            check(pool.getBoolean(fixture.controller) == materialPool, "Refusal site: " + label);
            // The retry gate opened: the step went on to ask for a new reservation, which the
            // still-held unused credit refuses. Without the bump it keeps waiting on capacity.
            fixture.until(() -> fixture.controller.snapshot().detail()
                    .equals("Waiting for GPU upload capacity"));
        } finally {
            Files.deleteIfExists(holdCredit);
            var hold = held.get();
            if (hold != null) hold.close();
            fixture.close();
        }
    }

    /**
     * #366 review: a tile refused RETRY_LATER on a capacity path (here a held source worker) waits
     * for a capacity change it did not cause. Its own unused-credit CREDIT is part of its baseline,
     * so it cannot wake its own retry at the transport's round-trip rate. Clock frozen and no
     * pulses during the window, so nothing else may retry it; the deadline still does afterwards.
     */
    private static void heldSourceWorkerIsNotRetriedByItsOwnCredit() throws Exception {
        String water = System.getProperty("vimi.world.water");
        System.setProperty("vimi.world.water", "false");
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        var refusals = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable held = () -> {};
        try {
            fixture.start(scope -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fixture.controller == null && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                fixture.controller.tileRefusalSeam = refusals::incrementAndGet;
                return fixture.gpu.start(scope);
            });
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            held = com.vibeminer.concepts.world.AuthoredSourceWorkerPressure.hold(
                    fixture.world.terrainStreaming().orElseThrow());
            fixture.controller.neighborhood(fixture.tiles.subList(0, 2));
            long first = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (refusals.get() < 1 && System.nanoTime() < first) Thread.sleep(1);
            check(refusals.get() >= 1, "The held source worker refuses the new tile");
            // Let the refusal's own unused credit land; no pulse, frozen clock.
            int before = refusals.get();
            long window = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < window) Thread.sleep(2);
            int attempts = refusals.get() - before;
            System.out.println("Held source worker: " + attempts + " refused attempts in 500 ms");
            check(attempts <= 1, "A refusal's own credit does not retry it: " + attempts);
            held.close();
            fixture.clock.addAndGet(600_000_000L);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
        } finally {
            held.close();
            fixture.close();
            if (water == null) System.clearProperty("vimi.world.water");
            else System.setProperty("vimi.world.water", water);
        }
    }

    /**
     * #534: a SOURCE_WORKER refusal waits on capacity changed by anything other than the refused
     * reservation. With the clock frozen and no frame presented (a presentation would also wake
     * it), its own unused credit and the worker's release do not retry it; an unrelated capacity
     * change (the session's commit-file retirement wake-up) does.
     */
    private static void capacityChangeFromElsewhereWakesASourceWorkerRefusal() throws Exception {
        String water = System.getProperty("vimi.world.water");
        System.setProperty("vimi.world.water", "false");
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        var refusals = new java.util.concurrent.atomic.AtomicInteger();
        var issues = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable held = () -> {};
        try {
            fixture.start(scope -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fixture.controller == null && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                fixture.controller.tileRefusalSeam = refusals::incrementAndGet;
                fixture.controller.tileIssueSeam = issues::incrementAndGet;
                return fixture.gpu.start(scope);
            });
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
            held = com.vibeminer.concepts.world.AuthoredSourceWorkerPressure.hold(
                    fixture.world.terrainStreaming().orElseThrow());
            fixture.controller.neighborhood(fixture.tiles.subList(0, 2));
            long first = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (refusals.get() < 1 && System.nanoTime() < first) Thread.sleep(1);
            check(refusals.get() >= 1, "The held source worker refuses the new tile");
            // Free the worker, then let the refusal's own credit land; no pulse, no presentation,
            // frozen clock.
            held.close();
            int issued = issues.get();
            long window = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < window) Thread.sleep(2);
            check(issues.get() == issued,
                    "Neither the refusal's own credit nor the worker release retries it: "
                            + (issues.get() - issued));
            var session = fixture.gpu.session;
            var elsewhere = session.getClass().getDeclaredMethod("capacityChanged");
            elsewhere.setAccessible(true);
            long revision = session.capacityRevision();
            elsewhere.invoke(session);
            check(session.capacityRevision() > revision, "The unrelated capacity change was counted");
            long retry = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (issues.get() == issued && System.nanoTime() < retry) Thread.sleep(1);
            check(issues.get() > issued, "The unrelated capacity change retries the tile");
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 2);
        } finally {
            held.close();
            fixture.close();
            if (water == null) System.clearProperty("vimi.world.water");
            else System.setProperty("vimi.world.water", water);
        }
    }

    /**
     * #304: while another holder keeps the geometry lane busy, the refused tile waits on the
     * source's geometry release instead of re-polling on every unused-credit CREDIT, and the
     * release itself wakes it promptly. The controller clock is frozen, so the 500 ms timeout
     * can never be what retries.
     */
    private static void busyGeometryLaneIsNotPolledAndItsReleaseWakesTheTile() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var geometry = com.vibeminer.concepts.world.AuthoredGeometryPressure.hold(fixture.world);
        var refusals = new java.util.concurrent.atomic.AtomicInteger();
        try {
            fixture.start(scope -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fixture.controller == null && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                fixture.controller.tileRefusalSeam = refusals::incrementAndGet;
                return fixture.gpu.start(scope);
            });
            fixture.until(() -> refusals.get() >= 1);
            // Let the refusal's own unused credit land and keep pulsing like the FX timer does.
            long window = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            int first = refusals.get();
            while (System.nanoTime() < window) {
                fixture.pump();
                Thread.sleep(2);
            }
            int attempts = refusals.get() - first;
            System.out.println("Busy geometry lane: " + attempts + " refused attempts in 500 ms");
            check(attempts <= 1, "A busy geometry lane is waited on, not polled: " + attempts);
            long released = System.nanoTime();
            geometry.close();
            // No pulse here: only the source's release listener may wake the waiting step.
            long quiet = released + TimeUnit.SECONDS.toNanos(2);
            while (fixture.controller.snapshot().detail()
                            .equals("Waiting for streaming resource capacity")
                    && System.nanoTime() < quiet) Thread.sleep(1);
            check(!fixture.controller.snapshot().detail()
                            .equals("Waiting for streaming resource capacity"),
                    "The geometry release itself woke the controller, without any pulse");
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released);
            System.out.println("Geometry release to seeded tile: " + latency + " ms");
            check(latency < 2_000, "The lane release woke the tile promptly: " + latency + " ms");
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY);
        } finally {
            geometry.close();
            fixture.close();
        }
    }
    private static void enqueueHeldFile(WorldStreamTransport.Session session,
            java.util.concurrent.CountDownLatch entered,java.util.concurrent.CountDownLatch release) throws Exception {
        var method=session.getClass().getDeclaredMethod("enqueueFile",Runnable.class);method.setAccessible(true);
        method.invoke(session,(Runnable)()->{entered.countDown();try{release.await();}
            catch(InterruptedException error){throw new AssertionError(error);}});
    }

    private static void returningNearestCanProgressWhenAllNeverPresentedNeighborsRemainWanted() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        try {
            fixture.start();fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9);
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            fixture.until(() -> fixture.pool.stats().leaseClaims() == 12
                    && fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.WAITING_CAPACITY);
            check(fixture.commitLines().size() == 12, "Three new native residents and nine historical source owners reach the fixed union");
            var desired = new ArrayList<WorldFormats.Tile>();desired.add(fixture.tiles.get(1));
            desired.addAll(fixture.tiles.subList(4, 12));
            fixture.controller.neighborhood(desired);
            fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,
                    new TerrainCamera(-266,-800,110,Math.PI,-.8)));
            // Current-only new9/10/11 remain wanted; the only unwanted current0 is also
            // last-presented. Drop/reload one non-nearest neighbor rather than deadlock.
            fixture.until(() -> fixture.commitLines().size() >= 14);
            check(sourceOwners(fixture) <= 12, "No thirteenth source owner while useful nearest is restored");
        } finally { fixture.close(); }
    }

    private static void newNearestCanProgressWhenAllNeverPresentedNeighborsRemainWanted() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        try {
            fixture.start();fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9);
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            fixture.until(() -> fixture.pool.stats().leaseClaims() == 12
                    && fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.WAITING_CAPACITY);
            check(fixture.commitLines().size() == 12, "Three new native residents and nine historical source owners reach the fixed union");
            var desired = new ArrayList<WorldFormats.Tile>();
            for (int index = 12; index >= 4; index--) desired.add(fixture.tiles.get(index));
            fixture.controller.neighborhood(desired);
            fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,
                    new TerrainCamera(-266,-6666,110,Math.PI,-.8)));
            // Latest nearest12 was NEVER presented; all three newly loaded fringe tiles
            // remain wanted after fast camera motion. Prior-presentation restoration is insufficient.
            fixture.until(() -> fixture.commitLines().size() >= 14);
            check(sourceOwners(fixture) <= 12, "No thirteenth source owner while useful nearest is restored");
        } finally { fixture.close(); }
    }

    private static void permanentSkyDisjointDemandRetiresPinsWithoutFakePresentation() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),18);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            var sequence=WorldStreamSourceController.class.getDeclaredField("acceptedSequence");
            var receipt=WorldStreamSourceController.class.getDeclaredField("acceptedReceipt");
            var pins=WorldStreamSourceController.class.getDeclaredField("presented");
            sequence.setAccessible(true); receipt.setAccessible(true); pins.setAccessible(true);
            long previousSequence=sequence.getLong(fixture.controller);
            Object previousReceipt=receipt.get(fixture.controller);
            Files.write(fixture.directory.resolve("authored-sky"),new byte[0]);
            fixture.controller.neighborhood(fixture.tiles.subList(9,18));
            fixture.until(()->fixture.controller.snapshot().detail().contains("showing previous view"));
            check(((List<?>)pins.get(fixture.controller)).isEmpty(),"Old Source pins explicitly retire for historical detached raster");
            check(sequence.getLong(fixture.controller)==previousSequence && receipt.get(fixture.controller)==previousReceipt,
                    "Historical raster transition never fabricates pixel ACK or new presentation receipt");
            fixture.until(()->fixture.controller.snapshot().prepared()==9);
            check(sequence.getLong(fixture.controller)==previousSequence,
                    "No new pixels acknowledged while complete disjoint demand loads behind retained raster");
            check(fixture.commitLines().size()==18,"Permanent sky cannot recycle only first three new tiles forever");
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            check(sequence.getLong(fixture.controller)>previousSequence && receipt.get(fixture.controller)!=previousReceipt,
                    "Only actual full-ready sky upload advances presentation ACK");
            awaitLeaseClaims(fixture,9,"Old historical image holds no obsolete Source owners");
        } finally { fixture.close(); }
    }

    private static void skyOnlyFullUnionAdmitsMissingLookaheadBeforeReacquiringVictim() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),13);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            Files.writeString(fixture.directory.resolve("authored-sky-until-y"),Integer.toString(fixture.tiles.get(12).y()));
            fixture.controller.neighborhood(fixture.tiles.subList(4,13));
            fixture.until(()->fixture.files.opens(1024)>0);
            check(sourceOwners(fixture)<=12,"Sky-blocked relief keeps source owner limit");
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            check(fixture.controller.snapshot().prepared()==9,"Useful lookahead pixels unblock victim reacquisition");
            awaitLeaseClaims(fixture,9,"Final useful frame retires old presentation pins");
        } finally { fixture.close(); }
    }

    private static void skyOnlyFeedbackPreservesPixelsWithoutSuppressingForegroundOrUserSky() throws Exception {
        var entered=new CompletableFuture<Void>();
        var release=new CompletableFuture<Void>();
        var fixture=new Fixture(new AuthoredTerrainSource.Files() {
            @Override FileTypes.FileStat stat(long id,Cancellation token) {
                if(id==1002) { entered.complete(null); release.join(); token.check(); }
                return super.stat(id,token);
            }
        },2);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.until(entered::isDone);
            var drops=new ConcurrentLinkedQueue<java.util.Map.Entry<String,WorldStreamTransport.Frame>>();
            fixture.controller.dropSeam=(path,frame)->drops.add(java.util.Map.entry(path,frame));
            Files.write(fixture.directory.resolve("authored-sky"),new byte[0]);
            fixture.camera(2);
            fixture.until(()->fixture.controller.snapshot().detail().equals("Loading terrain in viewing direction"));
            // The pending all-sky frame is presented (no freeze) and pins no resident.
            var sky=fixture.nextFrame();
            try {
                check(sky.skyOnly(),"The pending view is marked sky-only");
                check(WorldStreamBackground.matchesBasicSky(sky.pixels(),sky.width(),sky.height(),()->false),
                        "The presented frame is the live sky");
                check(fixture.controller.mayPresent(sky),"A sky frame replaces the previous view while terrain loads");
                check(fixture.controller.presented(sky),"The sky frame is accepted");
            } finally { fixture.controller.discard(sky); }
            var pins=WorldStreamSourceController.class.getDeclaredField("presented");pins.setAccessible(true);
            check(((List<?>)pins.get(fixture.controller)).isEmpty(),"Sky-only pixels pin no resident");
            check(fixture.controller.snapshot().detail().equals("Loading terrain in viewing direction"),
                    "Status says terrain is loading in the viewing direction");
            check(drops.stream().noneMatch(d->d.getKey().equals("sky")),"Sky frames are no longer dropped");
            var initial=fixture.controller.snapshot().seed().camera();
            fixture.controller.camera(new WorldStreamTransport.Camera(3,1,80,50,
                    new TerrainCamera(initial.x(),initial.y(),initial.z(),initial.yaw(),.3)));
            var up=fixture.nextFrame();
            try { check(fixture.controller.mayPresent(up),"User upward sky remains intentional while neighbor pending");
                fixture.controller.presented(up); } finally { fixture.controller.discard(up); }
            Files.delete(fixture.directory.resolve("authored-sky"));
            fixture.camera(4);
            var useful=fixture.nextFrame();
            try { check(fixture.controller.mayPresent(useful),"Foreground camera frames remain live during held source");
                fixture.controller.presented(useful); } finally { fixture.controller.discard(useful); }
            Files.write(fixture.directory.resolve("authored-sky"),new byte[0]);
            release.complete(null);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            awaitLeaseClaims(fixture,2,"All-wanted-ready sky does not suppress valid final receipt");
        } finally { release.complete(null); fixture.close(); }
    }

    private static void explicitUnsupportedSourceHasCleanNoOwnerDrain() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        var starts=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var custom=(com.vibeminer.concepts.world.BuildWorld)java.lang.reflect.Proxy.newProxyInstance(
                    com.vibeminer.concepts.world.BuildWorld.class.getClassLoader(),
                    new Class<?>[]{com.vibeminer.concepts.world.BuildWorld.class},
                    (proxy,method,args)-> {
                        if(method.getName().equals("terrainStreaming")) return java.util.Optional.empty();
                        throw new AssertionError("Unsupported custom source must perform no IO/close");
                    });
            var map=new WorldService.MapInfo(7,"Authored",null,0,1L);
            var inventory=fixture.world.inventory(map,Cancellation.NONE).join();
            int reads=fixture.files.totalOpens();
            fixture.controller=new WorldStreamSourceController(new WorldStreamSourceController.Request(
                    1,custom,map,inventory,fixture.tiles.getFirst(),null,WorldCurrentArea.Index.of(List.of())),
                    scope->{ starts.incrementAndGet(); return fixture.gpu.start(scope); },()->{},fixture.fx::add);
            fixture.until(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.UNAVAILABLE);
            fixture.controller.closeAsync().get(5,TimeUnit.SECONDS);
            check(starts.get()==0 && fixture.files.totalOpens()==reads,
                    "Explicit absent optional capability has no source IO or fake GPU owner");
        } finally { fixture.close(); }
    }

    private static void stallSummaryWithoutSessionIsBoundedAndNeverWaits() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        try {
            var custom=(com.vibeminer.concepts.world.BuildWorld)java.lang.reflect.Proxy.newProxyInstance(
                    com.vibeminer.concepts.world.BuildWorld.class.getClassLoader(),
                    new Class<?>[]{com.vibeminer.concepts.world.BuildWorld.class},
                    (proxy,method,args)-> {
                        if(method.getName().equals("terrainStreaming")) return java.util.Optional.empty();
                        throw new AssertionError("Unsupported custom source must perform no IO/close");
                    });
            var map=new WorldService.MapInfo(7,"Authored",null,0,1L);
            var inventory=fixture.world.inventory(map,Cancellation.NONE).join();
            fixture.controller=new WorldStreamSourceController(new WorldStreamSourceController.Request(
                    1,custom,map,inventory,fixture.tiles.getFirst(),null,WorldCurrentArea.Index.of(List.of())),
                    fixture.gpu::start,()->{},fixture.fx::add);
            fixture.until(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.UNAVAILABLE);
            var controller=fixture.controller;
            String summary=java.util.concurrent.CompletableFuture.supplyAsync(controller::stallSummary)
                    .get(2,TimeUnit.SECONDS);
            check(summary.startsWith("phase UNAVAILABLE (")
                            && summary.endsWith("; accepted frame sequence 0; presentation none; no session"),
                    "Controller stall summary without a session: "+summary);
            check(summary.length()<600,"Controller stall summary stays bounded");
            controller.closeAsync().get(5,TimeUnit.SECONDS);
        } finally { fixture.close(); }
    }

    private static void failedFxWakeStillCancelsHeldInitialSourceQualification() throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var fixture = new Fixture(new AuthoredTerrainSource.Files() {
            @Override @SuppressWarnings("try")
            FileTypes.FileStat stat(long id, Cancellation token) {
                if (armed.get() && id == 1) {
                    try (var registration = token.onCancel(() -> canceled.complete(null))) {
                        entered.complete(null);release.join();token.check();
                    }
                }
                return super.stat(id, token);
            }
        }, 1);
        try {
            fixture.start(fixture.gpu::start, () -> armed.set(true));
            entered.get(5, TimeUnit.SECONDS);
            var lock = WorldStreamSourceController.class.getDeclaredField("lock");lock.setAccessible(true);
            var events = WorldStreamSourceController.class.getDeclaredField("events");events.setAccessible(true);
            synchronized (lock.get(fixture.controller)) {
                events.set(fixture.controller, (WorldStreamSourceController.Events) () -> {
                    throw new IllegalStateException("Authored failing FX wake");
                });
            }
            var changed = WorldStreamSourceController.class.getDeclaredMethod("changed");changed.setAccessible(true);
            changed.invoke(fixture.controller);
            fixture.pump();
            var closing = fixture.controller.closeAsync();
            canceled.get(2, TimeUnit.SECONDS);
            check(!closing.isDone(), "Actual held qualification still owns close after cancellation request");
            release.complete(null);
            try { closing.get(5, TimeUnit.SECONDS); throw new AssertionError("FX failure must remain fatal"); }
            catch (java.util.concurrent.ExecutionException expected) { }
        } finally {
            release.complete(null);
            try { fixture.close(); }
            catch (java.util.concurrent.ExecutionException expected) {
                fixture.world.closeAsync().get(5, TimeUnit.SECONDS);
                fixture.pool.closeAsync().get(5, TimeUnit.SECONDS);
                fixture.tables.close();
                try (var paths = Files.walk(fixture.directory)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void fullPresentationUnionRecoversBackDemandWithoutAcceptingUnwantedPixels(boolean emptySubset) throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),13);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            fixture.controller.neighborhood(fixture.tiles.subList(4,13));
            fixture.until(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.WAITING_CAPACITY
                    && fixture.pool.stats().leaseClaims()==12);
            // A9 remains the visible/pinned table; B3 is current but never presented.
            var returning=new ArrayList<WorldFormats.Tile>();
            returning.add(fixture.tiles.get(1));
            if(!emptySubset) for(int i=0;i<9;i++) if(i!=1) returning.add(fixture.tiles.get(i));
            fixture.controller.neighborhood(returning);
            fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,
                    new TerrainCamera(-266,-800,110,Math.PI,-.8)));
            fixture.until(()->fixture.commitLines().size()>=14);
            check(fixture.commitLines().get(12).endsWith(emptySubset?" 0":" 6"),
                    "Relief commit contains only exact live wanted subset, including legitimate empty subset");
            check(sourceOwners(fixture)<=12,"Restoration never increases source owner cap");
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            check(fixture.controller.snapshot().prepared()==(emptySubset?1:9),
                    "Retired requested residents are freshly source-qualified/uploaded rather than skipped");
        } finally { fixture.close(); }
    }

    private static void uncoveredCameraFrameKeepsOldPixelsWhileNearestTileLoads() throws Exception {
        var entered=new CompletableFuture<Void>();
        var release=new CompletableFuture<Void>();
        var fixture=new Fixture(new AuthoredTerrainSource.Files() {
            @Override FileTypes.FileStat stat(long id,Cancellation token) {
                if(id==1002) { entered.complete(null); release.join(); token.check(); }
                return super.stat(id,token);
            }
        },2);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(List.of(fixture.tiles.get(1)));
            fixture.until(entered::isDone);
            fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,
                    new TerrainCamera(-266,-800,110,Math.PI,-.8)));
            var incomplete=fixture.nextFrame();
            try {
                check(incomplete.cameraRevision()==2,"Camera/frame pipeline keeps running during held source");
                check(!fixture.controller.mayPresent(incomplete),
                        "Receipt without nearest camera tile cannot overwrite last readable terrain with sky");
            } finally { fixture.controller.discard(incomplete); }
            release.complete(null);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            check(fixture.files.opens(1002)>0,"Actual nearest tile readiness unblocks presentation");
        } finally { release.complete(null); fixture.close(); }
    }

    private static void areaLookupCoversEveryResident() {
        // The camera area and picks scan every current resident's metadata (#173): the FAR 5 x 5
        // neighbourhood must stay inside the lookup's tile bound.
        check(WorldStreamTransport.MAX_RESIDENTS <= WorldCurrentArea.MAX_TILES,
                "Area lookup bound covers every resident: " + WorldStreamTransport.MAX_RESIDENTS
                        + " > " + WorldCurrentArea.MAX_TILES);
    }

    private static void pickedAreaUsesRetainedResidentMetadataWithoutBorrowing() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().area() != null);
            var view = fixture.controller.snapshot().seed().camera();
            var picked = fixture.controller.areaAt(view.x() + 1, view.y());
            var current = fixture.controller.snapshot().area();
            check(picked.equals(current), "Picked area matches the controller's camera-area lookup: "
                    + picked + " vs " + current);
            check(fixture.controller.areaAt(1e6, 1e6).reason() == WorldCurrentArea.Reason.NO_COVERAGE,
                    "Points off the residents have no coverage, never a nearest guess");
        } finally {
            fixture.close();
        }
        check(fixture.controller.areaAt(0, 0).reason() == WorldCurrentArea.Reason.NO_LOADED_METADATA,
                "A closing controller retains no area metadata for picks");
    }

    private static void backDemandDoesNotTreatPresentedOnlySourceAsLiveGpuResident() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 10);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(() -> fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared() == 9);
            fixture.controller.neighborhood(fixture.tiles.subList(1, 10));
            fixture.until(() -> fixture.commitLines().size() == 10
                    && fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.PRESENTING);
            // No replacement pixels have been acknowledged: outgoing tile0 is source-pinned
            // only for the last visible image. Its native resident was retired by COMMIT10.
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.until(() -> fixture.commitLines().size() == 11);
            check(sourceOwners(fixture) <= 12,
                    "Returning demand creates a separately charged live resident without dropping old pixels");
        } finally {
            fixture.close();
        }
    }

    private static void cameraPublicationSerializesInitialAndFxForwarding() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        var started=new CompletableFuture<Void>();
        var opening=new CompletableFuture<WorldStreamTransport.Session>();
        var fxDone=new CompletableFuture<Void>();
        Thread fx=null;
        try {
            fixture.start(scope->{
                fixture.gpu.start(scope);
                started.complete(null);
                return new WorldStreamSourceController.GpuOwner() {
                    public CompletableFuture<WorldStreamTransport.Session> session() { return opening.copy(); }
                    public CompletableFuture<Void> closeAsync() { return fixture.gpu.closeAsync(); }
                };
            });
            fixture.controller.camera(new WorldStreamTransport.Camera(1,1,80,50,new TerrainCamera(0,0,110,0,-.8)));
            started.get(5,TimeUnit.SECONDS);
            // Bounded by the transport's own startup deadline, which fails init clearly.
            fixture.gpu.session.initialized().get(60,TimeUnit.SECONDS);
            var field=WorldStreamTransport.Session.class.getDeclaredField("lock");
            field.setAccessible(true);
            Object parentGate=field.get(fixture.gpu.session);
            synchronized(parentGate) {
                opening.complete(fixture.gpu.session);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                boolean initialBlocked=false;
                while(!initialBlocked && System.nanoTime()<until) {
                    for(var entry:Thread.getAllStackTraces().entrySet()) {
                        if(!entry.getKey().getName().equals("world-source-controller")) continue;
                        for(var line:entry.getValue()) {
                            if(line.getClassName().equals(WorldStreamTransport.Session.class.getName())
                                    && line.getMethodName().equals("camera")) initialBlocked=true;
                        }
                    }
                    if(!initialBlocked) Thread.sleep(1);
                }
                check(initialBlocked,"Actual initial Session.camera is held at its metadata gate");
                fx=Thread.ofPlatform().daemon().name("authored-fx-camera").start(()->{
                    try {
                        fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,new TerrainCamera(1,0,110,0,-.8)));
                        fxDone.complete(null);
                    } catch(Throwable failure) { fxDone.completeExceptionally(failure); }
                });
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(fx.getState()!=Thread.State.BLOCKED && System.nanoTime()<until) Thread.sleep(1);
                check(fx.getState()==Thread.State.BLOCKED,"FX metadata update waits the same publication gate");
                var current=WorldStreamSourceController.class.getDeclaredField("camera");
                current.setAccessible(true);
                check(((WorldStreamTransport.Camera)current.get(fixture.controller)).revision()==1,
                        "FX revision2 cannot publish ahead of held initial revision1 forwarding");
            }
            fxDone.get(5,TimeUnit.SECONDS);
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            var frame=fixture.nextFrame();
            try {
                check(frame.cameraRevision()==2 && fixture.controller.mayPresent(frame),
                        "Actual accepted first frame belongs to latest monotonic camera");
                fixture.controller.presented(frame);
            } finally { fixture.controller.discard(frame); }
        } finally {
            opening.complete(fixture.gpu.session);
            if(fx!=null) fx.join(1000);
            fixture.close();
        }
    }

    private static void initialSeedHeldReplacementCameraAndPresentationDrain() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var files =
                new AuthoredTerrainSource.Files() {
                    @Override
                    @SuppressWarnings("try")
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1018) {
                            try (var registration = token.onCancel(() -> canceled.complete(null))) {
                                entered.complete(null);
                                release.join();
                                token.check();
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var fixture = new Fixture(files, 10);
        WorldStreamSourceController.Presentation held = null;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            check(
                    fixture.controller.snapshot().seed().camera() != null,
                    "First real source produces seed without prior camera");
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller.snapshot().prepared() == 9);
            check(
                    fixture.commitLines().size() == 9,
                    "Nine incrementally committed source-qualified tiles");
            fixture.controller.neighborhood(fixture.tiles.subList(1, 10));
            fixture.until(entered::isDone);
            check(
                    fixture.commitLines().size() == 9,
                    "Demand alone does not remove any outgoing current member");
            fixture.camera(2);
            held = fixture.nextFrame();
            check(
                    held.cameraRevision() == 2 && (held.pixels().get(0) & 255) == 9,
                    "Old complete table keeps camera frames while new root source is held");
            check(fixture.controller.mayPresent(held), "Pre-upload exact frame/source gate");
            release.complete(null);
            fixture.until(() -> fixture.commitLines().size() == 10);
            fixture.pump();
            check(
                    fixture.controller.mayPresent(held),
                    "New COMMITTED cannot invalidate an entered FX handshake");
            check(
                    fixture.controller.presented(held),
                    "Actual old upload ack remains valid through held newer commit");
            held = null;
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            awaitLeaseClaims(fixture, 9, "Outgoing base retires only after replacement pixels accepted");
            fixture.camera(3);
            held = fixture.nextFrame();
            var closing = fixture.controller.closeAsync();
            fixture.until(() -> fixture.gpu.physical != null && fixture.gpu.physical.isDone());
            check(
                    !closing.isDone(),
                    "Actual process drain does not discard a borrowed FX presentation");
            fixture.controller.discard(held);
            held = null;
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.pool.stats().leaseClaims() == 0,
                    "All unique source bases retired on close");
            check(!canceled.isDone(), "Useful held candidate was allowed to finish");
        } catch (Exception | Error failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            release.complete(null);
            if (held != null) fixture.controller.discard(held);
            fixture.close();
        }
    }

    private static void supersededHeldSourceCancelsBeforeStalePredicateAndKeepsSession()
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var fixture =
                new Fixture(
                        new AuthoredTerrainSource.Files() {
                            @Override
                            @SuppressWarnings("try")
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                if (id == 1002) {
                                    try (var registration =
                                            token.onCancel(() -> canceled.complete(null))) {
                                        entered.complete(null);
                                        release.join();
                                        token.check();
                                    }
                                }
                                return super.stat(id, token);
                            }
                        },
                        3);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(List.of(fixture.tiles.get(1)));
            fixture.until(entered::isDone);
            fixture.controller.neighborhood(List.of(fixture.tiles.get(2)));
            fixture.until(canceled::isDone);
            check(
                    fixture.files.opens(1004) == 0,
                    "Successor cannot start before actual canceled source work drains");
            release.complete(null);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    !fixture.gpu.session.completion().isDone(),
                    "Explicit stop-before-stale cancellation preserves live session");
            check(
                    fixture.files.opens(1002) == 0 && fixture.files.opens(1004) > 0,
                    "Canceled Root never opens and latest actual Root is qualified");
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    private static void missingNearestIsFiniteUnavailableWithoutFillingOutputUnion() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),13);
        fixture.files.sources.remove(1018L);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY
                    && fixture.controller.snapshot().prepared()==9);
            var wanted=new ArrayList<WorldFormats.Tile>(fixture.tiles.subList(9,13));
            wanted.addAll(fixture.tiles.subList(0,5));
            fixture.controller.neighborhood(wanted);
            fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,
                    new TerrainCamera(-266,-5066,110,Math.PI,-.8)));
            fixture.until(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.UNAVAILABLE
                    && fixture.controller.snapshot().detail().contains("Nearest terrain unavailable"));
            for(int i=0;i<30;i++) { fixture.pump(); Thread.sleep(2); }
            check(fixture.files.opens(1020)==0 && fixture.files.opens(1022)==0 &&fixture.files.opens(1024)==0,
                    "Known missing center does not fill twelve-owner union with unpresentable neighbors");
            awaitLeaseClaims(fixture,9,"Last readable table stays owned with finite unavailable state");
            fixture.controller.neighborhood(fixture.tiles.subList(0,9));
            fixture.camera(3);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
        } finally { fixture.close(); }
    }

    /**
     * Issue #365: the helper rejects every packet of one neighbour tile (REJECTED INVALID_PACKET,
     * through the real transport). The session keeps streaming: the nearest tile is presented,
     * the rejected tile is retried after a back-off on the controller clock, and after three
     * rejections it is unavailable with a visible reason and no further retries.
     */
    private static void helperRejectedTileRetriesWithBackoffThenBecomesUnavailable()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        Path reject = fixture.directory.resolve("authored-reject-tile");
        Path log = fixture.directory.resolve("authored-rejections.log");
        try {
            Files.writeString(reject, Integer.toString(fixture.tiles.get(1).y()));
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            int key = fixture.tiles.get(1).x() * 64 + fixture.tiles.get(1).y();
            for (int rejection = 1; rejection <= WorldStreamSourceController.MAX_HELPER_REJECTIONS;
                    rejection++) {
                int expected = rejection;
                fixture.until(() -> fixture.controller.helperRejections(key) == expected);
                if (rejection < WorldStreamSourceController.MAX_HELPER_REJECTIONS) {
                    // Before the back-off ends the tile is not offered again.
                    for (int i = 0; i < 20; i++) {
                        fixture.pump();
                        Thread.sleep(2);
                    }
                    check(lines(log) == rejection, "No retry before the back-off ends");
                    fixture.clock.addAndGet(500_000_000L << (rejection - 1));
                    fixture.controller.pulse();
                }
            }
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY
                            && fixture.controller.snapshot().detail().contains("1 unavailable"));
            check(
                    fixture.controller.snapshot().detail().contains("HELPER_REJECTED"),
                    "The rejected tile's reason is visible");
            fixture.clock.addAndGet(60_000_000_000L);
            for (int i = 0; i < 30; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(
                    lines(log) == WorldStreamSourceController.MAX_HELPER_REJECTIONS,
                    "An unavailable rejected tile is not retried while it stays wanted");
            check(
                    fixture.controller.snapshot().phase()
                            != WorldStreamSourceController.Phase.FAILED,
                    "A rejected tile never ends the World session");
        } finally {
            Files.deleteIfExists(reject);
            fixture.close();
        }
    }

    /**
     * Issue #365: the rejected tile leaves demand while its rejection is in flight, so the
     * controller cancels a reservation the helper has already rejected (the cancel is refused as
     * PACKET_REJECTED). That is terminal, not a session failure.
     */
    private static void helperRejectionCrossingACancelIsTerminalNotAFailure() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        Path reject = fixture.directory.resolve("authored-reject-tile");
        Path hold = fixture.directory.resolve("authored-hold-reject");
        Path held = fixture.directory.resolve("authored-held-reject");
        try {
            Files.writeString(reject, Integer.toString(fixture.tiles.get(1).y()));
            Files.writeString(hold, "hold");
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.until(() -> Files.exists(held));
            // The rejected tile leaves demand before its rejection arrives: the controller
            // cancels it, then the helper rejects the packet and refuses the stale cancel.
            fixture.controller.neighborhood(List.of(fixture.tiles.get(0)));
            for (int i = 0; i < 20; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            Files.delete(hold);
            fixture.until(
                    () -> lines(fixture.directory.resolve("authored-rejections.log")) == 1);
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.READY);
            for (int i = 0; i < 30; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(
                    fixture.controller.snapshot().phase()
                            != WorldStreamSourceController.Phase.FAILED,
                    "A rejection crossing a cancel never ends the World session");
            int key = fixture.tiles.get(1).x() * 64 + fixture.tiles.get(1).y();
            check(
                    fixture.controller.helperRejections(key) == 0,
                    "A tile that left demand keeps no rejection count");
        } finally {
            Files.deleteIfExists(hold);
            Files.deleteIfExists(reject);
            fixture.close();
        }
    }

    /**
     * Issue #365: the helper's rejection has already completed when the controller first sees
     * that the tile left demand. Nothing is canceled, the rejection is not counted, and no
     * UNAVAILABLE status is published for terrain nobody wants.
     */
    private static void helperRejectionOfATileOutOfDemandIsNotCounted() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        Path reject = fixture.directory.resolve("authored-reject-tile");
        Path hold = fixture.directory.resolve("authored-hold-reject");
        Path held = fixture.directory.resolve("authored-held-reject");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            Files.writeString(reject, Integer.toString(fixture.tiles.get(1).y()));
            Files.writeString(hold, "hold");
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.until(() -> Files.exists(held));
            // The step that handles the completed rejection must be the first to see the tile gone.
            // Hold the controller's single worker so no step can run while the rejection completes
            // and the demand changes: a step already running at completion (one kicked by the
            // rejection's own capacity change, say) would otherwise read the old demand first.
            var workerField = WorldStreamSourceController.class.getDeclaredField("worker");
            workerField.setAccessible(true);
            var entered = new java.util.concurrent.CountDownLatch(1);
            ((java.util.concurrent.ExecutorService) workerField.get(fixture.controller)).execute(() -> {
                entered.countDown();
                try {
                    gate.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            check(entered.await(5, TimeUnit.SECONDS), "Controller worker held");
            Files.delete(hold);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!fixture.controller.pendingRejectedByHelper() && System.nanoTime() < until)
                Thread.sleep(2);
            check(fixture.controller.pendingRejectedByHelper(), "The rejection completed while held");
            fixture.controller.neighborhood(List.of(fixture.tiles.get(0)));
            gate.countDown();
            fixture.until(() -> fixture.controller.rejectionsOutOfDemand() == 1);
            int key = fixture.tiles.get(1).x() * 64 + fixture.tiles.get(1).y();
            check(fixture.controller.helperRejections(key) == 0,
                    "A rejection for a tile out of demand is not counted");
            fixture.untilPresented(
                    () -> fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.READY);
            check(
                    !fixture.controller.snapshot().detail().contains("HELPER_REJECTED"),
                    "No unavailable status for a tile out of demand");
        } finally {
            gate.countDown();
            Files.deleteIfExists(hold);
            Files.deleteIfExists(reject);
            fixture.close();
        }
    }

    /**
     * Issue #365: the nearest tile is the one the helper keeps rejecting. Its neighbour still
     * streams and commits, and the status names HELPER_REJECTED.
     */
    private static void rejectedNearestTileDoesNotStallItsNeighbours() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 2);
        Path reject = fixture.directory.resolve("authored-reject-tile");
        Path log = fixture.directory.resolve("authored-rejections.log");
        try {
            Files.writeString(reject, Integer.toString(fixture.tiles.get(0).y()));
            fixture.start();
            // No camera yet: the first staged tile seeds it, and the nearest never stages.
            fixture.controller.neighborhood(fixture.tiles);
            int key = fixture.tiles.get(0).x() * 64 + fixture.tiles.get(0).y();
            // With the controller clock frozen, the nearest tile waits out its first back-off
            // while its neighbour is prepared and staged; that staged tile seeds the camera.
            fixture.until(() -> fixture.controller.helperRejections(key) >= 1);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            // Only a camera lets the staged neighbour commit and free its upload credit, so the
            // nearest tile's retries come after it: advance the clock through its back-offs
            // until it is given up.
            fixture.camera(1);
            fixture.until(
                    () -> {
                        fixture.clock.addAndGet(250_000_000L);
                        return fixture.controller.helperRejections(key)
                                >= WorldStreamSourceController.MAX_HELPER_REJECTIONS;
                    });
            fixture.untilPresented(
                    () -> fixture.commitLines().stream()
                                    .anyMatch(line -> !line.endsWith(" 0"))
                            && fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    fixture.controller.snapshot().detail().contains("HELPER_REJECTED"),
                    "The rejected nearest tile's reason is visible: "
                            + fixture.controller.snapshot().detail());
            check(lines(log) == WorldStreamSourceController.MAX_HELPER_REJECTIONS,
                    "The nearest tile is given up after its rejections");
            check(
                    fixture.controller.snapshot().phase()
                            != WorldStreamSourceController.Phase.FAILED,
                    "A rejected nearest tile never ends the World session");
        } finally {
            Files.deleteIfExists(reject);
            fixture.close();
        }
    }

    private static int lines(Path log) {
        try {
            return Files.exists(log) ? Files.readAllLines(log).size() : 0;
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static void missingRootIsFinitePerDemandAndDoesNotInvalidateCurrentScope()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 3);
        fixture.files.sources.remove(1002L);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller
                                            .snapshot()
                                            .detail()
                                            .contains("1 unavailable"));
            int probes = fixture.files.stats.get(1002L).get();
            for (int i = 0; i < 30; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(
                    fixture.files.stats.get(1002L).get() == probes,
                    "Missing Root has finite per-demand outcome, no retry loop");
            check(
                    fixture.controller.snapshot().detail().contains("SOURCE_INPUT"),
                    "Finite actual source-origin reason remains visible");
            check(
                    fixture.pool.stats().leaseClaims() == 2,
                    "Missing geometry never becomes height fallback or drops valid current tiles");
            fixture.camera(2);
            var frame = fixture.nextFrame();
            try {
                check(
                        fixture.controller.mayPresent(frame),
                        "Missing Root leaves existing Map/WDT scope valid");
            } finally {
                fixture.controller.discard(frame);
            }
        } finally {
            fixture.close();
        }
    }

    private static void heldPresentationBackpressuresTwelveUniqueOwnersAndKeepsFinalFrame()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller.snapshot().prepared() == 9);
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            // Deliberately leave new viewport pixels unaccepted while source/child keep
            // progressing.
            fixture.until(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.WAITING_CAPACITY
                                    && fixture.pool.stats().leaseClaims() == 12);
            check(
                    fixture.files.opens(1024) == 0,
                    "No thirteenth construction owner while old pixels remain presented");
            check(
                    fixture.commitLines().size() == 12,
                    "Current and presented aliases share exactly twelve unique source bases");
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    fixture.files.opens(1024) > 0,
                    "Accepted current pixels unblock remaining actual source acquisition");
            awaitLeaseClaims(fixture, 9,
                    "Final accepted table retires outgoing sources without a new camera event");
        } finally {
            fixture.close();
        }
    }

    /**
     * #381: the helper presents one interval after COMMITTED and its report can reach the
     * controller before the worker records the receipt. With a still camera it is never repeated,
     * so it must still be accepted once the receipt is recorded. The report is sent exactly once,
     * before the first commit exists.
     */
    private static void nativeReportBeforeTheReceiptIsStillAccepted() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 9);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            check(fixture.controller.currentContentRevision() < 0, "No receipt yet");
            // Content revisions count commits from 1; the first commit is content 1.
            fixture.controller.presentedNatively(1, 1);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 1));
            fixture.until(() -> fixture.controller.currentContentRevision() == 1
                    && fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY);
            check(fixture.controller.snapshot().prepared() == 1, "The one tile is presented natively");
        } finally {
            fixture.close();
        }
    }

    /**
     * A body source is asked once per session, after the first commit, and the body goes to the
     * child once; a new session (a restart) asks and sends it again.
     */
    private static void everySessionSendsTheBodyOnceAfterItsFirstCommit() throws Exception {
        for (int session = 0; session < 2; session++) {
            var fixture = new Fixture(new AuthoredTerrainSource.Files(), 9);
            var asked = new java.util.concurrent.atomic.AtomicInteger();
            var outcome = new java.util.concurrent.CompletableFuture<byte[]>();
            try {
                fixture.start();
                fixture.controller.body(() -> {
                    asked.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(new byte[64]);
                });
                fixture.until(() -> fixture.controller.snapshot().seed() != null);
                fixture.camera(1);
                fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
                fixture.untilPresented(() -> fixture.controller.snapshot().phase()
                        == WorldStreamSourceController.Phase.READY);
                var log = fixture.gpu.scratch.getParent().resolve("authored-commands.log");
                fixture.until(() -> {
                    try {
                        return Files.readString(log).contains("BodyModel[");
                    } catch (java.io.IOException e) {
                        return false;
                    }
                });
                // A second hand-over in the same session (walk entered again) is not a second send.
                fixture.controller.body(() -> {
                    asked.incrementAndGet();
                    return outcome;
                });
                outcome.complete(new byte[64]);
                for (int i = 0; i < 20; i++) fixture.pump();
                long sent = Files.readString(log).lines().filter(line -> line.startsWith("BodyModel[")).count();
                check(sent == 1, "Session " + session + " sent the body once: " + sent);
                check(asked.get() == 1, "The body was asked for once per session: " + asked.get());
            } finally {
                outcome.complete(new byte[64]);
                fixture.close();
            }
        }
    }

    /**
     * #381: a held camera never sends a new revision, and the helper announces the same camera
     * with each new content. Every commit is still accepted, so the stream returns to READY (which
     * owes no frame) instead of staying PRESENTING until the stall watchdog falls back.
     */
    private static void nativePresentationWithAStillCameraAcceptsEachCommit() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        var presentedCameras = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        try {
            fixture.start();
            fixture.controller.onPresented(presentedCameras::add);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilNativePresented(1, () -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY && fixture.controller.snapshot().prepared() == 9);
            long first = fixture.controller.currentContentRevision();
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            fixture.untilNativePresented(1, () -> fixture.controller.snapshot().phase()
                    == WorldStreamSourceController.Phase.READY
                    && fixture.controller.currentContentRevision() > first
                    && fixture.controller.snapshot().prepared() == 9);
            fixture.until(() -> !presentedCameras.isEmpty());
            check(presentedCameras.stream().allMatch(camera -> camera == 1),
                    "Only the held camera was presented: " + presentedCameras);
        } finally {
            fixture.close();
        }
    }

    /**
     * #381, window mode: no mapping frames are taken; PRESENTED for the current content is the
     * acceptance. READY is reached, and with thirteen owners capacity waits only until the window
     * presents the current content, as with accepted readback pixels.
     */
    private static void nativePresentationAcceptsCurrentContentAndRelievesTwelveOwners()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        var presentedCameras = new java.util.ArrayList<Long>();
        try {
            fixture.start();
            fixture.controller.onPresented(presentedCameras::add);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilNativePresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller.snapshot().prepared() == 9);
            check(!presentedCameras.isEmpty(), "Presented cameras reach the FX listener");
            fixture.controller.presentedNatively(1, 999_999);
            check(fixture.controller.snapshot().phase() == WorldStreamSourceController.Phase.READY,
                    "Presenting other content accepts nothing");
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            fixture.until(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.WAITING_CAPACITY
                                    && fixture.pool.stats().leaseClaims() == 12);
            check(fixture.files.opens(1024) == 0,
                    "No thirteenth owner while the window still shows the old content");
            fixture.untilNativePresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(fixture.files.opens(1024) > 0,
                    "The window presenting current content unblocks the remaining sources");
            check(fixture.pool.stats().leaseClaims() == 9,
                    "The presented table retires outgoing sources");
        } finally {
            fixture.close();
        }
    }

    private static void sameResidentCameraFrameDoesNotRetryMaterialPool() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),2);
        AutoCloseable held=null;
        try {
            fixture.start();fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            held=AuthoredTerrainPoolPressure.holdAvailable(fixture.pool);
            fixture.controller.neighborhood(fixture.tiles);
            var blocked=WorldStreamSourceController.class.getDeclaredField("blockedOnMaterialPool");blocked.setAccessible(true);
            fixture.until(() -> {try{return blocked.getBoolean(fixture.controller)
                    && fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.WAITING_CAPACITY;}
                    catch(IllegalAccessException e){throw new AssertionError(e);}});
            var ticket=fixture.gpu.session.getClass().getDeclaredField("tile");ticket.setAccessible(true);
            Object refused=ticket.get(fixture.gpu.session);
            var sequence=WorldStreamSourceController.class.getDeclaredField("acceptedSequence");sequence.setAccessible(true);
            long before=sequence.getLong(fixture.controller);
            fixture.camera(2);
            fixture.untilPresented(() -> {try{return sequence.getLong(fixture.controller)>before;}
                    catch(IllegalAccessException e){throw new AssertionError(e);}});
            fixture.pump();
            var worker=WorldStreamSourceController.class.getDeclaredField("worker");worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService)worker.get(fixture.controller)).submit(() -> {}).get(3,TimeUnit.SECONDS);
            check(ticket.get(fixture.gpu.session)==refused,
                    "Accepted same-resident camera pixels release no pool owner and must not reopen source pressure");
        } finally {if(held!=null)held.close();fixture.close();}
    }

    private static void transportFrameSignalQueuesPresentationWithoutAnyPulse() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            var worker=WorldStreamSourceController.class.getDeclaredField("worker");worker.setAccessible(true);
            var actor=(java.util.concurrent.ExecutorService)worker.get(fixture.controller);
            // Drain every earlier frame; discard() kicks, so fence the actor after each one.
            for(int quiet=0;quiet<3;) {
                actor.submit(() -> {}).get(3,TimeUnit.SECONDS);
                var stale=fixture.controller.takePresentation();
                if(stale.isPresent()) { fixture.controller.discard(stale.get()); quiet=0; }
                else { quiet++; Thread.sleep(50); }
            }
            // Bypass the controller: camera() and pulse() would kick a full step. Only the
            // transport's frame listener can now bring this frame to the controller.
            var seed=fixture.controller.snapshot().seed().camera();
            fixture.gpu.session.camera(new WorldStreamTransport.Camera(1000,1,80,50,
                    new TerrainCamera(seed.x()+5,seed.y(),seed.z(),seed.yaw(),seed.pitch())));
            WorldStreamSourceController.Presentation frame=null;
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(frame==null && System.nanoTime()<until) {
                frame=fixture.controller.takePresentation().orElse(null);
                if(frame==null) Thread.sleep(1);
            }
            check(frame!=null,"A published frame reaches the controller without a pulse");
            try {
                check(frame.cameraRevision()==1000,"The signaled frame is the new camera's");
            } finally { fixture.controller.discard(frame); }
        } finally { fixture.close(); }
    }

    private static boolean released(WorldStreamTransport.Frame frame) {
        try { frame.pixels(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    private static void awaitDrop(Fixture fixture,
            ConcurrentLinkedQueue<java.util.Map.Entry<String,WorldStreamTransport.Frame>> drops,
            String path) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(drops.stream().noneMatch(d->d.getKey().equals(path)) && System.nanoTime()<until)
            Thread.sleep(2);
        var found=drops.stream().filter(d->d.getKey().equals(path)).findFirst();
        check(found.isPresent(),"Drop path "+path+" reached; seen "+drops.stream().map(java.util.Map.Entry::getKey).toList());
        check(released(found.get().getValue()),"Drop path "+path+" released its pixels");
    }

    private static void awaitQueued(Fixture fixture) throws Exception {
        var queued=WorldStreamSourceController.class.getDeclaredField("queued");queued.setAccessible(true);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(queued.get(fixture.controller)==null && System.nanoTime()<until) Thread.sleep(1);
        check(queued.get(fixture.controller)!=null,"A frame is queued and not taken");
    }

    /** Each path that drops a frame unpresented releases it: discard, content, retire or close. */
    private static void everyControllerDropPathReleasesItsFrame(boolean retire) throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            var drops=new ConcurrentLinkedQueue<java.util.Map.Entry<String,WorldStreamTransport.Frame>>();
            fixture.controller.dropSeam=(path,frame)->drops.add(java.util.Map.entry(path,frame));
            fixture.camera(2);
            var shown=fixture.nextFrame();
            fixture.controller.discard(shown);
            awaitDrop(fixture,drops,"discard");
            var seed=fixture.controller.snapshot().seed().camera();
            if(!retire) {
                // Content mismatch: the worker's receipt no longer matches the offered frame.
                var worker=WorldStreamSourceController.class.getDeclaredField("worker");worker.setAccessible(true);
                var actor=(java.util.concurrent.ExecutorService)worker.get(fixture.controller);
                var receipt=WorldStreamSourceController.class.getDeclaredField("currentReceipt");receipt.setAccessible(true);
                var constructor=WorldStreamTransport.CommitReceipt.class.getDeclaredConstructor(long.class,long.class,List.class);
                constructor.setAccessible(true);
                var fake=constructor.newInstance(Long.MAX_VALUE,1L,List.of());
                var real=new Object[1];
                actor.submit(() -> { try { real[0]=receipt.get(fixture.controller); receipt.set(fixture.controller,fake); }
                        catch(IllegalAccessException e) { throw new AssertionError(e); } }).get(3,TimeUnit.SECONDS);
                fixture.gpu.session.camera(new WorldStreamTransport.Camera(1000,1,80,50,
                        new TerrainCamera(seed.x()+7,seed.y(),seed.z(),seed.yaw(),seed.pitch())));
                try { awaitDrop(fixture,drops,"content"); }
                finally {
                    actor.submit(() -> { try { receipt.set(fixture.controller,real[0]); }
                            catch(IllegalAccessException e) { throw new AssertionError(e); } }).get(3,TimeUnit.SECONDS);
                }
                // Close with a queued, untaken frame.
                fixture.camera(1001);
                awaitQueued(fixture);
                fixture.controller.closeAsync();
                awaitDrop(fixture,drops,"close");
            } else {
                // Retirement after a session failure, with a queued, untaken frame.
                fixture.camera(2001);
                awaitQueued(fixture);
                var owner=WorldStreamTransport.Session.class.getDeclaredField("owner");owner.setAccessible(true);
                var handle=owner.get(fixture.gpu.session);
                var close=handle.getClass().getDeclaredMethod("closeAsync");close.setAccessible(true);
                close.invoke(handle);
                awaitDrop(fixture,drops,"retire");
                // The authored session failure is the expected end of this controller.
                var drained=fixture.controller.closeAsync();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(!drained.isDone() && System.nanoTime()<until) { fixture.controller.pulse(); Thread.sleep(2); }
                check(drained.isCompletedExceptionally(),"Failed session drains exceptionally");
                fixture.controller=null;
            }
        } finally { fixture.close(); }
    }

    /**
     * Source owners now: atlases with an open lease plus slots reserved for a preparation (#498).
     * Bounds use this, not leaseClaims: while a tile prepares, its texture batch and the retained
     * copy it hands over both hold a claim on the same atlas, so one owner counts twice.
     */
    private static int sourceOwners(Fixture fixture) {
        var stats = fixture.pool.stats();
        return stats.claimedEntries() + stats.reservedEntries();
    }

    /**
     * Waits for the pool's live lease count (claims held now, not a running total) to settle.
     * Pins are retired on the worker step after an accepted presentation, which can follow the
     * READY publication, so a single read right after READY can still see old pins (#498).
     */
    private static void awaitLeaseClaims(Fixture fixture, int expected, String message) throws Exception {
        try {
            fixture.until(() -> fixture.pool.stats().leaseClaims()==expected);
        } catch(AssertionError timeout) {
            throw new AssertionError(message+": leaseClaims="+fixture.pool.stats().leaseClaims(), timeout);
        }
    }

    private static Object receiptOf(WorldStreamSourceController.Presentation presentation) throws Exception {
        var field=WorldStreamSourceController.Presentation.class.getDeclaredField("frame");field.setAccessible(true);
        return ((WorldStreamTransport.Frame)field.get(presentation)).content();
    }

    private static Object field(WorldStreamSourceController controller, String name) throws Exception {
        var field=WorldStreamSourceController.class.getDeclaredField(name);field.setAccessible(true);
        return field.get(controller);
    }

    /** A queued, untaken frame of the old receipt is released when a commit lands. */
    private static void commitReleasesAQueuedFrameOfTheOldReceipt() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),2);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            var drops=new ConcurrentLinkedQueue<java.util.Map.Entry<String,WorldStreamTransport.Frame>>();
            fixture.controller.dropSeam=(path,frame)->drops.add(java.util.Map.entry(path,frame));
            fixture.camera(2);
            awaitQueued(fixture);
            Object oldReceipt=field(fixture.controller,"currentReceipt");
            // The second tile commits while the frame stays queued (pump never takes it).
            fixture.controller.neighborhood(fixture.tiles);
            fixture.until(() -> drops.stream().anyMatch(d->d.getKey().equals("superseded")));
            awaitDrop(fixture,drops,"superseded");
            var next=fixture.nextFrame();
            try {
                check(receiptOf(next)!=oldReceipt && receiptOf(next)==field(fixture.controller,"currentReceipt"),
                        "The next frame carries the commit's new receipt");
            } finally { fixture.controller.discard(next); }
        } finally { fixture.close(); }
    }

    /** Terrain shown, then a pending sky frame that cannot be presented: old raster, no relabel. */
    private static void refusedPendingSkyKeepsTheOldRasterBelowTwelveOwners() throws Exception {
        var entered=new CompletableFuture<Void>();
        var release=new CompletableFuture<Void>();
        var fixture=new Fixture(new AuthoredTerrainSource.Files() {
            @Override FileTypes.FileStat stat(long id,Cancellation token) {
                if(id==1002) { entered.complete(null); release.join(); token.check(); }
                return super.stat(id,token);
            }
        },2);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            long accepted=(long)field(fixture.controller,"acceptedSequence");
            // The nearest wanted tile is now the held one, so no frame can be presented yet.
            fixture.controller.neighborhood(List.of(fixture.tiles.get(1),fixture.tiles.get(0)));
            fixture.until(entered::isDone);
            Files.write(fixture.directory.resolve("authored-sky"),new byte[0]);
            fixture.camera(2);
            var sky=fixture.nextFrame();
            try {
                check(sky.skyOnly(),"Pending sky frame");
                check(!fixture.controller.mayPresent(sky),"Nearest tile not current: the sky frame is refused");
                check(!fixture.controller.presented(sky),"Not accepted");
            } finally { fixture.controller.discard(sky); }
            check((long)field(fixture.controller,"acceptedSequence")==accepted,"The old raster stays");
            check(!((List<?>)field(fixture.controller,"presented")).isEmpty(),"Below twelve owners its pins stay");
            check(!(boolean)field(fixture.controller,"historicalRaster"),"No historical raster below twelve owners");
            check(!fixture.controller.snapshot().detail().contains("previous view"),
                    "No 'previous view' label without a historical raster");
        } finally { release.complete(null); fixture.close(); }
    }

    /**
     * A retained view labelled "showing previous view" (the twelve-owner historical raster) is
     * relabelled when live sky is accepted, and accepted terrain clears the loading label.
     */
    private static void acceptedSkyRelabelsTheRetainedViewAndTerrainClearsTheLabel() throws Exception {
        var entered=new CompletableFuture<Void>();
        var release=new CompletableFuture<Void>();
        var fixture=new Fixture(new AuthoredTerrainSource.Files() {
            @Override FileTypes.FileStat stat(long id,Cancellation token) {
                if(id==1002) { entered.complete(null); release.join(); token.check(); }
                return super.stat(id,token);
            }
        },2);
        try {
            fixture.start();
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(()->fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.until(entered::isDone);
            // Stand in for the twelve-owner retirement on the worker: historical raster + label.
            var worker=(java.util.concurrent.ExecutorService)field(fixture.controller,"worker");
            var historical=WorldStreamSourceController.class.getDeclaredField("historicalRaster");historical.setAccessible(true);
            var publish=WorldStreamSourceController.class.getDeclaredMethod("publish",WorldStreamSourceController.Phase.class,String.class);
            publish.setAccessible(true);
            worker.submit(() -> { try { historical.setBoolean(fixture.controller,true);
                    publish.invoke(fixture.controller,WorldStreamSourceController.Phase.PREPARING,"Loading terrain in viewing direction"); }
                    catch(ReflectiveOperationException e) { throw new AssertionError(e); } }).get(3,TimeUnit.SECONDS);
            check(fixture.controller.snapshot().detail().equals("Loading terrain; showing previous view"),
                    "Historical raster label");
            Files.write(fixture.directory.resolve("authored-sky"),new byte[0]);
            fixture.camera(2);
            var sky=fixture.nextFrame();
            try {
                check(sky.skyOnly() && fixture.controller.mayPresent(sky) && fixture.controller.presented(sky),
                        "Live sky accepted");
                check(fixture.controller.snapshot().detail().equals("Loading terrain in viewing direction"),
                        "Accepted sky relabels the retained view: "+fixture.controller.snapshot().detail());
            } finally { fixture.controller.discard(sky); }
            Files.delete(fixture.directory.resolve("authored-sky"));
            fixture.camera(3);
            var terrain=fixture.nextFrame();
            try {
                check(!terrain.skyOnly() && fixture.controller.mayPresent(terrain) && fixture.controller.presented(terrain),
                        "Terrain accepted");
                String detail=fixture.controller.snapshot().detail();
                check(!detail.equals("Loading terrain in viewing direction") && !detail.contains("previous view"),
                        "Accepted terrain clears the loading label: "+detail);
            } finally { fixture.controller.discard(terrain); }
        } finally { release.complete(null); fixture.close(); }
    }

    private static void steadyCameraFramesReuseAFewPixelArrays() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed()!=null);
            fixture.camera(1);
            fixture.untilPresented(() -> fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.READY);
            var sequence=WorldStreamSourceController.class.getDeclaredField("acceptedSequence");sequence.setAccessible(true);
            for(long revision=2;revision<=40;revision++) {
                long before=sequence.getLong(fixture.controller);
                fixture.camera(revision);
                fixture.untilPresented(() -> {try{return sequence.getLong(fixture.controller)>before;}
                        catch(IllegalAccessException e){throw new AssertionError(e);}});
            }
            long allocations=com.vibeminer.rendering.bgfx.AuthoredFramePool.allocations(fixture.gpu.session);
            // At most three frames are live at once: the read, the transport's latest and the
            // presentation. Every presented, discarded or superseded frame returns its array.
            check(allocations>=1 && allocations<=3,
                    "40 presented camera frames reuse pooled pixel arrays; allocations="+allocations);
        } finally { fixture.close(); }
    }

    @SuppressWarnings("try")
    private static void poolReleaseRetriesWithoutDemandOrParentCapacityChange() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var held = AuthoredTerrainPoolPressure.hold(fixture.pool);
        try {
            Files.writeString(fixture.directory.resolve("authored-hold-unused-credit"), "hold");
            fixture.start();
            fixture.until(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.WAITING_CAPACITY);
            fixture.until(() -> Files.exists(fixture.directory.resolve("authored-held-unused-credit")));
            var poolBlockedField=WorldStreamSourceController.class.getDeclaredField("blockedOnMaterialPool");
            poolBlockedField.setAccessible(true);
            fixture.until(() -> {
                try { return fixture.controller.snapshot().phase()==WorldStreamSourceController.Phase.WAITING_CAPACITY
                        && poolBlockedField.getBoolean(fixture.controller); }
                catch(IllegalAccessException failure) { throw new AssertionError(failure); }
            });
            check(poolBlockedField.getBoolean(fixture.controller),
                    "Actual clean source refusal identifies MATERIAL_POOL, not generic parent pressure");
            var reservationField=fixture.gpu.session.getClass().getDeclaredField("tile");
            reservationField.setAccessible(true);
            Object refusedReservation=reservationField.get(fixture.gpu.session);
            long beforeCredit=fixture.gpu.session.capacityRevision();
            Files.delete(fixture.directory.resolve("authored-hold-unused-credit"));
            fixture.until(() -> fixture.gpu.session.capacityRevision()>beforeCredit);
            // Process the late CREDIT causally, then fence the actual controller actor.
            fixture.pump();
            var workerField=WorldStreamSourceController.class.getDeclaredField("worker");
            workerField.setAccessible(true);
            ((java.util.concurrent.ExecutorService)workerField.get(fixture.controller))
                    .submit(() -> {}).get(3,TimeUnit.SECONDS);
            check(reservationField.get(fixture.gpu.session)==refusedReservation,
                    "Refusal's own late parent credit must not churn another material attempt at frozen clock");
            long capacity = fixture.gpu.session.capacityRevision();
            held.close();
            check(
                    fixture.gpu.session.capacityRevision() == capacity,
                    "Pool release itself sends no parent capacity event");
            fixture.pump();
            ((java.util.concurrent.ExecutorService)workerField.get(fixture.controller))
                    .submit(() -> {}).get(3,TimeUnit.SECONDS);
            check(reservationField.get(fixture.gpu.session)==refusedReservation,
                    "Settled parent work and released pool alone do not bypass the frozen retry clock");
            fixture.clock.set(600_000_000L);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            awaitLeaseClaims(fixture, 1, "Bounded retry recovers actual source material without new demand");
        } finally {
            Files.deleteIfExists(fixture.directory.resolve("authored-hold-unused-credit"));
            held.close();
            fixture.close();
        }
    }

    private static void closeDuringActualInitialWdtQualificationIsCleanAndWaitsSourceDrain()
            throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var fixture =
                new Fixture(
                        new AuthoredTerrainSource.Files() {
                            @Override
                            @SuppressWarnings("try")
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                if (armed.get() && id == 1) {
                                    try (var registration =
                                            token.onCancel(() -> canceled.complete(null))) {
                                        entered.complete(null);
                                        release.join();
                                        token.check();
                                    }
                                }
                                return super.stat(id, token);
                            }
                        },
                        1);
        try {
            fixture.start(
                    scope -> {
                        starts.incrementAndGet();
                        return fixture.gpu.start(scope);
                    },
                    () -> armed.set(true));
            entered.get(5, TimeUnit.SECONDS);
            var closing = fixture.controller.closeAsync();
            canceled.get(5, TimeUnit.SECONDS);
            check(
                    !closing.isDone(),
                    "Initial Map/WDT proof must actually drain before close succeeds");
            check(starts.get() == 0, "No queued GPU owner while initial source scope is held");
            release.complete(null);
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.CLOSED,
                    "Expected early-hide qualification cancellation is clean close, never"
                        + " quarantine");
            check(
                    fixture.pool.stats().leaseClaims() == 0,
                    "Early hide has no issued geometry/material owner");
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    private static void queuedGpuOwnerLateCloseIsPartOfControllerDrain() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var physical = new CompletableFuture<Void>();
        try {
            fixture.start(
                    scope -> {
                        entered.complete(null);
                        release.join();
                        return new WorldStreamSourceController.GpuOwner() {
                            public CompletableFuture<WorldStreamTransport.Session> session() {
                                return CompletableFuture.failedFuture(
                                        new java.util.concurrent.CancellationException());
                            }

                            public CompletableFuture<Void> closeAsync() {
                                return physical.copy();
                            }
                        };
                    });
            entered.get(5, TimeUnit.SECONDS);
            var closing = fixture.controller.closeAsync();
            check(!closing.isDone(), "In-flight GPU start is still an owner");
            release.complete(null);
            for (int i = 0; i < 20; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(!closing.isDone(), "Failed/no-session opening is not actual queued-owner drain");
            physical.complete(null);
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.files.opens(2) == 0,
                    "No tile acquisition before actual session initialization");
        } finally {
            release.complete(null);
            physical.complete(null);
            fixture.close();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @SuppressWarnings("try")
    private static final class Fixture implements AutoCloseable {
        final AuthoredTerrainSource.Files files;
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final AuthoredNativeMap tables;
        final WorldService world;
        final List<WorldFormats.Tile> tiles;
        final Path directory;
        final ConcurrentLinkedQueue<Runnable> fx = new ConcurrentLinkedQueue<>();
        final AtomicLong clock = new AtomicLong();
        final Gpu gpu = new Gpu();
        volatile boolean exteriors;
        // The legacy 3x3 bound keeps these capacity scenarios small; see fiveByFive below.
        int residents = 9;
        WorldStreamSourceController controller;

        Fixture(AuthoredTerrainSource.Files files, int count) throws Exception {
            this.files = files;
            tiles = install(files, count);
            tables = new AuthoredNativeMap(files.files());
            world = new WorldService(tables.tables(), files.files(), pool);
            directory = Files.createTempDirectory("world-controller-");
        }

        void start() {
            start(gpu::start);
        }

        void start(WorldStreamSourceController.GpuStarter starter) {
            start(starter, () -> {});
        }

        void start(WorldStreamSourceController.GpuStarter starter, Runnable beforeController) {
            start(starter, beforeController, (source, scope, tile, cancellation, formats, creatureBytes) -> WorldStreamSourceBridge.prepareExterior(source, scope, tile, cancellation));
        }

        void start(WorldStreamSourceController.GpuStarter starter, Runnable beforeController,
                WorldStreamSourceBridge.ExteriorPreparer preparer) {
            var map = new WorldService.MapInfo(7, "Authored", null, 0, 1L);
            var inventory = world.inventory(map, Cancellation.NONE).join();
            beforeController.run();
            controller =
                    new WorldStreamSourceController(
                            new WorldStreamSourceController.Request(
                                    1,
                                    world,
                                    map,
                                    inventory,
                                    tiles.getFirst(),
                                    null,
                                    WorldCurrentArea.Index.of(List.of())),
                            starter,
                            () -> {},
                            fx::add,
                            clock::get,
                            preparer,
                            residents);
        }

        void camera(long revision) {
            var seed = controller.snapshot().seed();
            var initial = seed.camera();
            controller.camera(
                    new WorldStreamTransport.Camera(
                            revision,
                            1,
                            80,
                            50,
                            new TerrainCamera(
                                    initial.x() + revision,
                                    initial.y(),
                                    initial.z(),
                                    initial.yaw(),
                                    initial.pitch())));
        }

        void pump() {
            controller.pulse();
            Runnable task;
            while ((task = fx.poll()) != null) task.run();
            if (controller.snapshot().phase() == WorldStreamSourceController.Phase.FAILED)
                throw new AssertionError(controller.snapshot().detail());
        }

        void until(BooleanSupplier condition) throws Exception {
            // A stall is 12s with no observable controller progress (any snapshot change), not
            // 12s of wall clock: slow, loaded CI hosts may need longer for a full neighborhood.
            long idle = TimeUnit.SECONDS.toNanos(12);
            long start = System.nanoTime(), until = start + idle;
            long cap = start + TimeUnit.SECONDS.toNanos(120);
            var observed = controller.snapshot();
            boolean matched = false;
            while (!(matched = condition.getAsBoolean())
                    && System.nanoTime() < Math.min(until, cap)) {
                pump();
                var latest = controller.snapshot();
                if (!latest.equals(observed)) {
                    observed = latest;
                    until = System.nanoTime() + idle;
                }
                Thread.sleep(2);
            }
            if (!matched) {
                Thread.getAllStackTraces()
                        .forEach(
                                (thread, stack) -> {
                                    if (thread.getName().equals("world-source-controller")) {
                                        System.err.println(thread);
                                        for (var line : stack) System.err.println("  " + line);
                                    }
                                });
                for (String name : List.of("borrowed", "queued", "commit")) {
                    var field = WorldStreamSourceController.class.getDeclaredField(name);
                    field.setAccessible(true);
                    System.err.println(name + "=" + field.get(controller));
                }
                if (gpu.scratch != null)
                    System.err.println(
                            Files.readString(
                                    gpu.scratch.getParent().resolve("authored-commands.log")));
            }
            check(matched, "Timed out: " + controller.snapshot());
        }

        void untilPresented(BooleanSupplier condition) throws Exception {
            until(
                    () -> {
                        controller
                                .takePresentation()
                                .ifPresent(
                                        frame -> {
                                            try {
                                                if (controller.mayPresent(frame))
                                                    controller.presented(frame);
                                            } finally {
                                                controller.discard(frame);
                                            }
                                        });
                        return condition.getAsBoolean();
                    });
        }

        /** Presents every committed content natively for the one, held {@code camera}. */
        void untilNativePresented(long camera, BooleanSupplier condition) throws Exception {
            until(
                    () -> {
                        long content = controller.currentContentRevision();
                        if (content >= 0) controller.presentedNatively(camera, content);
                        return condition.getAsBoolean();
                    });
        }

        /** #381 window mode: the helper reports PRESENTED for the current content each poll. */
        void untilNativePresented(BooleanSupplier condition) throws Exception {
            var camera = new AtomicLong();
            until(
                    () -> {
                        long content = controller.currentContentRevision();
                        if (content >= 0) controller.presentedNatively(camera.incrementAndGet(), content);
                        return condition.getAsBoolean();
                    });
        }

        WorldStreamSourceController.Presentation nextFrame() throws Exception {
            var held = new WorldStreamSourceController.Presentation[1];
            until(
                    () -> {
                        held[0] = controller.takePresentation().orElse(null);
                        return held[0] != null;
                    });
            return held[0];
        }

        List<String> objectLines() {
            try {
                var path = gpu.scratch.getParent().resolve("authored-objects.log");
                return Files.exists(path) ? Files.readAllLines(path) : List.of();
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }

        /** The controller's current commit receipt, read under its lock. */
        Object currentReceipt() {
            try {
                var lock = WorldStreamSourceController.class.getDeclaredField("lock");
                var receipt = WorldStreamSourceController.class.getDeclaredField("currentReceipt");
                lock.setAccessible(true);
                receipt.setAccessible(true);
                synchronized (lock.get(controller)) {
                    return receipt.get(controller);
                }
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        List<String> commitLines() {
            try {
                var path = gpu.scratch.getParent().resolve("authored-commits.log");
                return Files.exists(path) ? Files.readAllLines(path) : List.of();
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }

        public void close() throws Exception {
            if (controller != null) {
                var closing = controller.closeAsync();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!closing.isDone() && System.nanoTime() < until) {
                    controller.pulse();
                    Thread.sleep(2);
                }
                closing.get(1, TimeUnit.SECONDS);
            }
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }

        final class Gpu implements WorldStreamSourceController.GpuOwner {
            volatile WorldStreamTransport.Session session;
            volatile CompletableFuture<Void> physical;
            volatile Path scratch;

            WorldStreamSourceController.GpuOwner start(String scope) {
                try {
                    var prepared =
                            WorldStreamTransport.prepare(
                                    directory,
                                    new WorldStreamTransport.Spec(
                                            scope, 1, WorldStreamTransport.Backend.VULKAN,
                                            exteriors,
                                            residents > WorldStreamTransport.ViewDistance.NEAR.residents()
                                                    ? WorldStreamTransport.ViewDistance.FAR
                                                    : WorldStreamTransport.ViewDistance.NEAR));
                    session =
                            prepared.launch(
                                    arguments -> {
                                        scratch = Path.of(arguments.get(1));
                                        return new ProcessBuilder(
                                                        Path.of(
                                                                        System.getProperty(
                                                                                "java.home"),
                                                                        "bin",
                                                                        "java")
                                                                .toString(),
                                                        "--enable-native-access=ALL-UNNAMED",
                                                        "-cp",
                                                        System.getProperty("java.class.path"),
                                                        "com.vibeminer.rendering.bgfx.AuthoredWorldControllerChild",
                                                        arguments.get(1),
                                                        arguments.get(3))
                                                .start();
                                    });
                    return this;
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }

            public CompletableFuture<WorldStreamTransport.Session> session() {
                return CompletableFuture.completedFuture(session);
            }

            public synchronized CompletableFuture<Void> closeAsync() {
                if (physical == null) physical = session.closeAsync().thenApply(receipt -> null);
                return physical.copy();
            }
        }
    }

    private static List<WorldFormats.Tile> install(AuthoredTerrainSource.Files files, int count) {
        var out = new ArrayList<WorldFormats.Tile>();
        byte[] wdt = files.sources.get(1L).clone();
        var index = ByteBuffer.wrap(wdt).order(ByteOrder.LITTLE_ENDIAN);
        byte[] initial = files.sources.get(2L);
        for (int i = 0; i < count; i++) {
            long root = i == 0 ? 2 : 1000 + 2L * i, tex = i == 0 ? 3 : root + 1;
            var tile = new WorldFormats.Tile(7, 19 + i, root, 0, 0, 0, 0, tex, 0, 0);
            out.add(tile);
            int ordinal = tile.x() * 64 + tile.y();
            index.putInt(60 + ordinal * 8, 1)
                    .putInt(32836 + ordinal * 32, (int) root)
                    .putInt(32836 + ordinal * 32 + 12, (int) tex);
            if (i > 0) {
                byte[] geometry = initial.clone();
                var data = ByteBuffer.wrap(geometry).order(ByteOrder.LITTLE_ENDIAN);
                for (int chunk = 0; chunk < 256; chunk++) {
                    int at = 12 + chunk * 724 + 8 + 108;
                    data.putFloat(at, (float) (data.getFloat(at) - i * WorldFormats.TILE_SIZE));
                }
                files.sources.put(root, geometry);
                files.sources.put(tex, files.sources.get(3L));
            }
        }
        files.sources.put(1L, wdt);
        return List.copyOf(out);
    }
}
